package com.althmany.extractor.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.diagnostics.DiagnosticLog
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.engine.RuntimeOperation
import com.althmany.extractor.engine.RuntimeOperationCoordinator
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.profile.ProfileAccessibilityRuntime
import com.althmany.extractor.profile.WhatsAppInstanceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WhatsAppAccessibilityService : AccessibilityService() {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fallbackPollJob: Job? = null
    private var warmupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.record("ACCESSIBILITY", "service_onCreate")
        bindRuntime("onCreate")
        startFallbackPoll()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagnosticLog.record("ACCESSIBILITY", "service_onServiceConnected")
        bindRuntime("onServiceConnected")
        startFallbackPoll()
        startWarmupProbe()
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        bindRuntime("onRebind")
        startFallbackPoll()
        startWarmupProbe()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString()
        DiagnosticLog.recordAccessibilityEvent(packageName, safeEvent.eventType)
        AccessibilityRuntimeBridge.event(this, packageName)
        packageName?.let { runCatching { ProfileAccessibilityRuntime.recordEvent(this, it) } }
        attachControllersSafely("event")
        if (!WhatsAppInstanceRegistry.isSupportedPackage(packageName)) return
        routeEvent(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        DiagnosticLog.record("ACCESSIBILITY", "service_onUnbind")
        detachRuntime()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        DiagnosticLog.record("ACCESSIBILITY", "service_onDestroy")
        detachRuntime()
        runtimeScope.cancel()
        super.onDestroy()
    }

    private fun bindRuntime(source: String) {
        DiagnosticLog.record("ACCESSIBILITY_BIND", "source=$source")
        AccessibilityRuntimeBridge.bind(this)
        runCatching { ProfileAccessibilityRuntime.recordServiceConnected(this) }
            .onFailure { Log.w(TAG, "recordServiceConnected failed: $source", it) }
        attachControllersSafely(source)
    }

    private fun attachControllersSafely(source: String) {
        runCatching { ExtractionController.attachService(this) }
            .onFailure {
                Log.e(TAG, "Extraction attach failed: $source", it)
                DiagnosticLog.record("ACCESSIBILITY_ATTACH_ERROR", "Extraction source=$source", it)
            }
        runCatching { ScanController.attachService(this) }
            .onFailure {
                Log.e(TAG, "Scan attach failed: $source", it)
                DiagnosticLog.record("ACCESSIBILITY_ATTACH_ERROR", "Scan source=$source", it)
            }
        runCatching { PublishController.attachService(this) }
            .onFailure {
                Log.e(TAG, "Publish attach failed: $source", it)
                DiagnosticLog.record("ACCESSIBILITY_ATTACH_ERROR", "Publish source=$source", it)
            }
    }

    private fun detachRuntime() {
        fallbackPollJob?.cancel()
        fallbackPollJob = null
        warmupJob?.cancel()
        warmupJob = null
        AccessibilityRuntimeBridge.unbind(this)
        runCatching { ProfileAccessibilityRuntime.markDisconnected(this) }
        runCatching { ExtractionController.detachService(this) }
        runCatching { ScanController.detachService(this) }
        runCatching { PublishController.detachService(this) }
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
                else -> ExtractionController.notifyUiEvent(packageName)
            }
        }
    }

    private fun startWarmupProbe() {
        warmupJob?.cancel()
        warmupJob = runtimeScope.launch {
            repeat(24) {
                if (!isActive) return@launch
                AccessibilityRuntimeBridge.heartbeat()
                runCatching {
                    ProfileAccessibilityRuntime.heartbeat(
                        this@WhatsAppAccessibilityService,
                        force = true
                    )
                }
                attachControllersSafely("warmup")
                val root = rootInActiveWindow
                runCatching {
                    ProfileAccessibilityRuntime.recordRoot(
                        this@WhatsAppAccessibilityService,
                        root != null,
                        root?.packageName?.toString()
                    )
                }
                if (root != null) return@launch
                delay(250L)
            }
        }
    }

    private fun startFallbackPoll() {
        fallbackPollJob?.cancel()
        fallbackPollJob = runtimeScope.launch {
            while (isActive) {
                delay(FALLBACK_POLL_MS)
                AccessibilityRuntimeBridge.heartbeat()
                val root = rootInActiveWindow
                runCatching {
                    ProfileAccessibilityRuntime.recordRoot(
                        this@WhatsAppAccessibilityService,
                        root != null,
                        root?.packageName?.toString()
                    )
                }
                val pkg = root?.packageName?.toString()
                if (WhatsAppInstanceRegistry.isSupportedPackage(pkg)) {
                    attachControllersSafely("poll")
                    routeEvent(pkg)
                }
            }
        }
    }

    fun currentRoot(): AccessibilityNodeInfo? {
        AccessibilityRuntimeBridge.heartbeat()
        runCatching { ProfileAccessibilityRuntime.heartbeat(this) }
        val root = rootInActiveWindow
        runCatching {
            ProfileAccessibilityRuntime.recordRoot(this, root != null, root?.packageName?.toString())
        }
        return root
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun tapBounds(bounds: Rect?, durationMs: Long = 72L): Boolean {
        val b = bounds ?: return false
        if (b.width() <= 0 || b.height() <= 0) return false
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path, 0, durationMs.coerceIn(48L, 180L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeTowardOlderMessages(durationMs: Long): Boolean {
        val dm = resources.displayMetrics
        return dispatchSwipe(
            dm.widthPixels * 0.52f, dm.heightPixels * 0.34f,
            dm.widthPixels * 0.52f, dm.heightPixels * 0.78f, durationMs
        )
    }

    fun swipeChatListForward(durationMs: Long): Boolean {
        val dm = resources.displayMetrics
        return dispatchSwipe(
            dm.widthPixels * 0.52f, dm.heightPixels * 0.78f,
            dm.widthPixels * 0.52f, dm.heightPixels * 0.28f, durationMs
        )
    }

    fun swipeChatListBackward(durationMs: Long): Boolean {
        val dm = resources.displayMetrics
        return dispatchSwipe(
            dm.widthPixels * 0.52f, dm.heightPixels * 0.30f,
            dm.widthPixels * 0.52f, dm.heightPixels * 0.78f, durationMs
        )
    }

    private fun dispatchSwipe(
        fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path, 0, durationMs.coerceIn(72L, 900L)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "ALthmanyAccessibility"
        private const val FALLBACK_POLL_MS = 95L
    }
}
