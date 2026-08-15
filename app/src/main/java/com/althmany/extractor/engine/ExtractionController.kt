package com.althmany.extractor.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.althmany.extractor.accessibility.AccessibilityRuntimeBridge
import com.althmany.extractor.accessibility.WhatsAppAccessibilityService
import com.althmany.extractor.data.EngineStatus
import com.althmany.extractor.data.ExtractionMode
import com.althmany.extractor.data.ExtractionPreferences
import com.althmany.extractor.data.ExtractorRepository
import com.althmany.extractor.data.GroupCheckpoint
import com.althmany.extractor.data.GroupAccessMethod
import com.althmany.extractor.data.LinkCandidate
import com.althmany.extractor.data.GroupSyncCandidate
import com.althmany.extractor.data.GroupStatus
import com.althmany.extractor.data.SpeedProfile
import com.althmany.extractor.data.TargetGroup
import com.althmany.extractor.notification.ExtractionNotifier
import com.althmany.extractor.profile.RuntimeProfileDetector
import com.althmany.extractor.profile.ProfileLaunchPolicy
import com.althmany.extractor.profile.ProfileAccessibilityRuntime
import com.althmany.extractor.profile.NativeProfileEngineRouter
import com.althmany.extractor.shizuku.ShizukuBridge
import com.althmany.extractor.shizuku.ShizukuUiRuntime
import com.althmany.extractor.shizuku.ShizukuUiTree
import com.althmany.extractor.profile.RuntimeBackendKind
import com.althmany.extractor.profile.WhatsAppInstanceRegistry
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
 * Android extraction coordinator.
 *
 * Design goals copied from the proven WA-Workspace extraction behavior where Android permits it:
 * event-driven scanning, interleaved URL capture while scrolling, conservative dedupe, strict end proof,
 * bounded same-group retries, checkpoints, pause/resume, and immediate handling of structural boundaries.
 */
object ExtractionController {
    private lateinit var appContext: Context
    private lateinit var repository: ExtractorRepository
    private lateinit var stateStore: ExtractionStateStore
    private lateinit var settingsStore: ExtractionSettingsStore
    private lateinit var notifier: ExtractionNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val adapter = WhatsAppUiAdapter()
    private val accessRouter = GroupAccessRouter(adapter)
    private val shizukuUi: ShizukuUiRuntime by lazy { ShizukuUiRuntime(appContext) }
    private var service: WhatsAppAccessibilityService? = null
    private var runJob: Job? = null
    @Volatile private var syncCancelRequested: Boolean = false
    @Volatile private var syncPauseRequested: Boolean = false
    private var allowIncompleteCheckpointResume: Boolean = false

    private val _state = MutableStateFlow(ExtractionUiState())
    val state: StateFlow<ExtractionUiState> = _state.asStateFlow()

    fun initialize(context: Context, repo: ExtractorRepository) {
        appContext = context.applicationContext
        repository = repo
        stateStore = ExtractionStateStore(appContext)
        settingsStore = ExtractionSettingsStore(appContext)
        notifier = ExtractionNotifier(appContext)
        val prefs = settingsStore.get()
        _state.value = _state.value.copy(
            mode = prefs.mode,
            speed = prefs.speed,
            maxScrollIterations = prefs.maxScrollIterations,
            maxSameGroupRetries = prefs.maxSameGroupRetries,
            betweenItemsDelayMs = prefs.betweenItemsDelayMs
        )
        refreshRuntimeEnvironment()
        refreshStats()
    }

    fun attachService(accessibilityService: WhatsAppAccessibilityService) {
        service = accessibilityService
        refreshRuntimeEnvironment()
        _state.value = _state.value.copy(serviceConnected = true, message = "خدمة الاستخراج متصلة داخل ${_state.value.profileInfo.labelAr}")
        if (stateStore.active && runJob?.isActive != true) resume()
    }

    fun detachService(accessibilityService: WhatsAppAccessibilityService) {
        if (service === accessibilityService) service = null
        _state.value = _state.value.copy(serviceConnected = false)
    }

    fun notifyUiEvent(packageName: CharSequence?) {
        val observed = packageName?.toString()
        if (!WhatsAppInstanceRegistry.isSupportedPackage(observed)) return
        val expected = _state.value.selectedWhatsAppPackage
        val mismatch = ProfileLaunchPolicy.isMismatch(expected, observed)
        _state.value = _state.value.copy(
            whatsappPackage = observed,
            packageMismatch = mismatch,
            status = if (mismatch && stateStore.active) EngineStatus.PROFILE_MISMATCH else _state.value.status,
            message = if (mismatch && stateStore.active)
                "تم رصد ${WhatsAppInstanceRegistry.labelFor(observed)} بينما المهمة مرتبطة بـ ${WhatsAppInstanceRegistry.labelFor(expected)} — سيتم التصحيح قبل المتابعة"
            else _state.value.message
        )
        // Wake the coordinator even for a mismatch so the profile/package guard can recover immediately.
        uiEvents.tryEmit(Unit)
    }

    fun refreshRuntimeEnvironment() {
        if (!::appContext.isInitialized || !::settingsStore.isInitialized) return
        val profile = RuntimeProfileDetector.detect(appContext)
        val available = WhatsAppInstanceRegistry.launchable(appContext, forceRefresh = true)
        val saved = settingsStore.get().targetWhatsAppPackage
        val selected = ProfileLaunchPolicy.resolveSelected(saved, available.map { it.packageName })
        if (selected != null && selected != saved) settingsStore.setTargetWhatsAppPackage(selected)
        val localAccess = ProfileAccessibilityRuntime.snapshot(appContext)
        val shizuku = runCatching { ShizukuBridge.status() }.getOrNull()
        val route = NativeProfileEngineRouter.inspect(appContext)
        _state.value = _state.value.copy(
            profileInfo = profile,
            availableWhatsApp = available,
            selectedWhatsAppPackage = selected,
            packageMismatch = false,
            profileAccessibilityConnected = localAccess.localServiceConnected,
            shizukuReady = shizuku?.ready == true,
            shizukuDetail = when {
                shizuku == null || !shizuku.binderAlive -> "Shizuku غير شغّال"
                !shizuku.permissionGranted -> "Shizuku يحتاج إذن"
                shizuku.userServiceBound -> "Shizuku متصل + UserService"
                else -> "Shizuku جاهز للربط"
            },
            backendRecommendation = "${route.recommended.name}: ${route.reason}"
        )
    }

    fun requestShizukuPermission(): Boolean {
        val requested = runCatching { ShizukuBridge.requestPermission() }.getOrDefault(false)
        scope.launch {
            delay(500L)
            refreshRuntimeEnvironment()
            _state.value = _state.value.copy(message = if (requested) "تم طلب إذن Shizuku — وافق ثم اضغط اختبار" else "تعذر طلب إذن Shizuku")
        }
        return requested
    }

    fun probeShizuku() {
        if (!::appContext.isInitialized) return
        scope.launch {
            val target = _state.value.selectedWhatsAppPackage
            _state.value = _state.value.copy(message = "اختبار Shizuku داخل ${_state.value.profileInfo.labelAr}…")
            val detail = runCatching { ShizukuBridge.probe(appContext, target) }.getOrElse { "Shizuku probe error: ${it.message}" }
            refreshRuntimeEnvironment()
            _state.value = _state.value.copy(message = detail, shizukuDetail = detail.take(220))
        }
    }

    fun setTargetWhatsAppPackage(packageName: String): Boolean {
        if (runJob?.isActive == true) return false
        refreshRuntimeEnvironment()
        val valid = _state.value.availableWhatsApp.any { it.packageName == packageName && it.launchable }
        if (!valid) return false
        settingsStore.setTargetWhatsAppPackage(packageName)
        _state.value = _state.value.copy(
            selectedWhatsAppPackage = packageName,
            packageMismatch = false,
            message = "تم اختيار ${WhatsAppInstanceRegistry.labelFor(packageName)} داخل ${_state.value.profileInfo.labelAr}"
        )
        return true
    }

    fun setMode(mode: ExtractionMode) {
        settingsStore.setMode(mode)
        _state.value = _state.value.copy(mode = mode)
    }

    fun setSpeed(speed: SpeedProfile) {
        settingsStore.setSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun setMaxScrollIterations(value: Int) {
        val safe = value.coerceIn(100, 10_000)
        settingsStore.setMaxScrollIterations(safe)
        _state.value = _state.value.copy(maxScrollIterations = safe)
    }

    fun setMaxSameGroupRetries(value: Int) {
        val safe = value.coerceIn(1, 5)
        settingsStore.setMaxSameGroupRetries(safe)
        _state.value = _state.value.copy(maxSameGroupRetries = safe)
    }

    fun setBetweenItemsDelayMs(value: Long) {
        val safe = value.coerceIn(0L, 60_000L)
        settingsStore.setBetweenItemsDelayMs(safe)
        _state.value = _state.value.copy(betweenItemsDelayMs = safe)
    }

    fun isBusy(): Boolean = runJob?.isActive == true || stateStore.active

    fun start() {
        if (runJob?.isActive == true) return
        if (ScanController.isRunning() || PublishController.isRunning()) {
            _state.value = _state.value.copy(status = EngineStatus.ERROR, message = "أوقف الفحص أو النشر أولاً قبل تشغيل الاستخراج")
            return
        }
        refreshRuntimeEnvironment()
        if (_state.value.selectedWhatsAppPackage == null) {
            _state.value = _state.value.copy(status = EngineStatus.ERROR, message = "اختر نسخة واتساب داخل البيئة الحالية قبل البدء")
            return
        }
        if (!RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION)) {
            val owner = RuntimeOperationCoordinator.current()?.labelAr ?: "عملية أخرى"
            _state.value = _state.value.copy(status = EngineStatus.ERROR, message = "لا يمكن بدء الاستخراج أثناء تشغيل $owner")
            return
        }
        stateStore.active = true
        stateStore.paused = false
        stateStore.currentRetry = 0
        runJob = scope.launch { runExtraction(resetRun = true) }.also { job ->
            job.invokeOnCompletion { RuntimeOperationCoordinator.release(RuntimeOperation.EXTRACTION) }
        }
    }

    fun pause() {
        if (_state.value.status == EngineStatus.SYNCING_GROUPS || syncPauseRequested) {
            syncPauseRequested = true
            _state.value = _state.value.copy(status = EngineStatus.PAUSED, message = "مزامنة القروبات متوقفة مؤقتًا")
            notifier.show(_state.value)
            return
        }
        stateStore.paused = true
        _state.value = _state.value.copy(status = EngineStatus.PAUSED, message = "متوقف مؤقتًا — حفظ نقطة الاستكمال")
        notifier.show(_state.value)
        scope.launch {
            val name = stateStore.currentGroupName ?: return@launch
            val prefs = settingsStore.get()
            // NEW_ONLY keeps its last completed baseline untouched until a successful new-only pass.
            // Also avoid writing a bogus search/info-screen checkpoint if pause was pressed between chats.
            val root = service?.currentRoot()
            val inDeepChat = _state.value.status in setOf(EngineStatus.EXTRACTING, EngineStatus.VERIFYING_END) &&
                adapter.isGroupVisible(root, name, selectedPackageOrNull())
            if (prefs.mode != ExtractionMode.NEW_ONLY && inDeepChat) {
                val snap = adapter.snapshot(root)
                repository.saveCheckpoint(
                    GroupCheckpoint(
                        groupName = name, anchorTokens = snap.anchorTokens, signature = snap.contentSignature,
                        iteration = stateStore.currentIteration, uniqueLinks = _state.value.linksFoundThisGroup,
                        mode = prefs.mode, completed = false, updatedAt = System.currentTimeMillis()
                    )
                )
                repository.log(name, "INFO", "paused-checkpoint", "تم حفظ نقطة الاستكمال عند الإيقاف المؤقت")
            }
        }
    }

    fun resume() {
        if (syncPauseRequested) {
            syncPauseRequested = false
            _state.value = _state.value.copy(status = EngineStatus.SYNCING_GROUPS, message = "استئناف مزامنة القروبات")
            notifier.show(_state.value)
            return
        }
        recoverLiveService()
        stateStore.active = true
        stateStore.paused = false
        // Normal pause keeps the current coroutine alive at awaitIfPaused(). In that case resume must
        // only release the gate; starting another extraction coroutine would duplicate work.
        if (runJob?.isActive == true) {
            _state.value = _state.value.copy(status = EngineStatus.EXTRACTING, message = "استكمال المهمة من النقطة المحفوظة")
            notifier.show(_state.value)
            return
        }
        // This path is for process/service recovery where the old coroutine no longer exists.
        if (!RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION)) {
            val owner = RuntimeOperationCoordinator.current()?.labelAr ?: "عملية أخرى"
            _state.value = _state.value.copy(status = EngineStatus.ERROR, message = "لا يمكن استكمال الاستخراج أثناء تشغيل $owner")
            return
        }
        runJob = scope.launch { runExtraction(resetRun = false) }.also { job ->
            job.invokeOnCompletion { RuntimeOperationCoordinator.release(RuntimeOperation.EXTRACTION) }
        }
    }

    fun stop() {
        syncCancelRequested = true
        syncPauseRequested = false
        stateStore.active = false
        stateStore.paused = false
        runJob?.cancel()
        runJob = null
        RuntimeOperationCoordinator.release(RuntimeOperation.EXTRACTION)
        stateStore.clearRuntime()
        _state.value = _state.value.copy(status = EngineStatus.STOPPED, message = "تم إيقاف المهمة")
        notifier.cancel()
        refreshStats()
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        appContext.startActivity(intent)
    }

    fun openWhatsApp(): Boolean {
        recoverLiveService()
        refreshRuntimeEnvironment()
        val packageName = _state.value.selectedWhatsAppPackage ?: return false
        if (adapter.isWhatsAppRoot(service?.currentRoot(), packageName)) return true
        if (_state.value.availableWhatsApp.none { it.packageName == packageName && it.launchable }) return false
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        // Explicit package launch: PackageManager here is scoped to the current Android user/profile.
        // When this APK is installed inside Secure Folder or Work Profile, this resolves that profile's copy.
        intent.setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        appContext.startActivity(intent)
        return true
    }

    private fun recoverLiveService(): WhatsAppAccessibilityService? {
        val live = service ?: AccessibilityRuntimeBridge.currentEvenIfQuiet()
        if (live != null && service !== live) attachService(live)
        return live
    }

    private suspend fun awaitRuntimeService(timeoutMs: Long): WhatsAppAccessibilityService? {
        recoverLiveService()?.let { return it }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(75L)
            recoverLiveService()?.let { return it }
        }
        return recoverLiveService()
    }

    /** Event-first wait for the selected WhatsApp window, with a real launch timeout instead of a 150ms guess. */
    private suspend fun awaitWhatsAppRoot(
        svc: WhatsAppAccessibilityService,
        expectedPackage: String,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (adapter.isWhatsAppRoot(svc.currentRoot(), expectedPackage)) return true
            withTimeoutOrNull(220L) { uiEvents.first() }
            delay(20L)
        }
        return adapter.isWhatsAppRoot(svc.currentRoot(), expectedPackage)
    }

    /** Return to WhatsApp Chats without blindly backing out of the app. */
    private suspend fun recoverChatsSurface(
        svc: WhatsAppAccessibilityService,
        timing: TimingPolicy,
        expectedPackage: String
    ): Boolean {
        repeat(8) { pass ->
            val root = svc.currentRoot()
            if (!adapter.isWhatsAppRoot(root, expectedPackage)) {
                if (!openWhatsApp()) return false
                awaitWhatsAppRoot(svc, expectedPackage, 2_500L)
            }
            if (adapter.isConversationListVisible(svc.currentRoot())) return true
            if (adapter.activateChatsTab(svc.currentRoot())) {
                awaitUiChange(maxOf(timing.searchOpenMs, 140L))
                if (adapter.isConversationListVisible(svc.currentRoot())) return true
            }
            if (pass < 6) {
                svc.performBack()
                awaitUiChange(maxOf(timing.searchOpenMs, 140L))
            }
        }
        return adapter.isConversationListVisible(svc.currentRoot())
    }

    fun refreshStats() {
        if (!::repository.isInitialized) return
        scope.launch { _state.value = _state.value.copy(stats = repository.stats()) }
    }

    /**
     * Android group synchronization. The runtime first activates WhatsApp's real Groups filter, then
     * collects only chat rows from the main list. System cards/filter chips are never persisted, new
     * discoveries start unselected, and group identity is trusted only when the Groups filter applied.
     */
    suspend fun syncGroupsNow(): Int {
        syncCancelRequested = false
        syncPauseRequested = false
        if (ScanController.isRunning() || PublishController.isRunning()) {
            throw IllegalStateException("أوقف الفحص أو النشر قبل مزامنة القروبات")
        }
        if (!RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION)) {
            val owner = RuntimeOperationCoordinator.current()?.labelAr ?: "عملية أخرى"
            throw IllegalStateException("لا يمكن بدء المزامنة أثناء تشغيل $owner")
        }
        try {
        if (runJob?.isActive == true || stateStore.active) throw IllegalStateException("أوقف الاستخراج قبل المزامنة")
        val prefs = settingsStore.get()
        val timing = ExtractionPolicy.timing(prefs.speed)
        _state.value = _state.value.copy(status = EngineStatus.SYNCING_GROUPS, message = "مزامنة القروبات من واتساب", syncFound = 0)
        if (!openWhatsApp()) throw IllegalStateException("تعذر فتح واتساب المحدد داخل البيئة الحالية")
        val svc = awaitRuntimeService(1_800L)
        if (svc == null) {
            if (ShizukuBridge.status().ready) {
                return syncGroupsViaShizuku(timing)
            }
            throw IllegalStateException("تم فتح واتساب لكن لا Accessibility محلية ولا Shizuku جاهز داخل نفس البيئة")
        }
        val syncPackage = requireSelectedPackage()
        if (!awaitWhatsAppRoot(svc, syncPackage, 5_000L)) {
            if (ShizukuBridge.status().ready) return syncGroupsViaShizuku(timing)
            throw IllegalStateException("Accessibility متصلة لكن rootInActiveWindow لم يرَ واتساب المحدد خلال مهلة التشغيل")
        }
        if (!recoverChatsSurface(svc, timing, syncPackage)) {
            throw IllegalStateException("تعذر الوصول إلى تبويب الدردشات في واتساب؛ افتح واتساب مرة واحدة وتأكد أن Accessibility ترى الشاشة")
        }

        // Prefer WhatsApp's real Groups chip. If a build/profile does not expose that chip through
        // Accessibility, fall back to opening visible chat rows and proving Group Info before saving.
        // The fallback is slower but safe: private chats are never persisted merely because they are visible.
        val groupsFilterApplied = activateGroupsOnlyFilter(svc, timing)
        val found = linkedMapOf<String, GroupSyncCandidate>()
        val initialVisibleNames = adapter.collectChatListCandidates(svc.currentRoot()).take(8).toSet()

        if (groupsFilterApplied) {
            scanConversationList(svc, timing, found)
        } else {
            repository.log(null, "WARN", "sync-groups-filter-fallback", "فلتر المجموعات غير مكشوف؛ بدء تحقق صف-بصف عبر Group Info بدون Search")
            scanAndVerifyConversationList(svc, timing, found, "الدردشات")
        }
        restoreConversationListPosition(svc, timing, initialVisibleNames)
        // Archived normally lives near the top. Search upward with fresh roots instead of assuming
        // the list was restored to exactly the same pixel position.
        var archivedOpened = adapter.openArchived(svc.currentRoot())
        var archivePass = 0
        while (!archivedOpened && archivePass++ < 14) {
            if (syncCancelRequested) throw CancellationException("تم إيقاف المزامنة")
            val root = svc.currentRoot() ?: break
            val moved = adapter.scrollChatListBackward(root) || svc.swipeChatListBackward(timing.gestureDurationMs)
            awaitBurstWithoutSaving(timing)
            archivedOpened = adapter.openArchived(svc.currentRoot())
            if (!moved && !archivedOpened) break
        }
        if (archivedOpened) {
            awaitUiChange(maxOf(timing.groupOpenMs, 180L))
            if (activateGroupsOnlyFilter(svc, timing)) {
                repository.log(null, "INFO", "sync-archived-groups", "فحص القروبات المؤرشفة عبر فلتر المجموعات")
                scanConversationList(svc, timing, found)
            } else {
                repository.log(null, "WARN", "sync-archived-verify-fallback", "المؤرشفة بدون فلتر Groups مكشوف؛ تحقق صف-بصف عبر Group Info")
                scanAndVerifyConversationList(svc, timing, found, "المؤرشفة")
            }
            svc.performBack()
            awaitUiChange(maxOf(timing.searchOpenMs, 150L))
            recoverChatsSurface(svc, timing, syncPackage)
            activateGroupsOnlyFilter(svc, timing)
        } else {
            repository.log(null, "INFO", "sync-archived-none", "لم يظهر قسم المؤرشفة في هذه النسخة/الحساب")
        }

        if (found.isEmpty()) {
            throw IllegalStateException("تم فتح فلتر المجموعات لكن لم تظهر صفوف قابلة للقراءة في Accessibility؛ تم الحفاظ على قاعدة القروبات القديمة")
        }

        if (syncCancelRequested) throw CancellationException("تم إيقاف المزامنة")
        val syncGeneration = System.currentTimeMillis()
        val unifiedCandidates = found.values.mapIndexed { index, candidate ->
            candidate.copy(
                whatsappPackage = syncPackage,
                syncOrder = index,
                lastKnownAccessMethod = GroupAccessMethod.VISIBLE_LIST,
                verifiedGroupHint = true
            )
        }
        val added = repository.addDiscoveredGroupCandidates(unifiedCandidates, syncGeneration)
        repository.finalizeGroupSync(syncPackage, syncGeneration)
        repository.log(null, "INFO", "sync-complete", "تمت مزامنة ${found.size} قروب حقيقي عبر فلتر المجموعات، جديد منها $added")
        _state.value = _state.value.copy(status = EngineStatus.IDLE, message = "اكتملت مزامنة القروبات: ${found.size} — جاهزة للاستخراج حتى بدون تحديد يدوي", syncFound = found.size)
        refreshStats()
        return added
            } finally {
            RuntimeOperationCoordinator.release(RuntimeOperation.EXTRACTION)
        }
    }

    private suspend fun activateGroupsOnlyFilter(
        svc: WhatsAppAccessibilityService,
        timing: TimingPolicy
    ): Boolean {
        repeat(5) { attempt ->
            var root = svc.currentRoot() ?: return@repeat
            if (adapter.isGroupsFilterActive(root)) return true
            if (!adapter.isConversationListVisible(root)) {
                adapter.activateChatsTab(root)
                awaitUiChange(maxOf(timing.searchOpenMs, 140L))
                root = svc.currentRoot() ?: return@repeat
            }
            val beforeCandidates = adapter.collectChatListCandidates(root).take(6).toSet()
            val before = adapter.snapshot(root).signature
            val clicked = adapter.activateGroupsFilter(root) ||
                svc.tapBounds(adapter.groupsFilterBounds(root), timing.gestureDurationMs)
            if (!clicked) {
                awaitUiChange(120L + attempt * 40L)
                return@repeat
            }
            awaitUiChange(maxOf(timing.searchOpenMs, 160L))
            val afterRoot = svc.currentRoot()
            val after = adapter.snapshot(afterRoot).signature
            val active = adapter.isGroupsFilterActive(afterRoot)
            val afterCandidates = adapter.collectChatListCandidates(afterRoot).take(6).toSet()
            val contentChanged = before != after || beforeCandidates != afterCandidates
            repository.log(null, "INFO", "groups-filter", "attempt=${attempt + 1} active=$active changed=$contentChanged rows=${afterCandidates.size}")
            // v2.20: never trust a populated/changed list as proof that the Groups filter is active.
            // If WhatsApp does not expose selected/checked state, return false and use the slower
            // row-by-row Group Info verification fallback. This prevents private chats from being
            // persisted as groups.
            if (active) return true
        }
        return false
    }

    private suspend fun scanConversationList(
        svc: WhatsAppAccessibilityService,
        timing: TimingPolicy,
        found: MutableMap<String, GroupSyncCandidate>
    ) {
        var stableRounds = 0
        var noNewRounds = 0
        var lastVisibleSignature = Int.MIN_VALUE
        var iterations = 0
        val deadline = SystemClock.elapsedRealtime() + 35_000L
        while (found.size < ExtractionPolicy.MAX_SYNC_ITEMS && iterations++ < 1_200 && SystemClock.elapsedRealtime() < deadline) {
            if (syncCancelRequested) throw CancellationException("تم إيقاف المزامنة")
            awaitIfPaused()
            val root = svc.currentRoot()
            if (root == null) { awaitUiChange(timing.eventQuietMs); continue }
            if (!adapter.isWhatsAppRoot(root, requireSelectedPackage())) {
                openWhatsApp(); awaitUiChange(timing.recoveryMs); continue
            }
            val visible = adapter.collectChatListCandidatesDetailed(root)
            val visibleSignature = visible.map { it.name.trim().lowercase() }.sorted().joinToString("|").hashCode()
            val before = found.size
            visible.forEach { candidate ->
                val key = candidate.name.trim().lowercase()
                val previous = found[key]
                found[key] = if (previous == null) candidate else previous.copy(
                    unreadCount = maxOf(previous.unreadCount, candidate.unreadCount),
                    activityText = previous.activityText ?: candidate.activityText,
                    active = previous.active || candidate.active,
                    publishableHint = previous.publishableHint || candidate.publishableHint,
                    communityParentHint = previous.communityParentHint || candidate.communityParentHint
                )
            }
            _state.value = _state.value.copy(syncFound = found.size, message = "مزامنة القروبات • ${found.size} محفوظ • صفحة ${iterations}")

            noNewRounds = if (before == found.size) noNewRounds + 1 else 0
            stableRounds = if (visibleSignature == lastVisibleSignature && before == found.size) stableRounds + 1 else 0
            lastVisibleSignature = visibleSignature
            if (stableRounds >= 3 || noNewRounds >= 8) break

            val accepted = adapter.scrollChatListForward(root) || svc.swipeChatListForward(timing.gestureDurationMs)
            if (!accepted) stableRounds++
            awaitBurstWithoutSaving(timing)
        }
        repository.log(null, "INFO", "sync-list-pass", "iterations=$iterations rows=${found.size} stable=$stableRounds noNew=$noNewRounds")
    }

    /**
     * Safe fallback for WhatsApp builds that hide the Groups filter from Accessibility.
     * It never persists a visible chat until Group Info is structurally confirmed. Search is not used.
     */
    private suspend fun scanAndVerifyConversationList(
        svc: WhatsAppAccessibilityService,
        timing: TimingPolicy,
        found: MutableMap<String, GroupSyncCandidate>,
        label: String
    ) {
        val checked = hashSetOf<String>()
        var stable = 0
        var noNew = 0
        val deadline = SystemClock.elapsedRealtime() + 55_000L
        var page = 0

        while (page++ < 180 && SystemClock.elapsedRealtime() < deadline && found.size < ExtractionPolicy.MAX_SYNC_ITEMS) {
            if (syncCancelRequested) throw CancellationException("تم إيقاف المزامنة")
            awaitIfPaused()
            var root = svc.currentRoot()
            if (!adapter.isWhatsAppRoot(root, requireSelectedPackage())) {
                openWhatsApp()
                if (!awaitWhatsAppRoot(svc, requireSelectedPackage(), 2_500L)) break
                recoverChatsSurface(svc, timing, requireSelectedPackage())
                root = svc.currentRoot()
            }
            if (root == null) {
                awaitUiChange(timing.eventQuietMs)
                continue
            }

            val visible = adapter.collectChatListCandidatesDetailed(root).toList()
            val beforeFound = found.size
            var verifiedOnPage = 0

            for (candidate in visible) {
                val key = candidate.name.trim().lowercase()
                if (!checked.add(key)) continue
                val fresh = svc.currentRoot() ?: break
                val opened = adapter.openVisibleChatListRow(fresh, candidate.name) ||
                    svc.tapBounds(adapter.visibleChatListRowBounds(fresh, candidate.name), timing.gestureDurationMs)
                if (!opened) continue

                awaitUiChange(maxOf(timing.groupOpenMs, 180L))
                val chatRoot = svc.currentRoot()
                if (!adapter.isConversationOpenForTarget(chatRoot, candidate.name, requireSelectedPackage())) {
                    svc.performBack()
                    awaitUiChange(maxOf(timing.searchOpenMs, 130L))
                    recoverChatsSurface(svc, timing, requireSelectedPackage())
                    continue
                }

                val openedInfo = adapter.openCurrentChatInfo(chatRoot, candidate.name) ||
                    svc.tapBounds(adapter.currentChatHeaderBounds(chatRoot, candidate.name), timing.gestureDurationMs)
                var isGroup = false
                if (openedInfo) {
                    awaitUiChange(maxOf(timing.groupOpenMs, 180L))
                    isGroup = adapter.isGroupInfoScreen(svc.currentRoot())
                    svc.performBack()
                    awaitUiChange(maxOf(timing.searchOpenMs, 130L))
                }

                if (isGroup) {
                    found[key] = candidate.copy(
                        whatsappPackage = requireSelectedPackage(),
                        lastKnownAccessMethod = GroupAccessMethod.VISIBLE_LIST,
                        verifiedGroupHint = true
                    )
                    verifiedOnPage++
                    _state.value = _state.value.copy(
                        syncFound = found.size,
                        message = "مزامنة $label • ${found.size} قروب مؤكّد"
                    )
                }

                // Back to Chats regardless of whether the visible row was a private chat or a group.
                if (!adapter.isConversationListVisible(svc.currentRoot())) {
                    svc.performBack()
                    awaitUiChange(maxOf(timing.searchOpenMs, 130L))
                }
                recoverChatsSurface(svc, timing, requireSelectedPackage())
            }

            noNew = if (found.size == beforeFound) noNew + 1 else 0
            val listRoot = svc.currentRoot()
            val moved = adapter.scrollChatListForward(listRoot) || svc.swipeChatListForward(timing.gestureDurationMs)
            awaitBurstWithoutSaving(timing)
            stable = if (!moved || (visible.isEmpty() && verifiedOnPage == 0)) stable + 1 else 0
            if (stable >= 3 || noNew >= 8) break
        }
        repository.log(null, "INFO", "sync-verified-fallback", "$label checked=${checked.size} groups=${found.size} pages=$page")
    }

    private suspend fun restoreConversationListPosition(
        svc: WhatsAppAccessibilityService,
        timing: TimingPolicy,
        initialVisibleNames: Set<String>
    ) {
        if (initialVisibleNames.isEmpty()) return
        var quiet = 0
        repeat(180) {
            val root = svc.currentRoot() ?: return@repeat
            val current = adapter.collectChatListCandidates(root)
            if (current.count { it in initialVisibleNames } >= minOf(2, initialVisibleNames.size)) return
            val before = adapter.snapshot(root).signature
            val accepted = adapter.scrollChatListBackward(root) || svc.swipeChatListBackward(timing.gestureDurationMs)
            awaitBurstWithoutSaving(timing)
            val after = adapter.snapshot(svc.currentRoot()).signature
            quiet = if (!accepted || before == after) quiet + 1 else 0
            if (quiet >= 3) return
        }
    }


    /**
     * v2.17.1 PipelineFix: extraction must be able to consume the synchronized group cache
     * even when the user did not manually tick rows. This keeps the synchronized GroupRecord
     * as the source of truth while avoiding a shared `selected=1` side effect for Publisher.
     */
    private suspend fun extractionTargets(targetPackage: String): List<TargetGroup> {
        val explicitlySelected = repository.pendingSelectedGroups(targetPackage)
        if (explicitlySelected.isNotEmpty()) return explicitlySelected
        return repository.groups().filter { group ->
            !group.stale &&
                group.discovered &&
                group.status !in setOf(GroupStatus.COMPLETED, GroupStatus.SKIPPED_NOT_GROUP) &&
                (group.whatsappPackage.isBlank() || group.whatsappPackage == targetPackage)
        }
    }

    private suspend fun runExtraction(resetRun: Boolean) {
        try {
            allowIncompleteCheckpointResume = !resetRun
            val prefs = settingsStore.get()
            val targetPackage = prefs.targetWhatsAppPackage ?: requireSelectedPackage()
            _state.value = _state.value.copy(
                mode = prefs.mode,
                speed = prefs.speed,
                maxScrollIterations = prefs.maxScrollIterations,
                maxSameGroupRetries = prefs.maxSameGroupRetries,
                betweenItemsDelayMs = prefs.betweenItemsDelayMs
            )
            if (resetRun) repository.resetRunStatuses(targetPackage)
            var groups = extractionTargets(targetPackage)
            if (groups.isEmpty()) {
                finishRun("لا توجد قروبات مزامنة/محددة تحتاج إلى استخراج")
                return
            }

            _state.value = _state.value.copy(
                status = EngineStatus.PREPARING, runGroupCount = groups.size,
                currentGroupIndex = 0, message = "تحضير محرك الاستخراج ${prefs.mode.labelAr}"
            )
            refreshStatsAndNotify()

            if (!openWhatsApp()) {
                failRun("لم يتم اختيار/العثور على نسخة واتساب قابلة للتشغيل داخل ${_state.value.profileInfo.labelAr}")
                return
            }
            _state.value = _state.value.copy(status = EngineStatus.OPENING_WHATSAPP, message = "فتح ${WhatsAppInstanceRegistry.labelFor(requireSelectedPackage())} داخل ${_state.value.profileInfo.labelAr}")
            val liveAccessibility = awaitRuntimeService(1_200L)
            if (liveAccessibility == null) {
                if (ShizukuBridge.status().ready) {
                    runExtractionViaShizuku(groups, prefs, targetPackage)
                    return
                }
                failRun("تم فتح واتساب لكن لا Accessibility محلية ولا Shizuku جاهز داخل نفس البيئة")
                return
            }
            awaitUiChange(ExtractionPolicy.timing(prefs.speed).groupOpenMs)

            groups = extractionTargets(targetPackage)
            groups.forEachIndexed { index, group ->
                awaitIfPaused()
                if (!stateStore.active) return
                stateStore.currentGroupId = group.id
                stateStore.currentGroupName = group.name
                _state.value = _state.value.copy(
                    currentGroup = group.name, currentGroupIndex = index + 1, runGroupCount = groups.size,
                    linksFoundThisGroup = 0, retry = 0, phaseDetail = ""
                )
                notifier.show(_state.value)

                val outcome = processGroupWithRetries(group, prefs)
                when (outcome) {
                    ProcessOutcome.SUCCESS -> repository.updateStatus(group.id, GroupStatus.COMPLETED)
                    ProcessOutcome.NOT_GROUP -> repository.updateStatus(group.id, GroupStatus.SKIPPED_NOT_GROUP, "العنصر المكتشف ليس قروبًا مؤكّدًا")
                    ProcessOutcome.FAILED -> repository.updateStatus(group.id, GroupStatus.FAILED_FINAL, "فشل بعد ${prefs.maxSameGroupRetries} محاولات في نفس القروب")
                }
                refreshStatsAndNotify()
                returnToList(prefs)
                if (index < groups.lastIndex && prefs.betweenItemsDelayMs > 0L) {
                    _state.value = _state.value.copy(phaseDetail = "فاصل ${prefs.betweenItemsDelayMs}ms قبل القروب التالي")
                    delay(prefs.betweenItemsDelayMs)
                }
            }
            finishRun("اكتمل الاستخراج")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            failRun(t.message ?: t::class.java.simpleName)
        }
    }

    private suspend fun processGroupWithRetries(group: TargetGroup, prefs: ExtractionPreferences): ProcessOutcome {
        var lastError: Throwable? = null
        for (attempt in 1..prefs.maxSameGroupRetries) {
            awaitIfPaused()
            stateStore.currentRetry = attempt
            _state.value = _state.value.copy(retry = attempt, phaseDetail = "محاولة $attempt/${prefs.maxSameGroupRetries}")
            try {
                processGroup(group, prefs)
                return ProcessOutcome.SUCCESS
            } catch (c: CancellationException) {
                throw c
            } catch (_: NotAGroupException) {
                repository.log(group.name, "WARN", "not-group", "تم تخطي العنصر بعد فحص معلومات المحادثة")
                return ProcessOutcome.NOT_GROUP
            } catch (t: Throwable) {
                lastError = t
                repository.updateStatus(group.id, GroupStatus.FAILED, t.message)
                repository.log(group.name, "WARN", "same-group-retry", "المحاولة $attempt: ${t.message}")
                if (attempt < prefs.maxSameGroupRetries) {
                    _state.value = _state.value.copy(
                        status = EngineStatus.RECOVERING,
                        message = "إعادة فحص نفس القروب قبل السماح بالانتقال",
                        phaseDetail = "same-group-end-retry"
                    )
                    recoverForSameGroup(prefs)
                }
            }
        }
        repository.log(group.name, "ERROR", "same-group-retry-limit-reached", lastError?.message ?: "unknown")
        return ProcessOutcome.FAILED
    }

    private suspend fun processGroup(group: TargetGroup, prefs: ExtractionPreferences) {
        repository.updateStatus(group.id, GroupStatus.OPENING)
        _state.value = _state.value.copy(status = EngineStatus.OPENING_GROUP, message = "فتح ${group.name} من ذاكرة القروبات")
        notifier.show(_state.value)
        val access = openGroupFromMemory(group, prefs)
        if (!access.opened) error("تعذر فتح المجموعة من ذاكرة المزامنة بدون Search: ${access.detail}")

        if (group.discovered && !group.verifiedGroup) {
            repository.updateStatus(group.id, GroupStatus.VERIFYING)
            _state.value = _state.value.copy(status = EngineStatus.VERIFYING_GROUP, message = "التحقق أن المحادثة قروب وليست محادثة خاصة")
            val verified = verifyAutoDiscoveredGroup(group, prefs)
            if (!verified) {
                repository.updateGroupCapabilities(group.id, verified = false, active = group.active, publishable = false)
                throw NotAGroupException()
            }
            repository.updateGroupCapabilities(
                group.id,
                verified = true,
                active = true,
                publishable = !group.communityParent,
                communityParent = group.communityParent
            )
        }

        repository.updateStatus(group.id, GroupStatus.EXTRACTING)
        when (prefs.mode) {
            ExtractionMode.SMART -> {
                _state.value = _state.value.copy(status = EngineStatus.EXTRACTING, message = "المسار الذكي: الاستخراج العميق أولاً")
                var deepFailure: Throwable? = null
                try {
                    extractDeep(group, prefs.copy(mode = ExtractionMode.DEEP))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    deepFailure = t
                }
                if (deepFailure != null) {
                    repository.log(group.name, "WARN", "smart-fallback-links-tab", "تعذر المسار العميق: ${deepFailure.message}")
                    ensureChatVisible(group.name, prefs)
                    _state.value = _state.value.copy(status = EngineStatus.LINKS_TAB, message = "بديل ذكي: تبويب الروابط")
                    if (!extractViaLinksTab(group, prefs.copy(mode = ExtractionMode.LINKS_TAB))) {
                        throw deepFailure
                    }
                }
            }
            ExtractionMode.LINKS_TAB -> {
                _state.value = _state.value.copy(status = EngineStatus.LINKS_TAB, message = "استخراج سريع من تبويب الروابط")
                if (!extractViaLinksTab(group, prefs)) error("تعذر فتح تبويب الروابط في هذا الإصدار من واتساب")
            }
            ExtractionMode.DEEP -> extractDeep(group, prefs)
            ExtractionMode.ALL_CHATS -> {
                _state.value = _state.value.copy(status = EngineStatus.EXTRACTING, message = "جميع الدردشات: تمرير متداخل سريع داخل القروبات المحددة")
                extractDeep(group, prefs)
            }
            ExtractionMode.NEW_ONLY -> extractDeep(group, prefs)
        }
    }

    private suspend fun openGroupFromMemory(group: TargetGroup, prefs: ExtractionPreferences): GroupAccessRouter.Result {
        val svc = service ?: return GroupAccessRouter.Result(false, detail = "Accessibility غير متصلة")
        val expected = requireSelectedPackage()
        if (group.whatsappPackage.isNotBlank() && group.whatsappPackage != expected) {
            repository.log(group.name, "WARN", "group-package-mismatch", "القروب محفوظ لـ ${group.whatsappPackage} بينما المحدد $expected")
        }
        val result = accessRouter.open(
            group = group,
            service = svc,
            expectedPackage = expected,
            timing = ExtractionPolicy.timing(prefs.speed),
            waitForUi = { ms -> awaitUiChange(ms) },
            ensureForeground = {
                try {
                    ensureWhatsAppForeground(prefs)
                    true
                } catch (_: Throwable) {
                    false
                }
            },
            maxScrollPasses = 320,
            allowSearchFallback = false
        )
        if (result.opened) {
            repository.recordGroupAccessSuccess(group.id, result.method)
            if (group.whatsappPackage.isBlank()) repository.updateGroupIdentity(group.id, group.jidOrGroupId, expected)
            repository.log(group.name, "INFO", "group-open-route", result.detail)
        } else {
            result.attempted.lastOrNull()?.let { repository.recordGroupAccessFailure(group.id, it) }
            repository.log(group.name, "WARN", "group-open-failed", result.detail)
        }
        return result
    }

    // Search-based extraction was intentionally removed in v2.20. Extraction must open only
    // synchronized GroupRecord entries through visible-list/scroll matching.

    private suspend fun verifyAutoDiscoveredGroup(group: TargetGroup, prefs: ExtractionPreferences): Boolean {
        val svc = service ?: return false
        val timing = ExtractionPolicy.timing(prefs.speed)
        val root = svc.currentRoot() ?: return false
        val openedInfo = adapter.openCurrentChatInfo(root, group.name) ||
            svc.tapBounds(adapter.currentChatHeaderBounds(root, group.name), timing.gestureDurationMs)
        if (!openedInfo) return false
        awaitUiChange(timing.groupOpenMs)
        val isGroup = adapter.isGroupInfoScreen(svc.currentRoot())
        svc.performBack(); awaitUiChange(timing.searchOpenMs)
        return isGroup && adapter.isConversationOpenForTarget(svc.currentRoot(), group.name, selectedPackageOrNull())
    }

    private suspend fun extractViaLinksTab(group: TargetGroup, prefs: ExtractionPreferences): Boolean {
        val svc = service ?: return false
        val timing = ExtractionPolicy.timing(prefs.speed)
        val infoRoot = svc.currentRoot() ?: return false
        val openedInfo = adapter.openCurrentChatInfo(infoRoot, group.name) ||
            svc.tapBounds(adapter.currentChatHeaderBounds(infoRoot, group.name), timing.gestureDurationMs)
        if (!openedInfo) return false
        awaitUiChange(timing.groupOpenMs)
        if (!adapter.openMediaLinksDocs(svc.currentRoot())) { svc.performBack(); awaitUiChange(timing.searchOpenMs); return false }
        awaitUiChange(timing.groupOpenMs)
        if (!adapter.openLinksTab(svc.currentRoot())) { svc.performBack(); svc.performBack(); awaitUiChange(timing.searchOpenMs); return false }
        awaitUiChange(timing.hardSettleMs)
        if (!adapter.linksTabLooksOpen(svc.currentRoot())) { returnFromNestedToChat(group.name, prefs); return false }

        val seen = hashSetOf<String>()
        val tracker = EndProofTracker()
        var iterations = 0
        var newCount = 0
        while (stateStore.active && iterations++ < prefs.maxScrollIterations) {
            awaitIfPaused()
            val root = svc.currentRoot()
            if (root == null) { awaitUiChange(timing.eventQuietMs); continue }
            val before = adapter.snapshot(root)
            newCount += captureVisibleLinks(group, seen)
            updateProgress(newCount, "تبويب الروابط • $newCount رابط جديد")

            val accepted = adapter.scrollGenericForward(root) || svc.swipeChatListForward(timing.gestureDurationMs)
            val after = captureBurst(group, seen, timing)
            newCount += after.newLinks
            val changed = after.snapshot.contentSignature != before.contentSignature
            val evidence = EndEvidence(
                signature = after.snapshot.contentSignature, scrolled = accepted && changed,
                scrollable = after.snapshot.scrollableNodeFound, messageTokenCount = after.snapshot.messageTokenCount,
                urlCount = adapter.collectVisibleUrls(svc.currentRoot()).size,
                olderLoaderVisible = false, terminalBoundary = null
            )
            tracker.observe(evidence)
            if (tracker.shouldStartQuietEndProof() && proveQuietEnd(group, seen, timing, directionForward = true)) break
            if (iterations % 6 == 0) refreshStatsAndNotify()
        }
        if (iterations >= prefs.maxScrollIterations) throw EndUnverifiedException("تبويب الروابط لم يعط شهادة نهاية")
        repository.log(group.name, "INFO", "links-tab-completed", "روابط جديدة في الجولة: $newCount")
        returnFromNestedToChat(group.name, prefs)
        return true
    }

    private suspend fun extractDeep(group: TargetGroup, prefs: ExtractionPreferences) {
        val svc = service ?: error("Accessibility service disconnected")
        val timingNormal = ExtractionPolicy.timing(prefs.speed)
        val timingTurbo = ExtractionPolicy.timing(SpeedProfile.HYPER)
        val priorCheckpoint = repository.checkpoint(group.name)
        val previousNewOnlyAnchor = priorCheckpoint?.takeIf { it.completed && prefs.mode == ExtractionMode.NEW_ONLY }?.anchorTokens.orEmpty()
        val resumeAnchor = priorCheckpoint?.takeIf {
            allowIncompleteCheckpointResume && !it.completed && prefs.mode != ExtractionMode.NEW_ONLY
        }?.anchorTokens.orEmpty()
        var fastForwardResume = resumeAnchor.isNotEmpty()

        val firstRoot = svc.currentRoot() ?: error("لا توجد شجرة واجهة واتساب")
        val newestSnapshot = adapter.snapshot(firstRoot)
        val nextNewOnlyAnchor = newestSnapshot.anchorTokens

        val seen = HashSet<String>(512)
        val tracker = EndProofTracker()
        var totalNew = 0
        var iterations = 0
        var unreadBoundarySeen = false
        var finishReason: String? = null

        _state.value = _state.value.copy(status = EngineStatus.EXTRACTING, message = "قراءة المحادثة والسحب للرسائل الأقدم")

        while (stateStore.active && iterations < prefs.maxScrollIterations) {
            awaitIfPaused()
            ensureWhatsAppForeground(prefs)
            val timing = if (fastForwardResume) timingTurbo else timingNormal
            val root = svc.currentRoot()
            if (root == null) { awaitUiChange(timing.eventQuietMs); continue }
            if (!adapter.isConversationOpenForTarget(root, group.name, selectedPackageOrNull())) {
                awaitUiChange(timing.eventQuietMs)
                if (!adapter.isConversationOpenForTarget(svc.currentRoot(), group.name, selectedPackageOrNull())) error("خرج واتساب من المجموعة أثناء الاستخراج")
            }

            val before = adapter.snapshot(root)
            totalNew += captureVisibleLinks(group, seen)
            unreadBoundarySeen = unreadBoundarySeen || adapter.unreadDividerVisible(root)

            // Structural exceptions copied from the web extractor, but only accepted through the structural gate.
            val terminal = adapter.detectTerminalBoundary(root)
            if (terminal?.structural == true) {
                finishReason = terminal.code
                repository.log(group.name, "INFO", terminal.code, "نهاية فورية مؤكدة بنيويًا: ${terminal.label}")
                break
            }

            if (adapter.olderMessagesLoaderVisible(root)) {
                if (adapter.clickOlderMessagesLoader(root)) {
                    _state.value = _state.value.copy(message = "تحميل الرسائل الأقدم…", phaseDetail = "older-loader")
                    val loaded = captureBurst(group, seen, timing)
                    totalNew += loaded.newLinks
                    tracker.resetProgress()
                    updateProgress(totalNew, "تم تحميل دفعة رسائل أقدم")
                    continue
                }
            }

            if (fastForwardResume && before.matchesAnchor(resumeAnchor)) {
                fastForwardResume = false
                repository.log(group.name, "INFO", "resume-anchor-found", "تمت استعادة نقطة الاستكمال")
                _state.value = _state.value.copy(phaseDetail = "checkpoint-restored")
            }

            if (prefs.mode == ExtractionMode.NEW_ONLY) {
                if (previousNewOnlyAnchor.isNotEmpty() && before.matchesAnchor(previousNewOnlyAnchor)) {
                    finishReason = "new-only-previous-anchor"
                    break
                }
                if (unreadBoundarySeen && iterations >= 1) {
                    finishReason = "new-only-unread-boundary"
                    break
                }
                if (previousNewOnlyAnchor.isEmpty() && iterations >= ExtractionPolicy.NEW_ONLY_BOOTSTRAP_PAGES) {
                    finishReason = "new-only-bootstrap-limit"
                    break
                }
            }

            val accepted = adapter.scrollToOlderMessages(root) || svc.swipeTowardOlderMessages(timing.gestureDurationMs)
            val burst = captureBurst(group, seen, timing)
            totalNew += burst.newLinks
            val changed = burst.snapshot.contentSignature != before.contentSignature
            iterations++
            stateStore.currentIteration = iterations

            val evidence = EndEvidence(
                signature = burst.snapshot.contentSignature,
                scrolled = accepted && changed,
                scrollable = burst.snapshot.scrollableNodeFound,
                messageTokenCount = burst.snapshot.messageTokenCount,
                urlCount = adapter.collectVisibleUrls(svc.currentRoot()).size,
                olderLoaderVisible = adapter.olderMessagesLoaderVisible(svc.currentRoot()),
                terminalBoundary = adapter.detectTerminalBoundary(svc.currentRoot())
            )
            tracker.observe(evidence)
            tracker.immediateCompletionReason(evidence)?.let { reason -> finishReason = reason }
            if (finishReason != null) break

            updateProgress(totalNew, if (fastForwardResume) "استعادة نقطة الاستكمال بسرعة…" else "سحب وقراءة • $totalNew رابط جديد")

            if (iterations % 6 == 0 && prefs.mode != ExtractionMode.NEW_ONLY) {
                repository.saveCheckpoint(
                    GroupCheckpoint(group.name, burst.snapshot.anchorTokens, burst.snapshot.contentSignature,
                        iterations, totalNew, prefs.mode, completed = false, updatedAt = System.currentTimeMillis())
                )
            }
            if (iterations % 4 == 0) refreshStatsAndNotify()

            if (tracker.shouldStartQuietEndProof()) {
                _state.value = _state.value.copy(status = EngineStatus.VERIFYING_END, message = "التحقق الصارم من نهاية المحادثة", phaseDetail = "strict-end-proof")
                if (proveQuietEnd(group, seen, timing, directionForward = false)) {
                    finishReason = "strict-quiet-end"
                    break
                }
                tracker.resetProgress()
                _state.value = _state.value.copy(status = EngineStatus.EXTRACTING, message = "ظهرت بيانات جديدة — مواصلة نفس القروب")
            }
        }

        if (finishReason == null) throw EndUnverifiedException("تم بلوغ حد الحماية بدون إثبات نهاية المحادثة")

        repository.saveCheckpoint(
            GroupCheckpoint(
                groupName = group.name,
                anchorTokens = if (prefs.mode == ExtractionMode.NEW_ONLY) nextNewOnlyAnchor else adapter.snapshot(svc.currentRoot()).anchorTokens,
                signature = if (prefs.mode == ExtractionMode.NEW_ONLY) newestSnapshot.contentSignature else adapter.snapshot(svc.currentRoot()).contentSignature,
                iteration = iterations,
                uniqueLinks = totalNew,
                mode = prefs.mode,
                completed = true,
                updatedAt = System.currentTimeMillis()
            )
        )
        repository.log(group.name, "INFO", "group-completed", "reason=$finishReason iterations=$iterations newLinks=$totalNew")
        updateProgress(totalNew, "اكتمل القروب • $totalNew رابط جديد")
    }

    private suspend fun captureVisibleLinks(group: TargetGroup, seen: MutableSet<String>): Int {
        val root = service?.currentRoot() ?: return 0
        val batch = ArrayList<LinkCandidate>()
        for (url in adapter.collectVisibleUrls(root)) {
            val normalized = LinkExtractor.normalize(url)
            if (normalized.isBlank() || !seen.add(normalized)) continue
            batch += LinkCandidate(
                url = url,
                normalizedUrl = normalized,
                category = LinkExtractor.category(normalized),
                inviteCode = LinkExtractor.inviteCode(normalized)
            )
        }
        return repository.saveLinksBatch(batch, group)
    }

    private data class BurstResult(val snapshot: NodeSnapshot, val newLinks: Int)

    /** Capture links on every accessibility mutation until the renderer goes quiet. */
    private suspend fun captureBurst(group: TargetGroup, seen: MutableSet<String>, timing: TimingPolicy): BurstResult {
        val started = SystemClock.elapsedRealtime()
        var newLinks = 0
        while (SystemClock.elapsedRealtime() - started < timing.hardSettleMs) {
            val event = withTimeoutOrNull(timing.eventQuietMs) { uiEvents.first() }
            if (event == null) break
            if (timing.eventSampleDelayMs > 0) delay(timing.eventSampleDelayMs)
            newLinks += captureVisibleLinks(group, seen)
        }
        // Final pass catches text exposed after the last mutation event.
        newLinks += captureVisibleLinks(group, seen)
        return BurstResult(adapter.snapshot(service?.currentRoot()), newLinks)
    }

    private suspend fun proveQuietEnd(
        group: TargetGroup,
        seen: MutableSet<String>,
        timing: TimingPolicy,
        directionForward: Boolean
    ): Boolean {
        val svc = service ?: return false
        repeat(ExtractionPolicy.QUIET_END_PASSES) {
            val root = svc.currentRoot() ?: return false
            if (!directionForward && !adapter.isConversationOpenForTarget(root, group.name, selectedPackageOrNull())) return false
            if (!directionForward && adapter.olderMessagesLoaderVisible(root)) return false
            adapter.detectTerminalBoundary(root)?.takeIf { it.structural }?.let { return true }
            val before = adapter.snapshot(root).contentSignature
            val accepted = if (directionForward) {
                adapter.scrollGenericForward(root) || svc.swipeChatListForward(timing.gestureDurationMs)
            } else {
                adapter.scrollToOlderMessages(root) || svc.swipeTowardOlderMessages(timing.gestureDurationMs)
            }
            val burst = captureBurst(group, seen, timing.copy(hardSettleMs = maxOf(timing.hardSettleMs, timing.endQuietMs)))
            if (!directionForward && !adapter.isConversationOpenForTarget(svc.currentRoot(), group.name, selectedPackageOrNull())) return false
            if (accepted && burst.snapshot.contentSignature != before) return false
            if (burst.newLinks > 0) return false
        }
        return true
    }

    private suspend fun ensureChatVisible(groupName: String, prefs: ExtractionPreferences) {
        val svc = service ?: error("Accessibility service disconnected")
        val timing = ExtractionPolicy.timing(prefs.speed)
        repeat(4) {
            if (adapter.isConversationOpenForTarget(svc.currentRoot(), groupName, selectedPackageOrNull())) return
            svc.performBack(); awaitUiChange(timing.searchOpenMs)
        }
        val record = repository.groupByName(groupName, selectedPackageOrNull()) ?: error("لم يعد سجل القروب موجودًا")
        if (!openGroupFromMemory(record, prefs).opened) error("تعذر الرجوع للمحادثة بعد المسار الذكي")
    }

    private suspend fun returnFromNestedToChat(groupName: String, prefs: ExtractionPreferences) {
        val svc = service ?: return
        val timing = ExtractionPolicy.timing(prefs.speed)
        // Typical Android path: Links/Media -> Group info -> Chat. Do not stop merely because
        // the group title is still visible on the nested info page.
        repeat(2) { svc.performBack(); awaitUiChange(timing.searchOpenMs) }
        if (!adapter.isConversationOpenForTarget(svc.currentRoot(), groupName, selectedPackageOrNull())) {
            repository.groupByName(groupName, selectedPackageOrNull())?.let { openGroupFromMemory(it, prefs) }
        }
    }

    private suspend fun recoverForSameGroup(prefs: ExtractionPreferences) {
        val svc = service ?: return
        val timing = ExtractionPolicy.timing(prefs.speed)
        repeat(2) { svc.performBack(); awaitUiChange(timing.recoveryMs) }
        ensureWhatsAppForeground(prefs)
    }

    private suspend fun returnToList(prefs: ExtractionPreferences) {
        val svc = service ?: return
        val timing = ExtractionPolicy.timing(prefs.speed)
        val root = svc.currentRoot()
        // Avoid an unnecessary Back if recovery already returned us to a populated chat/search list.
        if (adapter.isConversationListVisible(root)) return
        svc.performBack()
        awaitUiChange(timing.searchOpenMs)
    }

    private suspend fun ensureWhatsAppForeground(prefs: ExtractionPreferences) {
        val expected = requireSelectedPackage()
        val root = service?.currentRoot()
        val observed = root?.packageName?.toString()
        if (adapter.isWhatsAppRoot(root, expected)) {
            if (_state.value.packageMismatch) _state.value = _state.value.copy(packageMismatch = false)
            return
        }

        if (WhatsAppInstanceRegistry.isSupportedPackage(observed) && observed != expected) {
            _state.value = _state.value.copy(
                status = EngineStatus.PROFILE_MISMATCH,
                packageMismatch = true,
                message = "النسخة المفتوحة هي ${WhatsAppInstanceRegistry.labelFor(observed)} وليست ${WhatsAppInstanceRegistry.labelFor(expected)} — تصحيح المسار"
            )
        } else {
            _state.value = _state.value.copy(status = EngineStatus.RECOVERING, message = "إعادة ${WhatsAppInstanceRegistry.labelFor(expected)} للواجهة")
        }

        if (!prefs.autoRecoverWhatsApp) error("واتساب المحدد لم يعد في الواجهة")
        if (!openWhatsApp()) error("تعذر فتح ${WhatsAppInstanceRegistry.labelFor(expected)} داخل ${_state.value.profileInfo.labelAr}")
        awaitUiChange(ExtractionPolicy.timing(prefs.speed).recoveryMs)
        val recoveredRoot = service?.currentRoot()
        if (!adapter.isWhatsAppRoot(recoveredRoot, expected)) {
            val now = recoveredRoot?.packageName?.toString()
            error("فشل Profile Guard: المتوقع ${WhatsAppInstanceRegistry.labelFor(expected)} لكن الظاهر ${WhatsAppInstanceRegistry.labelFor(now)}")
        }
        _state.value = _state.value.copy(packageMismatch = false)
    }

    private suspend fun awaitIfPaused() {
        while (syncPauseRequested && !syncCancelRequested) {
            _state.value = _state.value.copy(status = EngineStatus.PAUSED, message = "مزامنة القروبات متوقفة مؤقتًا")
            delay(180)
        }
        while (stateStore.active && stateStore.paused) {
            _state.value = _state.value.copy(status = EngineStatus.PAUSED, message = "متوقف مؤقتًا — البيانات محفوظة")
            delay(180)
        }
    }

    private suspend fun awaitUiChange(timeoutMs: Long) {
        // Event-first: wake immediately on the newest renderer mutation, then allow only a tiny
        // compositor settle window.  The one-slot SharedFlow intentionally discards stale bursts.
        withTimeoutOrNull(timeoutMs.coerceAtLeast(40)) { uiEvents.first() }
        delay(10)
    }

    private suspend fun awaitBurstWithoutSaving(timing: TimingPolicy) {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timing.hardSettleMs) {
            if (withTimeoutOrNull(timing.eventQuietMs) { uiEvents.first() } == null) break
            delay(8)
        }
    }

    private fun updateProgress(newLinks: Int, message: String) {
        _state.value = _state.value.copy(linksFoundThisGroup = newLinks, message = message)
        notifier.show(_state.value)
    }

    private suspend fun refreshStatsAndNotify() {
        val stats = repository.stats()
        _state.value = _state.value.copy(stats = stats)
        notifier.show(_state.value)
    }

    private fun finishRun(message: String) {
        stateStore.clearRuntime()
        _state.value = _state.value.copy(status = EngineStatus.COMPLETED, message = message, currentGroup = null, phaseDetail = "")
        notifier.show(_state.value, ongoing = false)
        refreshStats()
    }

    private fun failRun(message: String) {
        stateStore.active = false
        _state.value = _state.value.copy(status = EngineStatus.ERROR, message = message)
        notifier.show(_state.value, ongoing = false)
        refreshStats()
    }


    // ---------------------------------------------------------------------
    // Shizuku profile-local fallback. It is used only after Accessibility
    // fails to bind locally and Shizuku has explicit user permission.
    // ---------------------------------------------------------------------

    private suspend fun awaitShizukuTree(packageName: String, timeoutMs: Long = 5_000L): ShizukuUiTree? {
        if (!ShizukuBridge.status().ready || !ShizukuBridge.ensureBound(appContext)) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val tree = shizukuUi.snapshot(packageName)
            if (tree.state == "OK" && shizukuUi.isWhatsApp(tree, packageName) && tree.nodes.isNotEmpty()) return tree
            delay(90L)
        }
        val last = shizukuUi.snapshot(packageName)
        return last.takeIf { it.state == "OK" && shizukuUi.isWhatsApp(it, packageName) && it.nodes.isNotEmpty() }
    }

    private suspend fun syncGroupsViaShizuku(timing: TimingPolicy): Int {
        val packageName = requireSelectedPackage()
        _state.value = _state.value.copy(message = "Shizuku: ربط واجهة ${WhatsAppInstanceRegistry.labelFor(packageName)}")
        ShizukuBridge.reset(appContext)
        if (!ShizukuBridge.ensureBound(appContext)) {
            throw IllegalStateException("Shizuku جاهز لكن UserService لم يرتبط")
        }

        var tree: ShizukuUiTree =
            awaitShizukuTree(packageName, 1_200L)
                ?: run {
                    ShizukuBridge.launchPackage(appContext, packageName)
                    awaitShizukuTree(packageName, 5_000L)
                        ?: throw IllegalStateException(
                            "Shizuku متصل لكن UIAutomation لا يرى واجهة واتساب المحددة في هذا Profile"
                        )
                }

        // Reach Chats without blindly backing out of WhatsApp. Prefer bottom navigation first.
        var listReady = shizukuUi.isConversationListVisible(tree, packageName)
        for (pass in 0 until 8) {
            if (listReady) break
            if (shizukuUi.clickChatsTab(tree, packageName)) {
                delay(maxOf(timing.searchOpenMs, 140L).coerceAtMost(420L))
                tree = awaitShizukuTree(packageName, 1_000L) ?: tree
                listReady = shizukuUi.isConversationListVisible(tree, packageName)
                if (listReady) break
            }
            if (pass < 6) {
                shizukuUi.back()
                delay(maxOf(timing.searchOpenMs, 140L).coerceAtMost(420L))
                tree = awaitShizukuTree(packageName, 1_000L) ?: tree
                listReady = shizukuUi.isConversationListVisible(tree, packageName)
            }
        }
        if (!listReady) {
            throw IllegalStateException("Shizuku يرى واتساب لكن تعذر الوصول إلى تبويب الدردشات")
        }

        var filterClicked = false
        for (attempt in 0 until 5) {
            if (shizukuUi.clickGroupsFilter(tree, packageName)) {
                delay(maxOf(timing.searchOpenMs, 150L).coerceAtMost(450L))
                tree = awaitShizukuTree(packageName, 1_200L) ?: tree
                filterClicked = true
                // Some WhatsApp builds do not expose selected=true on the filter; rows are the stronger proof.
                if (shizukuUi.collectChatCandidates(tree, packageName).isNotEmpty()) break
            } else {
                delay(120L)
                tree = awaitShizukuTree(packageName, 500L) ?: tree
            }
        }
        if (!filterClicked) {
            throw IllegalStateException("Shizuku لم يجد فلتر المجموعات؛ أوقفت المزامنة لحماية قاعدة GroupRecord")
        }

        val found = linkedMapOf<String, GroupSyncCandidate>()

        suspend fun scanCurrentList(label: String) {
            var stable = 0
            var noNew = 0
            var lastVisibleSignature = Int.MIN_VALUE
            var sequence = shizukuUi.eventSequence(packageName)
            val deadline = SystemClock.elapsedRealtime() + 35_000L
            var iterations = 0
            while (
                iterations++ < 1_000 &&
                found.size < ExtractionPolicy.MAX_SYNC_ITEMS &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                if (syncCancelRequested) throw CancellationException("تم إيقاف مزامنة Shizuku")
                awaitIfPaused()
                val visible = shizukuUi.collectChatCandidates(tree, packageName)
                val visibleSignature = visible
                    .map { it.name.trim().lowercase() }
                    .sorted()
                    .joinToString("|")
                    .hashCode()
                val beforeCount = found.size
                visible.forEach { candidate ->
                    val key = candidate.name.trim().lowercase()
                    val previous = found[key]
                    found[key] = if (previous == null) candidate else previous.copy(
                        unreadCount = maxOf(previous.unreadCount, candidate.unreadCount),
                        activityText = previous.activityText ?: candidate.activityText,
                        active = previous.active || candidate.active,
                        publishableHint = previous.publishableHint || candidate.publishableHint,
                        communityParentHint = previous.communityParentHint || candidate.communityParentHint
                    )
                }
                _state.value = _state.value.copy(
                    syncFound = found.size,
                    message = "Shizuku: $label • ${found.size} قروب"
                )
                noNew = if (beforeCount == found.size) noNew + 1 else 0
                stable = if (visibleSignature == lastVisibleSignature && beforeCount == found.size) stable + 1 else 0
                lastVisibleSignature = visibleSignature
                if (stable >= 3 || noNew >= 8) break

                val moved = shizukuUi.swipeListForward(tree, timing.gestureDurationMs.toInt())
                if (!moved) stable++
                val frame = shizukuUi.waitFrame(
                    packageName,
                    sequence,
                    timing.eventQuietMs.toInt().coerceAtLeast(40)
                )
                sequence = frame.first
                tree = frame.second.takeIf { it.state == "OK" }
                    ?: shizukuUi.snapshot(packageName)
            }
            repository.log(
                null,
                "INFO",
                "sync-shizuku-pass",
                "$label rows=${found.size} stable=$stable noNew=$noNew"
            )
        }

        scanCurrentList("الدردشات")

        // Search for Archived by moving toward the top. This is bounded and never uses text Search.
        var archivedHandled = false
        for (archivePass in 0 until 20) {
            if (syncCancelRequested) throw CancellationException("تم إيقاف مزامنة Shizuku")
            if (shizukuUi.openArchived(tree, packageName)) {
                delay(maxOf(timing.groupOpenMs, 180L).coerceAtMost(500L))
                tree = awaitShizukuTree(packageName, 1_200L) ?: tree

                var archiveFilter = false
                for (filterAttempt in 0 until 4) {
                    if (shizukuUi.clickGroupsFilter(tree, packageName)) {
                        archiveFilter = true
                        delay(maxOf(timing.searchOpenMs, 150L).coerceAtMost(450L))
                        tree = awaitShizukuTree(packageName, 1_000L) ?: tree
                        break
                    }
                    delay(100L)
                    tree = awaitShizukuTree(packageName, 500L) ?: tree
                }
                if (archiveFilter) scanCurrentList("المؤرشفة")

                shizukuUi.back()
                delay(180L)
                tree = awaitShizukuTree(packageName, 1_000L) ?: tree
                archivedHandled = true
                break
            }

            val moved = shizukuUi.swipeListBackward(tree, timing.gestureDurationMs.toInt())
            delay(80L)
            tree = awaitShizukuTree(packageName, 800L) ?: tree
            if (!moved) break
        }
        repository.log(
            null,
            "INFO",
            "sync-shizuku-archive",
            if (archivedHandled) "Archived scanned" else "Archived not found/exposed"
        )

        if (found.isEmpty()) {
            throw IllegalStateException("Shizuku لم يستخرج أي صف قروب من قائمة المجموعات")
        }

        if (syncCancelRequested) throw CancellationException("تم إيقاف مزامنة Shizuku")
        val generation = System.currentTimeMillis()
        val candidates = found.values.mapIndexed { index, candidate ->
            candidate.copy(
                whatsappPackage = packageName,
                syncOrder = index,
                lastKnownAccessMethod = GroupAccessMethod.VISIBLE_LIST,
                verifiedGroupHint = true
            )
        }
        val added = repository.addDiscoveredGroupCandidates(candidates, generation)
        repository.finalizeGroupSync(packageName, generation)
        repository.log(
            null,
            "INFO",
            "sync-shizuku-complete",
            "Shizuku UIAutomation: groups=${found.size} added=$added profile=${_state.value.profileInfo.profileKey}"
        )
        _state.value = _state.value.copy(
            status = EngineStatus.IDLE,
            message = "اكتملت مزامنة Shizuku: ${found.size} — محفوظة وجاهزة للاستخراج",
            syncFound = found.size
        )
        refreshStats()
        return added
    }

    private suspend fun runExtractionViaShizuku(
        initialGroups: List<TargetGroup>,
        prefs: ExtractionPreferences,
        targetPackage: String
    ) {
        if (!ShizukuBridge.ensureBound(appContext)) {
            failRun("Shizuku غير قادر على إنشاء UserService داخل هذه البيئة")
            return
        }
        ShizukuBridge.reset(appContext)
        if (awaitShizukuTree(targetPackage) == null) {
            failRun("Shizuku جاهز لكن UIAutomation لا يرى ${WhatsAppInstanceRegistry.labelFor(targetPackage)} في هذا Profile")
            return
        }
        val groups = extractionTargets(targetPackage).ifEmpty { initialGroups }
        _state.value = _state.value.copy(message = "Shizuku Event-first • ${groups.size} قروب", runGroupCount = groups.size)
        groups.forEachIndexed { index, group ->
            awaitIfPaused()
            if (!stateStore.active) return
            stateStore.currentGroupId = group.id
            stateStore.currentGroupName = group.name
            _state.value = _state.value.copy(currentGroup = group.name, currentGroupIndex = index + 1, runGroupCount = groups.size, linksFoundThisGroup = 0, retry = 0, phaseDetail = "SHIZUKU")
            notifier.show(_state.value)

            var success = false
            var notGroup = false
            var lastError: String? = null
            for (attempt in 1..prefs.maxSameGroupRetries) {
                _state.value = _state.value.copy(retry = attempt, status = EngineStatus.OPENING_GROUP, message = "Shizuku: فتح ${group.name} • $attempt/${prefs.maxSameGroupRetries}")
                val opened = openGroupViaShizuku(group, prefs, targetPackage)
                if (!opened) {
                    lastError = "تعذر فتح القروب عبر Shizuku"
                    recoverShizukuToList(targetPackage, prefs)
                    continue
                }
                if (!group.verifiedGroup && group.discovered) {
                    val verified = verifyGroupViaShizuku(group, targetPackage, prefs)
                    if (!verified) { notGroup = true; break }
                }
                val result = runCatching {
                    when (prefs.mode) {
                        ExtractionMode.LINKS_TAB -> extractLinksTabViaShizuku(group, prefs, targetPackage)
                        ExtractionMode.SMART -> {
                            runCatching { extractDeepViaShizuku(group, prefs.copy(mode = ExtractionMode.DEEP), targetPackage) }
                                .getOrElse {
                                    repository.log(group.name, "WARN", "shizuku-smart-links-fallback", it.message ?: "deep failed")
                                    if (!extractLinksTabViaShizuku(group, prefs.copy(mode = ExtractionMode.LINKS_TAB), targetPackage)) throw it
                                }
                        }
                        else -> extractDeepViaShizuku(group, prefs, targetPackage)
                    }
                }
                if (result.isSuccess) { success = true; break }
                lastError = result.exceptionOrNull()?.message
                repository.log(group.name, "WARN", "shizuku-same-group-retry", lastError ?: "unknown")
                recoverShizukuToList(targetPackage, prefs)
            }
            when {
                success -> repository.updateStatus(group.id, GroupStatus.COMPLETED)
                notGroup -> repository.updateStatus(group.id, GroupStatus.SKIPPED_NOT_GROUP, "Shizuku: المحادثة ليست قروبًا مؤكداً")
                else -> repository.updateStatus(group.id, GroupStatus.FAILED_FINAL, lastError ?: "Shizuku failure")
            }
            refreshStatsAndNotify()
            recoverShizukuToList(targetPackage, prefs)
            if (index < groups.lastIndex && prefs.betweenItemsDelayMs > 0L) delay(prefs.betweenItemsDelayMs)
        }
        finishRun("اكتمل الاستخراج عبر Shizuku")
    }

    private suspend fun openGroupViaShizuku(group: TargetGroup, prefs: ExtractionPreferences, packageName: String): Boolean {
        val timing = ExtractionPolicy.timing(prefs.speed)
        var tree = awaitShizukuTree(packageName, 1_200L) ?: return false
        if (shizukuUi.isConversationOpenForTarget(tree, group.name, packageName)) {
            repository.recordGroupAccessSuccess(group.id, GroupAccessMethod.CURRENT_CHAT)
            return true
        }
        for (attempt in 0 until 4) {
            if (shizukuUi.isConversationListVisible(tree, packageName)) break
            shizukuUi.back()
            delay(timing.searchOpenMs.coerceAtMost(260L))
            tree = awaitShizukuTree(packageName, 900L) ?: tree
        }
        if (shizukuUi.openVisibleChat(tree, group.name, packageName)) {
            delay(timing.groupOpenMs.coerceAtMost(350L)); tree = awaitShizukuTree(packageName, 1_000L) ?: tree
            if (shizukuUi.isConversationOpenForTarget(tree, group.name, packageName)) { repository.recordGroupAccessSuccess(group.id, GroupAccessMethod.VISIBLE_LIST); return true }
        }

        var sequence = shizukuUi.eventSequence(packageName)
        var stable = 0
        for (pass in 0 until 280) {
            tree = awaitShizukuTree(packageName, 700L) ?: continue
            if (shizukuUi.openVisibleChat(tree, group.name, packageName)) {
                val frame = shizukuUi.waitFrame(packageName, sequence, timing.eventQuietMs.toInt().coerceAtLeast(40)); sequence = frame.first; tree = frame.second
                if (shizukuUi.isConversationOpenForTarget(tree, group.name, packageName)) { repository.recordGroupAccessSuccess(group.id, GroupAccessMethod.SCROLL_MATCH); return true }
            }
            val before = tree.signature
            if (!shizukuUi.swipeListForward(tree, timing.gestureDurationMs.toInt())) stable++
            val frame = shizukuUi.waitFrame(packageName, sequence, timing.eventQuietMs.toInt().coerceAtLeast(40)); sequence = frame.first; val next = frame.second
            stable = if (next.signature == before) stable + 1 else 0; tree = next
            if (stable >= 3) break
        }

        var backwardStable = 0
        for (pass in 0 until 180) {
            tree = awaitShizukuTree(packageName, 700L) ?: continue
            if (shizukuUi.openVisibleChat(tree, group.name, packageName)) {
                delay(timing.groupOpenMs.coerceAtMost(350L)); tree = awaitShizukuTree(packageName, 900L) ?: tree
                if (shizukuUi.isConversationOpenForTarget(tree, group.name, packageName)) { repository.recordGroupAccessSuccess(group.id, GroupAccessMethod.SCROLL_MATCH); return true }
            }
            val before = tree.signature
            val moved = shizukuUi.swipeListBackward(tree, timing.gestureDurationMs.toInt())
            delay(timing.eventQuietMs.coerceAtMost(120L))
            val next = awaitShizukuTree(packageName, 700L) ?: tree
            backwardStable = if (!moved || next.signature == before) backwardStable + 1 else 0
            tree = next
            if (backwardStable >= 3) break
        }

        // Extraction 2.16: synced groups are opened only from cached/list routes.
        // Never type the group name into WhatsApp Search during extraction.
        repository.recordGroupAccessFailure(group.id, GroupAccessMethod.SCROLL_MATCH)
        return false
    }

    private suspend fun verifyGroupViaShizuku(group: TargetGroup, packageName: String, prefs: ExtractionPreferences): Boolean {
        var tree = awaitShizukuTree(packageName, 900L) ?: return false
        if (!shizukuUi.isGroupVisible(tree, group.name, packageName)) return false
        if (!shizukuUi.clickHeader(tree, group.name, packageName)) return false
        delay(ExtractionPolicy.timing(prefs.speed).groupOpenMs.coerceAtMost(350L))
        tree = awaitShizukuTree(packageName, 1_000L) ?: return false
        val verified = shizukuUi.isGroupInfo(tree)
        shizukuUi.back(); delay(180L)
        if (verified) repository.updateGroupCapabilities(group.id, verified = true, active = true, publishable = !group.communityParent, communityParent = group.communityParent)
        return verified
    }

    private suspend fun extractDeepViaShizuku(group: TargetGroup, prefs: ExtractionPreferences, packageName: String) {
        val timing = ExtractionPolicy.timing(prefs.speed)
        var tree = awaitShizukuTree(packageName, 1_000L) ?: error("Shizuku UI root unavailable")
        if (!shizukuUi.isConversationOpenForTarget(tree, group.name, packageName)) error("Shizuku خرج من القروب قبل الاستخراج")
        val newest = shizukuUi.toNodeSnapshot(tree)
        val prior = repository.checkpoint(group.name)
        val previousNewAnchor = prior?.takeIf { it.completed && prefs.mode == ExtractionMode.NEW_ONLY }?.anchorTokens.orEmpty()
        val nextNewAnchor = newest.anchorTokens
        val seen = hashSetOf<String>()
        var totalNew = 0
        var iterations = 0
        var quietRounds = 0
        var sequence = shizukuUi.eventSequence(packageName)
        _state.value = _state.value.copy(status = EngineStatus.EXTRACTING, message = "Shizuku Event-first: قراءة وسحب الرسائل الأقدم")

        while (stateStore.active && iterations < prefs.maxScrollIterations) {
            awaitIfPaused()
            if (!shizukuUi.isConversationOpenForTarget(tree, group.name, packageName)) error("Shizuku فقد محادثة القروب أثناء الاستخراج")
            val before = shizukuUi.toNodeSnapshot(tree)
            val added = captureShizukuLinks(group, tree, seen)
            totalNew += added
            if (prefs.mode == ExtractionMode.NEW_ONLY && previousNewAnchor.isNotEmpty() && before.matchesAnchor(previousNewAnchor)) break
            if (prefs.mode == ExtractionMode.NEW_ONLY && shizukuUi.unreadDividerVisible(tree) && iterations >= 1) break

            if (shizukuUi.olderLoaderVisible(tree) && shizukuUi.clickOlderLoader(tree, packageName)) {
                val frame = shizukuUi.waitFrame(packageName, sequence, timing.eventQuietMs.toInt().coerceAtLeast(50)); sequence = frame.first; tree = frame.second
                continue
            }
            val moved = shizukuUi.swipeOlder(tree, timing.gestureDurationMs.toInt())
            val frame = shizukuUi.waitFrame(packageName, sequence, timing.eventQuietMs.toInt().coerceAtLeast(50))
            sequence = frame.first
            val next = frame.second.takeIf { it.state == "OK" } ?: shizukuUi.snapshot(packageName)
            val afterAdded = captureShizukuLinks(group, next, seen)
            totalNew += afterAdded
            val changed = next.contentSignature != before.contentSignature
            iterations++
            stateStore.currentIteration = iterations
            quietRounds = if (!moved || (!changed && added + afterAdded == 0)) quietRounds + 1 else 0
            tree = next
            updateProgress(totalNew, "Shizuku • سحب $iterations • $totalNew رابط جديد")

            if (iterations % 6 == 0 && prefs.mode != ExtractionMode.NEW_ONLY) {
                val snap = shizukuUi.toNodeSnapshot(tree)
                repository.saveCheckpoint(GroupCheckpoint(group.name, snap.anchorTokens, snap.contentSignature, iterations, totalNew, prefs.mode, false, System.currentTimeMillis()))
            }
            if (iterations % 4 == 0) refreshStatsAndNotify()

            if (quietRounds >= 4) {
                // Strict extra proof passes: three more event-first swipes must produce no new content.
                var proof = true
                repeat(3) {
                    val proofBefore = shizukuUi.toNodeSnapshot(tree)
                    shizukuUi.swipeOlder(tree, timing.gestureDurationMs.toInt())
                    val pf = shizukuUi.waitFrame(packageName, sequence, maxOf(timing.eventQuietMs.toInt(), 80)); sequence = pf.first
                    val proofTree = pf.second.takeIf { it.state == "OK" } ?: shizukuUi.snapshot(packageName)
                    val proofNew = captureShizukuLinks(group, proofTree, seen); totalNew += proofNew
                    if (proofTree.contentSignature != proofBefore.contentSignature || proofNew > 0) proof = false
                    tree = proofTree
                }
                if (proof) break else quietRounds = 0
            }
        }
        if (iterations >= prefs.maxScrollIterations) error("Shizuku بلغ حد الحماية بدون إثبات نهاية المحادثة")
        val finalSnap = shizukuUi.toNodeSnapshot(tree)
        repository.saveCheckpoint(GroupCheckpoint(group.name, if (prefs.mode == ExtractionMode.NEW_ONLY) nextNewAnchor else finalSnap.anchorTokens, if (prefs.mode == ExtractionMode.NEW_ONLY) newest.contentSignature else finalSnap.contentSignature, iterations, totalNew, prefs.mode, true, System.currentTimeMillis()))
        repository.log(group.name, "INFO", "shizuku-group-completed", "iterations=$iterations newLinks=$totalNew")
        updateProgress(totalNew, "اكتمل القروب عبر Shizuku • $totalNew رابط جديد")
    }

    private suspend fun extractLinksTabViaShizuku(group: TargetGroup, prefs: ExtractionPreferences, packageName: String): Boolean {
        val timing = ExtractionPolicy.timing(prefs.speed)
        var tree = awaitShizukuTree(packageName, 900L) ?: return false
        if (!shizukuUi.clickHeader(tree, group.name, packageName)) return false
        delay(timing.groupOpenMs.coerceAtMost(350L)); tree = awaitShizukuTree(packageName, 900L) ?: return false
        if (!shizukuUi.openMediaLinks(tree, packageName)) { shizukuUi.back(); return false }
        delay(timing.groupOpenMs.coerceAtMost(350L)); tree = awaitShizukuTree(packageName, 900L) ?: return false
        if (!shizukuUi.openLinksTab(tree, packageName)) { shizukuUi.back(); shizukuUi.back(); return false }
        delay(timing.hardSettleMs.coerceAtMost(450L)); tree = awaitShizukuTree(packageName, 900L) ?: return false
        if (!shizukuUi.linksTabLooksOpen(tree)) return false
        val seen = hashSetOf<String>(); var total = 0; var quiet = 0; var seq = shizukuUi.eventSequence(packageName)
        for (iteration in 0 until prefs.maxScrollIterations) {
            val before = tree.contentSignature
            total += captureShizukuLinks(group, tree, seen)
            shizukuUi.swipeListForward(tree, timing.gestureDurationMs.toInt())
            val frame = shizukuUi.waitFrame(packageName, seq, timing.eventQuietMs.toInt().coerceAtLeast(50))
            seq = frame.first
            val next = frame.second.takeIf { it.state == "OK" } ?: shizukuUi.snapshot(packageName)
            val newlyFound = captureShizukuLinks(group, next, seen)
            total += newlyFound
            quiet = if (next.contentSignature == before && newlyFound == 0) quiet + 1 else 0
            tree = next
            if (quiet >= 4) break
        }
        shizukuUi.back(); delay(120L); shizukuUi.back(); delay(120L)
        repository.log(group.name, "INFO", "shizuku-links-tab-completed", "newLinks=$total")
        return true
    }

    private suspend fun captureShizukuLinks(group: TargetGroup, tree: ShizukuUiTree, seen: MutableSet<String>): Int {
        val batch = ArrayList<LinkCandidate>()
        shizukuUi.collectUrls(tree).forEach { url ->
            val normalized = LinkExtractor.normalize(url)
            if (normalized.isBlank() || !seen.add(normalized)) return@forEach
            batch += LinkCandidate(url, normalized, LinkExtractor.category(normalized), LinkExtractor.inviteCode(normalized))
        }
        return repository.saveLinksBatch(batch, group)
    }

    private suspend fun recoverShizukuToList(packageName: String, prefs: ExtractionPreferences) {
        val delayMs = ExtractionPolicy.timing(prefs.speed).searchOpenMs.coerceAtMost(220L)
        repeat(4) {
            val tree = awaitShizukuTree(packageName, 700L) ?: return@repeat
            if (shizukuUi.isConversationListVisible(tree, packageName)) return
            shizukuUi.back(); delay(delayMs)
        }
    }

    private fun selectedPackageOrNull(): String? = _state.value.selectedWhatsAppPackage

    private fun requireSelectedPackage(): String = selectedPackageOrNull()
        ?: error("لم يتم اختيار نسخة واتساب في البيئة الحالية")

    const val WHATSAPP = WhatsAppInstanceRegistry.WHATSAPP
    const val WHATSAPP_BUSINESS = WhatsAppInstanceRegistry.WHATSAPP_BUSINESS

    private enum class ProcessOutcome { SUCCESS, NOT_GROUP, FAILED }
    private class NotAGroupException : RuntimeException()
    private class EndUnverifiedException(message: String) : RuntimeException(message)
}
