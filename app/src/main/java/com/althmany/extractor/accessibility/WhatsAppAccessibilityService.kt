package com.althmany.extractor.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.engine.RuntimeOperation
import com.althmany.extractor.engine.RuntimeOperationCoordinator
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.profile.WhatsAppInstanceRegistry
import com.althmany.extractor.profile.ProfileAccessibilityRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Event-first, profile-local Accessibility runtime.
 *
 * Runtime behavior is intentionally aligned with the proven AL-thmany 2.8.0 architecture:
 *  - bind the live service instance as early as onCreate;
 *  - recover from delayed onServiceConnected callbacks on the first real WhatsApp event;
 *  - route events only to the operation that currently owns the WhatsApp UI;
 *  - coalesce normal event-driven work in each controller, with a small fallback heartbeat/poll;
 *  - never control a different Android user because the bridge is process/user local.
 *
 * The extractor keeps its own three functions (Extraction / Scan / Publish).  No Join behavior is
 * copied into this service.
 */
class WhatsAppAccessibilityService : AccessibilityService() {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fallbackPollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        bindRuntime()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bindRuntime()
        startFallbackPoll()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString()

        // A delivered AccessibilityEvent is definitive proof that this exact service instance is
        // alive in the current Android user/profile. This self-heals delayed Samsung callbacks.
        AccessibilityRuntimeBridge.event(this, packageName)
        packageName?.let { ProfileAccessibilityRuntime.recordEvent(this, it) }
        attachControllers()

        if (!WhatsAppInstanceRegistry.isSupportedPackage(packageName)) return
        routeEvent(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detachRuntime()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachRuntime()
        runtimeScope.cancel()
        super.onDestroy()
    }

    private fun bindRuntime() {
        AccessibilityRuntimeBridge.bind(this)
        ProfileAccessibilityRuntime.recordServiceConnected(this)
        attachControllers()
    }

    private fun detachRuntime() {
        fallbackPollJob?.cancel()
        fallbackPollJob = null
        AccessibilityRuntimeBridge.unbind(this)
        ProfileAccessibilityRuntime.markDisconnected(this)
        ExtractionController.detachService(this)
        ScanController.detachService(this)
        PublishController.detachService(this)
    }

    private fun attachControllers() {
        ExtractionController.attachService(this)
        ScanController.attachService(this)
        PublishController.attachService(this)
    }

    private fun routeEvent(packageName: String?) {
        when (RuntimeOperationCoordinator.current()) {
            RuntimeOperation.EXTRACTION -> ExtractionController.notifyUiEvent(packageName)
            RuntimeOperation.SCAN -> ScanController.notifyUiEvent(packageName)
            RuntimeOperation.PUBLISH -> PublishController.notifyUiEvent(packageName)
            null -> when {
                ExtractionController.isBusy() -> ExtractionController.notifyUiEvent(packageName)
                ScanController.isRunning() -> ScanController.notifyUiEvent(packageName)
                PublishController.isRunning() -> PublishController.notifyUiEvent(packageName)
                else -> ExtractionController.notifyUiEvent(packageName) // enables group sync wakeups
            }
        }
    }

    /**
     * Event-first remains authoritative. This is only a liveness fallback like the 2.8.0 poll path:
     * if WhatsApp changes without emitting a useful event, the active engine still gets a wake-up.
     */
    private fun startFallbackPoll() {
        fallbackPollJob?.cancel()
        fallbackPollJob = runtimeScope.launch {
            while (isActive) {
                delay(FALLBACK_POLL_MS)
                AccessibilityRuntimeBridge.heartbeat()
                val pkg = rootInActiveWindow?.packageName?.toString()
                if (WhatsAppInstanceRegistry.isSupportedPackage(pkg)) {
                    routeEvent(pkg)
                }
            }
        }
    }

    fun currentRoot(): AccessibilityNodeInfo? {
        AccessibilityRuntimeBridge.heartbeat()
        ProfileAccessibilityRuntime.heartbeat(this)
        val root = rootInActiveWindow
        ProfileAccessibilityRuntime.recordRoot(this, root != null, root?.packageName?.toString())
        return root
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Tap fallback for WhatsApp nodes whose Accessibility ACTION_CLICK is not exposed. */
    fun tapBounds(bounds: Rect?, durationMs: Long = 72L): Boolean {
        val b = bounds ?: return false
        if (b.width() <= 0 || b.height() <= 0) return false
        val x = b.exactCenterX()
        val y = b.exactCenterY()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(48L, 180L)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Gesture fallback for WhatsApp builds whose message RecyclerView does not expose ACTION_SCROLL_BACKWARD. */
    fun swipeTowardOlderMessages(durationMs: Long): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.52f
        val fromY = dm.heightPixels * 0.34f
        val toY = dm.heightPixels * 0.78f
        return dispatchSwipe(x, fromY, x, toY, durationMs)
    }

    /** Gesture fallback for moving down through the main chat list during synchronization. */
    fun swipeChatListForward(durationMs: Long): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.52f
        val fromY = dm.heightPixels * 0.78f
        val toY = dm.heightPixels * 0.28f
        return dispatchSwipe(x, fromY, x, toY, durationMs)
    }

    /** Gesture fallback for restoring the chat list toward the position visible before sync. */
    fun swipeChatListBackward(durationMs: Long): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.52f
        val fromY = dm.heightPixels * 0.30f
        val toY = dm.heightPixels * 0.78f
        return dispatchSwipe(x, fromY, x, toY, durationMs)
    }

    private fun dispatchSwipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    durationMs.coerceIn(72L, 900L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val FALLBACK_POLL_MS = 95L
    }
}
