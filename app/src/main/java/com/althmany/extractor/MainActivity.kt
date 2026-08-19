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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.diagnostics.DiagnosticLog
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.engine.ScanActionMode
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.export.ExportFormat
import com.althmany.extractor.export.ExportManager
import com.althmany.extractor.ui.AlThmanyTheme
import com.althmany.extractor.ui.AppScreen
import com.althmany.extractor.ui.AppViewModel
import com.althmany.extractor.ui.LogsScreen
import com.althmany.extractor.ui.ResultsScreen
import com.althmany.extractor.ui.WorkspaceBottomBar
import com.althmany.extractor.ui.WorkspaceExtractionScreen
import com.althmany.extractor.ui.WorkspaceGroupsScreen
import com.althmany.extractor.ui.WorkspaceGlobalMiniBar
import com.althmany.extractor.ui.WorkspaceHomeScreen
import com.althmany.extractor.ui.WorkspacePublishScreen
import com.althmany.extractor.ui.WorkspaceScanScreen
import com.althmany.extractor.ui.WorkspaceSettingsScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.record("ACTIVITY", "MainActivity_onCreate")
        setContent {
            AlThmanyTheme { ExtractorAppUi(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.record("ACTIVITY", "MainActivity_onResume")
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

    LaunchedEffect(engine.accessibilityEnabledInSettings, engine.serviceConnected) {
        if (engine.accessibilityEnabledInSettings && !engine.serviceConnected) {
            repeat(20) {
                delay(400L)
                viewModel.refreshRuntimeEnvironment()
                if (viewModel.engineState.value.serviceConnected) return@LaunchedEffect
            }
        }
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

    val openScanFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importScanFile(uri)
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val extractionRunning = engine.status in setOf(
                com.althmany.extractor.data.EngineStatus.PREPARING,
                com.althmany.extractor.data.EngineStatus.SYNCING_GROUPS,
                com.althmany.extractor.data.EngineStatus.OPENING_WHATSAPP,
                com.althmany.extractor.data.EngineStatus.SEARCHING_GROUP,
                com.althmany.extractor.data.EngineStatus.OPENING_GROUP,
                com.althmany.extractor.data.EngineStatus.VERIFYING_GROUP,
                com.althmany.extractor.data.EngineStatus.EXTRACTING,
                com.althmany.extractor.data.EngineStatus.LINKS_TAB,
                com.althmany.extractor.data.EngineStatus.VERIFYING_END,
                com.althmany.extractor.data.EngineStatus.RECOVERING,
                com.althmany.extractor.data.EngineStatus.PROFILE_MISMATCH
            )
            val anyRunning = extractionRunning || scanState.running || publishState.running
            val anyPaused = engine.status == com.althmany.extractor.data.EngineStatus.PAUSED || scanState.paused || publishState.paused
            // Persistent mini bar = real global sequential pipeline.
            val startEnabled = engine.selectedWhatsAppPackage != null
            val operationLabel = when {
                publishState.running || publishState.paused -> "النشر • ${publishState.info}"
                scanState.running || scanState.paused -> "الفحص • ${scanState.message}"
                extractionRunning || engine.status == com.althmany.extractor.data.EngineStatus.PAUSED -> "الاستخراج • ${engine.message}"
                else -> "التحكم العام • لا توجد عملية نشطة"
            }
            androidx.compose.foundation.layout.Column {
                WorkspaceGlobalMiniBar(
                    operationLabel = operationLabel,
                    running = anyRunning,
                    paused = anyPaused,
                    startEnabled = startEnabled,
                    onStart = viewModel::startAllSmart,
                    onPause = viewModel::pauseActiveOperation,
                    onResume = viewModel::resumeActiveOperation,
                    onStopAll = viewModel::stopAllOperations
                )
                WorkspaceBottomBar(current = screen) { target ->
                    screen = target
                    when (target) {
                        AppScreen.RESULTS -> viewModel.reloadLinks()
                        AppScreen.GROUPS -> viewModel.refresh()
                        AppScreen.PUBLISH -> viewModel.reloadPublishItems()
                        AppScreen.SCAN -> viewModel.importScanLinksFromExtraction()
                        AppScreen.LOGS -> viewModel.reloadLogs()
                        else -> Unit
                    }
                }
            }
        }
    ) { scaffoldPadding ->
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val zero = PaddingValues(
                top = scaffoldPadding.calculateTopPadding(),
                bottom = scaffoldPadding.calculateBottomPadding()
            )
            when (screen) {
                AppScreen.HOME -> WorkspaceHomeScreen(
                    padding = zero,
                    engine = engine,
                    scan = scanState,
                    publish = publishState,
                    onExtract = { screen = AppScreen.EXTRACT },
                    onScan = { viewModel.importScanLinksFromExtraction(); screen = AppScreen.SCAN },
                    onPublish = { viewModel.reloadPublishItems(); screen = AppScreen.PUBLISH },
                    onAutoJoin = {
                        viewModel.setScanActionMode(ScanActionMode.SCAN_AND_JOIN)
                        viewModel.setScanRequestToJoinEnabled(true)
                        viewModel.importScanLinksFromExtraction()
                        screen = AppScreen.SCAN
                    },
                    onStart = viewModel::startExtractionSmart,
                    onPause = viewModel::pauseActiveOperation,
                    onResume = viewModel::resumeActiveOperation,
                    onStopAll = viewModel::stopAllOperations,
                    onSettings = { screen = AppScreen.SETTINGS }
                )

                AppScreen.EXTRACT -> WorkspaceExtractionScreen(
                    padding = zero,
                    engine = engine,
                    groups = groups,
                    accessibilityEnabled = isAccessibilityServiceEnabled(context),
                    onTargetWhatsApp = viewModel::setTargetWhatsApp,
                    onMode = viewModel::setMode,
                    onSpeed = viewModel::setSpeed,
                    onRounds = viewModel::setMaxRounds,
                    onGroups = { screen = AppScreen.GROUPS },
                    onResults = { viewModel.reloadLinks(); screen = AppScreen.RESULTS },
                    onStart = viewModel::startExtractionSmart,
                    onPause = viewModel::pauseActiveOperation,
                    onResume = viewModel::resumeActiveOperation,
                    onStopAll = viewModel::stopAllOperations,
                    onOpenWhatsApp = { ExtractionController.openWhatsApp() }
                )

                AppScreen.SCAN -> WorkspaceScanScreen(
                    padding = zero,
                    engine = engine,
                    scan = scanState,
                    items = scanItems,
                    onTargetWhatsApp = viewModel::setTargetWhatsApp,
                    onAddLinks = viewModel::addScanLinks,
                    onImportExtraction = viewModel::importScanLinksFromExtraction,
                    onImportFile = {
                        openScanFile.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "text/csv",
                                "text/plain",
                                "application/octet-stream"
                            )
                        )
                    },
                    onAction = { mode ->
                        viewModel.setScanActionMode(mode)
                        if (mode == ScanActionMode.SCAN_AND_JOIN) viewModel.setScanRequestToJoinEnabled(true)
                    },
                    onSpeed = viewModel::setScanSpeed,
                    onAttempts = viewModel::setScanMaxAttempts,
                    onStart = viewModel::startScanWithInput,
                    onPause = viewModel::pauseActiveOperation,
                    onResume = viewModel::resumeActiveOperation,
                    onStopAll = viewModel::stopAllOperations,
                    onClear = viewModel::clearScan,
                    onExport = { format ->
                        pendingFormat = format
                        createScanDocument.launch("AL-thmany-scan.${format.extension}")
                    }
                )

                AppScreen.PUBLISH -> WorkspacePublishScreen(
                    padding = zero,
                    engine = engine,
                    publish = publishState,
                    groups = groups,
                    onTargetWhatsApp = viewModel::setTargetWhatsApp,
                    onGroups = { screen = AppScreen.GROUPS },
                    onDraft = viewModel::setPublishDraft,
                    onContentMode = viewModel::setPublishContentMode,
                    onPickAttachment = { mode ->
                        val types = if (mode == PublishContentMode.IMAGE_WITH_CAPTION) arrayOf("image/*") else arrayOf("text/x-vcard", "text/vcard", "text/plain", "*/*")
                        pickPublishAttachment.launch(types)
                    },
                    onClearAttachment = { viewModel.setPublishAttachment(null, null) },
                    onSpeed = viewModel::setPublishSpeed,
                    onNavigation = viewModel::setPublishNavigationMode,
                    onAttempts = viewModel::setPublishMaxAttempts,
                    onStart = viewModel::startPublishSmart,
                    onPause = viewModel::pauseActiveOperation,
                    onResume = viewModel::resumeActiveOperation,
                    onStopAll = viewModel::stopAllOperations,
                    onExport = { format ->
                        pendingFormat = format
                        createPublishDocument.launch("AL-thmany-publish.${format.extension}")
                    }
                )

                AppScreen.GROUPS -> WorkspaceGroupsScreen(
                    padding = zero,
                    engine = engine,
                    groups = groups.filter { group ->
                        val selectedPackage = engine.selectedWhatsAppPackage
                        selectedPackage == null || group.whatsappPackage.isBlank() || group.whatsappPackage == selectedPackage
                    },
                    syncing = engine.status == com.althmany.extractor.data.EngineStatus.SYNCING_GROUPS,
                    syncFound = engine.syncFound,
                    onSync = viewModel::syncGroups,
                    onSelected = viewModel::setSelected,
                    onPreset = viewModel::applyGroupSelectionPreset,
                    onStartExtraction = { screen = AppScreen.EXTRACT; viewModel.startExtractionSmart() },
                    onPause = viewModel::pauseActiveOperation,
                    onResume = viewModel::resumeActiveOperation,
                    onStopAll = viewModel::stopAllOperations
                )

                AppScreen.RESULTS -> ResultsScreen(
                    padding = zero,
                    links = links,
                    onRefresh = viewModel::reloadLinks,
                    onExport = { format ->
                        pendingFormat = format
                        createDocument.launch("AL-thmany-links.${format.extension}")
                    },
                    onClearAll = viewModel::clearAll
                )

                AppScreen.LOGS -> LogsScreen(
                    padding = zero,
                    logs = logs,
                    onRefresh = viewModel::reloadLogs,
                    onShare = {
                        val runtimeLines = listOf(
                            "app.package=${context.packageName}",
                            "engine.status=${engine.status}",
                            "engine.message=${engine.message}",
                            "engine.phase=${engine.phaseDetail}",
                            "engine.targetWhatsApp=${engine.selectedWhatsAppPackage}",
                            "engine.accessibilityEnabledInSettings=${engine.accessibilityEnabledInSettings}",
                            "engine.serviceConnected=${engine.serviceConnected}",
                            "engine.profileAccessibilityConnected=${engine.profileAccessibilityConnected}",
                            "engine.shizukuReady=${engine.shizukuReady}",
                            "engine.shizukuDetail=${engine.shizukuDetail}",
                            "engine.backendRecommendation=${engine.backendRecommendation}",
                            "engine.currentGroup=${engine.currentGroup}",
                            "engine.progress=${engine.currentGroupIndex}/${engine.runGroupCount}",
                            "engine.availableWhatsApp=${engine.availableWhatsApp.joinToString { "${it.labelAr}:${it.packageName}:launchable=${it.launchable}:invite=${it.canHandleInvite}" }}",
                            "engine.stats=${engine.stats}",
                            "scan.status=${scanState.status}",
                            "scan.running=${scanState.running}",
                            "scan.paused=${scanState.paused}",
                            "scan.serviceConnected=${scanState.serviceConnected}",
                            "scan.mode=${scanState.actionMode}",
                            "scan.scope=${scanState.scope}",
                            "scan.speed=${scanState.speed}",
                            "scan.requestToJoin=${scanState.requestToJoinEnabled}",
                            "scan.progress=${scanState.currentIndex}/${scanState.total}",
                            "scan.attempt=${scanState.currentAttempt}",
                            "scan.currentUrl=${scanState.currentUrl}",
                            "scan.message=${scanState.message}",
                            "scan.stats=${scanState.stats}",
                            "publish.status=${publishState.status}",
                            "publish.running=${publishState.running}",
                            "publish.paused=${publishState.paused}",
                            "publish.progress=${publishState.currentIndex}/${publishState.total}",
                            "publish.currentGroup=${publishState.currentGroup}",
                            "publish.info=${publishState.info}",
                            "publish.runToken=${publishState.runToken}",
                            "publish.stats=${publishState.stats}"
                        )
                        DiagnosticLog.shareReport(
                            context = context,
                            runtimeLines = runtimeLines,
                            dbLogs = logs,
                            scanItems = scanItems,
                            publishItems = publishItems
                        )
                    },
                    onCopy = {
                        val runtimeLines = listOf(
                            "engine.status=${engine.status}",
                            "engine.message=${engine.message}",
                            "engine.targetWhatsApp=${engine.selectedWhatsAppPackage}",
                            "engine.accessibilityEnabledInSettings=${engine.accessibilityEnabledInSettings}",
                            "engine.serviceConnected=${engine.serviceConnected}",
                            "engine.profileAccessibilityConnected=${engine.profileAccessibilityConnected}",
                            "engine.shizukuReady=${engine.shizukuReady}",
                            "scan.status=${scanState.status}",
                            "scan.running=${scanState.running}",
                            "scan.serviceConnected=${scanState.serviceConnected}",
                            "scan.currentUrl=${scanState.currentUrl}",
                            "scan.message=${scanState.message}",
                            "publish.status=${publishState.status}",
                            "publish.running=${publishState.running}",
                            "publish.currentGroup=${publishState.currentGroup}",
                            "publish.info=${publishState.info}"
                        )
                        DiagnosticLog.copyReport(
                            context = context,
                            runtimeLines = runtimeLines,
                            dbLogs = logs,
                            scanItems = scanItems,
                            publishItems = publishItems
                        )
                    }
                )

                AppScreen.SETTINGS -> WorkspaceSettingsScreen(
                    padding = zero,
                    engine = engine,
                    scan = scanState,
                    publish = publishState,
                    onTargetWhatsApp = viewModel::setTargetWhatsApp,
                    onSpeed = viewModel::setSpeed,
                    onRetries = viewModel::setExtractionRetries,
                    onDelayMs = viewModel::setExtractionDelayMs,
                    onScanAttempts = viewModel::setScanMaxAttempts,
                    onPublishAttempts = viewModel::setPublishMaxAttempts,
                    onPublishNavigation = viewModel::setPublishNavigationMode,
                    onOpenAccessibility = ExtractionController::openAccessibilitySettings,
                    onRequestShizuku = { ExtractionController.requestShizukuPermission() },
                    onProbeShizuku = ExtractionController::probeShizuku,
                    onStart = viewModel::startAllSmart,
                    onPause = viewModel::pauseActiveOperation,
                    onResume = viewModel::resumeActiveOperation,
                    onStopAll = viewModel::stopAllOperations
                )
            }
        }
    }

}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/com.althmany.extractor.accessibility.WhatsAppAccessibilityService"
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
