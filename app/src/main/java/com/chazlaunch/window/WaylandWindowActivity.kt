package com.chazlaunch.window

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.chazlaunch.compositor.Compositor
import com.chazlaunch.compositor.WindowRegistry

/**
 * One Android task == one Linux window.
 *
 * Runs in the `:guest` process so it shares the loaded native compositor with
 * [com.chazlaunch.compositor.CompositorService]; its [SurfaceView] surface is
 * handed straight to the compositor, which composites the matching
 * `xdg_toplevel` into it. Touch/key events are forwarded back to the client.
 */
class WaylandWindowActivity : Activity(), WindowRegistry.WindowCallbacks {

    private var toplevelId: Long = -1L
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toplevelId = intent.getLongExtra(EXTRA_TOPLEVEL_ID, -1L)
        if (toplevelId < 0) { finish(); return }

        title = intent.getStringExtra(EXTRA_TITLE) ?: "Linux window"
        setTaskDescription(ActivityManager_taskDescription(title.toString()))

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(surfaceCallback)
            isFocusableInTouchMode = true
        }
        setContentView(surfaceView)
        WindowRegistry.register(toplevelId, this)
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Compositor.attachSurface(toplevelId, holder.surface, width, height)
            Compositor.resizeToplevel(toplevelId, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Compositor.detachSurface(toplevelId)
        }
    }

    // --- Input forwarding ---------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                Compositor.sendPointerMotion(toplevelId, x, y)
                Compositor.sendPointerButton(toplevelId, BTN_LEFT, true)
            }
            MotionEvent.ACTION_MOVE -> Compositor.sendPointerMotion(toplevelId, x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                Compositor.sendPointerButton(toplevelId, BTN_LEFT, false)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        Compositor.sendKey(toplevelId, keyCode, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        Compositor.sendKey(toplevelId, keyCode, false)
        return true
    }

    // --- WindowRegistry callbacks (native/service → this task) --------------

    override fun onTitleChanged(title: String) {
        runOnUiThread {
            this.title = title
            setTaskDescription(ActivityManager_taskDescription(title))
        }
    }

    override fun onClosedByClient() {
        runOnUiThread { finishAndRemoveTask() }
    }

    override fun onDestroy() {
        WindowRegistry.unregister(toplevelId)
        // If the user swiped the task away, ask the client to close cleanly.
        if (isFinishing) Compositor.requestClose(toplevelId)
        super.onDestroy()
    }

    @Suppress("FunctionName", "DEPRECATION")
    private fun ActivityManager_taskDescription(label: String) =
        android.app.ActivityManager.TaskDescription(label)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) surfaceView.requestFocus()
    }

    companion object {
        const val EXTRA_TOPLEVEL_ID = "toplevel_id"
        const val EXTRA_TITLE = "title"

        // linux/input-event-codes.h BTN_LEFT
        private const val BTN_LEFT = 0x110
    }
}
