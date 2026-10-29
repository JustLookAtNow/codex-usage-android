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
class ResetCreditNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        manager.cancelAll()
        manager.deleteNotificationChannel(ResetCreditNotifications.CHANNEL_ID)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun notificationShowsCountsAndOpensAppWithoutReplacingWeeklyAlert() {
        WeeklyResetNotifications(app).notifyReset()
        ResetCreditNotifications(app).notifyAdded(2, 5)
        val posted = shadowOf(manager).getNotification(ResetCreditNotifications.NOTIFICATION_ID)
        assertEquals("新的重置点到账！", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("新增 2 个重置点，当前可用 5 个", posted.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(ResetCreditNotifications.CHANNEL_ID, posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(posted.channelId).importance)
        assertEquals(MainActivity::class.java.name, shadowOf(posted.contentIntent).savedIntent.component!!.className)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNotNull(shadowOf(manager).getNotification(WeeklyResetNotifications.NOTIFICATION_ID))
        ResetCreditNotifications(app).notifyAdded(1, 6)
        assertEquals(2, shadowOf(manager).size())
    }

    @Test fun deniedPermissionAndDisabledChannelSuppressNotifications() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ResetCreditNotifications(app).notifyAdded(1, 2)
        assertEquals(0, shadowOf(manager).size())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val channel = manager.getNotificationChannel(ResetCreditNotifications.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        ResetCreditNotifications(app).notifyAdded(1, 2)
        assertEquals(0, shadowOf(manager).size())
    }
}
