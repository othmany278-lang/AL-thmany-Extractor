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
import com.althmany.extractor.data.GroupStatus
import com.althmany.extractor.data.SpeedProfile
import com.althmany.extractor.data.TargetGroup
import com.althmany.extractor.notification.ExtractionNotifier
import com.althmany.extractor.profile.RuntimeProfileDetector
import com.althmany.extractor.profile.ProfileLaunchPolicy
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
    private var service: WhatsAppAccessibilityService? = null
    private var runJob: Job? = null
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
        _state.value = _state.value.copy(mode = prefs.mode, speed = prefs.speed, maxScrollIterations = prefs.maxScrollIterations)
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
        val available = WhatsAppInstanceRegistry.launchable(appContext)
        val saved = settingsStore.get().targetWhatsAppPackage
        val selected = ProfileLaunchPolicy.resolveSelected(saved, available.map { it.packageName })
        if (selected != null && selected != saved) settingsStore.setTargetWhatsAppPackage(selected)
        _state.value = _state.value.copy(
            profileInfo = profile,
            availableWhatsApp = available,
            selectedWhatsAppPackage = selected,
            packageMismatch = false
        )
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

    fun refreshStats() {
        if (!::repository.isInitialized) return
        scope.launch { _state.value = _state.value.copy(stats = repository.stats()) }
    }

    /**
     * Best-effort Android equivalent of WA-Workspace sync. It collects conversation titles from the
     * WhatsApp list up to 4000 items. Because Android cannot read WhatsApp's private group database,
     * auto-discovered names are marked unverified and are group-validated before extraction.
     */
    suspend fun syncGroupsNow(): Int {
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
        val svc = awaitRuntimeService(5_000L)
            ?: throw IllegalStateException("تم فتح واتساب لكن خدمة Accessibility لم تتصل بالمحرك داخل نفس البيئة")
        awaitUiChange(timing.groupOpenMs)

        // Recover from a chat/info/search screen to the main list without assuming a specific WhatsApp layout.
        for (attempt in 0 until 3) {
            val root = svc.currentRoot()
            if (adapter.collectChatListCandidates(root).size >= 2) break
            svc.performBack()
            awaitUiChange(timing.searchOpenMs)
        }

        val found = linkedSetOf<String>()

        // Archived is normally reachable near the top of the main chat list, so scan it before the
        // main list is moved toward its end. Then return and continue with the normal chat list.
        if (adapter.openArchived(svc.currentRoot())) {
            awaitUiChange(timing.groupOpenMs)
            repository.log(null, "INFO", "sync-archived", "بدء فحص المحادثات المؤرشفة")
            scanConversationList(svc, timing, found)
            svc.performBack()
            awaitUiChange(timing.searchOpenMs)
        }
        scanConversationList(svc, timing, found)
        val added = repository.addDiscoveredGroups(found)
        repository.log(null, "INFO", "sync-complete", "اكتشفت ${found.size} اسمًا، جديد منها $added")
        _state.value = _state.value.copy(status = EngineStatus.IDLE, message = "اكتملت المزامنة: ${found.size}", syncFound = found.size)
        refreshStats()
        return added
            } finally {
            RuntimeOperationCoordinator.release(RuntimeOperation.EXTRACTION)
        }
    }

    private suspend fun scanConversationList(
        svc: WhatsAppAccessibilityService,
        timing: TimingPolicy,
        found: MutableSet<String>
    ) {
        var stableRounds = 0
        var lastSignature: Int? = null
        var iterations = 0
        while (found.size < ExtractionPolicy.MAX_SYNC_ITEMS && iterations++ < 1_200) {
            awaitIfPaused()
            val root = svc.currentRoot()
            if (root == null) { awaitUiChange(timing.eventQuietMs); continue }
            if (!adapter.isWhatsAppRoot(root, requireSelectedPackage())) {
                openWhatsApp(); awaitUiChange(timing.recoveryMs); continue
            }
            val snapshot = adapter.snapshot(root)
            val before = found.size
            found.addAll(adapter.collectChatListCandidates(root))
            _state.value = _state.value.copy(syncFound = found.size, message = "تم اكتشاف ${found.size} اسمًا — يجري الفحص")

            stableRounds = if (snapshot.signature == lastSignature && before == found.size) stableRounds + 1 else 0
            lastSignature = snapshot.signature
            if (stableRounds >= 3) break

            val accepted = adapter.scrollChatListForward(root) || svc.swipeChatListForward(timing.gestureDurationMs)
            if (!accepted) stableRounds++
            awaitBurstWithoutSaving(timing)
        }
    }

    private suspend fun runExtraction(resetRun: Boolean) {
        try {
            allowIncompleteCheckpointResume = !resetRun
            val prefs = settingsStore.get()
            _state.value = _state.value.copy(mode = prefs.mode, speed = prefs.speed, maxScrollIterations = prefs.maxScrollIterations)
            if (resetRun) repository.resetRunStatuses()
            var groups = repository.pendingSelectedGroups()
            if (groups.isEmpty()) {
                finishRun("لا توجد مجموعات محددة تحتاج إلى استخراج")
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
            if (awaitRuntimeService(5_000L) == null) {
                failRun("تم فتح واتساب لكن خدمة Accessibility لم تتصل بالمحرك داخل نفس البيئة")
                return
            }
            awaitUiChange(ExtractionPolicy.timing(prefs.speed).groupOpenMs)

            groups = repository.pendingSelectedGroups()
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
        repository.updateStatus(group.id, GroupStatus.SEARCHING)
        _state.value = _state.value.copy(status = EngineStatus.SEARCHING_GROUP, message = "البحث عن: ${group.name}")
        notifier.show(_state.value)
        if (!searchAndOpenGroup(group.name, prefs)) error("تعذر العثور على المجموعة أو فتحها")

        if (group.discovered && !group.verifiedGroup) {
            repository.updateStatus(group.id, GroupStatus.VERIFYING)
            _state.value = _state.value.copy(status = EngineStatus.VERIFYING_GROUP, message = "التحقق أن المحادثة قروب وليست محادثة خاصة")
            val verified = verifyAutoDiscoveredGroup(group, prefs)
            if (!verified) throw NotAGroupException()
            repository.markVerifiedGroup(group.id, true)
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

    private suspend fun searchAndOpenGroup(groupName: String, prefs: ExtractionPreferences): Boolean {
        val svc = service ?: return false
        val timing = ExtractionPolicy.timing(prefs.speed)
        repeat(3) { attempt ->
            awaitIfPaused()
            ensureWhatsAppForeground(prefs)
            var root = svc.currentRoot()
            var textSet = adapter.setSearchText(root, groupName)
            if (!textSet && adapter.findAndClickSearch(root)) {
                awaitUiChange(timing.searchOpenMs)
                root = svc.currentRoot()
                textSet = adapter.setSearchText(root, groupName)
            }
            if (textSet) {
                awaitUiChange(timing.searchResultMs)
                root = svc.currentRoot()
                if (adapter.openSearchResult(root, groupName)) {
                    _state.value = _state.value.copy(status = EngineStatus.OPENING_GROUP, message = "فتح المجموعة")
                    awaitUiChange(timing.groupOpenMs)
                    if (adapter.isGroupVisible(svc.currentRoot(), groupName, selectedPackageOrNull())) return true
                    awaitUiChange(timing.eventQuietMs)
                    if (adapter.isGroupVisible(svc.currentRoot(), groupName, selectedPackageOrNull())) return true
                }
            }
            if (attempt < 2) { svc.performBack(); awaitUiChange(timing.searchOpenMs) }
        }
        return false
    }

    private suspend fun verifyAutoDiscoveredGroup(group: TargetGroup, prefs: ExtractionPreferences): Boolean {
        val svc = service ?: return false
        val timing = ExtractionPolicy.timing(prefs.speed)
        val root = svc.currentRoot() ?: return false
        if (!adapter.openCurrentChatInfo(root, group.name)) return false
        awaitUiChange(timing.groupOpenMs)
        val isGroup = adapter.isGroupInfoScreen(svc.currentRoot())
        svc.performBack(); awaitUiChange(timing.searchOpenMs)
        return isGroup && adapter.isGroupVisible(svc.currentRoot(), group.name, selectedPackageOrNull())
    }

    private suspend fun extractViaLinksTab(group: TargetGroup, prefs: ExtractionPreferences): Boolean {
        val svc = service ?: return false
        val timing = ExtractionPolicy.timing(prefs.speed)
        if (!adapter.openCurrentChatInfo(svc.currentRoot(), group.name)) return false
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
            if (!adapter.isGroupVisible(root, group.name, selectedPackageOrNull())) {
                awaitUiChange(timing.eventQuietMs)
                if (!adapter.isGroupVisible(svc.currentRoot(), group.name, selectedPackageOrNull())) error("خرج واتساب من المجموعة أثناء الاستخراج")
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
        val batch = ArrayList<Pair<String, String>>()
        for (url in adapter.collectVisibleUrls(root)) {
            val normalized = LinkExtractor.normalize(url)
            if (normalized.isBlank() || !seen.add(normalized)) continue
            batch += url to normalized
        }
        return repository.saveLinksBatch(batch, group.name)
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
            if (!directionForward && !adapter.isGroupVisible(root, group.name, selectedPackageOrNull())) return false
            if (!directionForward && adapter.olderMessagesLoaderVisible(root)) return false
            adapter.detectTerminalBoundary(root)?.takeIf { it.structural }?.let { return true }
            val before = adapter.snapshot(root).contentSignature
            val accepted = if (directionForward) {
                adapter.scrollGenericForward(root) || svc.swipeChatListForward(timing.gestureDurationMs)
            } else {
                adapter.scrollToOlderMessages(root) || svc.swipeTowardOlderMessages(timing.gestureDurationMs)
            }
            val burst = captureBurst(group, seen, timing.copy(hardSettleMs = maxOf(timing.hardSettleMs, timing.endQuietMs)))
            if (!directionForward && !adapter.isGroupVisible(svc.currentRoot(), group.name, selectedPackageOrNull())) return false
            if (accepted && burst.snapshot.contentSignature != before) return false
            if (burst.newLinks > 0) return false
        }
        return true
    }

    private suspend fun ensureChatVisible(groupName: String, prefs: ExtractionPreferences) {
        val svc = service ?: error("Accessibility service disconnected")
        val timing = ExtractionPolicy.timing(prefs.speed)
        repeat(4) {
            if (adapter.isGroupVisible(svc.currentRoot(), groupName, selectedPackageOrNull())) return
            svc.performBack(); awaitUiChange(timing.searchOpenMs)
        }
        if (!searchAndOpenGroup(groupName, prefs)) error("تعذر الرجوع للمحادثة بعد المسار الذكي")
    }

    private suspend fun returnFromNestedToChat(groupName: String, prefs: ExtractionPreferences) {
        val svc = service ?: return
        val timing = ExtractionPolicy.timing(prefs.speed)
        // Typical Android path: Links/Media -> Group info -> Chat. Do not stop merely because
        // the group title is still visible on the nested info page.
        repeat(2) { svc.performBack(); awaitUiChange(timing.searchOpenMs) }
        if (!adapter.isGroupVisible(svc.currentRoot(), groupName, selectedPackageOrNull())) {
            searchAndOpenGroup(groupName, prefs)
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
        if (adapter.collectChatListCandidates(root).size >= 2) return
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

    private fun selectedPackageOrNull(): String? = _state.value.selectedWhatsAppPackage

    private fun requireSelectedPackage(): String = selectedPackageOrNull()
        ?: error("لم يتم اختيار نسخة واتساب في البيئة الحالية")

    const val WHATSAPP = WhatsAppInstanceRegistry.WHATSAPP
    const val WHATSAPP_BUSINESS = WhatsAppInstanceRegistry.WHATSAPP_BUSINESS

    private enum class ProcessOutcome { SUCCESS, NOT_GROUP, FAILED }
    private class NotAGroupException : RuntimeException()
    private class EndUnverifiedException(message: String) : RuntimeException(message)
}
