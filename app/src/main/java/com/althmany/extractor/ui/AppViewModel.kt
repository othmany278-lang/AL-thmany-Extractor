package com.althmany.extractor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.althmany.extractor.ExtractorApp
import com.althmany.extractor.data.ExtractionMode
import com.althmany.extractor.data.LinkRecord
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
import com.althmany.extractor.engine.ScanUiState
import com.althmany.extractor.engine.ScanScope
import com.althmany.extractor.engine.ScanSpeedProfile
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

    private val _scanItems = MutableStateFlow<List<ScanRecord>>(emptyList())
    val scanItems: StateFlow<List<ScanRecord>> = _scanItems.asStateFlow()

    private val _publishItems = MutableStateFlow<List<PublishItem>>(emptyList())
    val publishItems: StateFlow<List<PublishItem>> = _publishItems.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

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
            repo.addGroupsFromText(text)
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

    fun setMode(mode: ExtractionMode) = ExtractionController.setMode(mode)
    fun setSpeed(speed: SpeedProfile) = ExtractionController.setSpeed(speed)
    fun setMaxRounds(value: Int) = ExtractionController.setMaxScrollIterations(value)
    fun setTargetWhatsApp(packageName: String) = ExtractionController.setTargetWhatsAppPackage(packageName)
    fun refreshRuntimeEnvironment() = ExtractionController.refreshRuntimeEnvironment()

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
            repo.setSelectionPreset(preset)
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
    fun consumeMessage() { _message.value = null }
}
