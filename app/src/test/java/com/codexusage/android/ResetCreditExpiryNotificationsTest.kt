package com.codexusage.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResetCreditExpiryNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val now = 1800000000L
    private fun credit(hours: Int) = ResetCredit("$hours", null, "available", null, now + hours * 3600, null, null)

    @Before fun setup() {
        manager.cancelAll()
        manager.deleteNotificationChannel(ResetCreditExpiryNotifications.CHANNEL_ID)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun mixedBatchShowsStagesAndEarliestExpiryAndCoexistsWithExistingAlerts() {
        WeeklyResetNotifications(context).notifyReset()
        ResetCreditNotifications(context).notifyAdded(1, 2)
        val notifier = ResetCreditExpiryNotifications(context)
        notifier.notifyExpiring(listOf(credit(1), credit(2), credit(50)), now)
        val posted = shadowOf(manager).getNotification(ResetCreditExpiryNotifications.NOTIFICATION_ID)
        assertEquals("重置点将在24小时内到期", posted.extras.getString(Notification.EXTRA_TITLE))
        val message = posted.extras.getString(Notification.EXTRA_TEXT)!!
        assertTrue(message.contains("24小时内到期：2 个"))
        assertTrue(message.contains("24至72小时内到期：1 个"))
        assertTrue(message.contains("最近到期：${localTime(now + 3600)}"))
        assertEquals(ResetCreditExpiryNotifications.CHANNEL_ID, posted.channelId)
        assertEquals(MainActivity::class.java.name, shadowOf(posted.contentIntent).savedIntent.component!!.className)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(3, shadowOf(manager).size())
        notifier.notifyExpiring(listOf(credit(50)), now)
        assertEquals(3, shadowOf(manager).size())
        assertEquals("重置点将在72小时内到期", shadowOf(manager)
            .getNotification(ResetCreditExpiryNotifications.NOTIFICATION_ID).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun disabledPermissionChannelAndEmptyBatchDoNotPost() {
        val notifier = ResetCreditExpiryNotifications(context)
        notifier.notifyExpiring(emptyList(), now)
        notifier.notifyExpiring(listOf(credit(0), credit(80)), now)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.notifyExpiring(listOf(credit(1)), now)
        assertEquals(0, shadowOf(manager).size())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val channel = manager.getNotificationChannel(ResetCreditExpiryNotifications.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        notifier.notifyExpiring(listOf(credit(1)), now)
        assertEquals(0, shadowOf(manager).size())
    }
}
