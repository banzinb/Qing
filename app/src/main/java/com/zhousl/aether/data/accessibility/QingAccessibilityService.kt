package com.zhousl.aether.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Qing's screen-reading service.
 *
 * The user enables it by hand under Settings → Accessibility; Android forbids starting it
 * programmatically, and Qing never tries to. Its only job is to hand the tool layer a live view of
 * the screen the user is actually looking at: the active window's nodes, the foreground package,
 * tap/type primitives, and a registry of short-lived node handles.
 *
 * Deliberately **not** included in this first version: event watching, screenshots (Qing already
 * has other capture paths), pinch gestures and multi-window enumeration.
 *
 * Adapted from OpenMinis `accessibility/MinisAccessibilityService.kt` (GPLv3) — see NOTICE.
 */
class QingAccessibilityService : AccessibilityService() {

    companion object {
        private const val Tag = "QingA11y"
        private const val GestureTimeoutMillis = 5_000L

        @Volatile
        private var instance: QingAccessibilityService? = null

        /** Non-null only while the user has the service enabled and the system has it bound. */
        fun getInstance(): QingAccessibilityService? = instance

        fun isRunning(): Boolean = instance != null
    }

    val nodeRegistry: NodeRegistry = NodeRegistry()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(Tag, "service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(Tag, "service connected (manufacturer=${Build.MANUFACTURER})")
    }

    /** Events are not used in this version; the service reads the tree on demand. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.i(Tag, "service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        nodeRegistry.clear()
        Log.i(Tag, "service destroyed")
    }

    /** Root of the window the user is looking at. Null when nothing is readable. */
    fun activeRoot(): AccessibilityNodeInfo? = runCatching { rootInActiveWindow }.getOrNull()

    fun foregroundPackage(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    fun activeWindowId(): Int =
        runCatching { rootInActiveWindow?.windowId ?: 0 }.getOrDefault(0)

    /**
     * Taps/swipes at coordinates. Returns the *real* outcome: the callback reports cancellation,
     * and a timeout is a failure rather than an assumption of success.
     */
    fun dispatchSimpleGesture(
        path: Path,
        startTime: Long,
        durationMillis: Long,
        timeoutMillis: Long = GestureTimeoutMillis,
    ): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMillis)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val completed = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed[0] = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed[0] = false
                latch.countDown()
            }
        }
        Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, callback, null) }
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && completed[0]
    }

    /** Taps a screen point. [durationMillis] > 60 is treated as a long press. */
    fun tapPoint(x: Int, y: Int, durationMillis: Long): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f)
        }
        return dispatchSimpleGesture(path, 0L, durationMillis)
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Long): Boolean {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        return dispatchSimpleGesture(path, 0L, durationMillis)
    }

    /** Sets text on an editable node; returns what `performAction` actually reported. */
    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
