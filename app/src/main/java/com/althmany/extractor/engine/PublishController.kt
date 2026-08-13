package com.althmany.extractor.engine

import android.content.Context
import android.os.SystemClock
import com.althmany.extractor.accessibility.AccessibilityRuntimeBridge
import com.althmany.extractor.accessibility.WhatsAppAccessibilityService
import com.althmany.extractor.data.ExtractorRepository
import com.althmany.extractor.data.PublishItem
import com.althmany.extractor.data.PublishRunStatus
import com.althmany.extractor.data.PublishStatus
import com.althmany.extractor.notification.PublishNotifier
import com.althmany.extractor.profile.ProfileLaunchPolicy
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
 * Sequential publishing engine for user-selected WhatsApp groups.
 *
 * Safety/reliability rules:
 * - never publishes to an unselected target group;
 * - group identity is verified as a group before typing;
 * - the message is entered first, then the send action is explicit;
 * - after a send click, an ambiguous result is never blindly retried (prevents duplicate posts);
 * - pause/resume and process recovery keep per-group results in SQLite.
 */
object PublishController {
    private lateinit var appContext: Context
    private lateinit var repository: ExtractorRepository
    private lateinit var settings: PublishSettingsStore
    private lateinit var notifier: PublishNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val adapter = WhatsAppUiAdapter()
    private var service: WhatsAppAccessibilityService? = null
    private var job: Job? = null
    private var pauseRequested = false

    private val _state = MutableStateFlow(PublishUiState())
    val state: StateFlow<PublishUiState> = _state.asStateFlow()

    fun initialize(context: Context, repo: ExtractorRepository) {
        appContext = context.applicationContext
        repository = repo
        settings = PublishSettingsStore(appContext)
        notifier = PublishNotifier(appContext)
        _state.value = _state.value.copy(
            speed = settings.speed(),
            maxAttempts = settings.maxAttempts(),
            messageText = settings.lastMessage()
        )
        scope.launch {
            repository.resumablePublishRun()?.let { run ->
                _state.value = _state.value.copy(
                    activeRunId = run.id,
                    messageText = run.message,
                    speed = speedForDelay(run.delayMs),
                    maxAttempts = run.maxAttempts,
                    status = if (run.status == PublishRunStatus.PAUSED) PublishEngineStatus.PAUSED else PublishEngineStatus.IDLE,
                    paused = run.status == PublishRunStatus.PAUSED,
                    info = "توجد مهمة نشر غير مكتملة — يمكنك استكمالها"
                )
                refreshStats(run.id)
            }
        }
    }

    fun attachService(accessibilityService: WhatsAppAccessibilityService) {
        service = accessibilityService
        _state.value = _state.value.copy(serviceConnected = true)
    }

    fun detachService(accessibilityService: WhatsAppAccessibilityService) {
        if (service === accessibilityService) service = null
        _state.value = _state.value.copy(serviceConnected = false)
    }

    fun notifyUiEvent(packageName: CharSequence?) {
        val observed = packageName?.toString() ?: return
        val expected = ExtractionController.state.value.selectedWhatsAppPackage ?: return
        if (ProfileLaunchPolicy.isMismatch(expected, observed)) {
            if (_state.value.info != "تم رصد نسخة واتساب مختلفة؛ لن يتم النشر عليها") {
                _state.value = _state.value.copy(info = "تم رصد نسخة واتساب مختلفة؛ لن يتم النشر عليها")
            }
            return
        }
        uiEvents.tryEmit(Unit)
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

    fun setSpeed(value: PublishSpeedProfile) {
        if (isRunning()) return
        settings.setSpeed(value)
        _state.value = _state.value.copy(speed = value)
    }

    fun setMaxAttempts(value: Int) {
        if (isRunning()) return
        val safe = value.coerceIn(1, 3)
        settings.setMaxAttempts(safe)
        _state.value = _state.value.copy(maxAttempts = safe)
    }

    fun setDraft(message: String) {
        if (isRunning()) return
        val clean = message.take(8_000)
        settings.setLastMessage(clean)
        _state.value = _state.value.copy(messageText = clean)
    }

    fun start(message: String) {
        if (isRunning()) return
        if (ExtractionController.isBusy() || ScanController.isRunning()) {
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "أوقف الاستخراج أو الفحص قبل تشغيل النشر")
            return
        }
        val clean = message.trim()
        if (clean.isBlank()) {
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "اكتب رسالة النشر أولاً")
            return
        }
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage
        if (packageName == null) {
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "اختر نسخة واتساب أولاً")
            return
        }
        if (!RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.PUBLISH)) {
            val owner = RuntimeOperationCoordinator.current()?.labelAr ?: "عملية أخرى"
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "لا يمكن بدء النشر أثناء تشغيل $owner")
            return
        }
        setDraft(clean)
        pauseRequested = false
        job = scope.launch {
            val groups = repository.selectedGroups()
            if (groups.isEmpty()) {
                _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "حدد قروبًا واحدًا على الأقل من شاشة المجموعات")
                return@launch
            }
            val speed = settings.speed()
            val attempts = settings.maxAttempts()
            repository.stopResumablePublishRuns()
            val runId = repository.createPublishRun(clean, packageName, speed.betweenGroupsMs, attempts, groups.map { it.name })
            _state.value = _state.value.copy(activeRunId = runId, messageText = clean, speed = speed, maxAttempts = attempts)
            runPublish(runId)
        }.also { activeJob ->
            activeJob.invokeOnCompletion { RuntimeOperationCoordinator.release(RuntimeOperation.PUBLISH) }
        }
    }

    fun pause() {
        val runId = _state.value.activeRunId ?: return
        if (!isRunning()) return
        pauseRequested = true
        scope.launch { repository.updatePublishRunStatus(runId, PublishRunStatus.PAUSED) }
        _state.value = _state.value.copy(status = PublishEngineStatus.PAUSED, paused = true, info = "تم الإيقاف المؤقت — الحالة محفوظة")
        notifier.show(_state.value)
    }

    fun resume() {
        if (ExtractionController.isBusy() || ScanController.isRunning()) {
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "أوقف الاستخراج أو الفحص قبل استكمال النشر")
            return
        }
        pauseRequested = false
        if (job?.isActive == true) {
            _state.value = _state.value.copy(paused = false, status = PublishEngineStatus.PREPARING, info = "استكمال النشر")
            notifier.show(_state.value)
            return
        }
        if (!RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.PUBLISH)) {
            val owner = RuntimeOperationCoordinator.current()?.labelAr ?: "عملية أخرى"
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "لا يمكن استكمال النشر أثناء تشغيل $owner")
            return
        }
        job = scope.launch {
            val run = _state.value.activeRunId?.let { repository.publishRun(it) } ?: repository.resumablePublishRun()
            if (run == null) {
                _state.value = _state.value.copy(status = PublishEngineStatus.IDLE, info = "لا توجد مهمة نشر قابلة للاستكمال")
                return@launch
            }
            _state.value = _state.value.copy(activeRunId = run.id, messageText = run.message, maxAttempts = run.maxAttempts, speed = speedForDelay(run.delayMs))
            runPublish(run.id)
        }.also { activeJob ->
            activeJob.invokeOnCompletion { RuntimeOperationCoordinator.release(RuntimeOperation.PUBLISH) }
        }
    }

    fun stop() {
        val runId = _state.value.activeRunId
        job?.cancel(); job = null; pauseRequested = false
        RuntimeOperationCoordinator.release(RuntimeOperation.PUBLISH)
        if (runId != null) scope.launch { repository.updatePublishRunStatus(runId, PublishRunStatus.STOPPED) }
        _state.value = _state.value.copy(status = PublishEngineStatus.STOPPED, running = false, paused = false, info = "تم إنهاء مهمة النشر")
        notifier.cancel()
    }

    fun clearHistory() {
        if (isRunning()) return
        scope.launch {
            repository.clearPublishHistory()
            _state.value = PublishUiState(
                serviceConnected = service != null,
                speed = settings.speed(),
                maxAttempts = settings.maxAttempts(),
                messageText = settings.lastMessage(),
                info = "تم مسح سجل النشر"
            )
        }
    }

    fun refreshStats(runId: Long? = _state.value.activeRunId) {
        if (runId == null) return
        scope.launch { _state.value = _state.value.copy(stats = repository.publishStats(runId)) }
    }

    private suspend fun runPublish(runId: Long) {
        try {
            if (!ensureRuntimeReady()) {
                _state.value = _state.value.copy(
                    status = PublishEngineStatus.ERROR,
                    running = false,
                    paused = false,
                    info = "تعذر فتح واتساب أو توصيل Accessibility داخل نفس البيئة"
                )
                return
            }
            val run = repository.publishRun(runId) ?: error("تعذر قراءة مهمة النشر")
            repository.resetPublishTransientItems(runId)
            repository.updatePublishRunStatus(runId, PublishRunStatus.RUNNING)
            val items = repository.pendingPublishItems(runId)
            val all = repository.publishItems(runId)
            _state.value = _state.value.copy(
                status = PublishEngineStatus.PREPARING,
                running = true,
                paused = false,
                total = all.size,
                currentIndex = (all.size - items.size).coerceAtLeast(0),
                info = "تحضير النشر إلى ${all.size} قروب"
            )
            refreshStats(runId); notifier.show(_state.value)

            for ((offset, item) in items.withIndex()) {
                waitIfPaused(runId)
                val absoluteIndex = all.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.plus(1) ?: (offset + 1)
                _state.value = _state.value.copy(currentIndex = absoluteIndex, currentGroup = item.groupName, currentAttempt = 0)
                notifier.show(_state.value)

                val result = publishOne(run, item)
                repository.updatePublishItem(item.id, result.status, result.detail, incrementAttempt = false, verified = result.verified)
                refreshStats(runId)
                _state.value = _state.value.copy(info = result.detail ?: result.status.labelAr)
                notifier.show(_state.value)
                if (offset < items.lastIndex) delay(run.delayMs)
            }

            repository.updatePublishRunStatus(runId, PublishRunStatus.COMPLETED)
            _state.value = _state.value.copy(
                status = PublishEngineStatus.COMPLETED, running = false, paused = false,
                currentGroup = null, currentAttempt = 0, info = "اكتملت مهمة النشر"
            )
            refreshStats(runId); notifier.show(_state.value, ongoing = false)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val runIdNow = _state.value.activeRunId
            if (runIdNow != null) repository.updatePublishRunStatus(runIdNow, PublishRunStatus.ERROR)
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, running = false, paused = false, info = t.message ?: "خطأ في النشر")
            notifier.show(_state.value, ongoing = false)
        } finally {
            job = null
        }
    }

    private data class PublishDecision(val status: PublishStatus, val detail: String, val verified: Boolean = false)

    private suspend fun publishOne(run: com.althmany.extractor.data.PublishRun, item: PublishItem): PublishDecision {
        for (attempt in 1..run.maxAttempts) {
            waitIfPaused(run.id)
            _state.value = _state.value.copy(currentAttempt = attempt, status = PublishEngineStatus.OPENING_GROUP, info = "فتح ${item.groupName} • محاولة $attempt/${run.maxAttempts}")
            repository.updatePublishItem(item.id, PublishStatus.OPENING, "فتح القروب — محاولة $attempt", incrementAttempt = true)

            val opened = openVerifiedGroup(item.groupName, run.targetPackage, _state.value.speed.uiTimeoutMs)
            if (!opened) {
                if (attempt < run.maxAttempts) {
                    _state.value = _state.value.copy(status = PublishEngineStatus.RETRYING, info = "تعذر فتح القروب — إعادة المحاولة")
                    delay(700L + attempt * 450L)
                    continue
                }
                return PublishDecision(PublishStatus.FAILED, "تعذر فتح القروب أو التحقق من أنه مجموعة")
            }

            val root = service?.currentRoot()
            _state.value = _state.value.copy(status = PublishEngineStatus.WRITING, info = "تحضير الرسالة")
            repository.updatePublishItem(item.id, PublishStatus.PREPARING, "تحضير الرسالة")
            if (!adapter.setMessageComposerText(root, run.message)) {
                if (attempt < run.maxAttempts) { delay(500); continue }
                return PublishDecision(PublishStatus.FAILED, "لم يتم العثور على حقل كتابة الرسالة")
            }
            if (!waitUntil(run.delayMs.coerceAtMost(3_500L)) { adapter.messageComposerContains(service?.currentRoot(), run.message) }) {
                if (attempt < run.maxAttempts) continue
                return PublishDecision(PublishStatus.FAILED, "لم يتم تثبيت نص الرسالة في حقل الكتابة")
            }

            _state.value = _state.value.copy(status = PublishEngineStatus.SENDING, info = "إرسال الرسالة")
            repository.updatePublishItem(item.id, PublishStatus.SENDING, "تم تجهيز النص وجارٍ الإرسال")
            val beforeSendRoot = service?.currentRoot()
            if (!adapter.clickSendButton(beforeSendRoot)) {
                // No send click means it is safe to retry because no send action was accepted.
                if (attempt < run.maxAttempts) { delay(550); continue }
                return PublishDecision(PublishStatus.FAILED, "تعذر الضغط على زر الإرسال")
            }

            _state.value = _state.value.copy(status = PublishEngineStatus.VERIFYING, info = "التحقق من الإرسال")
            val verified = waitUntil(4_500L) {
                val current = service?.currentRoot()
                !adapter.messageComposerContains(current, run.message) && adapter.visibleNonEditableExactText(current, run.message)
            }
            if (verified) return PublishDecision(PublishStatus.VERIFIED, "تم الإرسال والتحقق من ظهور الرسالة", true)

            // Critical duplicate guard: once the send action was accepted, do not retry blindly.
            // If the composer cleared, WhatsApp likely accepted the message even if the bubble is not
            // accessible on this build. Record SENT (unverified) and move on.
            val composerStillHasText = adapter.messageComposerContains(service?.currentRoot(), run.message)
            return if (!composerStillHasText) {
                PublishDecision(PublishStatus.SENT, "تم تنفيذ الإرسال؛ تعذر التحقق البصري من الفقاعة", false)
            } else {
                PublishDecision(PublishStatus.FAILED, "زر الإرسال نُفذ لكن الحقل لم يتغير؛ لن نعيد الإرسال تلقائيًا لمنع التكرار")
            }
        }
        return PublishDecision(PublishStatus.FAILED, "استنفدت محاولات فتح القروب")
    }

    private suspend fun openVerifiedGroup(groupName: String, packageName: String, timeoutMs: Long): Boolean {
        if (!openTargetWhatsApp(packageName)) return false
        awaitEventOrDelay(150)
        val svc = service ?: return false

        // Recover to main chat list. Avoid using a search button inside the currently open chat.
        repeat(3) {
            val root = svc.currentRoot()
            if (adapter.collectChatListCandidates(root).size >= 2) return@repeat
            svc.performBack(); awaitEventOrDelay(170)
        }

        var root = svc.currentRoot()
        if (!adapter.findAndClickSearch(root)) return false
        awaitEventOrDelay(260)
        root = svc.currentRoot()
        if (!adapter.setSearchText(root, groupName)) return false
        awaitEventOrDelay(260)
        root = svc.currentRoot()
        if (!adapter.openSearchResult(root, groupName)) return false
        if (!waitUntil(timeoutMs.coerceIn(3_000L, 8_000L)) { adapter.isGroupVisible(service?.currentRoot(), groupName, packageName) }) return false

        // Verify it is really a group, not a contact with the same display name.
        root = svc.currentRoot()
        if (!adapter.openCurrentChatInfo(root, groupName)) return false
        val groupInfo = waitUntil(3_500L) { adapter.isGroupInfoScreen(service?.currentRoot()) }
        svc.performBack(); waitUntil(3_000L) { adapter.isGroupVisible(service?.currentRoot(), groupName, packageName) }
        return groupInfo && adapter.isGroupVisible(svc.currentRoot(), groupName, packageName)
    }

    private fun openTargetWhatsApp(packageName: String): Boolean = runCatching {
        if (adapter.isWhatsAppRoot(service?.currentRoot(), packageName)) return true
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.setPackage(packageName)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

    private suspend fun waitIfPaused(runId: Long) {
        while (pauseRequested) {
            repository.updatePublishRunStatus(runId, PublishRunStatus.PAUSED)
            delay(200)
        }
        repository.updatePublishRunStatus(runId, PublishRunStatus.RUNNING)
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            withTimeoutOrNull(150L) { uiEvents.first() }
            delay(_state.value.speed.settleMs.coerceAtMost(90L))
        }
        return predicate()
    }

    private suspend fun awaitEventOrDelay(ms: Long) {
        withTimeoutOrNull(ms) { uiEvents.first() }
        delay(_state.value.speed.settleMs.coerceAtMost(120L))
    }

    private fun speedForDelay(delayMs: Long): PublishSpeedProfile = PublishSpeedProfile.entries.minByOrNull {
        kotlin.math.abs(it.betweenGroupsMs - delayMs)
    } ?: PublishSpeedProfile.ADAPTIVE
}
