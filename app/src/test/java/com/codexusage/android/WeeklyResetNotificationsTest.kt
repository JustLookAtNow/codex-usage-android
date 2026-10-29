package com.codexusage.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeeklyResetNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        app.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("notifications", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        manager.deleteNotificationChannel(WeeklyResetNotifications.CHANNEL_ID)
    }

    @Test fun notificationHasChannelTextAndOpensAppWithFixedId() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = WeeklyResetNotifications(app)
        notifications.notifyReset()
        val posted = shadowOf(manager).getNotification(WeeklyResetNotifications.NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals("周额度已重置", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("额度已重置，尽情使用吧！", posted.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(WeeklyResetNotifications.CHANNEL_ID, posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(posted.channelId).importance)
        assertEquals(MainActivity::class.java.name, shadowOf(posted.contentIntent).savedIntent.component!!.className)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        notifications.notifyReset()
        assertEquals(1, shadowOf(manager).size())
    }

    @Test fun deniedPermissionAndBlockedChannelDoNotPost() {
        val notifications = WeeklyResetNotifications(app)
        notifications.notifyReset()
        assertEquals(0, shadowOf(manager).size())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val channel = manager.getNotificationChannel(WeeklyResetNotifications.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        notifications.notifyReset()
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun appRequestsPermissionOnceAndSettingsEntryOpensSystemSettings() {
        fun buttons(view: View): List<Button> = when (view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = first.get()
            val request = shadowOf(activity).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
            assertEquals(WeeklyResetNotifications.PERMISSION_REQUEST, request.requestCode)
            buttons(activity.window.decorView).single { it.text == app.getString(R.string.notification_settings) }.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        } finally {
            first.pause().stop().destroy()
        }
        // Permission remains denied, as if the user rejected the first prompt.
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertNull(shadowOf(second.get()).lastRequestedPermission)
        } finally {
            second.pause().stop().destroy()
        }
    }
}
