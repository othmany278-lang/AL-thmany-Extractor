package com.althmany.extractor

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.export.ExportFormat
import com.althmany.extractor.export.ExportManager
import com.althmany.extractor.ui.AlThmanyTheme
import com.althmany.extractor.ui.AppScreen
import com.althmany.extractor.ui.AppViewModel
import com.althmany.extractor.ui.BottomBar
import com.althmany.extractor.ui.GroupsScreen
import com.althmany.extractor.ui.HomeScreen
import com.althmany.extractor.ui.ResultsScreen
import com.althmany.extractor.ui.ScanScreen
import com.althmany.extractor.ui.PublishScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlThmanyTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ExtractorAppUi(viewModel)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        // Re-read the apps visible to this profile after returning from Secure Folder/Work settings.
        viewModel.refreshRuntimeEnvironment()
    }

}

@Composable
private fun ExtractorAppUi(viewModel: AppViewModel) {
    val context = LocalContext.current
    val engine by viewModel.engineState.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val links by viewModel.links.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val scanItems by viewModel.scanItems.collectAsState()
    val publishState by viewModel.publishState.collectAsState()
    val publishItems by viewModel.publishItems.collectAsState()
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var pendingFormat by remember { mutableStateOf(ExportFormat.XLSX) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.refreshRuntimeEnvironment()
        viewModel.refresh()
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            runCatching {
                ExportManager.export(context.contentResolver, uri, pendingFormat, links)
            }
        }
    }

    val createScanDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            runCatching {
                ExportManager.exportScan(context.contentResolver, uri, pendingFormat, scanItems)
            }
        }
    }

    val createPublishDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            runCatching {
                ExportManager.exportPublish(context.contentResolver, uri, pendingFormat, publishItems)
            }
        }
    }

    val pickPublishAttachment = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val mime = context.contentResolver.getType(uri)
            viewModel.setPublishAttachment(uri.toString(), mime)
        }
    }

    Scaffold(
        bottomBar = {
            BottomBar(
                current = screen,
                onNavigate = {
                    screen = it
                    if (it == AppScreen.RESULTS) viewModel.reloadLinks()
                    if (it == AppScreen.GROUPS) viewModel.refresh()
                    if (it == AppScreen.PUBLISH) viewModel.reloadPublishItems()
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                AppScreen.HOME -> HomeScreen(
                    padding = padding,
                    engine = engine,
                    accessibilityEnabled = isAccessibilityServiceEnabled(context),
                    onOpenAccessibility = ExtractionController::openAccessibilitySettings,
                    onOpenWhatsApp = { ExtractionController.openWhatsApp() },
                    onStart = ExtractionController::start,
                    onPause = ExtractionController::pause,
                    onResume = ExtractionController::resume,
                    onStop = ExtractionController::stop,
                    onGroups = { screen = AppScreen.GROUPS },
                    onResults = { viewModel.reloadLinks(); screen = AppScreen.RESULTS },
                    onScan = { viewModel.reloadScanItems(); screen = AppScreen.SCAN },
                    onPublish = { viewModel.reloadPublishItems(); screen = AppScreen.PUBLISH },
                    onMode = viewModel::setMode,
                    onSpeed = viewModel::setSpeed,
                    onMaxRounds = viewModel::setMaxRounds,
                    onTargetWhatsApp = viewModel::setTargetWhatsApp
                )

                AppScreen.SCAN -> ScanScreen(
                    padding = padding,
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
                    onMaxAttempts = viewModel::setScanMaxAttempts,
                    onExport = { format ->
                        pendingFormat = format
                        createScanDocument.launch("AL-thmany-scan.${format.extension}")
                    }
                )

                AppScreen.PUBLISH -> PublishScreen(
                    padding = padding,
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
                        val types = if (mode == PublishContentMode.IMAGE_WITH_CAPTION) {
                            arrayOf("image/*")
                        } else {
                            arrayOf("text/x-vcard", "text/vcard", "text/plain", "*/*")
                        }
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
                    onExport = { format ->
                        pendingFormat = format
                        createPublishDocument.launch("AL-thmany-publish.${format.extension}")
                    }
                )

                AppScreen.GROUPS -> GroupsScreen(
                    padding = padding,
                    groups = groups,
                    onAddGroups = viewModel::addGroups,
                    onSelected = viewModel::setSelected,
                    onPreset = viewModel::applyGroupSelectionPreset,
                    onStart = {
                        screen = AppScreen.HOME
                        ExtractionController.start()
                    },
                    syncing = engine.status == com.althmany.extractor.data.EngineStatus.SYNCING_GROUPS,
                    syncFound = engine.syncFound,
                    onSync = viewModel::syncGroups
                )

                AppScreen.RESULTS -> ResultsScreen(
                    padding = padding,
                    links = links,
                    onRefresh = viewModel::reloadLinks,
                    onExport = { format ->
                        pendingFormat = format
                        createDocument.launch("AL-thmany-links.${format.extension}")
                    },
                    onClearAll = viewModel::clearAll
                )
            }
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/com.althmany.extractor.accessibility.WhatsAppAccessibilityService"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
