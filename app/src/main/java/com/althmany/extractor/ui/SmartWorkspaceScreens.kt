package com.althmany.extractor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.althmany.extractor.data.*
import com.althmany.extractor.engine.*
import com.althmany.extractor.export.ExportFormat

private val WsBg = Color(0xFF020B13)
private val WsBg2 = Color(0xFF061522)
private val WsPanel = Color(0xFF081722)
private val WsPanel2 = Color(0xFF0C1D2A)
private val WsLine = Color(0xFF183448)
private val WsGreen = Color(0xFF00E696)
private val WsGreen2 = Color(0xFF00B977)
private val WsCyan = Color(0xFF3AD8FF)
private val WsBlue = Color(0xFF389BFF)
private val WsPurple = Color(0xFF8B65FF)
private val WsOrange = Color(0xFFFFB347)
private val WsDanger = Color(0xFFFF536F)
private val WsText = Color(0xFFF2F7FA)
private val WsMuted = Color(0xFF91A3B2)

private fun workspaceBrush() = Brush.verticalGradient(listOf(WsBg, WsBg2, WsBg))

@Composable
fun WorkspaceBottomBar(current: AppScreen, onNavigate: (AppScreen) -> Unit) {
    val center = when (current) {
        AppScreen.EXTRACT -> Triple(AppScreen.EXTRACT, "الاستخراج", Icons.Default.Download)
        AppScreen.SCAN -> Triple(AppScreen.SCAN, "الفحص", Icons.Default.Shield)
        AppScreen.PUBLISH -> Triple(AppScreen.PUBLISH, "النشر", Icons.Default.Send)
        else -> Triple(AppScreen.HOME, "الرئيسية", Icons.Default.Home)
    }
    val items = listOf(
        Triple(AppScreen.SETTINGS, "الإعدادات", Icons.Default.Settings),
        Triple(AppScreen.LOGS, "النشاط", Icons.Default.Timeline),
        center,
        Triple(AppScreen.RESULTS, "الإحصائيات", Icons.Default.PieChart),
        Triple(AppScreen.GROUPS, "المزيد", Icons.Default.MoreHoriz)
    )
    NavigationBar(
        containerColor = Color(0xFF061520),
        tonalElevation = 0.dp,
        modifier = Modifier.height(62.dp).border(1.dp, WsLine, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
    ) {
        items.forEach { (screen, label, icon) ->
            val selected = current == screen
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screen) },
                icon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
                label = { Text(label, fontSize = 9.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WsGreen,
                    selectedTextColor = WsGreen,
                    indicatorColor = WsGreen.copy(alpha = 0.12f),
                    unselectedIconColor = WsMuted,
                    unselectedTextColor = WsMuted
                )
            )
        }
    }
}

@Composable
private fun WsHeader(title: String, subtitle: String, engine: ExtractionUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("AL-thmany", color = WsGreen, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = WsMuted, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, tint = WsText, modifier = Modifier.size(19.dp))
                Icon(Icons.Default.Shield, null, tint = WsText, modifier = Modifier.size(19.dp))
                Icon(Icons.Default.MoreVert, null, tint = WsText, modifier = Modifier.size(19.dp))
            }
        }
        WsStatusStrip(engine)
        if (title.isNotBlank()) Text(title, color = WsText, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WsStatusStrip(engine: ExtractionUiState) {
    WsCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniState("الحالة", if (engine.status == EngineStatus.ERROR) "خطأ" else "نشطة", engine.status != EngineStatus.ERROR)
            MiniState("${engine.profileInfo.labelAr}", engine.selectedWhatsAppPackage?.substringAfterLast('.')?.uppercase() ?: "—", engine.selectedWhatsAppPackage != null)
            MiniState("Shizuku", if (engine.shizukuReady) "متصل" else "غير متصل", engine.shizukuReady)
            MiniState("السرعة", engine.speed.labelAr.substringBefore(' '), true)
            MiniState("المحاولات", engine.maxSameGroupRetries.toString(), true)
        }
    }
}

@Composable
private fun MiniState(label: String, value: String, good: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (good) WsGreen else WsDanger))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = if (good) WsGreen else WsMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, color = WsMuted, fontSize = 7.sp, maxLines = 1)
        }
    }
}

@Composable
private fun WsCard(modifier: Modifier = Modifier, accent: Color = WsLine, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = WsPanel.copy(alpha = 0.97f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.85f)),
        tonalElevation = 0.dp
    ) { Column(content = content) }
}

@Composable
private fun WsSectionTitle(title: String, icon: ImageVector? = null, tint: Color = WsGreen) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = WsText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (icon != null) { Spacer(Modifier.width(6.dp)); Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp)) }
    }
}

@Composable
private fun WsFeatureCard(title: String, subtitle: String, icon: ImageVector, tint: Color, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(128.dp).height(142.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) tint.copy(alpha = 0.12f) else WsPanel,
        border = BorderStroke(1.dp, if (selected) tint else tint.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = WsText, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = WsMuted, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun WsChoice(label: String, selected: Boolean, tint: Color = WsGreen, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 42.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) tint.copy(alpha = 0.13f) else WsPanel2,
        border = BorderStroke(1.dp, if (selected) tint else WsLine)
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) tint else WsText, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WsStat(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    WsCard(modifier, accent = tint.copy(alpha = 0.45f)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = WsMuted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 2)
            Text(value, color = tint, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun WsGlobalControls(
    running: Boolean,
    paused: Boolean,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit
) {
    WsCard(accent = WsGreen.copy(alpha = 0.55f)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            WsSectionTitle("التحكم العام", Icons.Default.Memory)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlButton("بدء", Icons.Default.PlayArrow, WsGreen, startEnabled && !running, Modifier.weight(1f), onStart)
                ControlButton("إيقاف مؤقت", Icons.Default.Pause, WsOrange, running && !paused, Modifier.weight(1f), onPause)
                ControlButton("استئناف", Icons.Default.Refresh, WsCyan, paused, Modifier.weight(1f), onResume)
                ControlButton("إيقاف الكل", Icons.Default.Stop, WsDanger, running || paused, Modifier.weight(1f), onStopAll)
            }
        }
    }
}

@Composable
private fun ControlButton(text: String, icon: ImageVector, tint: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(48.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) tint.copy(alpha = 0.15f) else WsPanel2,
        border = BorderStroke(1.dp, if (enabled) tint else WsLine)
    ) {
        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (enabled) tint else WsMuted, modifier = Modifier.size(17.dp))
            Text(text, color = if (enabled) WsText else WsMuted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

private fun extractionRunning(engine: ExtractionUiState): Boolean = engine.status in setOf(
    EngineStatus.PREPARING, EngineStatus.SYNCING_GROUPS, EngineStatus.OPENING_WHATSAPP, EngineStatus.SEARCHING_GROUP,
    EngineStatus.OPENING_GROUP, EngineStatus.VERIFYING_GROUP, EngineStatus.EXTRACTING, EngineStatus.LINKS_TAB,
    EngineStatus.VERIFYING_END, EngineStatus.RECOVERING, EngineStatus.PROFILE_MISMATCH
)

@Composable
fun WorkspaceHomeScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    scan: ScanUiState,
    publish: PublishUiState,
    onExtract: () -> Unit,
    onScan: () -> Unit,
    onPublish: () -> Unit,
    onAutoJoin: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit,
    onSettings: () -> Unit
) {
    val running = extractionRunning(engine) || scan.running || publish.running
    val paused = engine.status == EngineStatus.PAUSED || scan.paused || publish.paused
    LazyColumn(
        Modifier.fillMaxSize().background(workspaceBrush()).padding(padding),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item { WsHeader("", "Smart WhatsApp Workspace", engine) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                item { WsFeatureCard("الانضمام الذكي", "للمجموعات والدعوات", Icons.Default.Groups, WsPurple, onClick = onAutoJoin) }
                item { WsFeatureCard("النشر", "نشر الرسائل والمحتوى", Icons.Default.Send, WsGreen, onClick = onPublish) }
                item { WsFeatureCard("الفحص", "فحص الروابط والتحقق", Icons.Default.Shield, WsBlue, onClick = onScan) }
                item { WsFeatureCard("الاستخراج", "استخراج البيانات من واتساب", Icons.Default.Download, WsGreen, onClick = onExtract) }
            }
        }
        item {
            WsCard(accent = WsGreen.copy(alpha = 0.6f)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = WsGreen.copy(alpha = 0.15f), border = BorderStroke(1.dp, WsGreen), modifier = Modifier.size(72.dp).clickable(onClick = onStart)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.RocketLaunch, null, tint = WsGreen, modifier = Modifier.size(38.dp)) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(if (running) "المحرك يعمل" else "إجراء سريع", color = WsText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(if (running) "${RuntimeOperationCoordinator.current()?.labelAr ?: "مهمة نشطة"} • ${engine.message}" else "ابدأ الاستخراج الذكي أو افتح إحدى الوحدات", color = WsMuted, fontSize = 10.sp, textAlign = TextAlign.End)
                    }
                }
            }
        }
        item {
            WsSectionTitle("إعدادات سريعة", Icons.Default.Settings)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WsStat("نسخة واتساب", engine.selectedWhatsAppPackage?.substringAfterLast('.') ?: "—", WsGreen, Modifier.weight(1f))
                WsStat("وضع المحرك", engine.mode.labelAr.substringBefore(' '), WsCyan, Modifier.weight(1f))
                WsStat("السرعة", engine.speed.labelAr.substringBefore(' '), WsOrange, Modifier.weight(1f))
                WsStat("المحاولات", engine.maxSameGroupRetries.toString(), WsGreen, Modifier.weight(1f))
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("حالة المحرك والبيئة", Icons.Default.Shield)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WsStat("Shizuku", if (engine.shizukuReady) "جاهز" else "—", if (engine.shizukuReady) WsGreen else WsDanger, Modifier.weight(1f))
                        WsStat("Accessibility", if (engine.serviceConnected) "متصل" else "—", if (engine.serviceConnected) WsGreen else WsDanger, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            WsSectionTitle("نظرة عامة", Icons.Default.BarChart)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { WsStat("قروبات", engine.stats.syncedGroups.toString(), WsCyan, Modifier.width(92.dp)) }
                item { WsStat("روابط", engine.stats.totalUniqueLinks.toString(), WsGreen, Modifier.width(92.dp)) }
                item { WsStat("فحص", scan.stats.completed.toString(), WsBlue, Modifier.width(92.dp)) }
                item { WsStat("انضمام", scan.stats.joined.toString(), WsGreen, Modifier.width(92.dp)) }
                item { WsStat("نشر", (publish.stats.sent + publish.stats.verified).toString(), WsPurple, Modifier.width(92.dp)) }
                item { WsStat("أخطاء", (engine.stats.failedGroups + publish.stats.failed + scan.stats.invalid).toString(), WsDanger, Modifier.width(92.dp)) }
            }
        }
        item { WsGlobalControls(running, paused, engine.selectedWhatsAppPackage != null, onStart, onPause, onResume, onStopAll) }
        item {
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, WsLine)) {
                Icon(Icons.Default.Settings, null, tint = WsGreen); Spacer(Modifier.width(6.dp)); Text("الإعدادات المشتركة", color = WsText)
            }
        }
    }
}

@Composable
fun WorkspaceExtractionScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    groups: List<TargetGroup>,
    accessibilityEnabled: Boolean,
    onTargetWhatsApp: (String) -> Unit,
    onMode: (ExtractionMode) -> Unit,
    onSpeed: (SpeedProfile) -> Unit,
    onRounds: (Int) -> Unit,
    onGroups: () -> Unit,
    onResults: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit,
    onOpenWhatsApp: () -> Unit
) {
    val running = extractionRunning(engine)
    val paused = engine.status == EngineStatus.PAUSED
    LazyColumn(Modifier.fillMaxSize().background(workspaceBrush()).padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { WsHeader("", "Extractor", engine) }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("محرك التشغيل", Icons.Default.Memory)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WsStat("Shizuku", if (engine.shizukuReady) "متصل وجاهز" else "غير جاهز", if (engine.shizukuReady) WsGreen else WsDanger, Modifier.weight(1f))
                        WsStat("Accessibility", if (accessibilityEnabled && engine.serviceConnected) "متاح ومفعل" else "غير متصل", if (accessibilityEnabled && engine.serviceConnected) WsGreen else WsDanger, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("بيئة التشغيل", Icons.Default.Inventory2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WsStat("المتغير", engine.profileInfo.labelAr, WsGreen, Modifier.weight(1f))
                        WsStat("المحرك", engine.backendRecommendation, WsCyan, Modifier.weight(1f))
                        WsStat("المعالجة", engine.speed.labelAr.substringBefore(' '), WsOrange, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("اختيار نسخة واتساب", Icons.Default.Chat)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(engine.availableWhatsApp.filter { it.launchable }, key = { it.packageName }) { instance ->
                            item {
                                WsChoice(
                                    label = "${instance.labelAr}\n${instance.packageName}",
                                    selected = engine.selectedWhatsAppPackage == instance.packageName,
                                    modifier = Modifier.width(150.dp)
                                ) { onTargetWhatsApp(instance.packageName) }
                            }
                        }
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("طريقة الاستخراج", Icons.Default.Link)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        WsChoice("Deep Scan\nبحث عميق وشامل", engine.mode == ExtractionMode.DEEP, WsGreen, Modifier.weight(1f)) { onMode(ExtractionMode.DEEP) }
                        WsChoice("Links Tab\nتبويب الروابط", engine.mode == ExtractionMode.LINKS_TAB, WsBlue, Modifier.weight(1f)) { onMode(ExtractionMode.LINKS_TAB) }
                        WsChoice("SMART\nذكي تلقائي", engine.mode == ExtractionMode.SMART, WsPurple, Modifier.weight(1f)) { onMode(ExtractionMode.SMART) }
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("سرعة الاستخراج", Icons.Default.Speed)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(SpeedProfile.ADAPTIVE, SpeedProfile.SMART, SpeedProfile.HYPER, SpeedProfile.SAFE).forEach { speed ->
                            WsChoice(speed.labelAr, engine.speed == speed, if (speed == SpeedProfile.HYPER) WsBlue else WsGreen, Modifier.weight(1f)) { onSpeed(speed) }
                        }
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("أقصى دورات التحميل", Icons.Default.CloudDownload)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(1000, 2000, 4000, 10000).forEach { value ->
                            WsChoice("$value\nدورة", engine.maxScrollIterations == value, WsGreen, Modifier.weight(1f)) { onRounds(value) }
                        }
                    }
                }
            }
        }
        item {
            WsSectionTitle("حالة الاستخراج", Icons.Default.BarChart)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WsStat("الروابط الفريدة", engine.stats.totalUniqueLinks.toString(), WsGreen, Modifier.weight(1f))
                WsStat("القروبات", groups.size.toString(), WsCyan, Modifier.weight(1f))
                WsStat("المكتمل", engine.stats.completedGroups.toString(), WsBlue, Modifier.weight(1f))
                WsStat("المشاكل", engine.stats.failedGroups.toString(), WsDanger, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WsChoice("المجموعات\nعرض المستهدفة", false, WsCyan, Modifier.weight(1f), onGroups)
                WsChoice("النتائج والتصدير\nعرض وحفظ", false, WsGreen, Modifier.weight(1f), onResults)
            }
        }
        item {
            Surface(modifier = Modifier.fillMaxWidth().height(58.dp).clickable(enabled = engine.selectedWhatsAppPackage != null, onClick = onStart), shape = RoundedCornerShape(16.dp), color = WsGreen.copy(alpha = 0.17f), border = BorderStroke(1.dp, WsGreen)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RocketLaunch, null, tint = WsGreen); Spacer(Modifier.width(8.dp)); Text("بدء الاستخراج", color = WsText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        item { WsGlobalControls(running, paused, engine.selectedWhatsAppPackage != null, onStart, onPause, onResume, onStopAll) }
        item { OutlinedButton(onClick = onOpenWhatsApp, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, WsBlue)) { Text("فتح نسخة واتساب المحددة", color = WsBlue) } }
    }
}

@Composable
fun WorkspaceScanScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    scan: ScanUiState,
    items: List<ScanRecord>,
    onTargetWhatsApp: (String) -> Unit,
    onAddLinks: (String) -> Unit,
    onImportExtraction: () -> Unit,
    onAction: (ScanActionMode) -> Unit,
    onSpeed: (ScanSpeedProfile) -> Unit,
    onAttempts: (Int) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit,
    onClear: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val running = scan.running
    val paused = scan.paused
    LazyColumn(Modifier.fillMaxSize().background(workspaceBrush()).padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { WsHeader("الفحص", "Scan", engine) }
        item {
            WsCard {
                Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WsSectionTitle("نسخة واتساب", Icons.Default.Chat)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(engine.availableWhatsApp.filter { it.launchable }, key = { it.packageName }) { instance ->
                            WsChoice("${instance.labelAr}\n${instance.packageName}", engine.selectedWhatsAppPackage == instance.packageName, WsGreen, Modifier.width(150.dp)) { onTargetWhatsApp(instance.packageName) }
                        }
                    }
                }
            }
        }
        item {
            WsCard(accent = WsBlue.copy(alpha = 0.65f)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("فحص روابط دعوات واتساب والتحقق منها", color = WsMuted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(12000) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                        placeholder = { Text("الصق روابط دعوات واتساب هنا...\nكل رابط في سطر جديد", color = WsMuted, fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = WsGreen, unfocusedBorderColor = WsLine, focusedTextColor = WsText, unfocusedTextColor = WsText),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAddLinks(text); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = WsGreen, contentColor = Color.Black)) { Icon(Icons.Default.Add, null); Text("إضافة للفحص") }
                        OutlinedButton(onClick = onImportExtraction, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, WsGreen)) { Icon(Icons.Default.Download, null, tint = WsGreen); Text("من الاستخراج", color = WsText) }
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("وضع الفحص", Icons.Default.Shield)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ScanActionMode.entries.forEach { mode -> WsChoice(mode.labelAr, scan.actionMode == mode, if (mode == ScanActionMode.SCAN_AND_JOIN) WsPurple else WsGreen, Modifier.weight(1f)) { onAction(mode) } }
                    }
                    WsSectionTitle("سرعة الفحص", Icons.Default.Speed)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ScanSpeedProfile.entries.forEach { speed -> WsChoice(speed.labelAr, scan.speed == speed, WsGreen, Modifier.weight(1f)) { onSpeed(speed) } }
                    }
                    WsSectionTitle("المحاولات", Icons.Default.Refresh)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        (1..5).forEach { n -> WsChoice(n.toString(), scan.maxAttempts == n, WsGreen, Modifier.weight(1f)) { onAttempts(n) } }
                    }
                }
            }
        }
        item {
            WsSectionTitle("إحصائيات النتائج", Icons.Default.BarChart)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { WsStat("الكل", scan.stats.total.toString(), WsCyan, Modifier.width(82.dp)) }
                item { WsStat("مباشر", scan.stats.direct.toString(), WsGreen, Modifier.width(82.dp)) }
                item { WsStat("موافقة", scan.stats.approval.toString(), WsOrange, Modifier.width(82.dp)) }
                item { WsStat("تم الانضمام", scan.stats.joined.toString(), WsGreen, Modifier.width(82.dp)) }
                item { WsStat("طلب مرسل", scan.stats.requestPending.toString(), WsPurple, Modifier.width(82.dp)) }
                item { WsStat("عضو مسبقًا", scan.stats.alreadyMember.toString(), WsBlue, Modifier.width(82.dp)) }
                item { WsStat("غير صالح", scan.stats.invalid.toString(), WsDanger, Modifier.width(82.dp)) }
            }
        }
        item {
            Surface(modifier = Modifier.fillMaxWidth().height(56.dp).clickable(enabled = items.isNotEmpty() && engine.selectedWhatsAppPackage != null, onClick = onStart), shape = RoundedCornerShape(16.dp), color = WsGreen.copy(alpha = 0.17f), border = BorderStroke(1.dp, WsGreen)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.RocketLaunch, null, tint = WsGreen); Spacer(Modifier.width(8.dp)); Text(if (scan.actionMode == ScanActionMode.SCAN_AND_JOIN) "بدء الفحص + الانضمام" else "بدء الفحص", color = WsText, fontWeight = FontWeight.Bold) }
            }
        }
        item { WsGlobalControls(running, paused, items.isNotEmpty() && engine.selectedWhatsAppPackage != null, onStart, onPause, onResume, onStopAll) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportFormat.entries.forEach { f -> WsChoice(f.name, false, WsGreen, Modifier.weight(1f)) { onExport(f) } }
            }
        }
        item { OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, WsDanger)) { Icon(Icons.Default.Delete, null, tint = WsDanger); Text("مسح نتائج الفحص", color = WsDanger) } }
    }
}

@Composable
fun WorkspacePublishScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    publish: PublishUiState,
    groups: List<TargetGroup>,
    onTargetWhatsApp: (String) -> Unit,
    onGroups: () -> Unit,
    onDraft: (String) -> Unit,
    onContentMode: (PublishContentMode) -> Unit,
    onPickAttachment: (PublishContentMode) -> Unit,
    onClearAttachment: () -> Unit,
    onSpeed: (PublishSpeedProfile) -> Unit,
    onNavigation: (PublishNavigationMode) -> Unit,
    onAttempts: (Int) -> Unit,
    onStart: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    val running = publish.running
    val paused = publish.paused
    val publishable = groups.count { it.active && it.publishable && !it.communityParent }
    LazyColumn(Modifier.fillMaxSize().background(workspaceBrush()).padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { WsHeader("", "Publish", engine) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WsCard(Modifier.weight(1f), accent = WsGreen.copy(alpha = 0.6f)) { Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.End) { WsSectionTitle("محرك النشر", Icons.Default.Bolt); Text("${publish.speed.labelAr} • ${publish.navigationMode.labelAr}", color = WsGreen, fontSize = 10.sp) } }
                WsCard(Modifier.weight(1f)) { Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.End) { WsSectionTitle("اختيار القروبات", Icons.Default.Groups); Text("$publishable قروب قابل للنشر", color = WsMuted, fontSize = 10.sp); TextButton(onClick = onGroups) { Text("اختيار") } } }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WsSectionTitle("اختيار نسخة واتساب", Icons.Default.Chat)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(engine.availableWhatsApp.filter { it.launchable }, key = { it.packageName }) { instance ->
                            WsChoice("${instance.labelAr}\n${instance.packageName}", engine.selectedWhatsAppPackage == instance.packageName, WsGreen, Modifier.width(150.dp)) { onTargetWhatsApp(instance.packageName) }
                        }
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("نوع النشر", Icons.Default.Article)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(PublishContentMode.entries) { mode ->
                            WsChoice(mode.labelAr, publish.contentMode == mode, if (mode.attachmentRequired) WsPurple else WsGreen, Modifier.width(118.dp)) { onContentMode(mode) }
                        }
                    }
                    if (publish.contentMode.attachmentRequired) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onPickAttachment(publish.contentMode) }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, WsPurple)) {
                                Icon(Icons.Default.AttachFile, null, tint = WsPurple); Spacer(Modifier.width(5.dp)); Text(if (publish.attachmentUri.isNullOrBlank()) "اختيار المرفق" else "تغيير المرفق", color = WsText, fontSize = 9.sp)
                            }
                            if (!publish.attachmentUri.isNullOrBlank()) {
                                OutlinedButton(onClick = onClearAttachment, border = BorderStroke(1.dp, WsDanger)) { Icon(Icons.Default.Close, null, tint = WsDanger) }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = publish.messageText,
                        onValueChange = onDraft,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                        placeholder = { Text("اكتب محتوى الرسالة هنا...", color = WsMuted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = WsGreen, unfocusedBorderColor = WsLine, focusedTextColor = WsText, unfocusedTextColor = WsText),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Text("${publish.messageText.length} / 16000", color = WsMuted, fontSize = 8.sp)
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("سرعة النشر", Icons.Default.Speed)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { PublishSpeedProfile.entries.forEach { s -> WsChoice(s.labelAr, publish.speed == s, WsGreen, Modifier.weight(1f)) { onSpeed(s) } } }
                    WsSectionTitle("طريقة الوصول", Icons.Default.Route)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { PublishNavigationMode.entries.forEach { n -> WsChoice(n.labelAr, publish.navigationMode == n, if (n == PublishNavigationMode.SEMI_HIDDEN) WsPurple else WsGreen, Modifier.weight(1f)) { onNavigation(n) } } }
                    WsSectionTitle("محاولات فتح القروب", Icons.Default.Refresh)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..3).forEach { n -> WsChoice(n.toString(), publish.maxAttempts == n, WsGreen, Modifier.weight(1f)) { onAttempts(n) } } }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WsStat("المحدد", publish.stats.total.toString(), WsCyan, Modifier.weight(1f))
                WsStat("ناجح", (publish.stats.sent + publish.stats.verified).toString(), WsGreen, Modifier.weight(1f))
                WsStat("غير محسوم", publish.stats.uncertain.toString(), WsOrange, Modifier.weight(1f))
                WsStat("فشل", publish.stats.failed.toString(), WsDanger, Modifier.weight(1f))
            }
        }
        item {
            WsCard(accent = if (publish.status == PublishEngineStatus.ERROR) WsDanger else WsGreen) {
                Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.End) {
                    WsSectionTitle("حالة النشر", Icons.Default.CheckCircle, if (publish.status == PublishEngineStatus.ERROR) WsDanger else WsGreen)
                    Text(publish.info, color = if (publish.status == PublishEngineStatus.ERROR) WsDanger else WsGreen, fontSize = 11.sp)
                }
            }
        }
        item {
            Surface(modifier = Modifier.fillMaxWidth().height(58.dp).clickable(enabled = publish.messageText.isNotBlank() && engine.selectedWhatsAppPackage != null, onClick = { onStart(publish.messageText) }), shape = RoundedCornerShape(16.dp), color = WsGreen.copy(alpha = 0.18f), border = BorderStroke(1.dp, WsGreen)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Send, null, tint = WsGreen); Spacer(Modifier.width(8.dp)); Text("معاينة وبدء النشر", color = WsText, fontWeight = FontWeight.Bold) }
            }
        }
        item { WsGlobalControls(running, paused, publish.messageText.isNotBlank() && engine.selectedWhatsAppPackage != null, { onStart(publish.messageText) }, onPause, onResume, onStopAll) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { ExportFormat.entries.forEach { f -> WsChoice(f.name, false, WsGreen, Modifier.weight(1f)) { onExport(f) } } } }
    }
}

@Composable
fun WorkspaceGroupsScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    groups: List<TargetGroup>,
    syncing: Boolean,
    syncFound: Int,
    onSync: () -> Unit,
    onSelected: (Long, Boolean) -> Unit,
    onPreset: (GroupSelectionPreset) -> Unit,
    onStartExtraction: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) groups else groups.filter { it.name.contains(query, true) }
    val selectedCount = groups.count { it.selected }
    val running = extractionRunning(engine)
    val paused = engine.status == EngineStatus.PAUSED
    LazyColumn(Modifier.fillMaxSize().background(workspaceBrush()).padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { WsHeader("المجموعات", "Sync & Groups", engine) }
        item {
            WsCard(accent = WsGreen.copy(alpha = 0.55f)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("القروبات الموجودة في واتساب", Icons.Default.Sync)
                    Text("المزامنة تقرأ الدردشات والقروبات المؤرشفة، تحفظ GroupRecord ومسار الوصول، ولا تعتمد على Search أثناء الاستخراج.", color = WsMuted, fontSize = 9.sp, textAlign = TextAlign.End)
                    Button(onClick = onSync, enabled = !syncing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WsGreen, contentColor = Color.Black)) { Icon(Icons.Default.Sync, null); Text(if (syncing) "جارٍ المزامنة… $syncFound" else "مزامنة القروبات") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WsStat("المزامنة", groups.size.toString(), WsCyan, Modifier.weight(1f))
                WsStat("المحدد", selectedCount.toString(), WsGreen, Modifier.weight(1f))
                WsStat("غير مقروء", groups.count { it.unreadCount > 0 }.toString(), WsOrange, Modifier.weight(1f))
                WsStat("قابل للنشر", groups.count { it.publishable && !it.communityParent }.toString(), WsGreen, Modifier.weight(1f))
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("بحث باسم القروب", color = WsMuted) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = WsGreen, unfocusedBorderColor = WsLine, focusedTextColor = WsText, unfocusedTextColor = WsText), shape = RoundedCornerShape(14.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(GroupSelectionPreset.entries) { p ->
                    WsChoice(p.labelAr, false, WsGreen, Modifier.width(110.dp)) { onPreset(p) }
                }
            }
        }
        items(filtered, key = { it.id }) { group ->
            WsCard(accent = if (group.selected) WsGreen else WsLine) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = group.selected, onCheckedChange = { onSelected(group.id, it) }, colors = CheckboxDefaults.colors(checkedColor = WsGreen))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(group.name, color = WsText, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${if (group.verifiedGroup) "قروب مؤكد" else "غير مؤكد"} • ${if (group.publishable) "قابل للنشر" else "قراءة فقط"} • ${group.lastSuccessfulOpenMethod.labelAr}", color = if (group.verifiedGroup) WsGreen else WsMuted, fontSize = 8.sp, maxLines = 2)
                    }
                }
            }
        }
        item { WsGlobalControls(running, paused, groups.isNotEmpty(), onStartExtraction, onPause, onResume, onStopAll) }
    }
}

@Composable
fun WorkspaceSettingsScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    scan: ScanUiState,
    publish: PublishUiState,
    onTargetWhatsApp: (String) -> Unit,
    onSpeed: (SpeedProfile) -> Unit,
    onRetries: (Int) -> Unit,
    onDelayMs: (Long) -> Unit,
    onScanAttempts: (Int) -> Unit,
    onPublishAttempts: (Int) -> Unit,
    onPublishNavigation: (PublishNavigationMode) -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestShizuku: () -> Unit,
    onProbeShizuku: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit
) {
    val running = extractionRunning(engine) || scan.running || publish.running
    val paused = engine.status == EngineStatus.PAUSED || scan.paused || publish.paused
    LazyColumn(Modifier.fillMaxSize().background(workspaceBrush()).padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { WsHeader("", "Shared Settings", engine) }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("بيئة التشغيل", Icons.Default.Computer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WsChoice("الملف الشخصي\n${engine.profileInfo.labelAr}", true, WsGreen, Modifier.weight(1f)) { }
                        WsChoice("Shizuku\n${if (engine.shizukuReady) "جاهز" else "إعداد"}", engine.shizukuReady, WsGreen, Modifier.weight(1f), if (engine.shizukuReady) onProbeShizuku else onRequestShizuku)
                        WsChoice("Accessibility\n${if (engine.serviceConnected) "متصل" else "إعداد"}", engine.serviceConnected, WsCyan, Modifier.weight(1f), onOpenAccessibility)
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("اختيار نسخة واتساب", Icons.Default.Chat)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(engine.availableWhatsApp.filter { it.launchable }, key = { it.packageName }) { instance ->
                            WsChoice("${instance.labelAr}\n${instance.packageName}", engine.selectedWhatsAppPackage == instance.packageName, WsGreen, Modifier.width(160.dp)) { onTargetWhatsApp(instance.packageName) }
                        }
                    }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("السرعة والأداء", Icons.Default.Speed)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(SpeedProfile.ADAPTIVE, SpeedProfile.SMART, SpeedProfile.HYPER, SpeedProfile.SAFE).forEach { s -> WsChoice(s.labelAr, engine.speed == s, WsGreen, Modifier.weight(1f)) { onSpeed(s) } } }
                    WsSectionTitle("إعادة المحاولة", Icons.Default.Refresh)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { n -> WsChoice(n.toString(), engine.maxSameGroupRetries == n, WsGreen, Modifier.weight(1f)) { onRetries(n) } } }
                    WsSectionTitle("الفاصل بين القروبات", Icons.Default.Timer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0L, 500L, 1000L, 2500L).forEach { ms -> WsChoice(if (ms == 0L) "0s" else "${ms / 1000.0}s", engine.betweenItemsDelayMs == ms, WsGreen, Modifier.weight(1f)) { onDelayMs(ms) } } }
                }
            }
        }
        item {
            WsCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsSectionTitle("الذكاء والتنقل", Icons.Default.Psychology)
                    ReadOnlySmartRow("Event-First", "الأولوية لأحداث Accessibility", true)
                    ReadOnlySmartRow("الرجوع التلقائي", "Recovery بعد الشاشة غير المتوقعة", true)
                    ReadOnlySmartRow("إغلاق النوافذ", "Close / Back / Retry", true)
                    ReadOnlySmartRow("منع التكرار", "Deduplication أثناء الاستخراج", true)
                    WsSectionTitle("النشر", Icons.Default.Send)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { PublishNavigationMode.entries.forEach { n -> WsChoice(n.labelAr, publish.navigationMode == n, WsPurple, Modifier.weight(1f)) { onPublishNavigation(n) } } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..3).forEach { n -> WsChoice("نشر $n", publish.maxAttempts == n, WsGreen, Modifier.weight(1f)) { onPublishAttempts(n) } } }
                    WsSectionTitle("الفحص", Icons.Default.Shield)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { n -> WsChoice("فحص $n", scan.maxAttempts == n, WsBlue, Modifier.weight(1f)) { onScanAttempts(n) } } }
                }
            }
        }
        item { WsGlobalControls(running, paused, engine.selectedWhatsAppPackage != null, onStart, onPause, onResume, onStopAll) }
    }
}

@Composable
private fun ReadOnlySmartRow(title: String, subtitle: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = null, colors = SwitchDefaults.colors(checkedThumbColor = WsGreen, checkedTrackColor = WsGreen.copy(alpha = 0.25f)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(title, color = WsText, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
            Text(subtitle, color = WsMuted, fontSize = 8.sp)
        }
    }
}

/** Persistent compact controller shown above bottom navigation on every window. */
@Composable
fun WorkspaceGlobalMiniBar(
    operationLabel: String,
    running: Boolean,
    paused: Boolean,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAll: () -> Unit
) {
    Surface(color = Color(0xFF06131D), border = BorderStroke(1.dp, WsLine), tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(operationLabel, color = if (running || paused) WsGreen else WsMuted, fontSize = 8.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            MiniControl(Icons.Default.PlayArrow, WsGreen, startEnabled && !running && !paused, onStart)
            MiniControl(Icons.Default.Pause, WsOrange, running && !paused, onPause)
            MiniControl(Icons.Default.Refresh, WsCyan, paused, onResume)
            MiniControl(Icons.Default.Stop, WsDanger, running || paused, onStopAll)
        }
    }
}

@Composable
private fun MiniControl(icon: ImageVector, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(34.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) tint.copy(alpha = 0.14f) else WsPanel2,
        border = BorderStroke(1.dp, if (enabled) tint else WsLine)
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (enabled) tint else WsMuted, modifier = Modifier.size(17.dp)) } }
}
