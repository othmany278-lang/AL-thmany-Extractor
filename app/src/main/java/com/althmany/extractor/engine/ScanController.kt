package com.althmany.extractor.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.althmany.extractor.accessibility.AccessibilityRuntimeBridge
import com.althmany.extractor.accessibility.WhatsAppAccessibilityService
import com.althmany.extractor.data.ExtractorRepository
import com.althmany.extractor.data.InviteKind
import com.althmany.extractor.data.ScanRecord
import com.althmany.extractor.data.ScanStatus
import com.althmany.extractor.profile.WhatsAppInstanceRegistry
import com.althmany.extractor.notification.ScanNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Professional read-only WhatsApp invite scanner.
 *
 * Guarantees by design:
 *  - never clicks Join / Request-to-join;
 *  - launches only the explicitly selected WhatsApp package;
 *  - retries only uncertain/transient outcomes;
 *  - stores confidence, signal, metadata and duration for auditability;
 *  - interrupted SCANNING rows are returned to PENDING on the next run.
 */
object ScanController {
    private lateinit var appContext: Context
    private lateinit var repository: ExtractorRepository
    private lateinit var settingsStore: ScanSettingsStore
    private lateinit var notifier: ScanNotifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val adapter = WhatsAppUiAdapter()
    private var service: WhatsAppAccessibilityService? = null
    private var job: Job? = null
    @Volatile private var pauseRequested = false

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun initialize(context: Context, repo: ExtractorRepository) {
        appContext = context.applicationContext
        repository = repo
        settingsStore = ScanSettingsStore(appContext)
        notifier = ScanNotifier(appContext)
        _state.value = _state.value.copy(
            speed = settingsStore.loadSpeed(),
            scope = settingsStore.loadScope(),
            maxAttempts = settingsStore.loadMaxAttempts()
        )
        refreshStats()
    }

    fun attachService(value: WhatsAppAccessibilityService) {
        service = value
        _state.value = _state.value.copy(serviceConnected = true)
    }

    fun detachService(value: WhatsAppAccessibilityService) {
        if (service === value) service = null
        _state.value = _state.value.copy(serviceConnected = false)
    }

    fun notifyUiEvent(packageName: CharSequence?) {
        val observed = packageName?.toString() ?: return
        val expected = ExtractionController.state.value.selectedWhatsAppPackage ?: return
        if (observed == expected) uiEvents.tryEmit(Unit)
    }

    private fun recoverLiveService(): WhatsAppAccessibilityService? {
        val live = service ?: AccessibilityRuntimeBridge.currentEvenIfQuiet()
        if (live != null && service !== live) attachService(live)
        return live
    }

    private suspend fun ensureRuntimeReady(timeoutMs: Long = 5_000L): Boolean {
        if (!ExtractionController.openWhatsApp()) return false
        recoverLiveService()?.let { return true }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(75L)
            recoverLiveService()?.let { return true }
        }
        return recoverLiveService() != null
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun setSpeed(value: ScanSpeedProfile) {
        if (isRunning()) return
        settingsStore.saveSpeed(value)
        _state.value = _state.value.copy(speed = value)
    }

    fun setScope(value: ScanScope) {
        if (isRunning()) return
        settingsStore.saveScope(value)
        _state.value = _state.value.copy(scope = value)
    }

    fun setMaxAttempts(value: Int) {
        if (isRunning()) return
        val clean = value.coerceIn(1, 5)
        settingsStore.saveMaxAttempts(clean)
        _state.value = _state.value.copy(maxAttempts = clean)
    }

    fun start() {
        if (job?.isActive == true) return
        if (ExtractionController.isBusy() || PublishController.isRunning()) {
            _state.value = _state.value.copy(status = ScanEngineStatus.ERROR, message = "أوقف الاستخراج أو النشر أولاً قبل تشغيل الفحص")
            return
        }
        if (ExtractionController.state.value.selectedWhatsAppPackage == null) {
            _state.value = _state.value.copy(status = ScanEngineStatus.ERROR, message = "اختر نسخة واتساب أولاً")
            return
        }
        if (!RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.SCAN)) {
            val owner = RuntimeOperationCoordinator.current()?.labelAr ?: "عملية أخرى"
            _state.value = _state.value.copy(status = ScanEngineStatus.ERROR, message = "لا يمكن بدء الفحص أثناء تشغيل $owner")
            return
        }
        pauseRequested = false
        job = scope.launch { runScan() }.also { activeJob ->
            activeJob.invokeOnCompletion { RuntimeOperationCoordinator.release(RuntimeOperation.SCAN) }
        }
    }

    fun pause() {
        if (job?.isActive != true) return
        pauseRequested = true
        _state.value = _state.value.copy(status = ScanEngineStatus.PAUSED, paused = true, message = "الفحص متوقف مؤقتًا")
        notifier.show(_state.value)
    }

    fun resume() {
        if (job?.isActive != true) return
        pauseRequested = false
        _state.value = _state.value.copy(status = ScanEngineStatus.CLASSIFYING, paused = false, message = "استكمال الفحص")
        notifier.show(_state.value)
    }

    fun stop() {
        job?.cancel()
        job = null
        RuntimeOperationCoordinator.release(RuntimeOperation.SCAN)
        pauseRequested = false
        _state.value = _state.value.copy(
            status = ScanEngineStatus.STOPPED,
            running = false,
            paused = false,
            currentUrl = null,
            currentAttempt = 0,
            message = "تم إيقاف الفحص — يمكن بدءه لاحقًا لاستكمال العناصر غير المكتملة"
        )
        notifier.cancel()
        refreshStats()
    }

    fun refreshStats() {
        if (!::repository.isInitialized) return
        scope.launch { _state.value = _state.value.copy(stats = repository.scanStats()) }
    }

    private suspend fun runScan() {
        try {
            if (!ensureRuntimeReady()) {
                _state.value = _state.value.copy(
                    status = ScanEngineStatus.ERROR,
                    running = false,
                    message = "تعذر فتح واتساب أو توصيل Accessibility داخل نفس البيئة"
                )
                return
            }
            repository.resetScanRunningItems()
            val configuredScope = _state.value.scope
            val scanScope = if (configuredScope == ScanScope.RECHECK_ALL) {
                // RECHECK_ALL is a one-shot command. Convert it to PENDING_ONLY immediately after
                // resetting so an interrupted job resumes from the remaining rows instead of
                // resetting the whole queue again on the next Start.
                repository.prepareRecheckAll()
                settingsStore.saveScope(ScanScope.PENDING_ONLY)
                _state.value = _state.value.copy(scope = ScanScope.PENDING_ONLY)
                ScanScope.PENDING_ONLY
            } else configuredScope
            val items = repository.scanItemsForScope(scanScope)
            if (items.isEmpty()) {
                _state.value = _state.value.copy(status = ScanEngineStatus.COMPLETED, running = false, message = "لا توجد روابط مطابقة لنطاق الفحص")
                refreshStats(); return
            }

            val speed = _state.value.speed
            val maxAttempts = _state.value.maxAttempts
            _state.value = _state.value.copy(
                status = ScanEngineStatus.PREPARING,
                running = true,
                paused = false,
                total = items.size,
                currentIndex = 0,
                currentAttempt = 0,
                message = "تحضير ${items.size} رابط • ${configuredScope.labelAr} • ${speed.labelAr}"
            )
            notifier.show(_state.value)

            for ((index, item) in items.withIndex()) {
                waitIfPaused()
                awaitNetworkAvailability()
                var finalResult: TimedDecision? = null

                for (attempt in 1..maxAttempts) {
                    waitIfPaused()
                    _state.value = _state.value.copy(
                        status = ScanEngineStatus.OPENING,
                        running = true,
                        currentUrl = item.normalizedUrl,
                        currentIndex = index + 1,
                        total = items.size,
                        currentAttempt = attempt,
                        currentConfidence = 0,
                        message = "فحص ${index + 1}/${items.size} • محاولة $attempt/$maxAttempts"
                    )
                    notifier.show(_state.value)
                    repository.markScanAttempt(
                        id = item.id,
                        detail = "جارٍ الفحص — محاولة $attempt/$maxAttempts",
                        targetPackage = ExtractionController.state.value.selectedWhatsAppPackage
                    )

                    val result = scanOne(item, speed)
                    finalResult = result
                    val d = result.decision
                    _state.value = _state.value.copy(currentConfidence = d.confidence)

                    repository.updateScanResult(
                        id = item.id,
                        status = d.status,
                        groupName = d.groupName,
                        detail = d.detail,
                        incrementAttempt = false,
                        confidence = d.confidence,
                        memberCountText = d.memberCountText,
                        inviteKind = d.inviteKind,
                        signalCode = d.signalCode,
                        durationMs = result.durationMs,
                        targetPackage = ExtractionController.state.value.selectedWhatsAppPackage
                    )
                    refreshStats()

                    if (!ScanRetryPolicy.shouldRetry(d.status, attempt, maxAttempts)) break

                    _state.value = _state.value.copy(
                        status = ScanEngineStatus.RETRYING,
                        message = "${d.status.labelAr} — إعادة تحقق ذكية (${attempt + 1}/$maxAttempts)"
                    )
                    safelyReturnFromInvite(speed)
                    delay(ScanRetryPolicy.backoffMs(d.status, attempt, speed))
                }

                val last = finalResult?.decision
                if (last != null) {
                    _state.value = _state.value.copy(
                        message = "${last.status.labelAr} • ثقة ${last.confidence}%",
                        currentConfidence = last.confidence
                    )
                    notifier.show(_state.value)
                }
                safelyReturnFromInvite(speed)
                delay(speed.settleDelayMs)
            }

            _state.value = _state.value.copy(
                status = ScanEngineStatus.COMPLETED,
                running = false,
                paused = false,
                currentUrl = null,
                currentAttempt = 0,
                currentConfidence = 0,
                message = "اكتمل فحص الروابط"
            )
            notifier.show(_state.value, ongoing = false)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                status = ScanEngineStatus.ERROR,
                running = false,
                paused = false,
                message = t.message ?: "تعذر إكمال الفحص"
            )
            notifier.show(_state.value, ongoing = false)
        } finally {
            job = null
            refreshStats()
        }
    }

    private data class TimedDecision(val decision: InviteScanDecision, val durationMs: Long)

    private suspend fun scanOne(item: ScanRecord, speed: ScanSpeedProfile): TimedDecision {
        val started = SystemClock.uptimeMillis()
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage
            ?: return TimedDecision(
                InviteScanDecision(ScanStatus.ERROR, "لم يتم تحديد نسخة واتساب", true, 100, "NO_TARGET_PACKAGE"),
                0L
            )

        awaitNetworkAvailability()
        if (!openInvite(item.normalizedUrl, packageName)) {
            return TimedDecision(
                InviteScanDecision(
                    ScanStatus.ERROR,
                    "تعذر فتح الرابط في ${WhatsAppInstanceRegistry.labelFor(packageName)}",
                    true,
                    100,
                    "LAUNCH_FAILED"
                ),
                SystemClock.uptimeMillis() - started
            )
        }

        _state.value = _state.value.copy(status = ScanEngineStatus.CLASSIFYING, message = "تحليل شاشة الدعوة")
        var deadline = SystemClock.uptimeMillis() + speed.previewTimeoutMs
        var last = InviteScanDecision(ScanStatus.UNKNOWN, "بانتظار ظهور حالة الدعوة", false, 0, "WAITING")
        var stableSignature = 0
        var stableRounds = 0
        val stableThreshold = when (speed) {
            ScanSpeedProfile.HYPER -> 2
            ScanSpeedProfile.ADAPTIVE -> 4
            ScanSpeedProfile.SAFE -> 6
        }
        // Even a strong Join/Request/Expired label passes through a short stability gate. This
        // prevents one transient accessibility mutation from becoming the committed result while
        // still allowing clear cases to finish far below the 5.6s adaptive ceiling.
        val definitiveStableThreshold = when (speed) {
            ScanSpeedProfile.HYPER -> 1
            ScanSpeedProfile.ADAPTIVE -> 2
            ScanSpeedProfile.SAFE -> 3
        }
        var definitiveSignature = 0
        var definitiveRounds = 0
        var definitiveCandidate: InviteScanDecision? = null

        while (SystemClock.uptimeMillis() < deadline) {
            waitIfPaused()
            if (!isNetworkAvailable()) {
                _state.value = _state.value.copy(
                    status = ScanEngineStatus.WAITING_NETWORK,
                    message = "انقطع الاتصال — حفظ الرابط الحالي وانتظار رجوع الإنترنت"
                )
                awaitNetworkAvailability()
                // Re-process the exact same URL after connectivity returns. No result is committed
                // and the queue index is not advanced while the connection is unavailable.
                safelyReturnFromInvite(speed)
                if (!openInvite(item.normalizedUrl, packageName)) {
                    return TimedDecision(
                        InviteScanDecision(ScanStatus.ERROR, "عاد الاتصال لكن تعذر إعادة فتح نفس الرابط", true, 100, "REOPEN_FAILED"),
                        SystemClock.uptimeMillis() - started
                    )
                }
                stableSignature = 0
                stableRounds = 0
                last = InviteScanDecision(ScanStatus.UNKNOWN, "إعادة قراءة الرابط بعد عودة الاتصال", false, 0, "WAITING")
                deadline = SystemClock.uptimeMillis() + speed.previewTimeoutMs
                _state.value = _state.value.copy(status = ScanEngineStatus.CLASSIFYING, message = "عاد الاتصال — إعادة تحليل نفس الرابط")
            }
            val root = service?.currentRoot()
            if (root != null && adapter.isWhatsAppRoot(root, packageName)) {
                val snap = adapter.snapshot(root)
                val decision = InviteScanClassifier.classify(snap.texts)
                last = chooseBetter(last, decision)
                _state.value = _state.value.copy(currentConfidence = last.confidence)
                if (decision.definitive) {
                    val sameFact = definitiveCandidate?.status == decision.status &&
                        definitiveCandidate?.groupName == decision.groupName &&
                        definitiveCandidate?.inviteKind == decision.inviteKind
                    if (sameFact && snap.signature != 0 && snap.signature == definitiveSignature) {
                        definitiveRounds++
                    } else {
                        definitiveCandidate = decision
                        definitiveSignature = snap.signature
                        definitiveRounds = 0
                    }
                    if (definitiveRounds >= definitiveStableThreshold) {
                        return TimedDecision(definitiveCandidate ?: decision, SystemClock.uptimeMillis() - started)
                    }
                } else {
                    definitiveCandidate = null
                    definitiveSignature = 0
                    definitiveRounds = 0
                }

                if (snap.signature != 0 && snap.signature == stableSignature) {
                    stableRounds++
                } else {
                    stableSignature = snap.signature
                    stableRounds = 0
                }

                // Stop waiting only when WhatsApp is visibly stable and the preview contains enough
                // structure. A transient blank/loading root must not become UNKNOWN prematurely.
                if (stableRounds >= stableThreshold && snap.visibleNodeCount > 15 && last.confidence >= 25) break
            }
            withTimeoutOrNull(speed.eventWaitMs) { uiEvents.first() }
            delay(speed.settleDelayMs)
        }

        val final = last.copy(
            status = if (last.status == ScanStatus.UNKNOWN) ScanStatus.UNKNOWN else last.status,
            detail = if (last.status == ScanStatus.UNKNOWN) "لم تظهر علامة مؤكدة بعد انتظار شاشة مستقرة" else last.detail,
            definitive = true,
            signalCode = if (last.signalCode == "WAITING") "TIMEOUT_NO_SIGNAL" else last.signalCode
        )
        return TimedDecision(final, SystemClock.uptimeMillis() - started)
    }

    private fun chooseBetter(a: InviteScanDecision, b: InviteScanDecision): InviteScanDecision {
        if (b.definitive && !a.definitive) return b
        if (a.definitive && !b.definitive) return a
        return if (b.confidence >= a.confidence) b else a
    }

    private fun openInvite(url: String, packageName: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

    private suspend fun safelyReturnFromInvite(speed: ScanSpeedProfile) {
        val svc = service ?: return
        repeat(2) {
            val root = svc.currentRoot() ?: return@repeat
            val pkg = root.packageName?.toString()
            if (!WhatsAppInstanceRegistry.isSupportedPackage(pkg)) return
            svc.performBack()
            delay(speed.settleDelayMs + 25L)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun awaitNetworkAvailability() {
        while (!isNetworkAvailable()) {
            waitIfPaused()
            _state.value = _state.value.copy(
                status = ScanEngineStatus.WAITING_NETWORK,
                running = true,
                message = "لا يوجد اتصال إنترنت — لن يتم تصنيف الرابط كتالف، بانتظار الشبكة"
            )
            notifier.show(_state.value)
            delay(650L)
        }
    }

    private suspend fun waitIfPaused() {
        while (pauseRequested) delay(120)
    }
}
