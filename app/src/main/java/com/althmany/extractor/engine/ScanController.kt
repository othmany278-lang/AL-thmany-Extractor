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
import com.althmany.extractor.shizuku.ShizukuBridge
import com.althmany.extractor.shizuku.ShizukuUiRuntime
import com.althmany.extractor.shizuku.ShizukuUiTree
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
 * WhatsApp invite scanner with three explicit modes: scan only, join only, and scan + join.
 *
 * Guarantees by design:
 *  - membership actions run only when the selected action mode permits them;
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
    private val shizukuUi: ShizukuUiRuntime by lazy { ShizukuUiRuntime(appContext) }
    @Volatile private var shizukuMode = false
    private var service: WhatsAppAccessibilityService? = null
    private var job: Job? = null
    @Volatile private var pauseRequested = false
    private const val POST_ACTION_GRACE_MS = 650L
    private const val MAX_POSITIVE_FOLLOW_UPS = 2

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun initialize(context: Context, repo: ExtractorRepository) {
        appContext = context.applicationContext
        repository = repo
        settingsStore = ScanSettingsStore(appContext)
        notifier = ScanNotifier(appContext)
        val loadedMode = settingsStore.loadActionMode()
        val loadedRequestToJoin = settingsStore.loadRequestToJoinEnabled()
        val effectiveRequestToJoin = loadedMode == ScanActionMode.JOIN_ONLY || loadedRequestToJoin
        if (effectiveRequestToJoin != loadedRequestToJoin) settingsStore.saveRequestToJoinEnabled(effectiveRequestToJoin)
        _state.value = _state.value.copy(
            speed = settingsStore.loadSpeed(),
            scope = settingsStore.loadScope(),
            maxAttempts = settingsStore.loadMaxAttempts(),
            actionMode = loadedMode,
            requestToJoinEnabled = effectiveRequestToJoin
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

    private suspend fun ensureRuntimeReady(timeoutMs: Long = 8_500L): Boolean {
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage ?: run {
            _state.value = _state.value.copy(message = "RUNTIME_NO_TARGET: اختر نسخة واتساب أولاً")
            return false
        }
        var opened = ExtractionController.openWhatsApp()
        if (!opened && ShizukuBridge.status().ready) {
            opened = ShizukuBridge.launchPackage(appContext, packageName)
        }
        if (!opened) {
            _state.value = _state.value.copy(message = "RUNTIME_OPEN_FAILED: تعذر فتح نسخة واتساب المحددة")
            return false
        }

        val accessDeadline = SystemClock.elapsedRealtime() + minOf(timeoutMs, 1_800L)
        while (SystemClock.elapsedRealtime() < accessDeadline) {
            val live = recoverLiveService()
            if (live != null && adapter.isWhatsAppRoot(live.currentRoot(), packageName)) {
                shizukuMode = false
                _state.value = _state.value.copy(message = "READY_ACCESSIBILITY: واتساب ظاهر لخدمة Accessibility")
                return true
            }
            withTimeoutOrNull(150L) { uiEvents.first() }
            delay(20L)
        }

        val sh = ShizukuBridge.status()
        if (sh.ready) {
            if (!ShizukuBridge.ensureBound(appContext, 4_500L)) {
                _state.value = _state.value.copy(message = "SHIZUKU_BIND_FAILED: الإذن موجود لكن UserService لم يرتبط")
            } else {
                ShizukuBridge.launchPackage(appContext, packageName)
                var lastDetail = "NO_SNAPSHOT"
                var resetTried = false
                val deadline = SystemClock.elapsedRealtime() + (timeoutMs - 1_800L).coerceAtLeast(5_000L)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val tree = shizukuUi.snapshot(packageName)
                    lastDetail = "${tree.state}:${tree.detail.take(140)}"
                    if (tree.state == "OK" && tree.nodes.isNotEmpty() && shizukuUi.isWhatsApp(tree, packageName)) {
                        shizukuMode = true
                        _state.value = _state.value.copy(message = "READY_SHIZUKU: UIAutomation يرى واتساب (${tree.nodes.size} node)")
                        return true
                    }
                    if (!resetTried && tree.state in setOf("UNAVAILABLE", "ERROR", "NO_ROOT")) {
                        resetTried = true
                        ShizukuBridge.reset(appContext)
                        ShizukuBridge.launchPackage(appContext, packageName)
                    }
                    delay(110L)
                }
                _state.value = _state.value.copy(message = "SHIZUKU_UI_NOT_READY: $lastDetail")
            }
        } else {
            _state.value = _state.value.copy(
                message = when {
                    !sh.binderAlive -> "SHIZUKU_BINDER_OFF: شغّل Shizuku"
                    !sh.permissionGranted -> "SHIZUKU_PERMISSION_DENIED: امنح التطبيق إذن Shizuku"
                    else -> "RUNTIME_NO_BACKEND: لا يوجد محرك تحكم جاهز"
                }
            )
        }
        shizukuMode = false
        return false
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

    fun setActionMode(value: ScanActionMode) {
        if (isRunning()) return
        settingsStore.saveActionMode(value)
        val requestEnabled = if (value == ScanActionMode.JOIN_ONLY) true else _state.value.requestToJoinEnabled
        if (value == ScanActionMode.JOIN_ONLY) settingsStore.saveRequestToJoinEnabled(true)
        _state.value = _state.value.copy(actionMode = value, requestToJoinEnabled = requestEnabled)
    }

    fun setRequestToJoinEnabled(value: Boolean) {
        if (isRunning()) return
        // JOIN_ONLY always includes approval links; do not allow the UI setting to disable them.
        val effective = if (_state.value.actionMode == ScanActionMode.JOIN_ONLY) true else value
        settingsStore.saveRequestToJoinEnabled(effective)
        _state.value = _state.value.copy(requestToJoinEnabled = effective)
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
            repository.resetScanRunningItems()
            val configuredScope = _state.value.scope
            val preflightScope = if (configuredScope == ScanScope.RECHECK_ALL) ScanScope.PENDING_ONLY else configuredScope
            if (configuredScope != ScanScope.RECHECK_ALL && repository.scanItemsForScope(preflightScope).isEmpty()) {
                _state.value = _state.value.copy(
                    status = ScanEngineStatus.COMPLETED,
                    running = false,
                    message = "NO_SCAN_ITEMS: أضف روابط للفحص أولاً"
                )
                refreshStats()
                return
            }
            if (!ensureRuntimeReady()) {
                val detail = _state.value.message
                _state.value = _state.value.copy(
                    status = ScanEngineStatus.ERROR,
                    running = false,
                    message = if (detail.isBlank()) "RUNTIME_NOT_READY: تعذر تجهيز محرك الفحص" else detail
                )
                return
            }
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
                // Event-first: انتقل للرابط التالي بدون Delay صناعي ثانٍ.
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
        if (shizukuMode) return scanOneShizuku(item, speed)
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
                        val stableDecision = definitiveCandidate ?: decision
                        val acted = maybeApplyMembershipAction(stableDecision, speed, packageName)
                        return TimedDecision(acted, SystemClock.uptimeMillis() - started)
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
        val actedFinal = maybeApplyMembershipAction(final, speed, packageName)
        return TimedDecision(actedFinal, SystemClock.uptimeMillis() - started)
    }

    private suspend fun maybeApplyMembershipAction(
        decision: InviteScanDecision,
        speed: ScanSpeedProfile,
        packageName: String
    ): InviteScanDecision {
        if (shizukuMode) return maybeApplyMembershipActionShizuku(decision, speed, packageName)
        val mode = _state.value.actionMode
        if (mode == ScanActionMode.SCAN_ONLY) return decision
        if (decision.status == ScanStatus.ALREADY_MEMBER || decision.status == ScanStatus.JOINED || decision.status == ScanStatus.REQUEST_PENDING) {
            return decision
        }

        val approval = decision.status == ScanStatus.APPROVAL
        val direct = decision.status == ScanStatus.DIRECT
        if (!approval && !direct) return decision

        // JOIN_ONLY means perform every supported membership action, including Request to join.
        // In SCAN_AND_JOIN the explicit approval toggle is still respected.
        val approvalAllowed = mode == ScanActionMode.JOIN_ONLY || _state.value.requestToJoinEnabled
        if (approval && !approvalAllowed) {
            return decision.copy(
                detail = "${decision.detail} — إرسال طلب الانضمام معطل من الإعدادات",
                signalCode = "APPROVAL_ACTION_DISABLED"
            )
        }

        val svc = service ?: return decision.copy(
            status = ScanStatus.ERROR,
            detail = "تعذر تنفيذ الإجراء: Accessibility غير متصلة",
            signalCode = "ACTION_NO_SERVICE",
            definitive = true
        )
        val root = svc.currentRoot()
        _state.value = _state.value.copy(
            status = ScanEngineStatus.CLASSIFYING,
            message = if (approval) "طلب الانضمام • تنفيذ ثم متابعة نفس الرابط" else "الانضمام • تنفيذ ثم متابعة نفس الرابط"
        )
        if (!adapter.inviteActionAvailable(root, approval) || !adapter.clickInviteAction(root, approval)) {
            return decision.copy(
                status = ScanStatus.ACTION_UNCERTAIN,
                detail = "لم يتم العثور على زر الإجراء أو تعذر ضغطه عبر Accessibility",
                signalCode = "ACTION_SEMANTIC_CLICK_FAILED",
                definitive = true
            )
        }
        delay(POST_ACTION_GRACE_MS)

        val deadline = SystemClock.uptimeMillis() + when (speed) {
            ScanSpeedProfile.HYPER -> 3_500L
            ScanSpeedProfile.ADAPTIVE -> 5_000L
            ScanSpeedProfile.SAFE -> 7_000L
        }
        var bestPost = decision
        var positiveFollowUps = 0
        while (SystemClock.uptimeMillis() < deadline) {
            waitIfPaused()
            val current = svc.currentRoot()
            if (current != null && adapter.isWhatsAppRoot(current, packageName)) {
                if (positiveFollowUps < MAX_POSITIVE_FOLLOW_UPS &&
                    adapter.inviteConfirmationAvailable(current) &&
                    adapter.clickInviteConfirmation(current)
                ) {
                    positiveFollowUps++
                    _state.value = _state.value.copy(message = "Continue/Confirm على نفس الرابط • $positiveFollowUps/$MAX_POSITIVE_FOLLOW_UPS")
                    delay(POST_ACTION_GRACE_MS)
                    continue
                }
                val post = InviteScanClassifier.classify(adapter.snapshot(current).texts)
                bestPost = chooseBetter(bestPost, post)
                if (!approval) {
                    val chatVisible = decision.groupName?.let { adapter.isConversationOpenForTarget(current, it, packageName) } == true
                    if (post.status == ScanStatus.ALREADY_MEMBER || chatVisible) {
                        return decision.copy(
                            status = ScanStatus.JOINED,
                            detail = "تم الانضمام والتحقق من انتقال واتساب إلى القروب",
                            signalCode = "JOIN_VERIFIED",
                            confidence = 100,
                            definitive = true
                        )
                    }
                } else if (post.status == ScanStatus.REQUEST_PENDING) {
                    return post.copy(
                        detail = "تم إرسال طلب الانضمام والتحقق من أنه قيد المراجعة",
                        signalCode = "REQUEST_VERIFIED",
                        confidence = 100,
                        definitive = true,
                        groupName = post.groupName ?: decision.groupName,
                        memberCountText = post.memberCountText ?: decision.memberCountText,
                        inviteKind = if (post.inviteKind == InviteKind.UNKNOWN) decision.inviteKind else post.inviteKind
                    )
                }
                if (post.status in setOf(ScanStatus.INVALID, ScanStatus.FULL, ScanStatus.REMOVED, ScanStatus.ACCOUNT_LIMIT)) {
                    return post
                }
            }
            withTimeoutOrNull(speed.eventWaitMs) { uiEvents.first() }
            delay(speed.settleDelayMs)
        }

        return decision.copy(
            status = ScanStatus.ACTION_UNCERTAIN,
            detail = if (approval)
                "تم ضغط طلب الانضمام لكن لم تظهر إشارة تحقق نهائية؛ لن يعاد الضغط تلقائيًا"
            else
                "تم ضغط الانضمام لكن لم تظهر إشارة تحقق نهائية؛ لن يعاد الضغط تلقائيًا",
            signalCode = if (approval) "REQUEST_ACTION_UNCERTAIN" else "JOIN_ACTION_UNCERTAIN",
            confidence = maxOf(decision.confidence, bestPost.confidence),
            definitive = true
        )
    }


    private suspend fun awaitShizukuTree(packageName: String, timeoutMs: Long = 3_000L): ShizukuUiTree? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val tree = shizukuUi.snapshot(packageName)
            if (tree.state == "OK" && tree.nodes.isNotEmpty() && shizukuUi.isWhatsApp(tree, packageName)) return tree
            delay(75L)
        }
        return null
    }

    private suspend fun scanOneShizuku(item: ScanRecord, speed: ScanSpeedProfile): TimedDecision {
        val started = SystemClock.uptimeMillis()
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage
            ?: return TimedDecision(InviteScanDecision(ScanStatus.ERROR, "لم يتم تحديد نسخة واتساب", true, 100, "NO_TARGET_PACKAGE"), 0L)
        awaitNetworkAvailability()
        if (!openInvite(item.normalizedUrl, packageName)) {
            return TimedDecision(InviteScanDecision(ScanStatus.ERROR, "تعذر فتح الرابط في ${WhatsAppInstanceRegistry.labelFor(packageName)}", true, 100, "LAUNCH_FAILED"), SystemClock.uptimeMillis()-started)
        }
        _state.value = _state.value.copy(status = ScanEngineStatus.CLASSIFYING, message = "Shizuku: تحليل شاشة الدعوة")
        var deadline = SystemClock.uptimeMillis() + speed.previewTimeoutMs
        var last = InviteScanDecision(ScanStatus.UNKNOWN, "بانتظار ظهور حالة الدعوة", false, 0, "WAITING")
        var stableSignature = 0
        var stableRounds = 0
        val stableThreshold = when(speed){ScanSpeedProfile.HYPER->2;ScanSpeedProfile.ADAPTIVE->4;ScanSpeedProfile.SAFE->6}
        val definitiveThreshold = when(speed){ScanSpeedProfile.HYPER->1;ScanSpeedProfile.ADAPTIVE->2;ScanSpeedProfile.SAFE->3}
        var definitiveSignature=0; var definitiveRounds=0; var definitiveCandidate:InviteScanDecision?=null
        var sequence = shizukuUi.eventSequence(packageName)

        while(SystemClock.uptimeMillis()<deadline){
            waitIfPaused()
            if(!isNetworkAvailable()){
                _state.value=_state.value.copy(status=ScanEngineStatus.WAITING_NETWORK,message="انقطع الاتصال — انتظار الشبكة بدون تصنيف الرابط كتالف")
                awaitNetworkAvailability(); safelyReturnFromInvite(speed)
                if(!openInvite(item.normalizedUrl,packageName)) return TimedDecision(InviteScanDecision(ScanStatus.ERROR,"عاد الاتصال لكن تعذر فتح نفس الرابط",true,100,"REOPEN_FAILED"),SystemClock.uptimeMillis()-started)
                stableSignature=0;stableRounds=0;definitiveSignature=0;definitiveRounds=0;definitiveCandidate=null;deadline=SystemClock.uptimeMillis()+speed.previewTimeoutMs
            }
            val frame=shizukuUi.waitFrame(packageName,sequence,speed.eventWaitMs.toInt().coerceAtLeast(40));sequence=frame.first
            val tree=frame.second.takeIf{it.state=="OK"}?:awaitShizukuTree(packageName,500L)
            if(tree!=null){
                val decision=InviteScanClassifier.classify(tree.texts);last=chooseBetter(last,decision);_state.value=_state.value.copy(currentConfidence=last.confidence)
                if(decision.definitive){
                    val same=definitiveCandidate?.status==decision.status&&definitiveCandidate?.groupName==decision.groupName&&definitiveCandidate?.inviteKind==decision.inviteKind
                    if(same&&tree.signature!=0&&tree.signature==definitiveSignature)definitiveRounds++ else {definitiveCandidate=decision;definitiveSignature=tree.signature;definitiveRounds=0}
                    if(definitiveRounds>=definitiveThreshold){val d=definitiveCandidate?:decision;return TimedDecision(maybeApplyMembershipActionShizuku(d,speed,packageName),SystemClock.uptimeMillis()-started)}
                } else {definitiveCandidate=null;definitiveSignature=0;definitiveRounds=0}
                if(tree.signature!=0&&tree.signature==stableSignature)stableRounds++ else {stableSignature=tree.signature;stableRounds=0}
                if(stableRounds>=stableThreshold&&tree.visibleNodeCount>12&&last.confidence>=25)break
            }
            delay(speed.settleDelayMs)
        }
        val final=last.copy(status=last.status,detail=if(last.status==ScanStatus.UNKNOWN)"Shizuku: لم تظهر علامة مؤكدة بعد انتظار شاشة مستقرة" else last.detail,definitive=true,signalCode=if(last.signalCode=="WAITING")"TIMEOUT_NO_SIGNAL" else last.signalCode)
        return TimedDecision(maybeApplyMembershipActionShizuku(final,speed,packageName),SystemClock.uptimeMillis()-started)
    }

    private suspend fun maybeApplyMembershipActionShizuku(decision: InviteScanDecision, speed: ScanSpeedProfile, packageName: String): InviteScanDecision {
        val mode=_state.value.actionMode
        if(mode==ScanActionMode.SCAN_ONLY)return decision
        if(decision.status in setOf(ScanStatus.ALREADY_MEMBER,ScanStatus.JOINED,ScanStatus.REQUEST_PENDING))return decision
        val approval=decision.status==ScanStatus.APPROVAL;val direct=decision.status==ScanStatus.DIRECT
        if(!approval&&!direct)return decision
        val approvalAllowed = mode==ScanActionMode.JOIN_ONLY || _state.value.requestToJoinEnabled
        if(approval&&!approvalAllowed)return decision.copy(detail="${decision.detail} — إرسال طلب الانضمام معطل",signalCode="APPROVAL_ACTION_DISABLED")
        var tree=awaitShizukuTree(packageName,900L)
        _state.value=_state.value.copy(message=if(approval)"Shizuku: طلب انضمام • UI ثم shell fallback" else "Shizuku: انضمام • UI ثم shell fallback")
        val firstAction = when {
            tree != null && shizukuUi.inviteActionAvailable(tree,approval) &&
                shizukuUi.clickInviteAction(tree,packageName,approval) -> true
            else -> shizukuUi.shellClickInviteAction(packageName,approval)
        }
        if(!firstAction)return decision.copy(
            status=ScanStatus.ACTION_UNCERTAIN,
            detail="لم ينجح UIAutomation أو shell semantic fallback",
            signalCode="SHIZUKU_ACTION_ALL_LANES_FAILED",
            definitive=true
        )
        delay(POST_ACTION_GRACE_MS)
        val deadline=SystemClock.uptimeMillis()+when(speed){ScanSpeedProfile.HYPER->3500L;ScanSpeedProfile.ADAPTIVE->5000L;ScanSpeedProfile.SAFE->7000L}
        var best=decision
        var positiveFollowUps=0
        while(SystemClock.uptimeMillis()<deadline){
            val fresh=awaitShizukuTree(packageName,600L)
            if(fresh!=null)tree=fresh
            val currentTree=tree
            if(currentTree==null){
                if(positiveFollowUps<1 && shizukuUi.shellClickInviteConfirmation(packageName)){
                    positiveFollowUps++
                    delay(POST_ACTION_GRACE_MS)
                    continue
                }
                delay(speed.settleDelayMs)
                continue
            }
            if(positiveFollowUps<MAX_POSITIVE_FOLLOW_UPS &&
                shizukuUi.inviteConfirmationAvailable(currentTree) &&
                shizukuUi.clickInviteConfirmation(currentTree,packageName)
            ){
                positiveFollowUps++
                _state.value=_state.value.copy(message="Shizuku: Continue/Confirm على نفس الرابط • $positiveFollowUps/$MAX_POSITIVE_FOLLOW_UPS")
                delay(POST_ACTION_GRACE_MS)
                continue
            }
            val post=InviteScanClassifier.classify(currentTree.texts);best=chooseBetter(best,post)
            if(!approval){val chat=decision.groupName?.let{shizukuUi.isConversationOpenForTarget(currentTree,it,packageName)}==true;if(post.status==ScanStatus.ALREADY_MEMBER||chat)return decision.copy(status=ScanStatus.JOINED,detail="تم الانضمام والتحقق عبر Shizuku",signalCode="JOIN_VERIFIED",confidence=100,definitive=true)}
            else if(post.status==ScanStatus.REQUEST_PENDING)return post.copy(detail="تم إرسال الطلب والتحقق عبر Shizuku",signalCode="REQUEST_VERIFIED",confidence=100,definitive=true,groupName=post.groupName?:decision.groupName,memberCountText=post.memberCountText?:decision.memberCountText,inviteKind=if(post.inviteKind==InviteKind.UNKNOWN)decision.inviteKind else post.inviteKind)
            if(post.status in setOf(ScanStatus.INVALID,ScanStatus.FULL,ScanStatus.REMOVED,ScanStatus.ACCOUNT_LIMIT))return post
            delay(speed.settleDelayMs)
        }
        return decision.copy(status=ScanStatus.ACTION_UNCERTAIN,detail=if(approval)"تم ضغط الطلب لكن لم يظهر إثبات نهائي؛ لن يعاد تلقائيًا" else "تم ضغط الانضمام لكن لم يظهر إثبات نهائي؛ لن يعاد تلقائيًا",signalCode=if(approval)"REQUEST_ACTION_UNCERTAIN" else "JOIN_ACTION_UNCERTAIN",confidence=maxOf(decision.confidence,best.confidence),definitive=true)
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
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage ?: return
        if (shizukuMode) {
            val tree = awaitShizukuTree(packageName, 350L)
            if (tree != null && shizukuUi.clickInviteClose(tree, packageName)) {
                delay(maxOf(speed.settleDelayMs, 18L))
                return
            }
            ShizukuBridge.fastBack(appContext)
            delay(maxOf(speed.settleDelayMs, 18L))
            val after = awaitShizukuTree(packageName, 350L)
            if (after == null || !shizukuUi.isWhatsApp(after, packageName)) {
                ShizukuBridge.launchPackage(appContext, packageName)
            }
            return
        }
        val svc = service ?: recoverLiveService() ?: return
        val root = svc.currentRoot()
        if (root != null && adapter.isWhatsAppRoot(root, packageName) && adapter.clickInviteClose(root)) {
            withTimeoutOrNull(speed.eventWaitMs) { uiEvents.first() }
            delay(maxOf(speed.settleDelayMs, 18L))
            return
        }
        svc.performBack()
        withTimeoutOrNull(speed.eventWaitMs) { uiEvents.first() }
        delay(maxOf(speed.settleDelayMs, 18L))
        val after = svc.currentRoot()
        if (after == null || !adapter.isWhatsAppRoot(after, packageName)) {
            ExtractionController.openWhatsApp()
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
