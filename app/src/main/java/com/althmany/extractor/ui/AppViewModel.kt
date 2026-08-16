package com.althmany.extractor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.althmany.extractor.ExtractorApp
import com.althmany.extractor.data.ExtractionMode
import com.althmany.extractor.data.LinkRecord
import com.althmany.extractor.data.ExtractionLog
import com.althmany.extractor.data.GroupSelectionPreset
import com.althmany.extractor.data.SpeedProfile
import com.althmany.extractor.data.ScanRecord
import com.althmany.extractor.data.TargetGroup
import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.data.PublishItem
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.ExtractionUiState
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.engine.PublishUiState
import com.althmany.extractor.engine.PublishSpeedProfile
import com.althmany.extractor.engine.PublishNavigationMode
import com.althmany.extractor.engine.ScanUiState
import com.althmany.extractor.engine.ScanScope
import com.althmany.extractor.engine.ScanActionMode
import com.althmany.extractor.engine.ScanSpeedProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as ExtractorApp).repository

    val engineState: StateFlow<ExtractionUiState> = ExtractionController.state
    val scanState: StateFlow<ScanUiState> = ScanController.state
    val publishState: StateFlow<PublishUiState> = PublishController.state

    private val _groups = MutableStateFlow<List<TargetGroup>>(emptyList())
    val groups: StateFlow<List<TargetGroup>> = _groups.asStateFlow()

    private val _links = MutableStateFlow<List<LinkRecord>>(emptyList())
    val links: StateFlow<List<LinkRecord>> = _links.asStateFlow()

    private val _logs = MutableStateFlow<List<ExtractionLog>>(emptyList())
    val logs: StateFlow<List<ExtractionLog>> = _logs.asStateFlow()

    private val _scanItems = MutableStateFlow<List<ScanRecord>>(emptyList())
    val scanItems: StateFlow<List<ScanRecord>> = _scanItems.asStateFlow()

    private val _publishItems = MutableStateFlow<List<PublishItem>>(emptyList())
    val publishItems: StateFlow<List<PublishItem>> = _publishItems.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var globalJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            var lastIndex = -1
            var lastStatus = ""
            var lastStatsHash = 0
            ScanController.state.collect { state ->
                val key = state.status.name
                val statsHash = state.stats.hashCode()
                if (state.currentIndex != lastIndex || key != lastStatus || statsHash != lastStatsHash) {
                    lastIndex = state.currentIndex
                    lastStatus = key
                    lastStatsHash = statsHash
                    _scanItems.value = repo.scanItems()
                }
            }
        }
        viewModelScope.launch {
            var lastIndex = -1
            var lastStatus = ""
            PublishController.state.collect { state ->
                if (state.currentIndex != lastIndex || state.status.name != lastStatus) {
                    lastIndex = state.currentIndex
                    lastStatus = state.status.name
                    val runId = state.activeRunId
                    _publishItems.value = if (runId == null) emptyList() else repo.publishItems(runId)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _groups.value = repo.groups()
            _links.value = repo.links()
            _logs.value = repo.logs()
            _scanItems.value = repo.scanItems()
            val publishRunId = PublishController.state.value.activeRunId
            _publishItems.value = if (publishRunId == null) emptyList() else repo.publishItems(publishRunId)
            ExtractionController.refreshStats()
            ScanController.refreshStats()
            PublishController.refreshStats()
        }
    }

    fun addGroups(text: String) {
        viewModelScope.launch {
            repo.addGroupsFromText(text, engineState.value.selectedWhatsAppPackage.orEmpty())
            _groups.value = repo.groups()
            _message.value = "تم حفظ قائمة المجموعات"
        }
    }

    fun syncGroups() {
        viewModelScope.launch {
            runCatching { ExtractionController.syncGroupsNow() }
                .onSuccess { added -> _message.value = "اكتملت المزامنة — أضيف $added عنصر جديد" }
                .onFailure { _message.value = it.message ?: "تعذرت المزامنة" }
            _groups.value = repo.groups()
            ExtractionController.refreshStats()
        }
    }

    private suspend fun ensureGroupsReady(): Boolean {
        val packageName = engineState.value.selectedWhatsAppPackage
        if (packageName.isNullOrBlank()) {
            _message.value = "RUNTIME_NO_TARGET: اختر نسخة واتساب أولاً"
            return false
        }
        fun usable(list: List<TargetGroup>) = list.filter {
            !it.stale && it.active && (it.discovered || it.selected) &&
                (it.whatsappPackage.isBlank() || it.whatsappPackage == packageName)
        }

        var current = usable(repo.groups())
        if (current.isNotEmpty()) {
            _groups.value = repo.groups()
            return true
        }

        _message.value = "SYNC_REQUIRED: لا توجد قروبات — بدء المزامنة تلقائيًا"
        val sync = runCatching { ExtractionController.syncGroupsNow() }
        if (sync.isFailure) {
            _message.value = "SYNC_FAILED: ${sync.exceptionOrNull()?.message ?: "تعذرت المزامنة"}"
            _groups.value = repo.groups()
            return false
        }

        val all = repo.groups()
        _groups.value = all
        current = usable(all)
        if (current.isEmpty()) {
            _message.value = "NO_GROUPS_SYNCED: انتهت المزامنة بدون قروبات مؤكدة"
            return false
        }
        _message.value = "SYNC_READY: ${current.size} قروب جاهز"
        return true
    }

    fun startExtractionSmart() {
        if (globalJob?.isActive == true) return
        if (ExtractionController.isBusy() || ScanController.isRunning() || PublishController.isRunning()) {
            _message.value = "هناك عملية تعمل بالفعل"
            return
        }
        viewModelScope.launch {
            if (ensureGroupsReady()) ExtractionController.start()
        }
    }

    fun startScanWithInput(input: String) {
        if (globalJob?.isActive == true) return
        viewModelScope.launch {
            if (input.isNotBlank()) {
                val added = repo.addScanLinksFromText(input)
                _message.value = "تمت إضافة $added رابط جديد — بدء الفحص"
            }
            _scanItems.value = repo.scanItems()
            ScanController.refreshStats()
            if (_scanItems.value.isEmpty()) {
                _message.value = "NO_SCAN_ITEMS: الصق روابط أو استوردها من الاستخراج"
                return@launch
            }
            ScanController.start()
        }
    }

    private suspend fun waitExtractionFinished(): Boolean {
        var started = false
        while (true) {
            started = started || ExtractionController.isBusy()
            val status = engineState.value.status
            if (status == com.althmany.extractor.data.EngineStatus.ERROR ||
                status == com.althmany.extractor.data.EngineStatus.STOPPED) return false
            if (started && !ExtractionController.isBusy()) {
                return status == com.althmany.extractor.data.EngineStatus.COMPLETED
            }
            delay(100L)
        }
    }

    private suspend fun waitScanFinished(): Boolean {
        var started = false
        while (true) {
            started = started || ScanController.isRunning()
            val status = scanState.value.status
            if (status == com.althmany.extractor.engine.ScanEngineStatus.ERROR ||
                status == com.althmany.extractor.engine.ScanEngineStatus.STOPPED) return false
            if (started && !ScanController.isRunning()) {
                return status == com.althmany.extractor.engine.ScanEngineStatus.COMPLETED
            }
            delay(100L)
        }
    }

    private suspend fun waitPublishFinished(): Boolean {
        var started = false
        while (true) {
            started = started || PublishController.isRunning()
            val status = publishState.value.status
            if (status == com.althmany.extractor.engine.PublishEngineStatus.ERROR ||
                status == com.althmany.extractor.engine.PublishEngineStatus.STOPPED) return false
            if (started && !PublishController.isRunning()) {
                return status == com.althmany.extractor.engine.PublishEngineStatus.COMPLETED
            }
            delay(100L)
        }
    }

    fun startAllSmart() {
        if (globalJob?.isActive == true ||
            ExtractionController.isBusy() || ScanController.isRunning() || PublishController.isRunning()) {
            _message.value = "لا يمكن بدء الكل: توجد عملية نشطة"
            return
        }

        globalJob = viewModelScope.launch {
            if (!ensureGroupsReady()) return@launch

            _message.value = "1/4 • القروبات جاهزة — بدء الاستخراج"
            ExtractionController.start()
            if (!waitExtractionFinished()) {
                _message.value = "PIPELINE_STOPPED: الاستخراج • ${engineState.value.message}"
                return@launch
            }

            val imported = repo.importInviteLinksFromExtraction()
            _scanItems.value = repo.scanItems()
            ScanController.refreshStats()

            if (_scanItems.value.isNotEmpty()) {
                _message.value = "3/4 • بدء الفحص${if (scanState.value.actionMode == ScanActionMode.SCAN_AND_JOIN) " + الانضمام" else ""} • جديد $imported"
                ScanController.start()
                if (!waitScanFinished()) {
                    _message.value = "PIPELINE_STOPPED: الفحص • ${scanState.value.message}"
                    return@launch
                }
            } else {
                _message.value = "3/4 • لا توجد روابط دعوة — تخطي الفحص"
            }

            val draft = publishState.value.messageText.trim()
            if (draft.isNotBlank()) {
                _message.value = "4/4 • بدء النشر"
                PublishController.start(draft)
                if (!waitPublishFinished()) {
                    _message.value = "PIPELINE_STOPPED: النشر • ${publishState.value.info}"
                    return@launch
                }
                _message.value = "PIPELINE_COMPLETED: اكتملت جميع المراحل"
            } else {
                _message.value = "PIPELINE_COMPLETED: اكتمل الاستخراج والفحص — النشر متخطى لعدم وجود رسالة"
            }
        }
    }

    fun setMode(mode: ExtractionMode) = ExtractionController.setMode(mode)
    fun setSpeed(speed: SpeedProfile) = ExtractionController.setSpeed(speed)
    fun setMaxRounds(value: Int) = ExtractionController.setMaxScrollIterations(value)
    fun setExtractionRetries(value: Int) = ExtractionController.setMaxSameGroupRetries(value)
    fun setExtractionDelayMs(value: Long) = ExtractionController.setBetweenItemsDelayMs(value)
    fun setTargetWhatsApp(packageName: String) = ExtractionController.setTargetWhatsAppPackage(packageName)
    fun refreshRuntimeEnvironment() = ExtractionController.refreshRuntimeEnvironment()

    /** Global controls used by every screen. Only the engine that currently owns WhatsApp reacts. */
    fun pauseActiveOperation() {
        when {
            publishState.value.running && !publishState.value.paused -> PublishController.pause()
            scanState.value.running && !scanState.value.paused -> ScanController.pause()
            engineState.value.status !in setOf(
                com.althmany.extractor.data.EngineStatus.IDLE,
                com.althmany.extractor.data.EngineStatus.PAUSED,
                com.althmany.extractor.data.EngineStatus.COMPLETED,
                com.althmany.extractor.data.EngineStatus.STOPPED,
                com.althmany.extractor.data.EngineStatus.ERROR
            ) -> ExtractionController.pause()
        }
    }

    fun resumeActiveOperation() {
        when {
            publishState.value.paused -> PublishController.resume()
            scanState.value.paused -> ScanController.resume()
            engineState.value.status == com.althmany.extractor.data.EngineStatus.PAUSED -> ExtractionController.resume()
        }
    }

    /** Emergency/global stop: cancels every engine and releases the single WhatsApp UI owner. */
    fun stopAllOperations() {
        globalJob?.cancel()
        globalJob = null
        ExtractionController.stop()
        ScanController.stop()
        PublishController.stop()
        _message.value = "تم إيقاف جميع العمليات والمسار العام"
    }

    fun setSelected(id: Long, selected: Boolean) {
        viewModelScope.launch {
            repo.setSelected(id, selected)
            _groups.value = repo.groups()
            ExtractionController.refreshStats()
        }
    }

    fun setAllSelected(selected: Boolean) {
        viewModelScope.launch {
            repo.setAllSelected(selected)
            _groups.value = repo.groups()
            ExtractionController.refreshStats()
        }
    }

    fun applyGroupSelectionPreset(preset: GroupSelectionPreset) {
        viewModelScope.launch {
            repo.setSelectionPreset(preset, engineState.value.selectedWhatsAppPackage)
            _groups.value = repo.groups()
            ExtractionController.refreshStats()
            _message.value = preset.labelAr
        }
    }

    fun addScanLinks(text: String) {
        viewModelScope.launch {
            val added = repo.addScanLinksFromText(text)
            _scanItems.value = repo.scanItems()
            ScanController.refreshStats()
            _message.value = "تمت إضافة $added رابط دعوة جديد للفحص"
        }
    }

    fun importScanLinksFromExtraction() {
        viewModelScope.launch {
            val added = repo.importInviteLinksFromExtraction()
            _scanItems.value = repo.scanItems()
            ScanController.refreshStats()
            _message.value = "تم استيراد $added رابط دعوة جديد من نتائج الاستخراج"
        }
    }

    fun reloadScanItems() {
        viewModelScope.launch {
            _scanItems.value = repo.scanItems()
            ScanController.refreshStats()
        }
    }

    fun setScanSpeed(value: ScanSpeedProfile) = ScanController.setSpeed(value)
    fun setScanScope(value: ScanScope) = ScanController.setScope(value)
    fun setScanActionMode(value: ScanActionMode) = ScanController.setActionMode(value)
    fun setScanRequestToJoinEnabled(value: Boolean) = ScanController.setRequestToJoinEnabled(value)
    fun setScanMaxAttempts(value: Int) = ScanController.setMaxAttempts(value)

    fun clearScan() {
        ScanController.stop()
        viewModelScope.launch {
            repo.clearScan()
            _scanItems.value = emptyList()
            ScanController.refreshStats()
            _message.value = "تم مسح نتائج الفحص"
        }
    }

    fun setPublishSpeed(value: PublishSpeedProfile) = PublishController.setSpeed(value)
    fun setPublishMaxAttempts(value: Int) = PublishController.setMaxAttempts(value)
    fun setPublishNavigationMode(value: PublishNavigationMode) = PublishController.setNavigationMode(value)
    fun setPublishDraft(value: String) = PublishController.setDraft(value)
    fun setPublishContentMode(value: PublishContentMode) = PublishController.setContentMode(value)
    fun setPublishAttachment(uri: String?, mime: String?) = PublishController.setAttachment(uri, mime)
    fun reloadPublishItems() {
        viewModelScope.launch {
            val runId = PublishController.state.value.activeRunId
            _publishItems.value = if (runId == null) emptyList() else repo.publishItems(runId)
            PublishController.refreshStats(runId)
        }
    }
    fun clearPublishHistory() {
        PublishController.clearHistory()
        _publishItems.value = emptyList()
    }

    fun clearAll() {
        viewModelScope.launch {
            ExtractionController.stop()
            ScanController.stop()
            PublishController.stop()
            repo.clearAll()
            refresh()
            _message.value = "تم مسح البيانات"
        }
    }

    fun reloadLinks() { viewModelScope.launch { _links.value = repo.links() } }
    fun reloadLogs() { viewModelScope.launch { _logs.value = repo.logs() } }
    fun consumeMessage() { _message.value = null }
}
