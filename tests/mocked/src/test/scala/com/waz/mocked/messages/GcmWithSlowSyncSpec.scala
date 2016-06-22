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
package com.waz.mocked.messages

import java.util.Date

import com.waz.RobolectricUtils
import com.waz.api.{MessagesList, MockedClientApiSpec}
import com.waz.mocked.{MockBackend, SystemTimeline}
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.sync.client.PushNotification
import com.waz.testutils.Implicits._
import com.waz.utils.returning
import org.scalatest.{FeatureSpec, Matchers}

import scala.concurrent.duration._

class GcmWithSlowSyncSpec extends FeatureSpec with Matchers with MockedClientApiSpec with MockBackend with RobolectricUtils { self =>
  import DefaultPushBehaviour.Implicit

  val userId = UserId()
  lazy val convId = addConnection(userId).convId
  lazy val convs  = api.getConversations
  lazy val conv   = convs.getConversation(userId.str)

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    ZMessaging.currentAccounts = accounts
  }

  feature("Unread dot") {

    scenario("init") {
      addMessageEvents(convId, count = 5)

      lazy val msgs = conv.getMessages

      withDelay {
        convs should not be empty
        msgs should have size 8
      }
    }

    scenario("Receive gcm notification about message that is unread, then resume with slow sync") {
      val msgs = conv.getMessages
      withDelay { msgs should have size 8 }

      markAllMessagesAsRead(msgs)
      api.onPause()
      withDelay {
        zmessaging.websocket.connected.currentValue shouldEqual Some(false)
      }
      val newMsg = MessageAddEvent(Uid(), convId, EventId(9), new Date, userId, "meep")
      pushMessageToAppInBackground(newMsg)

      forceSlowSync()
      api.onResume()
      awaitUi(1.second)

      withDelay {
        msgs.getUnreadCount shouldEqual 2 // + OTR_LOST_HISTORY
      }
    }

    scenario("Receive gcm notification about message that was already read on another device, then resume with slow sync") {
      val msgs = conv.getMessages
      withDelay { msgs should have size 10 }

      markAllMessagesAsRead(msgs)
      awaitUi(1.second) // this is to make sure that we don't overwrite lastRead on backend with some older value

      api.onPause()
      withDelay {
        zmessaging.websocket.connected.currentValue shouldEqual Some(false)
      }
      val newMsg = readNewMessageOnOtherDevice(EventId(10))
      pushMessageToAppInBackground(newMsg)

      forceSlowSync()
      api.onResume()
      awaitUi(1.second)

      withDelay {
        msgs should have size 12
        withClue((conv.data.lastRead, msgs.getLastMessage.data)) {
          conv.getUnreadCount shouldEqual 0
          msgs.getUnreadCount shouldEqual 0
        }
      }
    }

    def markAllMessagesAsRead(msgs: MessagesList): Unit = msgs.get(msgs.size - 1)

    def readNewMessageOnOtherDevice(eventId: EventId): MessageAddEvent = {
      returning(MessageAddEvent(Uid(), convId, eventId, SystemTimeline.next(), userId, "meep meep")) { msgAdd =>
        addEvent(msgAdd)
        markAsRead(convId, eventId)
      }
    }

    def pushMessageToAppInBackground(msg: MessageAddEvent): Unit = {
      pushGcm(PushNotification(Uid(), Seq(msg), transient = false), selfUserId)
      awaitUi(1.second)
    }

    def forceSlowSync(): Unit = notifications = Vector.empty
  }
}
