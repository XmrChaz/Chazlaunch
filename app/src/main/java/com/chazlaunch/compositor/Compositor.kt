package com.chazlaunch.compositor

import android.view.Surface
import androidx.annotation.Keep

/**
 * JNI bridge to the native Wayland compositor (src/main/cpp).
 *
 * The native side runs a real `wl_display` on a Unix socket inside the app's
 * private runtime dir. That socket is bind-mounted into the PRoot guest at
 * `$XDG_RUNTIME_DIR/wayland-0`, so Linux GUI clients connect to it exactly as
 * they would to any Wayland compositor.
 *
 * Each `xdg_toplevel` the compositor maps is reported here via [Listener]. The
 * [CompositorService] responds by launching a dedicated [window.WaylandWindowActivity]
 * task — one Recents entry per Linux window — and hands the activity's
 * [Surface] back down with [attachSurface] so the client's buffers are
 * composited straight into that Android task.
 */
object Compositor {

    init {
        System.loadLibrary("chazcompositor")
    }

    /** Native → JVM window lifecycle, dispatched on the compositor thread. */
    interface Listener {
        fun onToplevelCreated(info: ToplevelInfo)
        fun onToplevelTitleChanged(id: Long, title: String)
        fun onToplevelResized(id: Long, width: Int, height: Int)
        fun onToplevelClosed(id: Long)
    }

    @Volatile private var listener: Listener? = null

    fun setListener(l: Listener?) { listener = l }

    // --- Lifecycle ----------------------------------------------------------

    /**
     * Start the compositor and bind its socket under [socketDir].
     * @return the Wayland display name (e.g. "wayland-0") to export as
     *         WAYLAND_DISPLAY in the guest.
     */
    external fun start(socketDir: String, scale: Float): String

    external fun stop()

    external fun isRunning(): Boolean

    // --- Per-window surface plumbing ---------------------------------------

    /** Give a toplevel the Android [surface] of its WaylandWindowActivity. */
    external fun attachSurface(toplevelId: Long, surface: Surface, width: Int, height: Int)

    external fun detachSurface(toplevelId: Long)

    external fun resizeToplevel(toplevelId: Long, width: Int, height: Int)

    /** Ask the client to close (xdg_toplevel.close), e.g. when its task is swiped away. */
    external fun requestClose(toplevelId: Long)

    // --- Input forwarding (from the activity's SurfaceView) -----------------

    external fun sendPointerMotion(toplevelId: Long, x: Float, y: Float)
    external fun sendPointerButton(toplevelId: Long, button: Int, pressed: Boolean)
    external fun sendPointerScroll(toplevelId: Long, dx: Float, dy: Float)
    external fun sendTouch(toplevelId: Long, pointerId: Int, x: Float, y: Float, action: Int)
    external fun sendKey(toplevelId: Long, keyCode: Int, pressed: Boolean)

    // --- Invoked from native via JNI (keep names/signatures stable) ---------

    @Keep
    @JvmStatic
    private fun nativeOnToplevelCreated(id: Long, title: String?, appId: String?) {
        listener?.onToplevelCreated(ToplevelInfo(id, title ?: "Linux window", appId ?: ""))
    }

    @Keep
    @JvmStatic
    private fun nativeOnTitleChanged(id: Long, title: String?) {
        listener?.onToplevelTitleChanged(id, title ?: "")
    }

    @Keep
    @JvmStatic
    private fun nativeOnResized(id: Long, width: Int, height: Int) {
        listener?.onToplevelResized(id, width, height)
    }

    @Keep
    @JvmStatic
    private fun nativeOnToplevelClosed(id: Long) {
        listener?.onToplevelClosed(id)
    }
}

@Keep
data class ToplevelInfo(
    val id: Long,
    val title: String,
    val appId: String,
)
