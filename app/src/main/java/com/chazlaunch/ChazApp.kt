package com.chazlaunch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.chazlaunch.data.Prefs
import com.chazlaunch.proot.PRootManager

/**
 * Process-wide singletons. Note the app runs in two processes: the default
 * (launcher UI) and `:guest` (compositor + PRoot). [onCreate] runs in both, so
 * keep it idempotent and cheap.
 */
class ChazApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        PRootManager.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            GUEST_CHANNEL_ID,
            getString(R.string.guest_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val GUEST_CHANNEL_ID = "chaz_guest"

        lateinit var instance: ChazApp
            private set
    }
}
