package com.chazlaunch.compositor

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.chazlaunch.ChazApp
import com.chazlaunch.R
import com.chazlaunch.data.Prefs
import com.chazlaunch.proot.PRootManager
import com.chazlaunch.storage.SdCardManager
import com.chazlaunch.window.WaylandWindowActivity
import kotlin.concurrent.thread

/**
 * Long-lived foreground service (process `:guest`) that:
 *  - boots the native Wayland compositor and exposes its socket,
 *  - launches the guest session (a login shell / the user's chosen command),
 *  - turns each mapped Linux toplevel into its own Android task.
 */
class CompositorService : Service(), Compositor.Listener {

    private var guestProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!Compositor.isRunning()) startCompositor()

        when (intent?.action) {
            ACTION_LAUNCH -> intent.getStringExtra(EXTRA_COMMAND)?.let { launchGuestCommand(it) }
            ACTION_START_SESSION -> startLoginSession()
        }
        return START_STICKY
    }

    private fun startCompositor() {
        Compositor.setListener(this)
        val display = Compositor.start(
            socketDir = PRootManager.waylandSocketDir.absolutePath,
            scale = Prefs.compositorScale,
        )
        Log.i(TAG, "compositor up on WAYLAND_DISPLAY=$display")
    }

    /** Start an interactive guest login shell (no GUI yet, just keeps dbus etc alive). */
    private fun startLoginSession() = launchGuestCommand("/run/current-system/sw/bin/bash -l")

    /** Run [command] inside the guest with WAYLAND_DISPLAY pointed at us. */
    private fun launchGuestCommand(command: String) {
        val sd = SdCardManager(this)
        val invocation = PRootManager.buildProotCommand(
            command = listOf(command),
            sdCardTmp = sd.tmpDir(),
            sdCardCache = sd.cacheDir(),
            loginShell = true,
        )
        thread(name = "guest-$command", isDaemon = true) {
            val proc = PRootManager.start(invocation).also { guestProcess = it }
            proc.inputStream.bufferedReader().forEachLine { Log.d(TAG, it) }
            Log.i(TAG, "guest command '$command' exited ${proc.waitFor()}")
        }
    }

    // --- Compositor.Listener: window lifecycle ------------------------------

    override fun onToplevelCreated(info: ToplevelInfo) {
        WindowRegistry.putPending(info)
        // One task per window: NEW_DOCUMENT + MULTIPLE_TASK gives each its own
        // Recents card, keyed by the toplevel id in the intent data.
        val intent = Intent(this, WaylandWindowActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            )
            putExtra(WaylandWindowActivity.EXTRA_TOPLEVEL_ID, info.id)
            putExtra(WaylandWindowActivity.EXTRA_TITLE, info.title)
            // Unique data URI keeps Android from collapsing the documents.
            data = android.net.Uri.parse("chaz://window/${info.id}")
        }
        startActivity(intent)
    }

    override fun onToplevelTitleChanged(id: Long, title: String) =
        WindowRegistry.dispatchTitle(id, title)

    override fun onToplevelResized(id: Long, width: Int, height: Int) {
        // The activity drives real resize via its SurfaceView callbacks; this is
        // only the client-initiated hint, forwarded for any UI that cares.
        Log.d(TAG, "toplevel $id wants ${width}x$height")
    }

    override fun onToplevelClosed(id: Long) {
        WindowRegistry.takePending(id)
        WindowRegistry.dispatchClose(id)
    }

    override fun onDestroy() {
        guestProcess?.destroy()
        Compositor.setListener(null)
        Compositor.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val tapToLauncher = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, ChazApp.GUEST_CHANNEL_ID)
            .setContentTitle(getString(R.string.guest_running))
            .setContentText(getString(R.string.guest_running_desc))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapToLauncher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CompositorService"
        private const val NOTIF_ID = 42

        const val ACTION_START_SESSION = "com.chazlaunch.START_SESSION"
        const val ACTION_LAUNCH = "com.chazlaunch.LAUNCH"
        const val EXTRA_COMMAND = "command"

        fun startSession(context: android.content.Context) {
            val i = Intent(context, CompositorService::class.java).setAction(ACTION_START_SESSION)
            context.startForegroundService(i)
        }

        fun launch(context: android.content.Context, command: String) {
            val i = Intent(context, CompositorService::class.java)
                .setAction(ACTION_LAUNCH)
                .putExtra(EXTRA_COMMAND, command)
            context.startForegroundService(i)
        }
    }

    @Suppress("unused")
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
}
