package com.althmany.extractor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.althmany.extractor.data.EngineStatus
import com.althmany.extractor.data.ExtractionLog
import com.althmany.extractor.data.ExtractionMode
import com.althmany.extractor.data.SpeedProfile
import com.althmany.extractor.engine.ExtractionUiState
import com.althmany.extractor.engine.PublishUiState
import com.althmany.extractor.engine.ScanActionMode
import com.althmany.extractor.engine.ScanUiState
import com.althmany.extractor.profile.WhatsAppInstance
import kotlin.math.min

private val DashboardBg = Color(0xFF020B12)
private val DashboardPanel = Color(0xFF07131D)
private val DashboardPanel2 = Color(0xFF0A1823)
private val DashboardBorder = Color(0xFF173142)
private val DashboardGreen = Color(0xFF00F27A)
private val DashboardGreen2 = Color(0xFF00C965)
private val DashboardCyan = Color(0xFF14D9FF)
private val DashboardBlue = Color(0xFF3498FF)
private val DashboardPurple = Color(0xFF9C58FF)
private val DashboardOrange = Color(0xFFFFA42E)
private val DashboardText = Color(0xFFF4F7FA)
private val DashboardMuted = Color(0xFFA7B5C4)
private val DashboardDanger = Color(0xFFFF5F76)

@Composable
fun AppSideRail(
    current: AppScreen,
    engine: ExtractionUiState,
    onNavigate: (AppScreen) -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        RailItem("لوحة التحكم", Icons.Default.Home, AppScreen.HOME),
        RailItem("النشر", Icons.Default.Send, AppScreen.PUBLISH),
        RailItem("الفحص", Icons.Default.Security, AppScreen.SCAN),
        RailItem("الاستخراج", Icons.Default.CloudDownload, AppScreen.HOME),
        RailItem("الإحصائيات", Icons.Default.BarChart, AppScreen.RESULTS),
        RailItem("المجموعات", Icons.Default.Groups, AppScreen.GROUPS)
    )
    val runtimeReady = engine.serviceConnected || engine.shizukuReady
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Brush.verticalGradient(listOf(Color(0xFF03101A), Color(0xFF020A10))))
            .border(1.dp, DashboardBorder)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items.forEach { item ->
            val selected = if (item.label == "لوحة التحكم") current == AppScreen.HOME
            else item.screen != AppScreen.HOME && current == item.screen
            RailButton(item.label, item.icon, selected) { onNavigate(item.screen) }
            if (item != items.last()) HorizontalDivider(color = DashboardBorder.copy(alpha = 0.55f), modifier = Modifier.padding(horizontal = 6.dp))
        }
        RailButton("الإعدادات", Icons.Default.Settings, false, onSettings)
        HorizontalDivider(color = DashboardBorder.copy(alpha = 0.55f), modifier = Modifier.padding(horizontal = 6.dp))
        RailButton("المساعدة", Icons.Default.Help, false, onHelp)
        Spacer(Modifier.weight(1f))
        Surface(
            color = DashboardPanel.copy(alpha = 0.98f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DashboardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("الحالة العامة", color = DashboardMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (runtimeReady) "تشغيل" else "غير جاهز", color = if (runtimeReady) DashboardGreen else DashboardDanger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Icon(if (runtimeReady) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = if (runtimeReady) DashboardGreen else DashboardDanger, modifier = Modifier.size(14.dp))
                }
                MiniStatus("Shizuku", engine.shizukuReady)
                MiniStatus("Accessibility", engine.serviceConnected)
                MiniStatus("الأداء", runtimeReady)
            }
        }
    }
}

private data class RailItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val screen: AppScreen)

@Composable
private fun RailButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick),
        color = if (selected) DashboardGreen.copy(alpha = 0.13f) else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(1.dp, DashboardGreen.copy(alpha = 0.8f)) else null
    ) {
        Column(Modifier.padding(vertical = 11.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (selected) DashboardGreen else Color(0xFFCED7E0), modifier = Modifier.size(25.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, color = if (selected) DashboardGreen else Color(0xFFCED7E0), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MiniStatus(label: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(label, color = DashboardMuted, fontSize = 8.sp, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (good) DashboardGreen else DashboardDanger))
    }
}

@Composable
fun ExactDashboardScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    scan: ScanUiState,
    publish: PublishUiState,
    accessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestShizuku: () -> Unit,
    onProbeShizuku: () -> Unit,
    onOpenWhatsApp: () -> Unit,
    onSettings: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onGroups: () -> Unit,
    onResults: () -> Unit,
    onScan: () -> Unit,
    onAutoJoin: () -> Unit,
    onPublish: () -> Unit,
    onMode: (ExtractionMode) -> Unit,
    onSpeed: (SpeedProfile) -> Unit,
    onMaxRounds: (Int) -> Unit,
    onRetries: (Int) -> Unit,
    onDelayMs: (Long) -> Unit,
    onTargetWhatsApp: (String) -> Unit,
    onLogs: () -> Unit,
    onExportData: () -> Unit,
    onOpenFile: () -> Unit
) {
    val running = engine.status in setOf(
        EngineStatus.PREPARING, EngineStatus.OPENING_WHATSAPP, EngineStatus.SYNCING_GROUPS,
        EngineStatus.SEARCHING_GROUP, EngineStatus.OPENING_GROUP, EngineStatus.VERIFYING_GROUP,
        EngineStatus.EXTRACTING, EngineStatus.LINKS_TAB, EngineStatus.VERIFYING_END,
        EngineStatus.RECOVERING, EngineStatus.PROFILE_MISMATCH
    )
    val runtimeReady = (accessibilityEnabled && engine.serviceConnected) || engine.shizukuReady

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DashboardBg, Color(0xFF03121D), DashboardBg))).padding(padding),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { DashboardHeader(onSettings) }
        item {
            DashboardSection("الخصائص السريعة") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    item { QuickFeature("نشر الرسائل", "Publisher", Icons.Default.Send, DashboardOrange, onPublish) }
                    item { QuickFeature("استخراج الروابط", "Extractor", Icons.Default.Download, DashboardCyan, onGroups) }
                    item { QuickFeature("فحص الروابط", "Scan Links", Icons.Default.Search, DashboardBlue, onScan) }
                    item { QuickFeature("انضمام ذكي", "Auto Join", Icons.Default.Groups, DashboardPurple, onAutoJoin) }
                }
            }
        }
        item {
            DashboardSection("إحصائيات سريعة") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { StatCard("تم النشر", (publish.stats.sent + publish.stats.verified).toString(), DashboardBlue) }
                    item { StatCard("تم الاستخراج", engine.stats.totalUniqueLinks.toString(), DashboardOrange) }
                    item { StatCard("تم الفحص", scan.stats.completed.toString(), DashboardBlue) }
                    item { StatCard("طلبات الانضمام", scan.stats.requestPending.toString(), DashboardGreen) }
                    item { StatCard("تم الانضمام", scan.stats.joined.toString(), DashboardGreen) }
                }
            }
        }
        item {
            DashboardSection("محرك التشغيل") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 520.dp
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        if (compact) {
                            RadarControl(running = running, paused = engine.status == EngineStatus.PAUSED, enabled = runtimeReady, onStart = onStart, onResume = onResume)
                            EngineStatusStack(engine, accessibilityEnabled, onOpenAccessibility, onRequestShizuku, onProbeShizuku, onSpeed)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Box(Modifier.weight(0.46f)) { RadarControl(running, engine.status == EngineStatus.PAUSED, runtimeReady, onStart, onResume) }
                                Box(Modifier.weight(0.54f)) { EngineStatusStack(engine, accessibilityEnabled, onOpenAccessibility, onRequestShizuku, onProbeShizuku, onSpeed) }
                            }
                        }
                        if (running || engine.status == EngineStatus.PAUSED) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                DashboardButton(if (engine.status == EngineStatus.PAUSED) "استكمال" else "إيقاف مؤقت", DashboardPanel2, DashboardText, Modifier.weight(1f)) {
                                    if (engine.status == EngineStatus.PAUSED) onResume() else onPause()
                                }
                                DashboardButton("إيقاف", DashboardDanger.copy(alpha = 0.13f), DashboardDanger, Modifier.weight(1f), onClick = onStop)
                            }
                        }
                    }
                }
            }
        }
        item {
            DashboardSection("إعدادات متقدمة") {
                AdvancedSettingsGrid(engine, running, onMode, onMaxRounds, onRetries, onDelayMs)
            }
        }
        item {
            DashboardSection("تبديل النسخ") {
                WhatsAppPackageStrip(engine.availableWhatsApp, engine.selectedWhatsAppPackage, running, onTargetWhatsApp)
            }
        }
        item {
            DashboardSection("مؤشر الأداء والتقدم") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(engine.message, color = if (running) DashboardGreen else DashboardText, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    if (engine.runGroupCount > 0) {
                        val p = (engine.currentGroupIndex.toFloat() / engine.runGroupCount.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = DashboardGreen, trackColor = DashboardBorder)
                        Text("${engine.currentGroupIndex}/${engine.runGroupCount} • الروابط الفريدة ${engine.stats.totalUniqueLinks}", color = DashboardMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DashboardGradientButton("عرض السجلات", Icons.Default.Description, listOf(Color(0xFF05C77B), Color(0xFF15C3CE)), Modifier.weight(1f), onLogs)
                DashboardGradientButton("تصدير البيانات", Icons.Default.Download, listOf(Color(0xFF0B83CD), Color(0xFF3157D6)), Modifier.weight(1f), onExportData)
                DashboardGradientButton("فتح الملف", Icons.Default.FolderOpen, listOf(Color(0xFF6530DB), Color(0xFFD12C8C)), Modifier.weight(1f), onOpenFile)
            }
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun DashboardHeader(onSettings: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(
                    Brush.linearGradient(listOf(DashboardGreen.copy(alpha = 0.18f), Color.Transparent))
                ), contentAlignment = Alignment.Center
            ) {
                Text("A", color = DashboardGreen, fontWeight = FontWeight.Black, fontSize = 34.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("AL-thmany", color = DashboardGreen, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Text("Ultimate WhatsApp Tool", color = DashboardMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        HeaderSquare(Icons.Default.WorkspacePremium, DashboardOrange, "برو") {}
        Spacer(Modifier.width(6.dp))
        HeaderSquare(Icons.Default.Settings, DashboardText, null, onSettings)
        Spacer(Modifier.width(6.dp))
        HeaderSquare(Icons.Default.Notifications, DashboardText, null) {}
    }
    }
}

@Composable
private fun HeaderSquare(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(44.dp).clickable(onClick = onClick),
        color = DashboardPanel,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, DashboardBorder)
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
            if (label != null) Text(label, color = DashboardText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DashboardSection(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardPanel.copy(alpha = 0.94f)),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, DashboardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = DashboardText, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            content()
        }
    }
}

@Composable
private fun QuickFeature(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(145.dp).height(132.dp).clickable(onClick = onClick),
        color = DashboardPanel2,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.62f))
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, color = DashboardText, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
            Text(subtitle, color = DashboardMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color) {
    Surface(
        modifier = Modifier.width(118.dp).height(104.dp),
        color = DashboardPanel2,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, DashboardBorder)
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, color = DashboardMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(value, color = DashboardText, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text("●", color = accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RadarControl(running: Boolean, paused: Boolean, enabled: Boolean, onStart: () -> Unit, onResume: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
            RadarGraphic(Modifier.size(200.dp))
            Surface(
                modifier = Modifier.size(72.dp).clickable(enabled = enabled) { if (paused) onResume() else onStart() },
                color = if (enabled) DashboardGreen.copy(alpha = 0.18f) else DashboardBorder,
                shape = CircleShape,
                border = BorderStroke(1.dp, if (enabled) DashboardGreen else DashboardMuted)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (enabled) DashboardGreen else DashboardMuted, modifier = Modifier.size(42.dp))
                }
            }
        }
        DashboardButton(
            text = when {
                paused -> "استكمال التشغيل"
                running -> "المحرك يعمل"
                else -> "بدء التشغيل الذكي"
            },
            background = if (enabled) DashboardGreen.copy(alpha = 0.18f) else DashboardBorder.copy(alpha = 0.4f),
            content = if (enabled) DashboardGreen else DashboardMuted,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && !running,
            onClick = { if (paused) onResume() else onStart() }
        )
    }
}

@Composable
private fun RadarGraphic(modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2.25f
        for (i in 1..4) {
            drawCircle(DashboardGreen.copy(alpha = 0.35f - i * 0.035f), radius * i / 4f, c, style = Stroke(width = 1.2f))
        }
        drawLine(DashboardGreen.copy(alpha = 0.42f), Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1f)
        drawLine(DashboardGreen.copy(alpha = 0.42f), Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1f)
        drawArc(DashboardGreen.copy(alpha = 0.8f), -35f, 42f, false, topLeft = Offset(c.x-radius, c.y-radius), size = androidx.compose.ui.geometry.Size(radius*2, radius*2), style = Stroke(width = 3f, cap = StrokeCap.Round))
        listOf(0.22f, 0.47f, 0.73f, 0.91f).forEachIndexed { idx, f ->
            val x = c.x - radius + (radius * 2 * f)
            val direction = if (idx % 2 == 0) -1f else 1f
            val y = c.y + direction * radius * (0.28f + idx.toFloat() * 0.09f)
            drawCircle(DashboardGreen, 2.5f, Offset(x, y))
        }
    }
}

@Composable
private fun EngineStatusStack(
    engine: ExtractionUiState,
    accessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestShizuku: () -> Unit,
    onProbeShizuku: () -> Unit,
    onSpeed: (SpeedProfile) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        EngineLine("Shizuku", if (engine.shizukuReady) "متصل" else "غير متصل", Icons.Default.Link, engine.shizukuReady) {
            if (engine.shizukuReady) onProbeShizuku() else onRequestShizuku()
        }
        EngineLine("Accessibility", if (engine.serviceConnected) "نشط" else if (accessibilityEnabled) "بانتظار واتساب" else "غير مفعل", Icons.Default.Security, engine.serviceConnected, onOpenAccessibility)
        EngineLine("وضع التشغيل", speedModeLabel(engine.speed), Icons.Default.RocketLaunch, true) { onSpeed(nextSpeed(engine.speed)) }
        EngineLine("سرعة التنفيذ", speedTimingLabel(engine.speed), Icons.Default.Speed, true) { onSpeed(nextSpeed(engine.speed)) }
        EngineLine("الدقة الذكية", if (engine.mode == ExtractionMode.SMART || engine.mode == ExtractionMode.DEEP) "عالية جداً" else "عالية", Icons.Default.Psychology, true) {}
    }
}

@Composable
private fun EngineLine(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, good: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (good) DashboardGreen.copy(alpha = 0.055f) else DashboardPanel2,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (good) DashboardGreen.copy(alpha = 0.32f) else DashboardBorder)
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (good) DashboardGreen else DashboardMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = DashboardMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(value, color = if (good) DashboardGreen else DashboardDanger, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            Spacer(Modifier.width(5.dp))
            Icon(if (good) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = if (good) DashboardGreen else DashboardDanger, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun AdvancedSettingsGrid(
    engine: ExtractionUiState,
    running: Boolean,
    onMode: (ExtractionMode) -> Unit,
    onMaxRounds: (Int) -> Unit,
    onRetries: (Int) -> Unit,
    onDelayMs: (Long) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 640.dp) 4 else 2
        val cards = listOf<@Composable () -> Unit>(
            { SettingTile("طريقة الاستخراج", modeShort(engine.mode), Icons.Default.Link, enabled = !running) { onMode(nextMode(engine.mode)) } },
            { SettingTile("أقصى دورات", engine.maxScrollIterations.toString(), Icons.Default.Refresh, enabled = !running) { onMaxRounds(nextRounds(engine.maxScrollIterations)) } },
            { SettingTile("تخطي المكرر", "مفعل", Icons.Default.CheckCircle, enabled = true) {} },
            { SettingTile("إعادة المحاولة", "${engine.maxSameGroupRetries} مرات", Icons.Default.Replay, enabled = !running) { onRetries(if (engine.maxSameGroupRetries >= 5) 1 else engine.maxSameGroupRetries + 1) } },
            { SettingTile("تأخير بين القروبات", if (engine.betweenItemsDelayMs == 0L) "0s" else "${engine.betweenItemsDelayMs / 1000.0}s", Icons.Default.Timer, enabled = !running) { onDelayMs(nextDelay(engine.betweenItemsDelayMs)) } },
            { SettingTile("تمرير تلقائي", "مفعل", Icons.Default.Sync, enabled = true) {} },
            { SettingTile("تخطي الأخطاء", "مفعل", Icons.Default.ErrorOutline, enabled = true) {} },
            { SettingTile("Join Communities", "مفعل", Icons.Default.Groups, enabled = true) {} }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { card -> Box(Modifier.weight(1f)) { card() } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SettingTile(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(88.dp).clickable(enabled = enabled, onClick = onClick),
        color = DashboardPanel2,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DashboardBorder)
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(title, color = DashboardMuted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.width(4.dp))
                Icon(icon, null, tint = DashboardGreen, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(value, color = if (enabled) DashboardGreen else DashboardMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun WhatsAppPackageStrip(instances: List<WhatsAppInstance>, selected: String?, running: Boolean, onSelect: (String) -> Unit) {
    val shown = if (instances.isNotEmpty()) instances else emptyList()
    if (shown.isEmpty()) {
        Text("لم يتم اكتشاف نسخة واتساب قابلة للتشغيل داخل هذا الملف الشخصي.", color = DashboardDanger, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown) { instance ->
            val active = selected == instance.packageName
            Surface(
                modifier = Modifier.width(180.dp).height(92.dp).clickable(enabled = !running && instance.launchable) { onSelect(instance.packageName) },
                color = if (active) DashboardGreen.copy(alpha = 0.08f) else DashboardPanel2,
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, if (active) DashboardGreen else DashboardBorder)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = DashboardGreen.copy(alpha = 0.12f), modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PhoneAndroid, null, tint = DashboardGreen) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(instance.labelAr, color = DashboardText, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(instance.packageName, color = DashboardMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (active) Text("نشط حالياً ✓", color = DashboardGreen, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardButton(text: String, background: Color, content: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(52.dp).clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) background else DashboardBorder.copy(alpha = 0.35f),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (enabled) content.copy(alpha = 0.55f) else DashboardBorder)
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, color = if (enabled) content else DashboardMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

@Composable
private fun DashboardGradientButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, colors: List<Color>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.height(60.dp).clip(RoundedCornerShape(15.dp)).background(Brush.horizontalGradient(colors)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(text, color = DashboardText, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.width(6.dp))
            Icon(icon, null, tint = DashboardText, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun LogsScreen(padding: PaddingValues, logs: List<ExtractionLog>, onRefresh: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DashboardBg, Color(0xFF03121D), DashboardBg))).padding(padding).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.clickable(onClick = onRefresh), color = DashboardPanel2, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, DashboardBorder)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, null, tint = DashboardGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("تحديث", color = DashboardGreen)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("سجلات التشغيل", color = DashboardText, fontWeight = FontWeight.Black, fontSize = 22.sp)
            }
        }
        if (logs.isEmpty()) item { Text("لا توجد سجلات بعد.", color = DashboardMuted, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center) }
        items(logs) { log ->
            Surface(color = DashboardPanel, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, DashboardBorder), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.End) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(log.level, color = when (log.level.uppercase()) { "ERROR" -> DashboardDanger; "WARN" -> DashboardOrange; else -> DashboardGreen }, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Spacer(Modifier.weight(1f))
                        Text(log.code, color = DashboardCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    log.groupName?.let { Text(it, color = DashboardText, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                    Text(log.message, color = DashboardMuted, fontSize = 10.sp, textAlign = TextAlign.End)
                }
            }
        }
    }
}

private fun nextSpeed(value: SpeedProfile): SpeedProfile = when (value) {
    SpeedProfile.HYPER -> SpeedProfile.ADAPTIVE
    SpeedProfile.ADAPTIVE -> SpeedProfile.SMART
    SpeedProfile.SMART -> SpeedProfile.BALANCED
    SpeedProfile.BALANCED -> SpeedProfile.SAFE
    SpeedProfile.SAFE -> SpeedProfile.HYPER
}
private fun speedModeLabel(value: SpeedProfile) = when (value) {
    SpeedProfile.HYPER -> "Hyper"
    SpeedProfile.ADAPTIVE -> "Turbo"
    SpeedProfile.SMART -> "Smart"
    SpeedProfile.BALANCED -> "Balanced"
    SpeedProfile.SAFE -> "Safe"
}
private fun speedTimingLabel(value: SpeedProfile) = when (value) {
    SpeedProfile.HYPER -> "150ms"
    SpeedProfile.ADAPTIVE -> "205ms"
    SpeedProfile.SMART -> "285ms"
    SpeedProfile.BALANCED -> "450ms"
    SpeedProfile.SAFE -> "640ms"
}
private fun nextMode(value: ExtractionMode): ExtractionMode = when (value) {
    ExtractionMode.DEEP -> ExtractionMode.SMART
    ExtractionMode.SMART -> ExtractionMode.NEW_ONLY
    ExtractionMode.NEW_ONLY -> ExtractionMode.ALL_CHATS
    ExtractionMode.ALL_CHATS -> ExtractionMode.LINKS_TAB
    ExtractionMode.LINKS_TAB -> ExtractionMode.DEEP
}
private fun modeShort(value: ExtractionMode) = when (value) {
    ExtractionMode.DEEP -> "Deep Scan"
    ExtractionMode.SMART -> "Smart"
    ExtractionMode.NEW_ONLY -> "New Only"
    ExtractionMode.ALL_CHATS -> "All Chats"
    ExtractionMode.LINKS_TAB -> "Links Tab"
}
private fun nextRounds(value: Int): Int = when {
    value < 1_000 -> 1_000
    value < 2_000 -> 2_000
    value < 4_000 -> 4_000
    value < 10_000 -> 10_000
    else -> 1_000
}
private fun nextDelay(value: Long): Long = when (value) {
    0L -> 250L
    250L -> 500L
    500L -> 1_000L
    1_000L -> 2_000L
    else -> 0L
}
