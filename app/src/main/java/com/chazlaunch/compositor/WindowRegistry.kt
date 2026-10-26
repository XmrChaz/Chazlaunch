package com.chazlaunch.compositor

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process ( `:guest` ) rendezvous between the [CompositorService], which
 * owns the compositor and reacts to toplevel lifecycle, and each
 * [window.WaylandWindowActivity], which owns an Android task + Surface.
 *
 * Both live in the same `:guest` process, so a plain concurrent map is enough —
 * no IPC required.
 */
object WindowRegistry {

    /** Callbacks an activity exposes so native/service events reach its UI. */
    interface WindowCallbacks {
        fun onTitleChanged(title: String)
        fun onClosedByClient()
    }

    private val callbacks = ConcurrentHashMap<Long, WindowCallbacks>()
    private val pending = ConcurrentHashMap<Long, ToplevelInfo>()

    /** Service stashes freshly-created toplevels until their activity binds. */
    fun putPending(info: ToplevelInfo) { pending[info.id] = info }
    fun takePending(id: Long): ToplevelInfo? = pending.remove(id)

    fun register(id: Long, cb: WindowCallbacks) { callbacks[id] = cb }
    fun unregister(id: Long) { callbacks.remove(id) }

    fun dispatchTitle(id: Long, title: String) { callbacks[id]?.onTitleChanged(title) }
    fun dispatchClose(id: Long) { callbacks[id]?.onClosedByClient() }

    fun hasActiveWindows(): Boolean = callbacks.isNotEmpty() || pending.isNotEmpty()
}
