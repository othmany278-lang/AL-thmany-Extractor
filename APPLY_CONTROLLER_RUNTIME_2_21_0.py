#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path.cwd().resolve()
if not (ROOT / "app/build.gradle.kts").exists():
    raise SystemExit("❌ شغّل الملف من جذر AL-thmany-Extractor")

def p(rel): return ROOT / rel
def read(rel): return p(rel).read_text(encoding="utf-8")
def write(rel, s):
    path = p(rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(s, encoding="utf-8")

def replace_once(rel, old, new, label):
    s = read(rel)
    if new in s:
        print(f"ℹ️ {label}: مطبق مسبقًا")
        return
    if old not in s:
        raise SystemExit(f"❌ لم أجد موضع التعديل: {label}")
    write(rel, s.replace(old, new, 1))
    print(f"✅ {label}")

def replace_all(rel, pairs, label):
    s = read(rel)
    old_s = s
    for a, b in pairs:
        s = s.replace(a, b)
    if s != old_s:
        write(rel, s)
        print(f"✅ {label}")
    else:
        print(f"ℹ️ {label}: لا تغيير")

build = read("app/build.gradle.kts")
if 'versionName = "2.21.0"' not in build and 'versionName = "2.20.2"' not in build:
    raise SystemExit("❌ هذا التحديث مبني على v2.20.2")

stamp = time.strftime("%Y%m%d-%H%M%S")
backup = Path(tempfile.gettempdir()) / f"althmany-2210-backup-{stamp}"
targets = [
    "app/build.gradle.kts",
    ".github/workflows/build-apk.yml",
    "scripts/validate_release.py",
    "app/src/main/java/com/althmany/extractor/MainActivity.kt",
    "app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt",
    "app/src/main/java/com/althmany/extractor/ui/SmartWorkspaceScreens.kt",
    "app/src/main/java/com/althmany/extractor/engine/InviteScanClassifier.kt",
    "app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt",
    "app/src/main/java/com/althmany/extractor/engine/ScanController.kt",
    "app/src/main/java/com/althmany/extractor/shizuku/ShizukuBridge.kt",
    "app/src/main/java/com/althmany/extractor/shizuku/ShizukuUiRuntime.kt",
    "app/src/main/java/com/althmany/extractor/engine/GroupAccessRouter.kt",
]
for rel in targets:
    src = p(rel)
    if not src.exists():
        raise SystemExit(f"❌ ملف مطلوب غير موجود: {rel}")
    dst = backup / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)

replace_all("app/build.gradle.kts", [
    ("versionCode = 2202", "versionCode = 2210"),
    ('versionName = "2.20.2"', 'versionName = "2.21.0"'),
], "رفع الإصدار إلى 2.21.0")

replace_all(".github/workflows/build-apk.yml", [
    ("2.20.2", "2.21.0"),
    ("AccessibilityRuntimeFix", "ControllerRuntime"),
    ("RuntimeFix", "ControllerRuntime"),
], "تحديث GitHub Action")

replace_all("scripts/validate_release.py", [
    ("versionCode 2202", "versionCode 2210"),
    ("versionCode = 2202", "versionCode = 2210"),
    ("versionName 2.20.2", "versionName 2.21.0"),
    ('versionName = \\"2.20.2\\"', 'versionName = \\"2.21.0\\"'),
    ("2.20.2 Accessibility Runtime", "2.21.0 Controller Runtime"),
], "تحديث Source Contract")

replace_all("scripts/validate_release.py", [
    (
        "'AUTO backend router': all(x in profile_router for x in ['RuntimeBackendKind.ACCESSIBILITY', 'RuntimeBackendKind.SHIZUKU', 'ProfileAccessibilityRuntime.snapshot']),",
        "'AUTO backend router': all(x in profile_router for x in ['RuntimeBackendKind.ACCESSIBILITY', 'RuntimeBackendKind.SHIZUKU', 'AccessibilityRuntimeBridge.currentEvenIfQuiet']),"
    )
], "تثبيت فحص AUTO backend")

importer = r'''package com.althmany.extractor.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ScanInputImporter {
    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 12 * 1024 * 1024

    fun readLinks(resolver: ContentResolver, uri: Uri): Set<String> {
        val name = displayName(resolver, uri).lowercase()
        val mime = resolver.getType(uri).orEmpty().lowercase()
        return resolver.openInputStream(uri)?.use { input ->
            when {
                name.endsWith(".xlsx") || mime.contains("spreadsheetml") -> readXlsx(input)
                name.endsWith(".xls") || mime.contains("ms-excel") -> readLegacyXls(input)
                else -> readTextFile(input)
            }
        } ?: emptySet()
    }

    private fun readXlsx(input: InputStream): Set<String> {
        val out = linkedSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val n = entry.name.lowercase()
                if (!n.startsWith("xl/")) continue
                if (!(n.endsWith(".xml") || n.endsWith(".rels"))) continue
                val bytes = readLimited(zip, MAX_ZIP_ENTRY_BYTES)
                val text = bytes.toString(Charsets.UTF_8)
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                InviteLinkParser.extract(text).forEach { out += it.normalizedUrl }
            }
        }
        return out
    }

    private fun readLegacyXls(input: InputStream): Set<String> {
        val bytes = readLimited(input, MAX_FILE_BYTES)
        val out = linkedSetOf<String>()
        InviteLinkParser.extract(bytes.toString(Charsets.ISO_8859_1)).forEach { out += it.normalizedUrl }
        if (bytes.size >= 2) {
            runCatching { bytes.toString(Charsets.UTF_16LE) }
                .getOrNull()
                ?.let(InviteLinkParser::extract)
                ?.forEach { out += it.normalizedUrl }
        }
        return out
    }

    private fun readTextFile(input: InputStream): Set<String> {
        val bytes = readLimited(input, MAX_FILE_BYTES)
        return InviteLinkParser.extract(bytes.toString(Charsets.UTF_8))
            .mapTo(linkedSetOf()) { it.normalizedUrl }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 256 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            val allowed = minOf(n, limit - total)
            if (allowed > 0) out.write(buffer, 0, allowed)
            total += allowed
            if (total >= limit) break
        }
        return out.toByteArray()
    }
}
'''
write("app/src/main/java/com/althmany/extractor/engine/ScanInputImporter.kt", importer)
print("✅ ScanInputImporter XLSX/XLS/CSV/TXT")

vm_marker = '''    fun importScanLinksFromExtraction() {'''
vm_method = r'''    fun importScanFile(uri: android.net.Uri) {
        if (globalJob?.isActive == true) return
        viewModelScope.launch {
            val links = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.althmany.extractor.engine.ScanInputImporter.readLinks(
                        getApplication<Application>().contentResolver,
                        uri
                    )
                }
            }.getOrElse {
                _message.value = "FILE_IMPORT_FAILED: ${it.message ?: "تعذر قراءة الملف"}"
                return@launch
            }
            if (links.isEmpty()) {
                _message.value = "لم يتم العثور على روابط دعوات واتساب داخل الملف"
                return@launch
            }
            val added = repo.addScanLinksFromText(links.joinToString("\n"))
            _scanItems.value = repo.scanItems()
            ScanController.refreshStats()
            _message.value = "تم استيراد $added رابط جديد من الملف • الإجمالي ${_scanItems.value.size}"
        }
    }

'''
s = read("app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt")
if "fun importScanFile(uri:" not in s:
    if vm_marker not in s:
        raise SystemExit("❌ AppViewModel import marker missing")
    write("app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt", s.replace(vm_marker, vm_method + vm_marker, 1))
    print("✅ AppViewModel file import")

main_marker = '''    val pickPublishAttachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->'''
main_launcher = r'''    val openScanFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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

'''
s = read("app/src/main/java/com/althmany/extractor/MainActivity.kt")
if "val openScanFile =" not in s:
    if main_marker not in s:
        raise SystemExit("❌ MainActivity file picker marker missing")
    s = s.replace(main_marker, main_launcher + main_marker, 1)

scan_call_old = '''                    onImportExtraction = viewModel::importScanLinksFromExtraction,
                    onAction = { mode ->'''
scan_call_new = '''                    onImportExtraction = viewModel::importScanLinksFromExtraction,
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
                    onAction = { mode ->'''
if scan_call_new not in s:
    if scan_call_old not in s:
        raise SystemExit("❌ WorkspaceScanScreen call marker missing")
    s = s.replace(scan_call_old, scan_call_new, 1)
write("app/src/main/java/com/althmany/extractor/MainActivity.kt", s)
print("✅ MainActivity Excel/file picker")

ui = read("app/src/main/java/com/althmany/extractor/ui/SmartWorkspaceScreens.kt")
sig_old = '''    onAddLinks: (String) -> Unit,
    onImportExtraction: () -> Unit,
    onAction: (ScanActionMode) -> Unit,'''
sig_new = '''    onAddLinks: (String) -> Unit,
    onImportExtraction: () -> Unit,
    onImportFile: () -> Unit,
    onAction: (ScanActionMode) -> Unit,'''
if sig_new not in ui:
    if sig_old not in ui:
        raise SystemExit("❌ WorkspaceScanScreen signature marker missing")
    ui = ui.replace(sig_old, sig_new, 1)

buttons_old = '''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAddLinks(text); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = WsGreen, contentColor = Color.Black)) { Icon(Icons.Default.Add, null); Text("إضافة للفحص") }
                        OutlinedButton(onClick = onImportExtraction, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, WsGreen)) { Icon(Icons.Default.Download, null, tint = WsGreen); Text("من الاستخراج", color = WsText) }
                    }'''
buttons_new = '''                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Button(
                                onClick = { onAddLinks(text); text = "" },
                                enabled = text.isNotBlank(),
                                modifier = Modifier.width(138.dp).height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = WsGreen, contentColor = Color.Black)
                            ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("الروابط الملصوقة", fontSize = 9.sp) }
                        }
                        item {
                            OutlinedButton(
                                onClick = onImportFile,
                                modifier = Modifier.width(138.dp).height(50.dp),
                                border = BorderStroke(1.dp, WsCyan)
                            ) { Icon(Icons.Default.TableChart, null, tint = WsCyan); Spacer(Modifier.width(4.dp)); Text("ملف Excel", color = WsText, fontSize = 9.sp) }
                        }
                        item {
                            OutlinedButton(
                                onClick = onImportExtraction,
                                modifier = Modifier.width(138.dp).height(50.dp),
                                border = BorderStroke(1.dp, WsGreen)
                            ) { Icon(Icons.Default.Download, null, tint = WsGreen); Spacer(Modifier.width(4.dp)); Text("من الاستخراج", color = WsText, fontSize = 9.sp) }
                        }
                    }'''
if buttons_new not in ui:
    if buttons_old not in ui:
        raise SystemExit("❌ Scan input buttons marker missing")
    ui = ui.replace(buttons_old, buttons_new, 1)
write("app/src/main/java/com/althmany/extractor/ui/SmartWorkspaceScreens.kt", ui)
print("✅ Scan UI queue sources")

classifier = read("app/src/main/java/com/althmany/extractor/engine/InviteScanClassifier.kt")
old = '''        Rule(ScanStatus.APPROVAL, "APPROVAL_REQUIRED", listOf(
            "طلب الانضمام", "إرسال طلب الانضمام", "طلب للانضمام", "request to join", "send request", "request to join group"
        ), "الانضمام يحتاج موافقة المشرف", 99),'''
new = '''        Rule(ScanStatus.APPROVAL, "APPROVAL_REQUIRED", listOf(
            "طلب الانضمام", "إرسال طلب الانضمام", "ارسال طلب الانضمام", "طلب للانضمام",
            "طلب الانضمام إلى القروب", "طلب الانضمام الى القروب", "طلب الانضمام للقروب",
            "اطلب الانضمام إلى المجموعة", "اطلب الانضمام الى المجموعة", "اطلب الانضمام للمجموعة",
            "اطلب الانضمام إلى المجتمع", "اطلب الانضمام الى المجتمع", "اطلب الانضمام للمجتمع",
            "طلب دخول", "request to join", "send request", "request to join group",
            "request membership", "request group access", "ask for access", "send request to join",
            "request to join community", "ask to join community", "request community access"
        ), "الانضمام يحتاج موافقة المشرف", 99),'''
if new not in classifier:
    if old not in classifier: raise SystemExit("❌ Classifier APPROVAL marker missing")
    classifier = classifier.replace(old, new, 1)

old = '''        Rule(ScanStatus.DIRECT, "DIRECT_JOIN", listOf(
            "الانضمام إلى المجموعة", "انضمام إلى المجموعة", "انضم إلى المجموعة", "join group", "join this group"
        ), "الرابط يتيح الانضمام المباشر", 99),'''
new = '''        Rule(ScanStatus.DIRECT, "DIRECT_JOIN", listOf(
            "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "الانضمام للمجموعة",
            "انضمام إلى المجموعة", "انضمام الى المجموعة", "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
            "الانضمام إلى القروب", "الانضمام الى القروب", "انضم إلى القروب", "انضم الى القروب", "انضم للقروب",
            "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
            "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
            "join group", "join the group", "join this group", "join group now",
            "join this chat", "join this group now",
            "join community", "join this community", "join the community",
            "join community now", "join this community now", "join community chat"
        ), "الرابط يتيح الانضمام المباشر", 99),'''
if new not in classifier:
    if old not in classifier: raise SystemExit("❌ Classifier DIRECT marker missing")
    classifier = classifier.replace(old, new, 1)
write("app/src/main/java/com/althmany/extractor/engine/InviteScanClassifier.kt", classifier)
print("✅ Invite classification semantics")

adapter = read("app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt")
anchor = '''    private val archivedPatterns = listOf("مؤرشفة", "المؤرشفة", "Archived")
'''
addition = '''    private val archivedPatterns = listOf("مؤرشفة", "المؤرشفة", "Archived")
    private val inviteJoinLabels = listOf(
        "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "الانضمام للمجموعة",
        "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
        "الانضمام إلى القروب", "الانضمام الى القروب", "انضم إلى القروب", "انضم الى القروب", "انضم للقروب",
        "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
        "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
        "Join group", "Join the group", "Join this group", "Join group now",
        "Join this chat", "Join this group now",
        "Join community", "Join this community", "Join the community",
        "Join community now", "Join this community now", "Join community chat"
    )
    private val inviteRequestLabels = listOf(
        "طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام الى المجموعة", "طلب الانضمام للمجموعة",
        "إرسال طلب الانضمام", "ارسال طلب الانضمام", "طلب للانضمام",
        "طلب الانضمام إلى القروب", "طلب الانضمام الى القروب", "طلب الانضمام للقروب",
        "اطلب الانضمام إلى المجموعة", "اطلب الانضمام الى المجموعة", "اطلب الانضمام للمجموعة",
        "اطلب الانضمام إلى المجتمع", "اطلب الانضمام الى المجتمع", "اطلب الانضمام للمجتمع",
        "Request to join", "Send request", "Request to join group",
        "Request membership", "Request group access", "Ask for access", "Send request to join",
        "Request to join community", "Ask to join community", "Request community access"
    )
    private val inviteConfirmationLabels = listOf(
        "متابعة", "تابع", "تأكيد", "تاكيد", "تأكيد الانضمام", "تاكيد الانضمام",
        "موافق", "نعم", "استمرار",
        "Continue", "Confirm", "Confirm join", "Continue to join", "Continue joining",
        "Proceed", "Yes", "OK", "Okay"
    )
    private val inviteCloseLabels = listOf("إغلاق", "اغلاق", "Close", "Dismiss")
    private val inviteJoinIds = listOf(
        "join_group", "group_join", "group_join_button", "join_group_button",
        "join_community", "community_join", "community_join_button", "join_community_button"
    )
    private val inviteRequestIds = listOf(
        "request_join", "request_to_join", "join_request", "send_join_request",
        "request_community", "community_request",
        "request_join_button", "request_to_join_button", "join_request_button",
        "send_join_request_button", "request_community_button", "community_request_button"
    )
    private val inviteConfirmationIds = listOf(
        "confirm_button", "confirmation_button", "continue_button",
        "join_confirm_button", "positive_button"
    )
    private val inviteCloseIds = listOf("close", "close_button", "dismiss_button", "cancel_button")
'''
if "private val inviteConfirmationLabels" not in adapter:
    if anchor not in adapter: raise SystemExit("❌ Adapter label anchor missing")
    adapter = adapter.replace(anchor, addition, 1)

old = '''    fun inviteActionAvailable(root: AccessibilityNodeInfo?, approval: Boolean): Boolean {
        val labels = if (approval) {
            listOf("طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام للمجموعة", "إرسال طلب الانضمام", "طلب للانضمام", "Request to join", "Send request", "Request to join group")
        } else {
            listOf("الانضمام إلى المجموعة", "الانضمام للمجموعة", "انضمام إلى المجموعة", "انضمام للمجموعة", "انضم إلى المجموعة", "الانضمام إلى المجتمع", "انضمام إلى المجتمع", "Join group", "Join this group", "Join community")
        }
        val node = findVisibleNodeByPatterns(root, labels) ?: return false
        return hasClickableSelfOrAncestor(node)
    }

    fun clickInviteAction(root: AccessibilityNodeInfo?, approval: Boolean): Boolean {
        val labels = if (approval) {
            listOf("طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام للمجموعة", "إرسال طلب الانضمام", "طلب للانضمام", "Request to join", "Send request", "Request to join group")
        } else {
            listOf("الانضمام إلى المجموعة", "الانضمام للمجموعة", "انضمام إلى المجموعة", "انضمام للمجموعة", "انضم إلى المجموعة", "الانضمام إلى المجتمع", "انضمام إلى المجتمع", "Join group", "Join this group", "Join community")
        }
        val node = findVisibleNodeByPatterns(root, labels) ?: return false
        if (!hasClickableSelfOrAncestor(node)) return false
        return clickNodeOrParent(node)
    }
'''
new = '''    private fun findInviteActionNode(root: AccessibilityNodeInfo?, approval: Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val ids = if (approval) inviteRequestIds else inviteJoinIds
        findByKnownIds(root, ids)?.let { node ->
            if (node.isVisibleToUser && node.isEnabled && hasClickableSelfOrAncestor(node)) return node
        }
        val labels = if (approval) inviteRequestLabels else inviteJoinLabels
        return findVisibleNodeByPatterns(root, labels)
            ?.takeIf { it.isVisibleToUser && it.isEnabled && hasClickableSelfOrAncestor(it) }
    }

    fun inviteActionAvailable(root: AccessibilityNodeInfo?, approval: Boolean): Boolean =
        findInviteActionNode(root, approval) != null

    fun clickInviteAction(root: AccessibilityNodeInfo?, approval: Boolean): Boolean {
        val node = findInviteActionNode(root, approval) ?: return false
        return clickNodeOrParent(node)
    }

    private fun findInviteConfirmationNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        findByKnownIds(root, inviteConfirmationIds)?.let { node ->
            if (node.isVisibleToUser && node.isEnabled && hasClickableSelfOrAncestor(node)) return node
        }
        return findVisibleNodeByPatterns(root, inviteConfirmationLabels)
            ?.takeIf { it.isVisibleToUser && it.isEnabled && hasClickableSelfOrAncestor(it) }
    }

    fun inviteConfirmationAvailable(root: AccessibilityNodeInfo?): Boolean =
        findInviteConfirmationNode(root) != null

    fun clickInviteConfirmation(root: AccessibilityNodeInfo?): Boolean {
        val node = findInviteConfirmationNode(root) ?: return false
        return clickNodeOrParent(node)
    }

    fun clickInviteClose(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        findByKnownIds(root, inviteCloseIds)?.let { node ->
            if (node.isVisibleToUser && node.isEnabled && clickNodeOrParent(node)) return true
        }
        val node = findVisibleNodeByPatterns(root, inviteCloseLabels) ?: return false
        return node.isEnabled && hasClickableSelfOrAncestor(node) && clickNodeOrParent(node)
    }
'''
if new not in adapter:
    if old not in adapter: raise SystemExit("❌ Adapter invite block changed")
    adapter = adapter.replace(old, new, 1)
write("app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt", adapter)
print("✅ Accessibility membership actions")

bridge = read("app/src/main/java/com/althmany/extractor/shizuku/ShizukuBridge.kt")
if "data class ShellUiAction" not in bridge:
    bridge = bridge.replace(
        '''    data class ShellResult(val exitCode:Int,val output:String){val success:Boolean get()=exitCode==0}
''',
        '''    data class ShellResult(val exitCode:Int,val output:String){val success:Boolean get()=exitCode==0}
    data class ShellUiAction(val found:Boolean,val x:Int,val y:Int,val detail:String)
''',
        1
    )

helper_anchor = '''    suspend fun probe(context:Context,targetPackage:String?):String{'''
bridge_helpers = r'''    suspend fun isPackageForeground(context:Context,targetPackage:String):Boolean {
        if(!Regex("[A-Za-z0-9_.]+").matches(targetPackage))return false
        val r=execute(
            context,
            "dumpsys activity activities 2>/dev/null | grep -m 2 -E 'mResumedActivity|topResumedActivity'; " +
                "dumpsys window windows 2>/dev/null | grep -m 2 -E 'mCurrentFocus|mFocusedApp'",
            2500
        )
        return r.output.contains(targetPackage)
    }

    private fun xmlAttr(node:String,name:String):String =
        Regex("\\b${Regex.escape(name)}=\\\"([^\\\"]*)\\\"")
            .find(node)?.groupValues?.getOrNull(1).orEmpty()

    private fun decodeXmlAttr(value:String):String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
        .trim()

    suspend fun shellFindUiAction(
        context:Context,
        targetPackage:String,
        labels:List<String>,
        resourceIds:List<String>
    ):ShellUiAction = withContext(Dispatchers.IO) {
        if(!Regex("[A-Za-z0-9_.]+").matches(targetPackage))
            return@withContext ShellUiAction(false,0,0,"bad-package")
        if(!isPackageForeground(context,targetPackage))
            return@withContext ShellUiAction(false,0,0,"target-not-foreground")

        val tmp="/data/local/tmp/althmany_join_${Process.myUid()}.xml"
        val dump=execute(
            context,
            "rm -f '$tmp'; uiautomator dump --compressed '$tmp' >/dev/null 2>&1; cat '$tmp' 2>/dev/null; rm -f '$tmp'",
            4500
        )
        if(!dump.success || !dump.output.contains("<hierarchy"))
            return@withContext ShellUiAction(false,0,0,"shell-dump-failed:${dump.exitCode}")

        val wantedLabels=labels.map {
            it.lowercase().replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "").trim()
        }.filter(String::isNotBlank)
        val wantedIds=resourceIds.map(String::lowercase)
        val boundsRx=Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
        var best:ShellUiAction?=null

        Regex("<node\\b[^>]*>").findAll(dump.output).forEach { m ->
            val raw=m.value
            val text=decodeXmlAttr(xmlAttr(raw,"text"))
            val desc=decodeXmlAttr(xmlAttr(raw,"content-desc"))
            val id=decodeXmlAttr(xmlAttr(raw,"resource-id")).lowercase()
            val label="$text $desc".lowercase().trim()
            val semantic=wantedLabels.any { token -> label==token || label.contains(token) }
            val idHit=wantedIds.any { token -> id.contains(token) }
            if(!semantic && !idHit)return@forEach

            val b=boundsRx.find(xmlAttr(raw,"bounds"))?.groupValues ?: return@forEach
            val left=b.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val top=b.getOrNull(2)?.toIntOrNull() ?: return@forEach
            val right=b.getOrNull(3)?.toIntOrNull() ?: return@forEach
            val bottom=b.getOrNull(4)?.toIntOrNull() ?: return@forEach
            if(right<=left || bottom<=top)return@forEach
            val candidate=ShellUiAction(true,(left+right)/2,(top+bottom)/2,"shell-semantic")
            if(best==null || candidate.y > best!!.y)best=candidate
        }
        best ?: ShellUiAction(false,0,0,"shell-no-action")
    }

    suspend fun profileSafeTap(
        context:Context,targetPackage:String,x:Int,y:Int
    ):Boolean = withContext(Dispatchers.IO) {
        if(x<0 || y<0 || !Regex("[A-Za-z0-9_.]+").matches(targetPackage))
            return@withContext false
        if(!isPackageForeground(context,targetPackage))
            return@withContext false
        execute(context,"input tap $x $y",2500).success
    }

'''
if "suspend fun shellFindUiAction(" not in bridge:
    if helper_anchor not in bridge: raise SystemExit("❌ ShizukuBridge helper anchor missing")
    bridge = bridge.replace(helper_anchor, bridge_helpers + helper_anchor, 1)
write("app/src/main/java/com/althmany/extractor/shizuku/ShizukuBridge.kt", bridge)
print("✅ Shizuku semantic shell fallback")

sh = read("app/src/main/java/com/althmany/extractor/shizuku/ShizukuUiRuntime.kt")
sh_anchor = '''    private val unreadPatterns = listOf("رسائل غير مقروءة", "Unread messages", "Unread message")
'''
sh_add = '''    private val unreadPatterns = listOf("رسائل غير مقروءة", "Unread messages", "Unread message")
    private val inviteJoinLabels = listOf(
        "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "الانضمام للمجموعة",
        "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
        "الانضمام إلى القروب", "الانضمام الى القروب", "انضم إلى القروب", "انضم الى القروب", "انضم للقروب",
        "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
        "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
        "Join group", "Join the group", "Join this group", "Join group now",
        "Join this chat", "Join this group now",
        "Join community", "Join this community", "Join the community",
        "Join community now", "Join this community now", "Join community chat"
    )
    private val inviteRequestLabels = listOf(
        "طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام الى المجموعة", "طلب الانضمام للمجموعة",
        "إرسال طلب الانضمام", "ارسال طلب الانضمام", "طلب للانضمام",
        "Request to join", "Send request", "Request to join group",
        "Request membership", "Request group access", "Ask for access", "Send request to join",
        "Request to join community", "Ask to join community", "Request community access"
    )
    private val inviteConfirmationLabels = listOf(
        "متابعة", "تابع", "تأكيد", "تاكيد", "تأكيد الانضمام", "تاكيد الانضمام",
        "موافق", "نعم", "استمرار",
        "Continue", "Confirm", "Confirm join", "Continue to join", "Continue joining",
        "Proceed", "Yes", "OK", "Okay"
    )
    private val inviteCloseLabels = listOf("إغلاق", "اغلاق", "Close", "Dismiss")
    private val inviteJoinIds = listOf(
        "join_group", "group_join", "group_join_button", "join_group_button",
        "join_community", "community_join", "community_join_button", "join_community_button"
    )
    private val inviteRequestIds = listOf(
        "request_join", "request_to_join", "join_request", "send_join_request",
        "request_community", "community_request",
        "request_join_button", "request_to_join_button", "join_request_button",
        "send_join_request_button", "request_community_button", "community_request_button"
    )
    private val inviteConfirmationIds = listOf(
        "confirm_button", "confirmation_button", "continue_button",
        "join_confirm_button", "positive_button"
    )
    private val inviteCloseIds = listOf("close", "close_button", "dismiss_button", "cancel_button")
'''
if "private val inviteConfirmationLabels" not in sh:
    if sh_anchor not in sh: raise SystemExit("❌ Shizuku label anchor missing")
    sh = sh.replace(sh_anchor, sh_add, 1)

old = '''    suspend fun clickInviteAction(tree: ShizukuUiTree, packageName: String, approval: Boolean): Boolean = clickPattern(tree, packageName, if (approval) listOf("طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام للمجموعة", "إرسال طلب الانضمام", "Request to join", "Send request") else listOf("الانضمام إلى المجموعة", "الانضمام للمجموعة", "انضم إلى المجموعة", "الانضمام إلى المجتمع", "انضمام إلى المجتمع", "Join group", "Join this group", "Join community"), preferBottom = true)
    fun inviteActionAvailable(tree: ShizukuUiTree, approval: Boolean): Boolean = findPattern(tree, if (approval) listOf("طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام للمجموعة", "إرسال طلب الانضمام", "Request to join", "Send request") else listOf("الانضمام إلى المجموعة", "الانضمام للمجموعة", "انضم إلى المجموعة", "الانضمام إلى المجتمع", "انضمام إلى المجتمع", "Join group", "Join this group", "Join community")) != null
'''
new = '''    private fun findInviteAction(tree: ShizukuUiTree, approval: Boolean): ShizukuUiNode? {
        val labels = if (approval) inviteRequestLabels else inviteJoinLabels
        val ids = if (approval) inviteRequestIds else inviteJoinIds
        return tree.nodes.filter { it.enabled && (
            labels.any { p -> it.label.contains(p, true) } ||
                ids.any { id -> it.viewId.contains(id, true) }
        ) }.maxByOrNull { it.bounds.bottom }
    }

    suspend fun clickInviteAction(tree: ShizukuUiTree, packageName: String, approval: Boolean): Boolean {
        val node = findInviteAction(tree, approval) ?: return false
        return clickNode(tree, node, packageName)
    }

    fun inviteActionAvailable(tree: ShizukuUiTree, approval: Boolean): Boolean =
        findInviteAction(tree, approval) != null

    private fun findInviteConfirmation(tree: ShizukuUiTree): ShizukuUiNode? =
        tree.nodes.filter { it.enabled && (
            inviteConfirmationLabels.any { p -> it.label.contains(p, true) } ||
                inviteConfirmationIds.any { id -> it.viewId.contains(id, true) }
        ) }.maxByOrNull { it.bounds.bottom }

    fun inviteConfirmationAvailable(tree: ShizukuUiTree): Boolean =
        findInviteConfirmation(tree) != null

    suspend fun clickInviteConfirmation(tree: ShizukuUiTree, packageName: String): Boolean {
        val node = findInviteConfirmation(tree) ?: return false
        return clickNode(tree, node, packageName)
    }

    suspend fun clickInviteClose(tree: ShizukuUiTree, packageName: String): Boolean {
        val node = tree.nodes.filter { it.enabled && (
            inviteCloseLabels.any { p -> it.label.contains(p, true) } ||
                inviteCloseIds.any { id -> it.viewId.contains(id, true) }
        ) }.minByOrNull { it.bounds.top } ?: return false
        return clickNode(tree, node, packageName)
    }

    suspend fun shellClickInviteAction(packageName: String, approval: Boolean): Boolean {
        val labels = if (approval) inviteRequestLabels else inviteJoinLabels
        val ids = if (approval) inviteRequestIds else inviteJoinIds
        val action = ShizukuBridge.shellFindUiAction(context, packageName, labels, ids)
        return action.found && ShizukuBridge.profileSafeTap(context, packageName, action.x, action.y)
    }

    suspend fun shellClickInviteConfirmation(packageName: String): Boolean {
        val action = ShizukuBridge.shellFindUiAction(context, packageName, inviteConfirmationLabels, inviteConfirmationIds)
        return action.found && ShizukuBridge.profileSafeTap(context, packageName, action.x, action.y)
    }
'''
if new not in sh:
    if old not in sh: raise SystemExit("❌ Shizuku invite block changed")
    sh = sh.replace(old, new, 1)

old_click = '''    private suspend fun clickNode(tree: ShizukuUiTree, node: ShizukuUiNode, packageName: String): Boolean {
        val candidate = tree.ancestors(node).firstOrNull { it.clickable && it.enabled } ?: node
        if (ShizukuBridge.fastClickNode(context, packageName, candidate.centerX, candidate.centerY)) return true
        return ShizukuBridge.fastTap(context, candidate.centerX, candidate.centerY)
    }
'''
new_click = '''    private suspend fun clickNode(tree: ShizukuUiTree, node: ShizukuUiNode, packageName: String): Boolean {
        val candidate = tree.ancestors(node).firstOrNull { it.clickable && it.enabled } ?: node
        if (ShizukuBridge.fastClickNode(context, packageName, candidate.centerX, candidate.centerY)) return true
        if (ShizukuBridge.fastTap(context, candidate.centerX, candidate.centerY)) return true
        return ShizukuBridge.profileSafeTap(context, packageName, candidate.centerX, candidate.centerY)
    }
'''
if new_click not in sh:
    if old_click not in sh: raise SystemExit("❌ Shizuku clickNode marker missing")
    sh = sh.replace(old_click, new_click, 1)
write("app/src/main/java/com/althmany/extractor/shizuku/ShizukuUiRuntime.kt", sh)
print("✅ Shizuku membership actions")

scan = read("app/src/main/java/com/althmany/extractor/engine/ScanController.kt")
if "POST_ACTION_GRACE_MS" not in scan:
    marker = '''    @Volatile private var pauseRequested = false
'''
    add = '''    @Volatile private var pauseRequested = false
    private const val POST_ACTION_GRACE_MS = 650L
    private const val MAX_POSITIVE_FOLLOW_UPS = 2
'''
    if marker not in scan: raise SystemExit("❌ Scan constants marker missing")
    scan = scan.replace(marker, add, 1)

old = '''        val root = svc.currentRoot()
        if (!adapter.inviteActionAvailable(root, approval)) {
            return decision.copy(
                status = ScanStatus.ACTION_UNCERTAIN,
                detail = "الحالة واضحة لكن زر الإجراء لم يعد متاحًا عند التنفيذ؛ لن يعاد الضغط تلقائيًا",
                signalCode = "ACTION_BUTTON_GONE",
                definitive = true
            )
        }

        _state.value = _state.value.copy(
            status = ScanEngineStatus.CLASSIFYING,
            message = if (approval) "تنفيذ طلب الانضمام ثم التحقق" else "تنفيذ الانضمام ثم التحقق"
        )
        if (!adapter.clickInviteAction(root, approval)) {
            return decision.copy(
                status = ScanStatus.ACTION_UNCERTAIN,
                detail = "تعذر تأكيد ضغط زر الإجراء؛ لن يعاد الضغط تلقائيًا",
                signalCode = "ACTION_CLICK_UNCERTAIN",
                definitive = true
            )
        }

        val deadline = SystemClock.uptimeMillis() + when (speed) {'''
new = '''        val root = svc.currentRoot()
        _state.value = _state.value.copy(
            status = ScanEngineStatus.CLASSIFYING,
            message = if (approval) "طلب الانضمام • تنفيذ ثم متابعة نفس الرابط" else "الانضمام • تنفيذ ثم متابعة نفس الرابط"
        )
        if (!adapter.inviteActionAvailable(root, approval) || !adapter.clickInviteAction(root, approval)) {
            return decision.copy(
                status = ScanStatus.ACTION_UNCERTAIN,
                detail = "لم يتم العثور على زر الإجراء أو تعذر ضغطه عبر Accessibility",
                signalCode = "ACTION_SEMANTIC_CLICK_FAILED",
                definitive = true
            )
        }
        delay(POST_ACTION_GRACE_MS)

        val deadline = SystemClock.uptimeMillis() + when (speed) {'''
if new not in scan:
    if old not in scan: raise SystemExit("❌ Accessibility action block changed")
    scan = scan.replace(old, new, 1)

old = '''        var bestPost = decision
        while (SystemClock.uptimeMillis() < deadline) {
            waitIfPaused()
            val current = svc.currentRoot()
            if (current != null && adapter.isWhatsAppRoot(current, packageName)) {
                val post = InviteScanClassifier.classify(adapter.snapshot(current).texts)'''
new = '''        var bestPost = decision
        var positiveFollowUps = 0
        while (SystemClock.uptimeMillis() < deadline) {
            waitIfPaused()
            val current = svc.currentRoot()
            if (current != null && adapter.isWhatsAppRoot(current, packageName)) {
                if (positiveFollowUps < MAX_POSITIVE_FOLLOW_UPS &&
                    adapter.inviteConfirmationAvailable(current) &&
                    adapter.clickInviteConfirmation(current)
                ) {
                    positiveFollowUps++
                    _state.value = _state.value.copy(message = "Continue/Confirm على نفس الرابط • $positiveFollowUps/$MAX_POSITIVE_FOLLOW_UPS")
                    delay(POST_ACTION_GRACE_MS)
                    continue
                }
                val post = InviteScanClassifier.classify(adapter.snapshot(current).texts)'''
if new not in scan:
    if old not in scan: raise SystemExit("❌ post action marker missing")
    scan = scan.replace(old, new, 1)

old = '''        var tree=awaitShizukuTree(packageName,900L)?:return decision.copy(status=ScanStatus.ERROR,detail="Shizuku لا يرى واجهة الدعوة",signalCode="SHIZUKU_NO_UI",definitive=true)
        if(!shizukuUi.inviteActionAvailable(tree,approval))return decision.copy(status=ScanStatus.ACTION_UNCERTAIN,detail="الحالة واضحة لكن زر الإجراء غير متاح",signalCode="ACTION_BUTTON_GONE",definitive=true)
        _state.value=_state.value.copy(message=if(approval)"Shizuku: إرسال طلب الانضمام" else "Shizuku: تنفيذ الانضمام")
        if(!shizukuUi.clickInviteAction(tree,packageName,approval))return decision.copy(status=ScanStatus.ACTION_UNCERTAIN,detail="Shizuku لم يثبت ضغط زر الإجراء",signalCode="ACTION_CLICK_UNCERTAIN",definitive=true)
        val deadline=SystemClock.uptimeMillis()+when(speed){ScanSpeedProfile.HYPER->3500L;ScanSpeedProfile.ADAPTIVE->5000L;ScanSpeedProfile.SAFE->7000L}
        var best=decision
        while(SystemClock.uptimeMillis()<deadline){
            tree=awaitShizukuTree(packageName,600L)?:tree
            val post=InviteScanClassifier.classify(tree.texts);best=chooseBetter(best,post)
            if(!approval){val chat=decision.groupName?.let{shizukuUi.isConversationOpenForTarget(tree,it,packageName)}==true;if(post.status==ScanStatus.ALREADY_MEMBER||chat)return decision.copy(status=ScanStatus.JOINED,detail="تم الانضمام والتحقق عبر Shizuku",signalCode="JOIN_VERIFIED",confidence=100,definitive=true)}
            else if(post.status==ScanStatus.REQUEST_PENDING)return post.copy(detail="تم إرسال الطلب والتحقق عبر Shizuku",signalCode="REQUEST_VERIFIED",confidence=100,definitive=true,groupName=post.groupName?:decision.groupName,memberCountText=post.memberCountText?:decision.memberCountText,inviteKind=if(post.inviteKind==InviteKind.UNKNOWN)decision.inviteKind else post.inviteKind)
            if(post.status in setOf(ScanStatus.INVALID,ScanStatus.FULL,ScanStatus.REMOVED,ScanStatus.ACCOUNT_LIMIT))return post
            delay(speed.settleDelayMs)
        }'''
new = '''        var tree=awaitShizukuTree(packageName,900L)
        _state.value=_state.value.copy(message=if(approval)"Shizuku: طلب انضمام • UI ثم shell fallback" else "Shizuku: انضمام • UI ثم shell fallback")
        val firstAction = when {
            tree != null && shizukuUi.inviteActionAvailable(tree,approval) &&
                shizukuUi.clickInviteAction(tree,packageName,approval) -> true
            else -> shizukuUi.shellClickInviteAction(packageName,approval)
        }
        if(!firstAction)return decision.copy(
            status=ScanStatus.ACTION_UNCERTAIN,
            detail="لم ينجح UIAutomation أو shell semantic fallback",
            signalCode="SHIZUKU_ACTION_ALL_LANES_FAILED",
            definitive=true
        )
        delay(POST_ACTION_GRACE_MS)
        val deadline=SystemClock.uptimeMillis()+when(speed){ScanSpeedProfile.HYPER->3500L;ScanSpeedProfile.ADAPTIVE->5000L;ScanSpeedProfile.SAFE->7000L}
        var best=decision
        var positiveFollowUps=0
        while(SystemClock.uptimeMillis()<deadline){
            val fresh=awaitShizukuTree(packageName,600L)
            if(fresh!=null)tree=fresh
            val currentTree=tree
            if(currentTree==null){
                if(positiveFollowUps<1 && shizukuUi.shellClickInviteConfirmation(packageName)){
                    positiveFollowUps++
                    delay(POST_ACTION_GRACE_MS)
                    continue
                }
                delay(speed.settleDelayMs)
                continue
            }
            if(positiveFollowUps<MAX_POSITIVE_FOLLOW_UPS &&
                shizukuUi.inviteConfirmationAvailable(currentTree) &&
                shizukuUi.clickInviteConfirmation(currentTree,packageName)
            ){
                positiveFollowUps++
                _state.value=_state.value.copy(message="Shizuku: Continue/Confirm على نفس الرابط • $positiveFollowUps/$MAX_POSITIVE_FOLLOW_UPS")
                delay(POST_ACTION_GRACE_MS)
                continue
            }
            val post=InviteScanClassifier.classify(currentTree.texts);best=chooseBetter(best,post)
            if(!approval){val chat=decision.groupName?.let{shizukuUi.isConversationOpenForTarget(currentTree,it,packageName)}==true;if(post.status==ScanStatus.ALREADY_MEMBER||chat)return decision.copy(status=ScanStatus.JOINED,detail="تم الانضمام والتحقق عبر Shizuku",signalCode="JOIN_VERIFIED",confidence=100,definitive=true)}
            else if(post.status==ScanStatus.REQUEST_PENDING)return post.copy(detail="تم إرسال الطلب والتحقق عبر Shizuku",signalCode="REQUEST_VERIFIED",confidence=100,definitive=true,groupName=post.groupName?:decision.groupName,memberCountText=post.memberCountText?:decision.memberCountText,inviteKind=if(post.inviteKind==InviteKind.UNKNOWN)decision.inviteKind else post.inviteKind)
            if(post.status in setOf(ScanStatus.INVALID,ScanStatus.FULL,ScanStatus.REMOVED,ScanStatus.ACCOUNT_LIMIT))return post
            delay(speed.settleDelayMs)
        }'''
if new not in scan:
    if old not in scan: raise SystemExit("❌ Shizuku action block changed")
    scan = scan.replace(old, new, 1)

old = '''    private suspend fun safelyReturnFromInvite(speed: ScanSpeedProfile) {
        if (shizukuMode) {
            repeat(2) { ShizukuBridge.fastBack(appContext); delay(speed.settleDelayMs + 25L) }
            return
        }
        val svc = service ?: return
        repeat(2) {
            val root = svc.currentRoot() ?: return@repeat
            val pkg = root.packageName?.toString()
            if (!WhatsAppInstanceRegistry.isSupportedPackage(pkg)) return
            svc.performBack()
            delay(speed.settleDelayMs + 25L)
        }
    }'''
new = '''    private suspend fun safelyReturnFromInvite(speed: ScanSpeedProfile) {
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage ?: return
        if (shizukuMode) {
            val tree = awaitShizukuTree(packageName, 350L)
            if (tree != null && shizukuUi.clickInviteClose(tree, packageName)) {
                delay(maxOf(speed.settleDelayMs, 18L))
                return
            }
            ShizukuBridge.fastBack(appContext)
            delay(maxOf(speed.settleDelayMs, 18L))
            val after = awaitShizukuTree(packageName, 350L)
            if (after == null || !shizukuUi.isWhatsApp(after, packageName)) {
                ShizukuBridge.launchPackage(appContext, packageName)
            }
            return
        }
        val svc = service ?: recoverLiveService() ?: return
        val root = svc.currentRoot()
        if (root != null && adapter.isWhatsAppRoot(root, packageName) && adapter.clickInviteClose(root)) {
            withTimeoutOrNull(speed.eventWaitMs) { uiEvents.first() }
            delay(maxOf(speed.settleDelayMs, 18L))
            return
        }
        svc.performBack()
        withTimeoutOrNull(speed.eventWaitMs) { uiEvents.first() }
        delay(maxOf(speed.settleDelayMs, 18L))
        val after = svc.currentRoot()
        if (after == null || !adapter.isWhatsAppRoot(after, packageName)) {
            ExtractionController.openWhatsApp()
        }
    }'''
if new not in scan:
    if old not in scan: raise SystemExit("❌ safelyReturnFromInvite marker missing")
    scan = scan.replace(old, new, 1)

scan = scan.replace(
'''                safelyReturnFromInvite(speed)
                delay(speed.settleDelayMs)
            }''',
'''                safelyReturnFromInvite(speed)
                // Event-first: انتقل للرابط التالي بدون Delay صناعي ثانٍ.
            }''',
1
)
write("app/src/main/java/com/althmany/extractor/engine/ScanController.kt", scan)
print("✅ Same-link join continuity + smart next")

router = read("app/src/main/java/com/althmany/extractor/engine/GroupAccessRouter.kt")
if "Search is a last-resort compatibility fallback" not in router:
    router = router.replace(
'''        priority += GroupAccessMethod.VISIBLE_LIST
        priority += GroupAccessMethod.SCROLL_MATCH
        if (allowSearchFallback) priority += GroupAccessMethod.SEARCH_FALLBACK
''',
'''        priority += GroupAccessMethod.VISIBLE_LIST
        priority += GroupAccessMethod.SCROLL_MATCH
        // Search is a last-resort compatibility fallback only after memory/list/scroll routes fail.
        if (allowSearchFallback) priority += GroupAccessMethod.SEARCH_FALLBACK
''',
1)
write("app/src/main/java/com/althmany/extractor/engine/GroupAccessRouter.kt", router)
print("✅ Extraction Search LAST contract")

check = r'''#!/usr/bin/env python3
from pathlib import Path
R=Path(__file__).resolve().parents[1]
def t(p): return (R/p).read_text(encoding="utf-8")
router=t("app/src/main/java/com/althmany/extractor/engine/GroupAccessRouter.kt")
checks={
"version 2.21.0": 'versionName = "2.21.0"' in t("app/build.gradle.kts"),
"xlsx importer": "object ScanInputImporter" in t("app/src/main/java/com/althmany/extractor/engine/ScanInputImporter.kt"),
"same queue import": "repo.addScanLinksFromText(links.joinToString" in t("app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt"),
"file picker": "val openScanFile" in t("app/src/main/java/com/althmany/extractor/MainActivity.kt"),
"excel ui": 'Text("ملف Excel"' in t("app/src/main/java/com/althmany/extractor/ui/SmartWorkspaceScreens.kt"),
"community semantics": '"Join this community now"' in t("app/src/main/java/com/althmany/extractor/engine/InviteScanClassifier.kt"),
"resource id join": '"join_group_button"' in t("app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt"),
"continue confirm": "inviteConfirmationAvailable" in t("app/src/main/java/com/althmany/extractor/engine/ScanController.kt"),
"same link bound": "MAX_POSITIVE_FOLLOW_UPS" in t("app/src/main/java/com/althmany/extractor/engine/ScanController.kt"),
"smart X/back": "clickInviteClose" in t("app/src/main/java/com/althmany/extractor/engine/ScanController.kt"),
"shizuku shell semantic": "shellFindUiAction" in t("app/src/main/java/com/althmany/extractor/shizuku/ShizukuBridge.kt") and "shellClickInviteAction" in t("app/src/main/java/com/althmany/extractor/engine/ScanController.kt"),
"foreground guard": "isPackageForeground" in t("app/src/main/java/com/althmany/extractor/shizuku/ShizukuBridge.kt"),
"visible before search": router.find("priority += GroupAccessMethod.VISIBLE_LIST") < router.find("priority += GroupAccessMethod.SEARCH_FALLBACK"),
"scroll before search": router.find("priority += GroupAccessMethod.SCROLL_MATCH") < router.find("priority += GroupAccessMethod.SEARCH_FALLBACK"),
"extract URL surfaces": "collectVisibleUrls" in t("app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt"),
"extract older scroll": "scrollToOlderMessages" in t("app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt"),
}
bad=[]
for k,v in checks.items():
    print(("PASS" if v else "FAIL")+": "+k)
    if not v: bad.append(k)
if bad: raise SystemExit("CONTROLLER RUNTIME CHECK FAILED: "+", ".join(bad))
print("\nAL-thmany 2.21.0 Controller Runtime source checks: PASS")
'''
write("scripts/controller_runtime_2_21_0_check.py", check)
p("scripts/controller_runtime_2_21_0_check.py").chmod(0o755)

subprocess.run([sys.executable, "scripts/controller_runtime_2_21_0_check.py"], cwd=ROOT, check=True)
subprocess.run([sys.executable, "scripts/validate_release.py"], cwd=ROOT, check=True)

print("\n✅ AL-thmany v2.21.0 Controller Runtime applied")
print("Backup:", backup)
print('git add .')
print('git commit -m "AL-thmany v2.21.0 Controller Runtime"')
print("git fetch origin main && git rebase origin/main && git push origin main")
