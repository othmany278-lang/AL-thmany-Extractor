package com.althmany.extractor.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.althmany.extractor.accessibility.AccessibilityRuntimeBridge
import com.althmany.extractor.accessibility.WhatsAppAccessibilityService
import com.althmany.extractor.data.ExtractorRepository
import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.data.PublishItem
import com.althmany.extractor.data.PublishRunStatus
import com.althmany.extractor.data.PublishStatus
import com.althmany.extractor.data.TargetGroup
import com.althmany.extractor.data.GroupAccessMethod
import com.althmany.extractor.data.SpeedProfile
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
import java.util.UUID

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
    private val accessRouter = GroupAccessRouter(adapter)
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
            messageText = settings.lastMessage(),
            contentMode = settings.contentMode(),
            attachmentUri = settings.attachmentUri(),
            attachmentMime = settings.attachmentMime()
        )
        scope.launch {
            repository.resumablePublishRun()?.let { run ->
                _state.value = _state.value.copy(
                    activeRunId = run.id,
                    messageText = run.message,
                    speed = speedForDelay(run.delayMs),
                    maxAttempts = run.maxAttempts,
                    contentMode = run.contentMode,
                    attachmentUri = run.attachmentUri,
                    attachmentMime = run.attachmentMime,
                    runToken = run.runToken,
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
        val clean = message.take(16_000)
        settings.setLastMessage(clean)
        _state.value = _state.value.copy(messageText = clean)
    }

    fun setContentMode(mode: PublishContentMode) {
        if (isRunning()) return
        settings.setContentMode(mode)
        _state.value = _state.value.copy(contentMode = mode)
    }

    fun setAttachment(uri: String?, mime: String?) {
        if (isRunning()) return
        settings.setAttachment(uri, mime)
        _state.value = _state.value.copy(attachmentUri = uri, attachmentMime = mime)
    }

    fun start(message: String) {
        if (isRunning()) return
        if (ExtractionController.isBusy() || ScanController.isRunning()) {
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "أوقف الاستخراج أو الفحص قبل تشغيل النشر")
            return
        }
        val clean = message.trim()
        val mode = _state.value.contentMode
        val attachmentUri = _state.value.attachmentUri
        val attachmentMime = _state.value.attachmentMime
        if (mode != PublishContentMode.VCF && clean.isBlank()) {
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "أدخل محتوى النشر أولاً")
            return
        }
        if (mode.attachmentRequired && attachmentUri.isNullOrBlank()) {
            _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, info = "اختر ملف/صورة لهذا النوع قبل البدء")
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
            _state.value = _state.value.copy(status = PublishEngineStatus.PREPARING, running = true, info = "Preflight: التحقق من واتساب ومحرك الإرسال")
            if (!ensureRuntimeReady()) {
                _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, running = false, info = "فشل Preflight: واتساب/Accessibility غير جاهز في نفس البيئة")
                return@launch
            }
            val groups = repository.selectedGroups().filter {
                it.publishable && !it.communityParent && (it.whatsappPackage.isBlank() || it.whatsappPackage == packageName)
            }
            if (groups.isEmpty()) {
                _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, running = false, info = "لا توجد قروبات محددة وقابلة للنشر")
                return@launch
            }
            val speed = settings.speed()
            val attempts = settings.maxAttempts()
            val runToken = UUID.randomUUID().toString()
            repository.stopResumablePublishRuns()
            val runId = repository.createPublishRun(
                clean, packageName, speed.betweenGroupsMs, attempts, groups.map { it.name },
                mode, attachmentUri, attachmentMime, runToken
            )
            _state.value = _state.value.copy(
                activeRunId = runId, messageText = clean, speed = speed, maxAttempts = attempts,
                contentMode = mode, attachmentUri = attachmentUri, attachmentMime = attachmentMime, runToken = runToken
            )
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
            _state.value = _state.value.copy(
                activeRunId = run.id, messageText = run.message, maxAttempts = run.maxAttempts, speed = speedForDelay(run.delayMs),
                contentMode = run.contentMode, attachmentUri = run.attachmentUri, attachmentMime = run.attachmentMime, runToken = run.runToken
            )
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
                contentMode = settings.contentMode(),
                attachmentUri = settings.attachmentUri(),
                attachmentMime = settings.attachmentMime(),
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
                contentMode = run.contentMode,
                attachmentUri = run.attachmentUri,
                attachmentMime = run.attachmentMime,
                runToken = run.runToken,
                info = "تحضير النشر ${run.contentMode.labelAr} إلى ${all.size} قروب • الفاصل الحقيقي ${run.delayMs}ms"
            )
            refreshStats(runId); notifier.show(_state.value)

            for ((offset, item) in items.withIndex()) {
                waitIfPaused(runId)
                val absoluteIndex = all.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.plus(1) ?: (offset + 1)
                _state.value = _state.value.copy(currentIndex = absoluteIndex, currentGroup = item.groupName, currentAttempt = 0)
                notifier.show(_state.value)

                val result = publishOne(run, item, absoluteIndex - 1)
                repository.updatePublishItem(item.id, result.status, result.detail, incrementAttempt = false, verified = result.verified)
                repository.groupByName(item.groupName, run.targetPackage)?.let { group ->
                    repository.updateGroupPublishState(
                        group.id,
                        result.status,
                        if (result.status == PublishStatus.FAILED || result.status == PublishStatus.UNCERTAIN) result.detail else null
                    )
                }
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

    private suspend fun publishOne(
        run: com.althmany.extractor.data.PublishRun,
        item: PublishItem,
        itemIndex: Int
    ): PublishDecision {
        return when (run.contentMode) {
            PublishContentMode.SINGLE_TEXT,
            PublishContentMode.MULTI_TEXT,
            PublishContentMode.CONTACT_TEXT -> publishTextOne(run, item, itemIndex)
            PublishContentMode.VCF,
            PublishContentMode.VCF_WITH_TEXT,
            PublishContentMode.IMAGE_WITH_CAPTION -> publishAttachmentOne(run, item)
        }
    }

    private fun messageForItem(run: com.althmany.extractor.data.PublishRun, itemIndex: Int): String {
        return when (run.contentMode) {
            PublishContentMode.MULTI_TEXT -> {
                val parts = run.message
                    .split(Regex("(?m)^\\s*---+\\s*$"))
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                if (parts.isEmpty()) run.message else parts[itemIndex % parts.size]
            }
            PublishContentMode.CONTACT_TEXT -> formatContactsAsText(run.message)
            else -> run.message
        }
    }

    /**
     * CONTACT_TEXT accepts one contact per line as `name | number` (tab/comma/Arabic semicolon are
     * also accepted). If the input is ordinary prose it is preserved as-is instead of guessing.
     */
    private fun formatContactsAsText(raw: String): String {
        val lines = raw.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.isEmpty()) return raw
        var parsedAny = false
        val formatted = lines.map { line ->
            val parts = line.split(Regex("\\s*(?:\\||\\t|,|؛)\\s*"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].any(Char::isDigit)) {
                parsedAny = true
                "👤 ${parts[0].trim()}\n📞 ${parts[1].trim()}"
            } else line
        }
        return if (parsedAny) formatted.joinToString("\n\n") else raw
    }

    private suspend fun publishTextOne(
        run: com.althmany.extractor.data.PublishRun,
        item: PublishItem,
        itemIndex: Int
    ): PublishDecision {
        val message = messageForItem(run, itemIndex)
        if (message.isBlank()) return PublishDecision(PublishStatus.FAILED, "الرسالة المخصصة لهذا القروب فارغة")
        for (attempt in 1..run.maxAttempts) {
            waitIfPaused(run.id)
            _state.value = _state.value.copy(currentAttempt = attempt, status = PublishEngineStatus.OPENING_GROUP, info = "فتح ${item.groupName} • محاولة $attempt/${run.maxAttempts}")
            repository.updatePublishItem(item.id, PublishStatus.OPENING, "فتح القروب — محاولة $attempt", incrementAttempt = true)

            val groupRecord = repository.groupByName(item.groupName, run.targetPackage)
                ?: return PublishDecision(PublishStatus.FAILED, "سجل القروب غير موجود في قاعدة المزامنة")
            val opened = openVerifiedGroup(groupRecord, run.targetPackage, _state.value.speed.uiTimeoutMs)
            if (!opened) {
                if (attempt < run.maxAttempts) {
                    _state.value = _state.value.copy(status = PublishEngineStatus.RETRYING, info = "تعذر فتح القروب — إعادة المحاولة")
                    delay(500L + attempt * 300L)
                    continue
                }
                return PublishDecision(PublishStatus.FAILED, "تعذر فتح القروب أو التحقق من أنه مجموعة")
            }

            val root = service?.currentRoot()
            _state.value = _state.value.copy(status = PublishEngineStatus.WRITING, info = "تحضير المحتوى")
            repository.updatePublishItem(item.id, PublishStatus.PREPARING, "تحضير المحتوى")
            if (!adapter.setMessageComposerText(root, message)) {
                if (attempt < run.maxAttempts) { delay(350); continue }
                return PublishDecision(PublishStatus.FAILED, "لم يتم العثور على حقل كتابة الرسالة")
            }
            if (!waitUntil(2_600L) { adapter.messageComposerContains(service?.currentRoot(), message) }) {
                if (attempt < run.maxAttempts) continue
                return PublishDecision(PublishStatus.FAILED, "لم يتم تثبيت النص في حقل الكتابة")
            }

            _state.value = _state.value.copy(status = PublishEngineStatus.SENDING, info = "إرسال الرسالة")
            repository.updatePublishItem(item.id, PublishStatus.SENDING, "تم تجهيز النص وجارٍ الإرسال")
            if (!adapter.clickSendButton(service?.currentRoot())) {
                if (attempt < run.maxAttempts) { delay(420); continue }
                return PublishDecision(PublishStatus.FAILED, "تعذر الضغط على زر الإرسال")
            }

            _state.value = _state.value.copy(status = PublishEngineStatus.VERIFYING, info = "التحقق من الإرسال")
            val verified = waitUntil(4_500L) {
                val current = service?.currentRoot()
                !adapter.messageComposerContains(current, message) && adapter.visibleNonEditableExactText(current, message)
            }
            if (verified) return PublishDecision(PublishStatus.VERIFIED, "تم الإرسال والتحقق من ظهور الرسالة", true)

            val composerStillHasText = adapter.messageComposerContains(service?.currentRoot(), message)
            return if (!composerStillHasText) {
                PublishDecision(PublishStatus.SENT, "تم تنفيذ الإرسال؛ تعذر التحقق البصري من الفقاعة", false)
            } else {
                // Once a send click may have reached WhatsApp, fail closed and never blind-retry.
                PublishDecision(PublishStatus.UNCERTAIN, "تم تنفيذ أمر الإرسال لكن النتيجة غير محسومة؛ لن يُعاد تلقائيًا لمنع التكرار")
            }
        }
        return PublishDecision(PublishStatus.FAILED, "استنفدت محاولات فتح القروب")
    }

    private suspend fun publishAttachmentOne(
        run: com.althmany.extractor.data.PublishRun,
        item: PublishItem
    ): PublishDecision {
        val uriText = run.attachmentUri ?: return PublishDecision(PublishStatus.FAILED, "لا يوجد ملف مرفق")
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return PublishDecision(PublishStatus.FAILED, "رابط الملف المرفق غير صالح")
        val mime = run.attachmentMime ?: when (run.contentMode) {
            PublishContentMode.VCF, PublishContentMode.VCF_WITH_TEXT -> "text/x-vcard"
            PublishContentMode.IMAGE_WITH_CAPTION -> "image/*"
            else -> "application/octet-stream"
        }

        _state.value = _state.value.copy(status = PublishEngineStatus.PREPARING, info = "فتح مسار المشاركة الأصلي في واتساب")
        repository.updatePublishItem(item.id, PublishStatus.PREPARING, "تحضير مشاركة المرفق", incrementAttempt = true)
        val launched = runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                setPackage(run.targetPackage)
                putExtra(Intent.EXTRA_STREAM, uri)
                // Keep the image caption on the native share intent too. Some WhatsApp builds
                // commit directly after recipient selection without exposing a separate preview.
                if (run.contentMode == PublishContentMode.IMAGE_WITH_CAPTION && run.message.isNotBlank()) {
                    putExtra(Intent.EXTRA_TEXT, run.message)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) return PublishDecision(PublishStatus.FAILED, "تعذر فتح مشاركة المرفق في واتساب المحدد")

        awaitEventOrDelay(320)
        val svc = service ?: return PublishDecision(PublishStatus.FAILED, "Accessibility غير متصلة")
        var root = svc.currentRoot()
        if (!adapter.isWhatsAppRoot(root, run.targetPackage)) {
            if (!waitUntil(3_500L) { adapter.isWhatsAppRoot(service?.currentRoot(), run.targetPackage) }) {
                return PublishDecision(PublishStatus.FAILED, "لم تظهر واجهة مشاركة واتساب")
            }
            root = svc.currentRoot()
        }

        // Recipient picker: search exact selected group and never choose a different visible target.
        if (adapter.findAndClickSearch(root)) { awaitEventOrDelay(180); root = svc.currentRoot() }
        if (!adapter.setSearchText(root, item.groupName)) return PublishDecision(PublishStatus.FAILED, "تعذر البحث عن القروب داخل شاشة المشاركة")
        awaitEventOrDelay(250)
        if (!adapter.openSearchResult(svc.currentRoot(), item.groupName)) return PublishDecision(PublishStatus.FAILED, "تعذر تحديد القروب في شاشة المشاركة")
        awaitEventOrDelay(220)

        _state.value = _state.value.copy(status = PublishEngineStatus.SENDING, info = "تأكيد مشاركة المرفق")
        repository.updatePublishItem(item.id, PublishStatus.SENDING, "تم تحديد القروب وجارٍ تأكيد المرفق")

        // Move from recipient picker to preview/direct-send. This is the first action that may commit
        // a send, so no blind retry is allowed beyond this point.
        if (!adapter.clickPositiveShareAction(svc.currentRoot())) {
            return PublishDecision(PublishStatus.FAILED, "لم يظهر زر المتابعة/الإرسال للمرفق")
        }
        awaitEventOrDelay(320)

        if (run.contentMode == PublishContentMode.IMAGE_WITH_CAPTION && run.message.isNotBlank()) {
            val previewRoot = svc.currentRoot()
            if (adapter.setMessageComposerText(previewRoot, run.message)) {
                waitUntil(1_500L) { adapter.messageComposerContains(service?.currentRoot(), run.message) }
            }
        }
        // Some WhatsApp builds need one final Send on the preview; if the chat is already visible,
        // the first positive action committed the share and we do not click again.
        if (!adapter.isGroupVisible(svc.currentRoot(), item.groupName, run.targetPackage)) {
            adapter.clickPositiveShareAction(svc.currentRoot())
            awaitEventOrDelay(420)
        }

        val chatVisible = waitUntil(4_500L) { adapter.isGroupVisible(service?.currentRoot(), item.groupName, run.targetPackage) }
        if (chatVisible) {
            repository.groupByName(item.groupName, run.targetPackage)?.let {
                repository.recordGroupAccessSuccess(it.id, GroupAccessMethod.SHARE_PICKER)
            }
        }
        if (!chatVisible) {
            return PublishDecision(PublishStatus.UNCERTAIN, "تم تنفيذ مشاركة المرفق لكن تعذر إثبات النتيجة؛ لن تتم إعادة الإرسال تلقائيًا")
        }

        if (run.contentMode == PublishContentMode.VCF_WITH_TEXT && run.message.isNotBlank()) {
            // VCF + text is intentionally two committed operations: after the card share reaches the
            // verified chat, send the related text once with the same duplicate guard.
            val text = run.message.trim()
            if (adapter.setMessageComposerText(svc.currentRoot(), text) && waitUntil(1_800L) { adapter.messageComposerContains(service?.currentRoot(), text) }) {
                if (adapter.clickSendButton(svc.currentRoot())) {
                    val textVerified = waitUntil(3_500L) { !adapter.messageComposerContains(service?.currentRoot(), text) }
                    return if (textVerified) PublishDecision(PublishStatus.VERIFIED, "تم إرسال VCF ثم النص المرتبط", true)
                    else PublishDecision(PublishStatus.UNCERTAIN, "تمت مشاركة VCF ونُفذ إرسال النص لكن تعذر إثبات النص؛ لن يُعاد تلقائيًا")
                }
            }
            return PublishDecision(PublishStatus.UNCERTAIN, "تمت مشاركة VCF لكن تعذر إكمال النص المرتبط بدون مخاطرة تكرار البطاقة")
        }

        return PublishDecision(PublishStatus.SENT, "تم تنفيذ مشاركة المرفق ووصل التطبيق إلى القروب المحدد", false)
    }

    private suspend fun openVerifiedGroup(group: TargetGroup, packageName: String, timeoutMs: Long): Boolean {
        if (!openTargetWhatsApp(packageName)) return false
        awaitEventOrDelay(120)
        val svc = service ?: return false
        val timing = publishAccessTiming(_state.value.speed)

        val access = accessRouter.open(
            group = group,
            service = svc,
            expectedPackage = packageName,
            timing = timing,
            waitForUi = { ms -> awaitEventOrDelay(ms.coerceAtMost(timeoutMs)) },
            ensureForeground = { openTargetWhatsApp(packageName) },
            maxScrollPasses = 300,
            allowSearchFallback = true
        )
        if (!access.opened) {
            access.attempted.lastOrNull()?.let { repository.recordGroupAccessFailure(group.id, it) }
            return false
        }
        repository.recordGroupAccessSuccess(group.id, access.method)
        if (group.whatsappPackage.isBlank()) repository.updateGroupIdentity(group.id, group.jidOrGroupId, packageName)

        // Verify the opened target is actually a group before writing. This verification is cached
        // in GroupRecord after success, but a fresh UI check is still used when the record was not
        // previously verified.
        if (group.verifiedGroup) return adapter.isGroupVisible(svc.currentRoot(), group.name, packageName)
        var root = svc.currentRoot()
        if (!adapter.openCurrentChatInfo(root, group.name)) return false
        val groupInfo = waitUntil(3_500L) { adapter.isGroupInfoScreen(service?.currentRoot()) }
        svc.performBack()
        waitUntil(3_000L) { adapter.isGroupVisible(service?.currentRoot(), group.name, packageName) }
        if (groupInfo) repository.updateGroupCapabilities(group.id, verified = true, active = true, publishable = !group.communityParent, communityParent = group.communityParent)
        return groupInfo && adapter.isGroupVisible(svc.currentRoot(), group.name, packageName)
    }

    private fun publishAccessTiming(speed: PublishSpeedProfile): TimingPolicy = when (speed) {
        PublishSpeedProfile.TURBO -> ExtractionPolicy.timing(SpeedProfile.HYPER)
        PublishSpeedProfile.FAST -> ExtractionPolicy.timing(SpeedProfile.ADAPTIVE)
        PublishSpeedProfile.ADAPTIVE -> ExtractionPolicy.timing(SpeedProfile.SMART)
        PublishSpeedProfile.SAFE -> ExtractionPolicy.timing(SpeedProfile.SAFE)
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
