package com.althmany.extractor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.althmany.extractor.data.EngineStatus
import com.althmany.extractor.data.ExtractionMode
import com.althmany.extractor.data.LinkRecord
import com.althmany.extractor.data.LinkCategory
import com.althmany.extractor.data.GroupSelectionPreset
import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.data.PublishItem
import com.althmany.extractor.data.PublishStatus
import com.althmany.extractor.data.ScanRecord
import com.althmany.extractor.data.ScanStatus
import com.althmany.extractor.data.SpeedProfile
import com.althmany.extractor.data.TargetGroup
import com.althmany.extractor.engine.ExtractionUiState
import com.althmany.extractor.engine.PublishSpeedProfile
import com.althmany.extractor.engine.PublishUiState
import com.althmany.extractor.engine.ScanActionMode
import com.althmany.extractor.engine.ScanScope
import com.althmany.extractor.engine.ScanSpeedProfile
import com.althmany.extractor.engine.ScanUiState
import com.althmany.extractor.export.ExportFormat


enum class AppScreen { HOME, SCAN, PUBLISH, GROUPS, RESULTS }

@Composable
fun BottomBar(current: AppScreen, onNavigate: (AppScreen) -> Unit) {
    val items = listOf(
        Triple(AppScreen.HOME, "الاستخراج", Icons.Default.Download),
        Triple(AppScreen.SCAN, "الفحص", Icons.Default.CheckCircle),
        Triple(AppScreen.PUBLISH, "النشر", Icons.Default.Send)
    )
    NavigationBar(
        containerColor = NeonPanel,
        tonalElevation = 0.dp,
        modifier = Modifier
            .border(1.dp, NeonBorder, RoundedCornerShape(26.dp, 26.dp, 0.dp, 0.dp))
    ) {
        items.forEach { (screen, label, icon) ->
            val selected = when (screen) {
                AppScreen.HOME -> current == AppScreen.HOME || current == AppScreen.GROUPS || current == AppScreen.RESULTS
                else -> current == screen
            }
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screen) },
                icon = { Icon(icon, null) },
                label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NeonGreen,
                    selectedTextColor = NeonGreen,
                    indicatorColor = NeonGreen.copy(alpha = 0.12f),
                    unselectedIconColor = SoftText,
                    unselectedTextColor = SoftText
                )
            )
        }
    }
}

private val NeonGreen = Color(0xFF18F17A)
private val NeonGreen2 = Color(0xFF00B95B)
private val NeonCyan = Color(0xFF39D9FF)
private val DeepBlack = Color(0xFF020A12)
private val DeepNavy = Color(0xFF061521)
private val NeonPanel = Color(0xEB081925)
private val NeonPanel2 = Color(0xF30B1D2B)
private val NeonBorder = Color(0xFF284352)
private val SoftText = Color(0xFFA8B5BF)
private val Danger = Color(0xFFFF4D62)
private val Warning = Color(0xFFFFB52E)

private fun screenBrush() = Brush.verticalGradient(
    listOf(DeepBlack, DeepNavy, Color(0xFF03101A))
)

private fun activeBrush() = Brush.horizontalGradient(
    listOf(NeonGreen.copy(alpha = 0.32f), NeonGreen2.copy(alpha = 0.76f), NeonGreen.copy(alpha = 0.28f))
)

@Composable
private fun ScreenHeader(subtitle: String, onSettings: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onSettings != null) {
            Surface(
                modifier = Modifier.size(48.dp).clickable(onClick = onSettings),
                shape = RoundedCornerShape(15.dp),
                color = NeonPanel2,
                border = BorderStroke(1.dp, NeonBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Settings, null, tint = NeonGreen)
                }
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "AL-thmany",
                style = MaterialTheme.typography.headlineMedium,
                color = NeonGreen,
                fontWeight = FontWeight.ExtraBold
            )
            Text(subtitle, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

@Composable
private fun NeonCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, NeonBorder),
        colors = CardDefaults.cardColors(containerColor = NeonPanel2),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

@Composable
private fun NeonSectionTitle(title: String, icon: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        if (icon != null) {
            Spacer(Modifier.width(10.dp))
            icon()
        }
    }
}

@Composable
private fun NeonActionButton(
    text: String,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val brush = if (enabled) activeBrush() else Brush.horizontalGradient(listOf(Color(0xFF18303B), Color(0xFF18303B)))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .border(1.dp, if (enabled) NeonGreen else NeonBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text,
            color = if (enabled) Color.White else SoftText,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) NeonGreen.copy(alpha = 0.13f) else Color.Transparent)
            .border(1.dp, if (selected) NeonGreen else NeonBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (selected) "$label  ✓" else label,
            color = if (selected) NeonGreen else if (enabled) Color.White else SoftText,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun RuntimeStatusLine(good: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (good) Icons.Default.CheckCircle else Icons.Default.SettingsAccessibility,
            null,
            tint = if (good) NeonGreen else Danger,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (good) NeonGreen else Danger, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WhatsAppSelector(
    engine: ExtractionUiState,
    running: Boolean,
    onTargetWhatsApp: (String) -> Unit
) {
    NeonCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonSectionTitle("اختيار نسخة واتساب") {
                Icon(Icons.Default.CheckCircle, null, tint = NeonGreen)
            }
            if (engine.availableWhatsApp.isEmpty()) {
                Text("لا توجد نسخة واتساب قابلة للفتح داخل هذه البيئة", color = Danger)
            } else {
                engine.availableWhatsApp.forEach { instance ->
                    val selected = engine.selectedWhatsAppPackage == instance.packageName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) NeonGreen.copy(alpha = 0.07f) else Color.Transparent)
                            .border(1.dp, if (selected) NeonGreen else NeonBorder, RoundedCornerShape(16.dp))
                            .clickable(enabled = !running && instance.launchable) { onTargetWhatsApp(instance.packageName) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { if (!running && instance.launchable) onTargetWhatsApp(instance.packageName) },
                            enabled = !running && instance.launchable,
                            colors = RadioButtonDefaults.colors(selectedColor = NeonGreen)
                        )
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(instance.labelAr, color = if (selected) NeonGreen else Color.White, fontWeight = FontWeight.Bold)
                            Text(instance.packageName, style = MaterialTheme.typography.labelSmall, color = SoftText)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun HomeScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    accessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenWhatsApp: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onGroups: () -> Unit,
    onResults: () -> Unit,
    onScan: () -> Unit,
    onPublish: () -> Unit,
    onMode: (ExtractionMode) -> Unit,
    onSpeed: (SpeedProfile) -> Unit,
    onMaxRounds: (Int) -> Unit,
    onTargetWhatsApp: (String) -> Unit
) {
    val running = engine.status in setOf(
        EngineStatus.PREPARING,
        EngineStatus.OPENING_WHATSAPP,
        EngineStatus.SYNCING_GROUPS,
        EngineStatus.SEARCHING_GROUP,
        EngineStatus.OPENING_GROUP,
        EngineStatus.VERIFYING_GROUP,
        EngineStatus.EXTRACTING,
        EngineStatus.LINKS_TAB,
        EngineStatus.VERIFYING_END,
        EngineStatus.RECOVERING,
        EngineStatus.PROFILE_MISMATCH
    )
    val runtimeReady = accessibilityEnabled && engine.serviceConnected

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBrush())
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 26.dp)
    ) {
        item { ScreenHeader("Extractor", onOpenAccessibility) }
        item { FeatureSwitcher(AppScreen.HOME, {}, onScan, onPublish) }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = if (runtimeReady) NeonGreen.copy(alpha = 0.10f) else Danger.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, if (runtimeReady) NeonGreen else Danger)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SettingsAccessibility, null, tint = if (runtimeReady) NeonGreen else Danger)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("خدمة الاستخراج", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        RuntimeStatusLine(
                            runtimeReady,
                            when {
                                engine.serviceConnected -> "الخدمة متصلة وجاهزة"
                                accessibilityEnabled -> "مفعلة — افتح واتساب مرة لتتصل الخدمة"
                                else -> "الخدمة غير مفعلة"
                            }
                        )
                    }
                }
            }
        }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    NeonSectionTitle("بيئة التشغيل") { Icon(Icons.Default.Groups, null, tint = NeonGreen) }
                    Text(engine.profileInfo.labelAr, color = NeonGreen, fontWeight = FontWeight.Bold)
                    Text(engine.profileInfo.detailAr, color = SoftText, textAlign = TextAlign.End)
                    HorizontalDivider(color = NeonBorder)
                    Text(
                        "هذه النسخة تعمل فقط مع واتساب الموجود في نفس Profile. داخل ملف العمل أو المجلد الآمن ثبّت AL-thmany وواتساب في البيئة نفسها.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftText,
                        textAlign = TextAlign.End
                    )
                }
            }
        }

        item { WhatsAppSelector(engine, running, onTargetWhatsApp) }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeonSectionTitle("طريقة الاستخراج") { Icon(Icons.Default.Link, null, tint = NeonGreen) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ExtractionMode.entries) { mode ->
                            ChoicePill(
                                label = mode.labelAr,
                                selected = engine.mode == mode,
                                enabled = !running,
                                modifier = Modifier.width(150.dp),
                                onClick = { onMode(mode) }
                            )
                        }
                    }
                    Text("السرعة", fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SpeedProfile.entries.take(3).forEach { speed ->
                            ChoicePill(
                                label = speed.labelAr,
                                selected = engine.speed == speed,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                onClick = { onSpeed(speed) }
                            )
                        }
                    }
                    Text("أقصى دورات تحميل القديم", fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(1000, 2000, 4000, 10000).forEach { rounds ->
                            ChoicePill(
                                label = rounds.toString(),
                                selected = engine.maxScrollIterations == rounds,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                onClick = { onMaxRounds(rounds) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("العناصر المحددة", engine.stats.totalGroups.toString(), Modifier.weight(1f))
                MetricCard("المكتمل", engine.stats.completedGroups.toString(), Modifier.weight(1f), NeonGreen)
                MetricCard("المشاكل", engine.stats.failedGroups.toString(), Modifier.weight(1f), Warning)
                MetricCard("الروابط الفريدة", engine.stats.totalUniqueLinks.toString(), Modifier.weight(1f), NeonCyan)
            }
        }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    NeonSectionTitle("حالة المهمة")
                    Text(engine.message, color = if (running) NeonGreen else Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    engine.currentGroup?.let { Text("المجموعة الحالية: $it", color = SoftText) }
                    if (engine.runGroupCount > 0) {
                        val progress = (engine.currentGroupIndex.toFloat() / engine.runGroupCount.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(8.dp)),
                            color = NeonGreen,
                            trackColor = NeonBorder
                        )
                        Text("${engine.currentGroupIndex} / ${engine.runGroupCount}", color = SoftText)
                    }
                    if (engine.phaseDetail.isNotBlank()) Text(engine.phaseDetail, color = NeonGreen, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onResults,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                    border = BorderStroke(1.dp, NeonBorder)
                ) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("النتائج والتصدير") }
                OutlinedButton(
                    onClick = onGroups,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen),
                    border = BorderStroke(1.dp, NeonBorder)
                ) { Icon(Icons.Default.Groups, null); Spacer(Modifier.width(6.dp)); Text("المجموعات") }
            }
        }

        item {
            NeonActionButton(
                text = if (engine.selectedWhatsAppPackage == null) "اختر نسخة واتساب أولاً" else "فتح نسخة واتساب المحددة",
                enabled = engine.selectedWhatsAppPackage != null,
                icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.White) },
                onClick = onOpenWhatsApp
            )
        }

        item {
            when {
                engine.status == EngineStatus.PAUSED -> NeonActionButton("استكمال الاستخراج", icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.White) }, onClick = onResume)
                running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Danger), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) {
                        Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("إنهاء")
                    }
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Pause, null); Spacer(Modifier.width(4.dp)); Text("إيقاف مؤقت")
                    }
                }
                else -> NeonActionButton(
                    text = "بدء الاستخراج",
                    enabled = accessibilityEnabled && engine.selectedWhatsAppPackage != null && engine.stats.totalGroups > 0,
                    icon = { Icon(Icons.Default.Download, null, tint = Color.White) },
                    onClick = onStart
                )
            }
        }
    }
}


@Composable
private fun StatusCard(title: String, good: Boolean, text: String, actionLabel: String, onAction: () -> Unit) {
    NeonCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(15.dp),
                color = if (good) NeonGreen.copy(alpha = 0.10f) else Danger.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, if (good) NeonGreen else Danger)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (good) Icons.Default.CheckCircle else Icons.Default.SettingsAccessibility, null, tint = if (good) NeonGreen else Danger)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = if (good) NeonGreen else Danger, textAlign = TextAlign.End)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onAction, border = BorderStroke(1.dp, NeonBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier, accent: Color = NeonGreen) {
    NeonCard(modifier) {
        Column(
            Modifier.padding(vertical = 13.dp, horizontal = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = SoftText, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = accent)
        }
    }
}


@Composable
fun GroupsScreen(
    padding: PaddingValues,
    groups: List<TargetGroup>,
    onAddGroups: (String) -> Unit,
    onSelected: (Long, Boolean) -> Unit,
    onPreset: (GroupSelectionPreset) -> Unit,
    onStart: () -> Unit,
    syncing: Boolean,
    syncFound: Int,
    onSync: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val filtered = remember(groups, query) {
        if (query.isBlank()) groups else groups.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val selectedCount = groups.count { it.selected }
    val unreadCount = groups.count { it.unreadCount > 0 }
    val activeCount = groups.count { it.active }
    val publishableCount = groups.count { it.publishable && !it.communityParent }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBrush())
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 26.dp)
    ) {
        item {
            ScreenHeader("مزامنة واختيار القروبات")
        }
        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonSectionTitle("القروبات الموجودة في واتساب") { Icon(Icons.Default.Sync, null, tint = NeonGreen) }
                    Text(
                        "اختر نسخة واتساب من شاشة الاستخراج أولاً، ثم اضغط مزامنة. التطبيق يقرأ قائمة الدردشات تدريجيًا ويجمع الأسماء والحالة الظاهرة بدون فتح كل محادثة.",
                        color = SoftText,
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall
                    )
                    NeonActionButton(
                        text = if (syncing) "جارٍ المزامنة… $syncFound" else "مزامنة القروبات من واتساب الحقيقي",
                        enabled = !syncing,
                        icon = { Icon(Icons.Default.Sync, null, tint = Color.White) },
                        onClick = onSync
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("المزامنة", groups.size.toString(), Modifier.weight(1f), NeonCyan)
                MetricCard("المحدد", selectedCount.toString(), Modifier.weight(1f), NeonGreen)
                MetricCard("غير مقروء", unreadCount.toString(), Modifier.weight(1f), Warning)
                MetricCard("قابل للنشر", publishableCount.toString(), Modifier.weight(1f), NeonGreen)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("بحث باسم القروب") },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonGreen,
                    unfocusedBorderColor = NeonBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }
        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("التحديد السريع", fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(GroupSelectionPreset.entries) { preset ->
                            AssistChip(
                                onClick = { onPreset(preset) },
                                label = { Text(preset.labelAr) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = NeonPanel,
                                    labelColor = Color.White
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (preset == GroupSelectionPreset.UNREAD) Warning else NeonBorder
                                )
                            )
                        }
                    }
                    Text(
                        "النشطة: $activeCount • غير المؤكدة: ${groups.count { !it.verifiedGroup }}",
                        color = SoftText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { showAdd = true },
                    border = BorderStroke(1.dp, NeonBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("إضافة اسم يدويًا")
                }
            }
        }
        items(filtered, key = { it.id }) { group ->
            NeonCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = group.selected, onCheckedChange = { onSelected(group.id, it) })
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(group.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val chips = buildList {
                            if (group.unreadCount > 0) add("غير مقروء ${group.unreadCount}")
                            if (group.active) add("نشط") else add("غير نشط")
                            if (group.publishable && !group.communityParent) add("قابل للنشر")
                            if (group.communityParent) add("Community Parent")
                            if (group.verifiedGroup) add("قروب مؤكّد") else add("بانتظار التحقق")
                        }
                        Text(chips.joinToString(" • "), color = if (group.verifiedGroup) NeonGreen else SoftText, style = MaterialTheme.typography.bodySmall)
                        group.activityText?.let { Text("آخر نشاط ظاهر: $it", color = SoftText, style = MaterialTheme.typography.labelSmall) }
                        Text("الحالة: ${group.status.name} • روابط: ${group.extractedCount}", color = SoftText, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "المسار المفضل: ${group.preferredAccessMethod.labelAr} • آخر نجاح: ${group.lastSuccessfulOpenMethod.labelAr}",
                            color = SoftText,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End
                        )
                        Text(
                            "WhatsApp: ${group.whatsappPackage.ifBlank { "غير مربوط بعد" }} • نجاح الوصول: ${group.accessSuccessCount} • فشل: ${group.accessFailureCount}",
                            color = SoftText,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End
                        )
                        group.jidOrGroupId?.takeIf { it.isNotBlank() }?.let {
                            Text("ID/JID: $it", color = NeonCyan, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                        }
                        group.lastPublishStatus?.let { publishStatus ->
                            Text(
                                "آخر نشر: $publishStatus${group.lastPublishError?.let { error -> " • $error" }.orEmpty()}",
                                color = if (publishStatus == "FAILED") Danger else SoftText,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.End
                            )
                        }
                        group.lastError?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End) }
                    }
                }
            }
        }
        item {
            NeonActionButton(
                text = if (selectedCount == 0) "حدد قروبًا واحدًا على الأقل" else "بدء الاستخراج من $selectedCount قروب",
                enabled = selectedCount > 0 && !syncing,
                icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.White) },
                onClick = onStart
            )
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("إضافة مجموعات يدويًا") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("اسم مجموعة في كل سطر") },
                    minLines = 6,
                    maxLines = 12
                )
            },
            confirmButton = {
                Button(onClick = {
                    onAddGroups(input)
                    input = ""
                    showAdd = false
                }, enabled = input.isNotBlank()) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun ResultsScreen(
    padding: PaddingValues,
    links: List<LinkRecord>,
    onRefresh: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onClearAll: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<LinkCategory?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val filtered = remember(links, query, category) {
        links.filter { link ->
            val textMatch = query.isBlank() || link.url.contains(query, ignoreCase = true) || link.groupName.contains(query, ignoreCase = true)
            val categoryMatch = category == null || link.category == category
            textMatch && categoryMatch
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("النتائج", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val uniqueCount = links.asSequence().map { it.normalizedUrl }.distinct().count()
                Text("$uniqueCount رابط فريد • ${links.size} ظهور عبر القروبات")
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "تحديث") }
            IconButton(onClick = { clipboard.setText(AnnotatedString(links.joinToString("\n") { it.url })) }) {
                Icon(Icons.Default.ContentCopy, "نسخ جميع الروابط")
            }
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, "مسح") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("بحث بالرابط أو المجموعة") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                AssistChip(
                    onClick = { category = null },
                    label = { Text("الكل") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (category == null) NeonGreen.copy(alpha = 0.15f) else NeonPanel,
                        labelColor = if (category == null) NeonGreen else Color.White
                    )
                )
            }
            items(LinkCategory.entries) { linkCategory ->
                AssistChip(
                    onClick = { category = linkCategory },
                    label = { Text(linkCategory.labelAr) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (category == linkCategory) NeonGreen.copy(alpha = 0.15f) else NeonPanel,
                        labelColor = if (category == linkCategory) NeonGreen else Color.White
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportFormat.entries.forEach { format ->
                AssistChip(
                    onClick = { onExport(format) },
                    label = { Text(format.name) },
                    leadingIcon = { Icon(Icons.Default.Download, null) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(filtered, key = { it.id }) { link ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(link.url, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(link.groupName, fontWeight = FontWeight.SemiBold)
                        Text("النوع: ${link.category.labelAr}", color = SoftText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح جميع البيانات؟") },
            text = { Text("سيتم حذف قائمة المجموعات والروابط المحفوظة من التطبيق.") },
            confirmButton = { Button(onClick = { onClearAll(); confirmClear = false }) { Text("مسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun FeatureSwitcher(
    selected: AppScreen,
    onExtraction: () -> Unit,
    onScan: () -> Unit,
    onPublish: () -> Unit
) {
    val items = listOf(
        Triple(AppScreen.PUBLISH, "النشر", onPublish),
        Triple(AppScreen.SCAN, "الفحص", onScan),
        Triple(AppScreen.HOME, "الاستخراج", onExtraction)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(NeonPanel)
            .border(1.dp, NeonBorder, RoundedCornerShape(22.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (screen, label, action) ->
            val isSelected = selected == screen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) activeBrush() else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .border(1.dp, if (isSelected) NeonGreen else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable(onClick = action)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (isSelected) Color.White else SoftText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}


@Composable
fun ScanScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    scan: ScanUiState,
    items: List<ScanRecord>,
    accessibilityEnabled: Boolean,
    onExtraction: () -> Unit,
    onPublish: () -> Unit,
    onTargetWhatsApp: (String) -> Unit,
    onAddLinks: (String) -> Unit,
    onImportExtracted: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onScanSpeed: (ScanSpeedProfile) -> Unit,
    onScanScope: (ScanScope) -> Unit,
    onScanActionMode: (ScanActionMode) -> Unit,
    onRequestToJoinEnabled: (Boolean) -> Unit,
    onMaxAttempts: (Int) -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<ScanStatus?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val runtimeReady = accessibilityEnabled && scan.serviceConnected
    val filtered = remember(items, query, filter) {
        items.filter { item ->
            (filter == null || item.status == filter) &&
                (query.isBlank() || item.normalizedUrl.contains(query, true) || item.sourceGroup.orEmpty().contains(query, true) ||
                    item.groupName.orEmpty().contains(query, true) || item.memberCountText.orEmpty().contains(query, true) ||
                    item.signalCode.orEmpty().contains(query, true) || item.status.labelAr.contains(query, true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(screenBrush()).padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 26.dp)
    ) {
        item { ScreenHeader("الفحص") }
        item { FeatureSwitcher(AppScreen.SCAN, onExtraction, {}, onPublish) }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeonSectionTitle("فحص روابط دعوات واتساب") { Icon(Icons.Default.Link, null, tint = NeonGreen) }
                    Text("الصق روابط الدعوة أو استورد روابط الاستخراج. اختر: فحص فقط، انضمام فقط، أو فحص + انضمام. كل رابط يفتح مرة واحدة، ثم يُحفظ القرار قبل الانتقال.", color = SoftText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("الصق روابط chat.whatsapp.com ...") },
                        minLines = 4,
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = NeonBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = DeepBlack.copy(alpha = 0.25f),
                            unfocusedContainerColor = DeepBlack.copy(alpha = 0.25f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { onAddLinks(input); input = "" }, enabled = input.isNotBlank() && !scan.running, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, NeonBorder)) {
                            Text("إضافة للفحص")
                        }
                        Button(onClick = onImportExtracted, enabled = !scan.running, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = NeonGreen2, contentColor = Color.White)) {
                            Text("من الاستخراج")
                        }
                    }
                }
            }
        }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonSectionTitle("محرك الفحص") { Icon(Icons.Default.CheckCircle, null, tint = NeonGreen) }
                    RuntimeStatusLine(runtimeReady, if (runtimeReady) "Accessibility متصلة وجاهزة" else "Accessibility غير جاهزة لهذه النسخة")
                    HorizontalDivider(color = NeonBorder)
                    if (engine.availableWhatsApp.isEmpty()) {
                        Text("لا توجد نسخة واتساب قابلة للفتح", color = Danger)
                    } else {
                        engine.availableWhatsApp.forEach { instance ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !scan.running && instance.launchable) { onTargetWhatsApp(instance.packageName) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = engine.selectedWhatsAppPackage == instance.packageName,
                                    onClick = { if (!scan.running) onTargetWhatsApp(instance.packageName) },
                                    enabled = !scan.running && instance.launchable,
                                    colors = RadioButtonDefaults.colors(selectedColor = NeonGreen)
                                )
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text(instance.labelAr, fontWeight = FontWeight.Bold)
                                    Text(instance.packageName, style = MaterialTheme.typography.labelSmall, color = SoftText)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("وضع معالجة الرابط", fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    Text(
                        "OPEN ONCE → SCAN → CLASSIFY → تنفيذ الإجراء حسب الوضع → VERIFY → SAVE → NEXT",
                        color = SoftText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ScanActionMode.entries.forEach { mode ->
                        ChoicePill(
                            mode.labelAr,
                            scan.actionMode == mode,
                            !scan.running,
                            Modifier.fillMaxWidth()
                        ) { onScanActionMode(mode) }
                    }
                    ChoicePill(
                        if (scan.requestToJoinEnabled) "طلبات الموافقة: مفعلة" else "طلبات الموافقة: معطلة",
                        scan.requestToJoinEnabled,
                        !scan.running && scan.actionMode != ScanActionMode.SCAN_ONLY,
                        Modifier.fillMaxWidth()
                    ) { onRequestToJoinEnabled(!scan.requestToJoinEnabled) }
                    Text(
                        if (scan.actionMode == ScanActionMode.SCAN_ONLY)
                            "في وضع فحص فقط لن يتم الضغط على انضمام أو طلب انضمام."
                        else if (scan.requestToJoinEnabled)
                            "إذا ظهرت موافقة المشرف، يمكن إرسال طلب الانضمام مرة واحدة ثم التحقق من النتيجة."
                        else
                            "روابط الموافقة تُسجّل فقط؛ لا يُرسل طلب انضمام إلا عند تفعيل الخيار.",
                        color = SoftText,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NeonCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("سرعة الفحص", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        ScanSpeedProfile.entries.forEach { speed ->
                            ChoicePill(speed.labelAr, scan.speed == speed, !scan.running, Modifier.fillMaxWidth()) { onScanSpeed(speed) }
                        }
                    }
                }
                NeonCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("نطاق التشغيل", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        ScanScope.entries.forEach { scope ->
                            ChoicePill(scope.labelAr, scan.scope == scope, !scan.running, Modifier.fillMaxWidth()) { onScanScope(scope) }
                        }
                    }
                }
            }
        }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("عدد محاولات التحقق", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(1, 2, 3, 4, 5).forEach { attempts ->
                            ChoicePill(attempts.toString(), scan.maxAttempts == attempts, !scan.running, Modifier.weight(1f)) { onMaxAttempts(attempts) }
                        }
                    }
                    Text(scan.message, color = SoftText, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    if (scan.total > 0) {
                        val progress = (scan.currentIndex.toFloat() / scan.total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(8.dp)), color = NeonGreen, trackColor = NeonBorder)
                        Text("${scan.currentIndex} / ${scan.total}", color = SoftText, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("الكل", scan.stats.total.toString(), Modifier.weight(1f))
                MetricCard("مباشر", scan.stats.direct.toString(), Modifier.weight(1f), NeonGreen)
                MetricCard("موافقة", scan.stats.approval.toString(), Modifier.weight(1f), Color(0xFF9AE66E))
                MetricCard("غير صالح", scan.stats.invalid.toString(), Modifier.weight(1f), Danger)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("تم الانضمام", scan.stats.joined.toString(), Modifier.weight(1f), NeonGreen)
                MetricCard("طلب مرسل", scan.stats.requestPending.toString(), Modifier.weight(1f), Color(0xFF48E0C5))
                MetricCard("عضو مسبقًا", scan.stats.alreadyMember.toString(), Modifier.weight(1f), NeonCyan)
                MetricCard("نتيجة الإجراء؟", scan.stats.actionUncertain.toString(), Modifier.weight(1f), Warning)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("بانتظار", scan.stats.pending.toString(), Modifier.weight(1f), Color(0xFFA970FF))
                MetricCard("غير مؤكد", scan.stats.unknown.toString(), Modifier.weight(1f), Warning)
                MetricCard("اتصال", scan.stats.network.toString(), Modifier.weight(1f), Warning)
                MetricCard("أخرى", scan.stats.other.toString(), Modifier.weight(1f), SoftText)
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("بحث في نتائج الفحص...") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonGreen, unfocusedBorderColor = NeonBorder)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { ChoicePill("الكل", filter == null, modifier = Modifier.width(95.dp)) { filter = null } }
                items(listOf(ScanStatus.DIRECT, ScanStatus.APPROVAL, ScanStatus.JOINED, ScanStatus.REQUEST_PENDING, ScanStatus.ACTION_UNCERTAIN, ScanStatus.ALREADY_MEMBER, ScanStatus.INVALID, ScanStatus.UNKNOWN, ScanStatus.ERROR)) { status ->
                    ChoicePill(status.labelAr, filter == status, modifier = Modifier.width(115.dp)) { filter = status }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEach { format ->
                    OutlinedButton(onClick = { onExport(format) }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, NeonBorder), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp), tint = NeonGreen)
                        Spacer(Modifier.width(4.dp))
                        Text(format.name)
                    }
                }
            }
        }

        item {
            when {
                scan.paused -> NeonActionButton(
                    when (scan.actionMode) {
                        ScanActionMode.SCAN_ONLY -> "استكمال الفحص"
                        ScanActionMode.JOIN_ONLY -> "استكمال الانضمام"
                        ScanActionMode.SCAN_AND_JOIN -> "استكمال فحص + انضمام"
                    }, icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.White) }, onClick = onResume)
                scan.running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Danger), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.Default.Stop, null); Text("إنهاء") }
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Pause, null); Text("إيقاف مؤقت") }
                }
                else -> NeonActionButton(
                    when (scan.actionMode) {
                        ScanActionMode.SCAN_ONLY -> "بدء الفحص"
                        ScanActionMode.JOIN_ONLY -> "بدء الانضمام"
                        ScanActionMode.SCAN_AND_JOIN -> "بدء فحص + انضمام"
                    },
                    enabled = items.isNotEmpty() && accessibilityEnabled && scan.serviceConnected && engine.selectedWhatsAppPackage != null,
                    icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.White) },
                    onClick = onStart
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "تحديث", tint = NeonGreen) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(filtered.joinToString("\n") { it.normalizedUrl })) }) { Icon(Icons.Default.ContentCopy, "نسخ", tint = NeonCyan) }
                IconButton(onClick = { confirmClear = true }, enabled = !scan.running) { Icon(Icons.Default.Delete, "مسح", tint = Danger) }
            }
        }

        items(filtered.take(100), key = { "scan-${it.id}" }) { item ->
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                    Text(item.normalizedUrl, color = NeonGreen, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("ثقة ${item.confidence}%", color = NeonCyan, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text(item.status.labelAr, fontWeight = FontWeight.Bold, color = if (item.status == ScanStatus.INVALID || item.status == ScanStatus.ERROR) Danger else Color.White)
                    }
                    item.groupName?.let { Text(it, color = SoftText) }
                    item.memberCountText?.let { Text("الأعضاء: $it", color = SoftText, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح نتائج الفحص؟") },
            text = { Text("سيتم حذف روابط الفحص ونتائجها فقط، ولن تُحذف نتائج الاستخراج.") },
            confirmButton = { Button(onClick = { onClear(); confirmClear = false }) { Text("مسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } }
        )
    }
}


@Composable
fun PublishScreen(
    padding: PaddingValues,
    engine: ExtractionUiState,
    publish: PublishUiState,
    items: List<PublishItem>,
    accessibilityEnabled: Boolean,
    onExtraction: () -> Unit,
    onScan: () -> Unit,
    onGroups: () -> Unit,
    onTargetWhatsApp: (String) -> Unit,
    onDraftChanged: (String) -> Unit,
    onContentMode: (PublishContentMode) -> Unit,
    onPickAttachment: (PublishContentMode) -> Unit,
    onClearAttachment: () -> Unit,
    onSpeed: (PublishSpeedProfile) -> Unit,
    onMaxAttempts: (Int) -> Unit,
    onStart: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onClearHistory: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    var draft by remember(publish.messageText) { mutableStateOf(publish.messageText) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val running = publish.running
    val runtimeReady = accessibilityEnabled && publish.serviceConnected
    val contentReady = when (publish.contentMode) {
        PublishContentMode.VCF -> !publish.attachmentUri.isNullOrBlank()
        PublishContentMode.VCF_WITH_TEXT, PublishContentMode.IMAGE_WITH_CAPTION -> draft.isNotBlank() && !publish.attachmentUri.isNullOrBlank()
        else -> draft.isNotBlank()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(screenBrush()).padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 26.dp)
    ) {
        item { ScreenHeader("Publish") }
        item { FeatureSwitcher(AppScreen.PUBLISH, onExtraction, onScan, {}) }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(58.dp), shape = RoundedCornerShape(29.dp), color = NeonGreen.copy(alpha = 0.10f), border = BorderStroke(1.dp, NeonGreen)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Send, null, tint = NeonGreen, modifier = Modifier.size(28.dp)) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("محرك النشر", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        RuntimeStatusLine(runtimeReady, if (runtimeReady) "متصل وجاهز" else "Accessibility غير جاهزة لهذه البيئة")
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = onGroups, border = BorderStroke(1.dp, NeonBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)) {
                        Icon(Icons.Default.Groups, null); Spacer(Modifier.width(5.dp)); Text("اختيار القروبات")
                    }
                }
            }
        }

        item { WhatsAppSelector(engine, running, onTargetWhatsApp) }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonSectionTitle("نوع النشر") { Icon(Icons.Default.Send, null, tint = NeonGreen) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PublishContentMode.entries) { mode ->
                            ChoicePill(
                                label = mode.labelAr,
                                selected = publish.contentMode == mode,
                                enabled = !running,
                                modifier = Modifier.width(150.dp),
                                onClick = { onContentMode(mode) }
                            )
                        }
                    }
                    if (publish.contentMode.attachmentRequired) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onPickAttachment(publish.contentMode) },
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, NeonGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(5.dp))
                                Text(if (publish.attachmentUri == null) "اختيار ملف" else "تغيير الملف")
                            }
                            OutlinedButton(
                                onClick = onClearAttachment,
                                enabled = !running && publish.attachmentUri != null,
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, NeonBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftText)
                            ) { Text("إزالة المرفق") }
                        }
                        Text(
                            publish.attachmentUri?.let { "المرفق جاهز • ${publish.attachmentMime.orEmpty()}" } ?: "لم يتم اختيار مرفق بعد",
                            color = if (publish.attachmentUri == null) Warning else NeonGreen,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    when (publish.contentMode) {
                        PublishContentMode.MULTI_TEXT -> Text("افصل الرسائل بسطر يحتوي --- وسيتم توزيعها دائريًا على القروبات.", color = SoftText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                        PublishContentMode.CONTACT_TEXT -> Text("اكتب كل جهة اتصال بسطر: الاسم | الرقم، وسيتم تنسيقها تلقائيًا كنص.", color = SoftText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                        else -> Unit
                    }
                }
            }
        }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonSectionTitle(if (publish.contentMode == PublishContentMode.IMAGE_WITH_CAPTION) "التعليق" else "محتوى النشر") { Icon(Icons.Default.Send, null, tint = Color.White) }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { value ->
                            draft = value.take(16_000)
                            onDraftChanged(draft)
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                when (publish.contentMode) {
                                    PublishContentMode.MULTI_TEXT -> "رسالة 1\n---\nرسالة 2\n---\nرسالة 3"
                                    PublishContentMode.CONTACT_TEXT -> "أحمد | +9627xxxxxxx\nمحمد | +9677xxxxxxx"
                                    else -> "اكتب محتوى النشر..."
                                }
                            )
                        },
                        minLines = 7,
                        maxLines = 14,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = NeonBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = DeepBlack.copy(alpha = 0.30f),
                            unfocusedContainerColor = DeepBlack.copy(alpha = 0.30f)
                        )
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text("${draft.length} / 16000", color = if (draft.length > 15000) Warning else NeonGreen, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text("يتم النشر فقط في القروبات المحددة", color = SoftText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NeonCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("السرعة", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        PublishSpeedProfile.entries.forEach { speed ->
                            ChoicePill(speed.labelAr, publish.speed == speed, !running, Modifier.fillMaxWidth()) { onSpeed(speed) }
                        }
                    }
                }
                NeonCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("محاولات فتح القروب", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        listOf(1, 2, 3).forEach { attempts ->
                            ChoicePill(attempts.toString(), publish.maxAttempts == attempts, !running, Modifier.fillMaxWidth()) { onMaxAttempts(attempts) }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("المحدد", engine.stats.totalGroups.toString(), Modifier.weight(1f), NeonCyan)
                MetricCard("ناجح", (publish.stats.sent + publish.stats.verified).toString(), Modifier.weight(1f), NeonGreen)
                MetricCard("غير محسوم", publish.stats.uncertain.toString(), Modifier.weight(1f), Warning)
                MetricCard("فشل", publish.stats.failed.toString(), Modifier.weight(1f), Danger)
            }
        }

        item {
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    NeonSectionTitle("حالة النشر")
                    Text(publish.info, color = if (publish.running) NeonGreen else Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    publish.currentGroup?.let { Text("القروب الحالي: $it", color = SoftText) }
                    publish.runToken?.takeIf { it.isNotBlank() }?.let { Text("Run Token: ${it.take(8)}…", color = SoftText, style = MaterialTheme.typography.labelSmall) }
                    Text("الفاصل الفعلي: ${publish.speed.betweenGroupsMs}ms", color = NeonCyan, style = MaterialTheme.typography.labelSmall)
                    if (publish.total > 0) {
                        val progress = (publish.currentIndex.toFloat() / publish.total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(8.dp)), color = NeonGreen, trackColor = NeonBorder)
                        Text("${publish.currentIndex} / ${publish.total}", color = SoftText)
                    }
                }
            }
        }

        item {
            when {
                publish.paused -> NeonActionButton("استكمال النشر", icon = { Icon(Icons.Default.PlayArrow, null, tint = Color.White) }, onClick = onResume)
                publish.running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Danger), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.Default.Stop, null); Text("إنهاء") }
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Pause, null); Text("إيقاف مؤقت") }
                }
                else -> NeonActionButton(
                    "معاينة وبدء النشر",
                    enabled = contentReady && accessibilityEnabled && publish.serviceConnected && engine.selectedWhatsAppPackage != null && engine.stats.totalGroups > 0,
                    icon = { Icon(Icons.Default.Send, null, tint = Color.White) },
                    onClick = { confirmStart = true }
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEach { format ->
                    OutlinedButton(onClick = { onExport(format) }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, NeonBorder), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp), tint = NeonGreen)
                        Spacer(Modifier.width(4.dp)); Text(format.name)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "تحديث", tint = NeonGreen) }
                IconButton(onClick = { confirmClear = true }, enabled = !running) { Icon(Icons.Default.Delete, "مسح", tint = Danger) }
            }
        }

        items(items.takeLast(60).reversed(), key = { "publish-${it.id}" }) { item ->
            NeonCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                    Text(item.groupName, fontWeight = FontWeight.Bold)
                    Text(
                        item.status.labelAr,
                        color = when (item.status) { PublishStatus.FAILED -> Danger; PublishStatus.UNCERTAIN -> Warning; else -> NeonGreen },
                        fontWeight = FontWeight.SemiBold
                    )
                    item.detail?.let { Text(it, color = SoftText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End) }
                    Text("المحاولات: ${item.attempts}${if (item.verified) " • تم التحقق" else ""}", color = SoftText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("تأكيد النشر") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("سيتم تشغيل ${publish.contentMode.labelAr} على القروبات المحددة والقابلة للنشر فقط.")
                    if (draft.isNotBlank()) Text(draft, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    if (publish.attachmentUri != null) Text("المرفق: ${publish.attachmentMime.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Text("الإرسال Single-Flight، وبعد أي إرسال غير محسوم لن تتم إعادة المحاولة تلقائيًا.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { confirmStart = false; onStart(draft) }) { Text("بدء النشر") } },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("إلغاء") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح سجل النشر؟") },
            text = { Text("سيتم حذف سجل مهام النشر فقط، ولن تُحذف القروبات أو نتائج الاستخراج والفحص.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearHistory() }) { Text("مسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } }
        )
    }
}

