package com.althmany.extractor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.engine.ScanActionMode
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.export.ExportFormat
import com.althmany.extractor.export.ExportManager
import com.althmany.extractor.ui.AlThmanyTheme
import com.althmany.extractor.ui.AppScreen
import com.althmany.extractor.ui.AppSideRail
import com.althmany.extractor.ui.AppViewModel
import com.althmany.extractor.ui.ExactDashboardScreen
import com.althmany.extractor.ui.GroupsScreen
import com.althmany.extractor.ui.LogsScreen
import com.althmany.extractor.ui.PublishScreen
import com.althmany.extractor.ui.ResultsScreen
import com.althmany.extractor.ui.ScanScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlThmanyTheme { ExtractorAppUi(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimeEnvironment()
    }
}

@Composable
private fun ExtractorAppUi(viewModel: AppViewModel) {
    val context = LocalContext.current
    val engine by viewModel.engineState.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val links by viewModel.links.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val scanItems by viewModel.scanItems.collectAsState()
    val publishState by viewModel.publishState.collectAsState()
    val publishItems by viewModel.publishItems.collectAsState()
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var pendingFormat by remember { mutableStateOf(ExportFormat.XLSX) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.refreshRuntimeEnvironment()
        viewModel.refresh()
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) runCatching { ExportManager.export(context.contentResolver, uri, pendingFormat, links) }
    }
    val createScanDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) runCatching { ExportManager.exportScan(context.contentResolver, uri, pendingFormat, scanItems) }
    }
    val createPublishDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) runCatching { ExportManager.exportPublish(context.contentResolver, uri, pendingFormat, publishItems) }
    }

    val openGroupFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { text ->
                viewModel.addGroups(text)
                screen = AppScreen.GROUPS
            }
        }
    }

    val pickPublishAttachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.setPublishAttachment(uri.toString(), context.contentResolver.getType(uri))
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("إعدادات المحرك") },
            text = { Text("يمكنك فتح إعدادات Accessibility أو طلب/اختبار Shizuku. إعدادات السرعة والاستخراج موجودة مباشرة في لوحة التحكم.") },
            confirmButton = {
                TextButton(onClick = { showSettings = false; ExtractionController.openAccessibilitySettings() }) { Text("Accessibility") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false; ExtractionController.probeShizuku() }) { Text("اختبار Shizuku") }
            }
        )
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("المساعدة") },
            text = { Text("1) اختر نسخة واتساب. 2) فعّل Accessibility أو Shizuku. 3) مزامنة القروبات من صفحة المجموعات. 4) اختر القروبات. 5) ابدأ التشغيل الذكي. الاستخراج يفتح القروب من الذاكرة بدون Search ثم يقرأ الرسائل ويستخرج الروابط أثناء التمرير.") },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("حسنًا") } }
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { scaffoldPadding ->
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(scaffoldPadding)) {
                val railWidth = if (maxWidth < 460.dp) 88.dp else 116.dp
                Row(Modifier.fillMaxSize()) {
                    AppSideRail(
                        current = screen,
                        engine = engine,
                        onNavigate = { target ->
                            screen = target
                            if (target == AppScreen.RESULTS) viewModel.reloadLinks()
                            if (target == AppScreen.GROUPS) viewModel.refresh()
                            if (target == AppScreen.PUBLISH) viewModel.reloadPublishItems()
                            if (target == AppScreen.LOGS) viewModel.reloadLogs()
                        },
                        onSettings = { showSettings = true },
                        onHelp = { showHelp = true },
                        modifier = Modifier.width(railWidth)
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            val zero = PaddingValues(0.dp)
                            when (screen) {
                                AppScreen.HOME -> ExactDashboardScreen(
                                    padding = zero,
                                    engine = engine,
                                    scan = scanState,
                                    publish = publishState,
                                    accessibilityEnabled = isAccessibilityServiceEnabled(context),
                                    onOpenAccessibility = ExtractionController::openAccessibilitySettings,
                                    onRequestShizuku = { ExtractionController.requestShizukuPermission() },
                                    onProbeShizuku = ExtractionController::probeShizuku,
                                    onOpenWhatsApp = { ExtractionController.openWhatsApp() },
                                    onSettings = { showSettings = true },
                                    onStart = ExtractionController::start,
                                    onPause = ExtractionController::pause,
                                    onResume = ExtractionController::resume,
                                    onStop = ExtractionController::stop,
                                    onGroups = { screen = AppScreen.GROUPS },
                                    onResults = { viewModel.reloadLinks(); screen = AppScreen.RESULTS },
                                    onScan = { viewModel.reloadScanItems(); screen = AppScreen.SCAN },
                                    onAutoJoin = {
                                        viewModel.setScanActionMode(ScanActionMode.SCAN_AND_JOIN)
                                        viewModel.setScanRequestToJoinEnabled(true)
                                        viewModel.reloadScanItems()
                                        screen = AppScreen.SCAN
                                    },
                                    onPublish = { viewModel.reloadPublishItems(); screen = AppScreen.PUBLISH },
                                    onMode = viewModel::setMode,
                                    onSpeed = viewModel::setSpeed,
                                    onMaxRounds = viewModel::setMaxRounds,
                                    onRetries = viewModel::setExtractionRetries,
                                    onDelayMs = viewModel::setExtractionDelayMs,
                                    onTargetWhatsApp = viewModel::setTargetWhatsApp,
                                    onLogs = { viewModel.reloadLogs(); screen = AppScreen.LOGS },
                                    onExportData = {
                                        viewModel.reloadLinks()
                                        pendingFormat = ExportFormat.XLSX
                                        createDocument.launch("AL-thmany-links.xlsx")
                                    },
                                    onOpenFile = { openGroupFile.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*")) }
                                )

                                AppScreen.SCAN -> ScanScreen(
                                    padding = zero,
                                    engine = engine,
                                    scan = scanState,
                                    items = scanItems,
                                    accessibilityEnabled = isAccessibilityServiceEnabled(context),
                                    onExtraction = { screen = AppScreen.HOME },
                                    onPublish = { viewModel.reloadPublishItems(); screen = AppScreen.PUBLISH },
                                    onTargetWhatsApp = viewModel::setTargetWhatsApp,
                                    onAddLinks = viewModel::addScanLinks,
                                    onImportExtracted = viewModel::importScanLinksFromExtraction,
                                    onStart = ScanController::start,
                                    onPause = ScanController::pause,
                                    onResume = ScanController::resume,
                                    onStop = ScanController::stop,
                                    onRefresh = viewModel::reloadScanItems,
                                    onClear = viewModel::clearScan,
                                    onScanSpeed = viewModel::setScanSpeed,
                                    onScanScope = viewModel::setScanScope,
                                    onScanActionMode = viewModel::setScanActionMode,
                                    onRequestToJoinEnabled = viewModel::setScanRequestToJoinEnabled,
                                    onMaxAttempts = viewModel::setScanMaxAttempts,
                                    onExport = { format -> pendingFormat = format; createScanDocument.launch("AL-thmany-scan.${format.extension}") }
                                )

                                AppScreen.PUBLISH -> PublishScreen(
                                    padding = zero,
                                    engine = engine,
                                    publish = publishState,
                                    items = publishItems,
                                    accessibilityEnabled = isAccessibilityServiceEnabled(context),
                                    onExtraction = { screen = AppScreen.HOME },
                                    onScan = { viewModel.reloadScanItems(); screen = AppScreen.SCAN },
                                    onGroups = { screen = AppScreen.GROUPS },
                                    onTargetWhatsApp = viewModel::setTargetWhatsApp,
                                    onDraftChanged = viewModel::setPublishDraft,
                                    onContentMode = viewModel::setPublishContentMode,
                                    onPickAttachment = { mode ->
                                        val types = if (mode == PublishContentMode.IMAGE_WITH_CAPTION) arrayOf("image/*") else arrayOf("text/x-vcard", "text/vcard", "text/plain", "*/*")
                                        pickPublishAttachment.launch(types)
                                    },
                                    onClearAttachment = { viewModel.setPublishAttachment(null, null) },
                                    onSpeed = viewModel::setPublishSpeed,
                                    onMaxAttempts = viewModel::setPublishMaxAttempts,
                                    onStart = PublishController::start,
                                    onPause = PublishController::pause,
                                    onResume = PublishController::resume,
                                    onStop = PublishController::stop,
                                    onRefresh = viewModel::reloadPublishItems,
                                    onClearHistory = viewModel::clearPublishHistory,
                                    onExport = { format -> pendingFormat = format; createPublishDocument.launch("AL-thmany-publish.${format.extension}") }
                                )

                                AppScreen.GROUPS -> GroupsScreen(
                                    padding = zero,
                                    groups = groups.filter { group ->
                                        val selectedPackage = engine.selectedWhatsAppPackage
                                        selectedPackage == null || group.whatsappPackage.isBlank() || group.whatsappPackage == selectedPackage
                                    },
                                    onAddGroups = viewModel::addGroups,
                                    onSelected = viewModel::setSelected,
                                    onPreset = viewModel::applyGroupSelectionPreset,
                                    onStart = { screen = AppScreen.HOME; ExtractionController.start() },
                                    syncing = engine.status == com.althmany.extractor.data.EngineStatus.SYNCING_GROUPS,
                                    syncFound = engine.syncFound,
                                    onSync = viewModel::syncGroups
                                )

                                AppScreen.RESULTS -> ResultsScreen(
                                    padding = zero,
                                    links = links,
                                    onRefresh = viewModel::reloadLinks,
                                    onExport = { format -> pendingFormat = format; createDocument.launch("AL-thmany-links.${format.extension}") },
                                    onClearAll = viewModel::clearAll
                                )

                                AppScreen.LOGS -> LogsScreen(zero, logs, viewModel::reloadLogs)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/com.althmany.extractor.accessibility.WhatsAppAccessibilityService"
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
