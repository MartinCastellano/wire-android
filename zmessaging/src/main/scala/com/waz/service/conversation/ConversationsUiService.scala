/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.conversation

import com.waz.ZLog._
import com.waz.api
import com.waz.api.MessageContent.Asset.ErrorHandler
import com.waz.api.MessageContent.Text
import com.waz.api.impl._
import com.waz.api.{EphemeralExpiration, ImageAssetFactory, Message, NetworkMode}
import com.waz.content._
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.{Location, MsgEdit}
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service._
import com.waz.service.assets.AssetService
import com.waz.service.messages.{MessagesContentUpdater, MessagesService}
import com.waz.sync.SyncServiceHandle
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.Locales.currentLocaleOrdering
import com.waz.utils.events.EventStream
import com.waz.utils.{RichInstant, _}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds

class ConversationsUiService(self:            UserId,
                             assets:          AssetService,
                             users:           UserService,
                             usersStorage:    UsersStorage,
                             messages:        MessagesService,
                             messagesContent: MessagesContentUpdater,
                             members:         MembersStorage,
                             assetStorage:    AssetsStorage,
                             convsContent:    ConversationsContentUpdater,
                             convStorage:     ConversationStorage,
                             network:         NetworkModeService,
                             convs:           ConversationsService,
                             sync:            SyncServiceHandle,
                             lifecycle:       ZmsLifecycle,
                             errors:          ErrorsService) {

  import ConversationsUiService._
  import Threading.Implicits.Background

  val assetUploadStarted   = EventStream[AssetData]()
  val assetUploadCancelled = EventStream[Mime]() //size, mime
  val assetUploadFailed    = EventStream[ErrorResponse]()

  def sendMessage(convId: ConvId, content: api.MessageContent): Future[Option[MessageData]] = {

    def mentionsMap(us: Array[api.User]): Future[Map[UserId, String]] =
      users.getUsers(us.map { u => UserId(u.getId) }) map { uss =>
        uss.map(u => u.id -> u.getDisplayName)(breakOut)
      }

    def sendTextMessage(m: String, mentions: Map[UserId, String] = Map.empty) =
      for {
        msg <- messages.addTextMessage(convId, m, mentions)
        _ <- updateLastRead(msg)
        _ <- sync.postMessage(msg.id, convId, msg.editTime)
      } yield Some(msg)

    def sendLocationMessage(loc: Location) =
      for {
        msg <- messages.addLocationMessage(convId, loc)
        _ <- updateLastRead(msg)
        _ <- sync.postMessage(msg.id, convId, msg.editTime)
      } yield Some(msg)

    def sendImageMessage(img: api.ImageAsset, conv: ConversationData) = {
      verbose(s"sendImageMessage($img, $conv)")
      for {
        data <- assets.addImageAsset(img, conv.remoteId, isSelf = false)
        msg <- messages.addAssetMessage(convId, data)
        _ <- updateLastRead(msg)
        _ <- Future.successful(assetUploadStarted ! data)
        _ <- sync.postMessage(msg.id, convId, msg.editTime)
      } yield Some(msg)
    }

    def sendAssetMessage(in: AssetForUpload, conv: ConversationData, handler: ErrorHandler): Future[Option[MessageData]] = {

      def isFileTooLarge(size: Long, mime: Mime) = mime match {
        case Mime.Video() => false
        case _ => size > AssetData.MaxAllowedAssetSizeInBytes
      }

      def shouldWarnAboutFileSize(size: Long) =
        if (size < LargeAssetWarningThresholdInBytes) Future successful None
        else network.networkMode.head map {
          case api.NetworkMode.OFFLINE | api.NetworkMode.WIFI => None
          case net if lifecycle.isUiActive => Some(net)
          case _ => None
        }

      def showLargeFileWarning(size: Long, mime: Mime, net: NetworkMode, message: MessageData) = {
        Threading.assertUiThread()

        handler.noWifiAndFileIsLarge(size, net, new api.MessageContent.Asset.Answer {
          override def ok(): Unit = messages.retryMessageSending(convId, message.id)

          override def cancel(): Unit = messagesContent.deleteMessage(message).map(_ => assetUploadCancelled ! mime)
        })
      }

      def checkSize(size: Option[Long], mime: Mime, message: MessageData) = size match {
        case None => Future successful true
        case Some(s) if isFileTooLarge(s, mime) =>
          for {
            _ <- messages.updateMessageState(convId, message.id, Message.Status.FAILED)
            _ <- errors.addAssetTooLargeError(convId, message.id)
            _ <- Future.successful(assetUploadFailed ! ErrorResponse.internalError("asset too large"))
          } yield false
        case Some(s) =>
          shouldWarnAboutFileSize(s) flatMap {
            case Some(net) =>
              // will mark message as failed and ask user if it should really be sent
              // marking as failed ensures that user has a way to retry even if he doesn't respond to this warning
              // this is possible if app is paused or killed in meantime, we don't want to be left with message in state PENDING without a sync request
              messages.updateMessageState(convId, message.id, Message.Status.FAILED).map { _ =>
                showLargeFileWarning(s, mime, net, message)
                false
              }(Threading.Ui)
            case _ =>
              Future successful true
          }
      }

      for {
        mime <- in.mimeType
        asset <- assets.addAsset(in, conv.remoteId)
        message <- messages.addAssetMessage(convId, asset)
        size <- in.sizeInBytes
        _ <- Future.successful(assetUploadStarted ! asset)
        shouldSend <- checkSize(size, mime, message)
        _ <- if (shouldSend) sync.postMessage(message.id, convId, message.editTime) else Future.successful(())
      } yield Some(message)
    }

    content match {
      case m: api.MessageContent.Text =>
        debug(s"send text message ${m.getContent}")
        if (m.getMentions.isEmpty) sendTextMessage(m.getContent)
        else mentionsMap(m.getMentions) flatMap { ms => sendTextMessage(m.getContent, ms) }
      case m: api.MessageContent.Location =>
        sendLocationMessage(Location(m.getLongitude, m.getLatitude, m.getName, m.getZoom))
      case m: api.MessageContent.Image =>
        convsContent.convById(convId) flatMap {
          case Some(conv) => sendImageMessage(m.getContent, conv)
          case None => Future.failed(new IllegalArgumentException(s"No conversation found for $convId"))
        }
      case m: api.MessageContent.Asset =>
        convsContent.convById(convId) flatMap {
          case Some(conv) =>
            debug(s"send asset message ${m.getContent}")
            m.getContent match {
              case a@ContentUriAssetForUpload(_, uri) =>
                a.mimeType.flatMap {
                  case m@Mime.Image() =>
                    sendImageMessage(ImageAssetFactory.getImageAsset(uri), conv) // XXX: this has to run on UI thread, so we can't make if part of the for-expression
                  case _ =>
                    sendAssetMessage(a, conv, m.getErrorHandler)
                }(Threading.Ui)
              case a: AssetForUpload => sendAssetMessage(a, conv, m.getErrorHandler)
            }
          case None =>
            Future.failed(new IllegalArgumentException(s"No conversation found for $convId"))
        }
      case _ =>
        error(s"sendMessage($content) not supported yet")
        Future.failed(new IllegalArgumentException(s"MessageContent: $content is not supported yet"))
    }
  }

  def updateMessage(convId: ConvId, id: MessageId, content: Text): Future[Option[MessageData]] = {
    verbose(s"updateMessage($convId, $id, $content")
    messagesContent.updateMessage(id) {
      case m if m.convId == convId && m.userId == self =>
        val (tpe, ct) = MessageData.messageContent(content.getContent, weblinkEnabled = true)
        verbose(s"updated content: ${(tpe, ct)}")
        m.copy(msgType = tpe, content = ct, protos = Seq(GenericMessage(Uid(), MsgEdit(id, GenericContent.Text(content.getContent)))), state = Message.Status.PENDING, editTime = (m.time max m.editTime).plus(1.millis) max Instant.now)
      case m =>
        warn(s"Can not update msg: $m")
        m
    } flatMap {
      case Some(m) => sync.postMessage(m.id, m.convId, m.editTime) map { _ => Some(m) } // using PostMessage sync request to use the same logic for failures and retrying
      case None => Future successful None
    }
  }

  def deleteMessage(convId: ConvId, id: MessageId): Future[Unit] = for {
    _ <- messagesContent.deleteOnUserRequest(Seq(id))
    _ <- sync.postDeleted(convId, id)
  } yield ()

  def recallMessage(convId: ConvId, id: MessageId): Future[Option[MessageData]] =
    messages.recallMessage(convId, id, self) flatMap {
      case Some(msg) =>
        sync.postRecalled(convId, msg.id, id) map { _ => Some(msg) }
      case None =>
        warn(s"could not recall message $convId, $id")
        Future successful None
    }

  private def updateLastRead(msg: MessageData) = convsContent.updateConversationLastRead(msg.convId, msg.time)

  def setConversationArchived(id: ConvId, archived: Boolean): Future[Option[ConversationData]] = convs.setConversationArchived(id, archived)

  def setConversationMuted(id: ConvId, muted: Boolean): Future[Option[ConversationData]] =
    convsContent.updateConversationMuted(id, muted) map {
      case Some((_, conv)) => sync.postConversationState(id, ConversationState(muted = Some(conv.muted), muteTime = Some(conv.muteTime))); Some(conv)
      case None => None
    }

  def setConversationName(id: ConvId, name: String): Future[Option[ConversationData]] = users.withSelfUserFuture { selfUserId =>
    verbose(s"setConversationName($id, $name)")
    convsContent.updateConversationName(id, name) flatMap {
      case Some((_, conv)) if conv.name.contains(name) =>
        sync.postConversationName(id, conv.name.getOrElse(""))
        messages.addRenameConversationMessage(id, selfUserId, name) map (_ => Some(conv))
      case conv =>
        warn(s"Conversation name could not be changed for: $id, conv: $conv")
        CancellableFuture.successful(None)
    }
  }

  def addConversationMembers(conv: ConvId, users: Seq[UserId]): Future[Seq[ConversationMemberData]] =
    this.users.withSelfUserFuture { selfUserId =>
      ifAbleToModifyMembers(conv, selfUserId, Seq.empty[ConversationMemberData]) {
        members.add(conv, users: _*) flatMap { members =>
          if (members.nonEmpty) {
            messages.addMemberJoinMessage(conv, selfUserId, members.map(_.userId).toSet) map { _ =>
              sync.postConversationMemberJoin(conv, members.map(_.userId).toSeq)
              members.toSeq
            }
          } else Future.successful(members.toSeq)
        }
      }
    }

  def removeConversationMember(conv: ConvId, user: UserId): Future[Option[SyncId]] =
    this.users.withSelfUserFuture { selfUserId =>
      ifAbleToModifyMembers(conv, selfUserId, Option.empty[SyncId]) {
        members.remove(conv, user) flatMap { members =>
          if (members.isEmpty) Future.successful(None)
          else messages.addMemberLeaveMessage(conv, selfUserId, user) flatMap { _ =>
            sync.postConversationMemberLeave(conv, user).map(Some(_))
          }
        }
      }
    }

  def leaveConversation(conv: ConvId): Future[Option[ConversationData]] = {
    verbose(s"leaveConversation($conv)")

    for {
      updated <- convsContent.setConvActive(conv, active = false)
      _ <- users.withSelfUserFuture {
        removeConversationMember(conv, _)
      }
      archived <- convsContent.updateConversationArchived(conv, archived = true)
    } yield archived.map(_._2).orElse(updated.map(_._2))
  }

  def ifAbleToModifyMembers[A, M[_]](conv: ConvId, user: UserId, zero: M[A])(f: => Future[M[A]]): Future[M[A]] = {
    val isGroup = convsContent.convById(conv).map(_.exists(_.convType == ConversationType.Group))
    val isActiveMember = members.isActiveMember(conv, user)
    for {
      p1 <- isGroup
      p2 <- isActiveMember
      res <- if (p1 && p2) f else Future.successful(zero)
    } yield res
  }

  def clearConversation(id: ConvId): Future[Option[ConversationData]] = convsContent.convById(id) flatMap {
    case Some(conv) if conv.convType == ConversationType.Group || conv.convType == ConversationType.OneToOne =>
      verbose(s"clearConversation($conv)")

      convsContent.updateConversationCleared(conv.id, conv.lastEventTime) flatMap {
        case Some((_, c)) =>
          for {
            _ <- convsContent.updateConversationLastRead(c.id, c.cleared)
            _ <- convsContent.updateConversationArchived(c.id, archived = true)
            _ <- sync.postCleared(c.id, c.cleared)
          } yield Some(c)
        case None =>
          verbose("updateConversationCleared did nothing - already cleared")
          Future successful None
      }
    case Some(conv) =>
      warn(s"conversation of type ${conv.convType} can not be cleared")
      Future successful None
    case None =>
      warn(s"conversation to be cleared not found: $id")
      Future successful None
  }

  def getOrCreateOneToOneConversation(toUser: UserId): Future[ConversationData] =
    convsContent.convById(ConvId(toUser.str)) flatMap {
      case Some(conv) => Future.successful(conv)
      case _ => users.withSelfUserFuture { selfUserId => createOneToOneConversation(toUser, selfUserId) }
    }

  /**
    * Creates conversation with already connected user.
    */
  def createOneToOneConversation(toUser: UserId, selfUserId: UserId) =
  usersStorage.get(toUser).flatMap {
    case Some(u) if u.connection == ConnectionStatus.Ignored =>
      convsContent.createConversationWithMembers(ConvId(toUser.str), u.conversation.getOrElse(RConvId()), ConversationType.Incoming, toUser, Seq(selfUserId), hidden = true) flatMap { conv =>
        messages.addMemberJoinMessage(conv.id, toUser, Set(selfUserId), firstMessage = true) flatMap { _ =>
          u.connectionMessage.fold {
            Future.successful(conv)
          } { msg =>
            messages.addConnectRequestMessage(conv.id, toUser, selfUserId, msg, u.name) map { _ => conv }
          }
        }
      }
    case _ =>
      sync.postConversation(ConvId(toUser.str), Seq(toUser), None, None)
      convsContent.createConversationWithMembers(ConvId(toUser.str), RConvId(), ConversationType.OneToOne, selfUserId, Seq(toUser)) flatMap { conv =>
        messages.addMemberJoinMessage(conv.id, selfUserId, Set(toUser), firstMessage = true) map (_ => conv)
      }
  }

  def createGroupConversation(id: ConvId, members: Seq[UserId], teamId: Option[TeamId] = None): Future[ConversationData] = {
    debug(s"createGroupConversation, id: $id, members: $members, team: $teamId")

    def createAndPost = convsContent.createConversationWithMembers(id, ConversationsService.generateTempConversationId((self +: members).distinct: _*), ConversationType.Group, self, members, teamId = teamId) flatMap { conv =>
      debug(s"created: $conv")
      sync.postConversation(id, members, conv.name, teamId)
      messages.addMemberJoinMessage(conv.id, self, members.toSet, firstMessage = true) map (_ => conv)
    }

    teamId.fold(createAndPost) { teamId =>

      members match {
        case Seq(other) =>
          verbose(s"Checking for 1:1 conversation with user: $other")
          (for {
            allConvs <- this.members.getByUsers(Set(other)).map(_.map(_.convId))
            allMembers <- this.members.getByConvs(allConvs.toSet).map(_.map(m => m.convId -> m.userId))
            onlyUs = allMembers.groupBy { case (c, _) => c }.map { case (cid, us) => cid -> us.map(_._2).toSet }.collect { case (c, us) if us == Set(other, self) => c}
            data <- convStorage.getAll(onlyUs).map(_.flatten)
          } yield data.filter(_.team.contains(teamId))).flatMap { convs =>
            if (convs.isEmpty) {
              verbose(s"No conversation with user $other found, creating new group conversation")
              createAndPost
            } else {
              if (convs.size > 1) warn(s"Found ${convs.size} available team conversations with user: $other, returning first conversation found")
              Future.successful(convs.head)
            }
          }

        case _ => createAndPost
      }
    }
  }

  def findGroupConversations(prefix: SearchKey, limit: Int, handleOnly: Boolean): Future[Seq[ConversationData]] =
    users.withSelfUserFuture(id => convStorage.search(prefix, id, handleOnly)).map(_.sortBy(_.displayName)(currentLocaleOrdering).take(limit))

  def knock(id: ConvId): Future[Option[MessageData]] =
    for {
      msg <- messages.addKnockMessage(id, self)
      _ <- sync.postMessage(msg.id, id, msg.editTime)
    } yield
      Some(msg)

  def setLastRead(convId: ConvId, msg: MessageData): Future[Option[ConversationData]] = {
    convsContent.updateConversationLastRead(convId, msg.time) map {
      case Some((_, conv)) =>
        sync.postLastRead(convId, conv.lastRead)
        Some(conv)
      case _ => None
    }
  }

  def setEphemeral(id: ConvId, expiration: EphemeralExpiration) =
    convStorage.update(id, _.copy(ephemeral = expiration))
}

object ConversationsUiService {
  private implicit val logTag: LogTag = logTagFor[ConversationsUiService]

  val LargeAssetWarningThresholdInBytes = 3145728L // 3MiB
}
