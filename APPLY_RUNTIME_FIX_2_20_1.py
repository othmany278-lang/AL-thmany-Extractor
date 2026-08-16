#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# AL-thmany Extractor v2.20.1 Root Runtime Fix
# Run from repository root: python3 APPLY_RUNTIME_FIX_2_20_1.py

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path.cwd().resolve()
if not (ROOT / "app/build.gradle.kts").exists():
    raise SystemExit("❌ شغّل الملف من جذر مستودع AL-thmany-Extractor")

STAMP = time.strftime("%Y%m%d-%H%M%S")
BACKUP = Path(tempfile.gettempdir()) / f"althmany-v2201-backup-{STAMP}"
BACKUP.mkdir(parents=True, exist_ok=True)

FILES = [
    "app/build.gradle.kts",
    ".github/workflows/build-apk.yml",
    "scripts/validate_release.py",
    "app/src/main/java/com/althmany/extractor/engine/RuntimeOperationCoordinator.kt",
    "app/src/test/java/com/althmany/extractor/engine/RuntimeOperationCoordinatorTest.kt",
    "app/src/main/java/com/althmany/extractor/engine/ScanController.kt",
    "app/src/main/java/com/althmany/extractor/engine/PublishController.kt",
    "app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt",
    "app/src/main/java/com/althmany/extractor/profile/NativeProfileEngineRouter.kt",
    "app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt",
    "app/src/main/java/com/althmany/extractor/MainActivity.kt",
    "app/src/main/java/com/althmany/extractor/ui/SmartWorkspaceScreens.kt",
]
for rel in FILES:
    src = ROOT / rel
    if not src.exists():
        raise SystemExit(f"❌ ملف مطلوب غير موجود: {rel}")
    dst = BACKUP / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, s):
    (ROOT / rel).write_text(s, encoding="utf-8")

def replace_once(rel, old, new, label):
    s = read(rel)
    if new in s:
        print(f"ℹ️ {label}: مطبق سابقًا")
        return
    if old not in s:
        raise SystemExit(f"❌ لم أجد موضع التعديل: {label}\nالنسخة تختلف عن main الذي فُحص.")
    write(rel, s.replace(old, new, 1))
    print(f"✅ {label}")

def replace_all_text(rel, replacements, label):
    s = read(rel)
    original = s
    for a, b in replacements:
        s = s.replace(a, b)
    if s != original:
        write(rel, s)
        print(f"✅ {label}")
    else:
        print(f"ℹ️ {label}: لا تغيير/مطبق سابقًا")

# 1) Version/workflow/validator
replace_all_text("app/build.gradle.kts", [
    ('versionCode = 2200', 'versionCode = 2201'),
    ('versionName = "2.20.0"', 'versionName = "2.20.1"'),
], "رفع الإصدار إلى 2.20.1")

replace_all_text(".github/workflows/build-apk.yml", [
    ("FinalRuntimeFix", "RuntimeFix"),
    ("2.20.0", "2.20.1"),
], "تحديث GitHub Actions")

replace_all_text("scripts/validate_release.py", [
    ("'versionCode 2200': 'versionCode = 2200' in app_build",
     "'versionCode 2201': 'versionCode = 2201' in app_build"),
    ("'versionName 2.20.0': 'versionName = \"2.20.0\"' in app_build",
     "'versionName 2.20.1': 'versionName = \"2.20.1\"' in app_build"),
    ("'Workflow artifact v2.20.0': 'FinalRuntimeFix-2.20.0' in workflow",
     "'Workflow artifact v2.20.1': 'RuntimeFix-2.20.1' in workflow"),
    ("AL-thmany Extractor 2.20.0 Final Runtime Fix source contract: PASS",
     "AL-thmany Extractor 2.20.1 Runtime Fix source contract: PASS"),
], "تحديث Source Contract")

# 2) Strict single UI owner
replace_once(
    "app/src/main/java/com/althmany/extractor/engine/RuntimeOperationCoordinator.kt",
'''    fun tryAcquire(operation: RuntimeOperation): Boolean {
        val current = owner.get()
        return current == operation || owner.compareAndSet(null, operation)
    }''',
'''    fun tryAcquire(operation: RuntimeOperation): Boolean {
        // Strict single-owner gate. Sync and Extraction both use EXTRACTION,
        // so the same enum may not re-enter while it already owns WhatsApp UI.
        return owner.compareAndSet(null, operation)
    }''',
    "منع Sync وExtraction من التداخل"
)

test_rel = "app/src/test/java/com/althmany/extractor/engine/RuntimeOperationCoordinatorTest.kt"
test_s = read(test_rel)
if "sameOperationCannotReenter" not in test_s:
    marker = "    @Test fun releaseAllowsNextEngine() {"
    addition = '''    @Test fun sameOperationCannotReenter() {
        assertTrue(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION))
        assertFalse(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION))
        assertEquals(RuntimeOperation.EXTRACTION, RuntimeOperationCoordinator.current())
    }

'''
    if marker not in test_s:
        raise SystemExit("❌ تعذر إضافة اختبار strict owner")
    write(test_rel, test_s.replace(marker, addition + marker, 1))
    print("✅ اختبار strict owner")

# 3) Scan runtime preflight
replace_once(
    "app/src/main/java/com/althmany/extractor/engine/ScanController.kt",
'''    private suspend fun ensureRuntimeReady(timeoutMs: Long = 5_000L): Boolean {
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage ?: return false
        var opened = ExtractionController.openWhatsApp()
        if (!opened && ShizukuBridge.status().ready) opened = ShizukuBridge.launchPackage(appContext, packageName)
        if (!opened) return false
        recoverLiveService()?.let { shizukuMode = false; return true }
        val accessDeadline = SystemClock.elapsedRealtime() + minOf(timeoutMs, 1_200L)
        while (SystemClock.elapsedRealtime() < accessDeadline) {
            delay(75L); recoverLiveService()?.let { shizukuMode = false; return true }
        }
        if (ShizukuBridge.status().ready && ShizukuBridge.ensureBound(appContext)) {
            val deadline = SystemClock.elapsedRealtime() + (timeoutMs - 1_200L).coerceAtLeast(1_500L)
            while (SystemClock.elapsedRealtime() < deadline) {
                val tree = shizukuUi.snapshot(packageName)
                if (tree.state == "OK" && shizukuUi.isWhatsApp(tree, packageName)) { shizukuMode = true; return true }
                delay(90L)
            }
        }
        shizukuMode = false
        return false
    }''',
'''    private suspend fun ensureRuntimeReady(timeoutMs: Long = 8_500L): Boolean {
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage ?: run {
            _state.value = _state.value.copy(message = "RUNTIME_NO_TARGET: اختر نسخة واتساب أولاً")
            return false
        }
        var opened = ExtractionController.openWhatsApp()
        if (!opened && ShizukuBridge.status().ready) {
            opened = ShizukuBridge.launchPackage(appContext, packageName)
        }
        if (!opened) {
            _state.value = _state.value.copy(message = "RUNTIME_OPEN_FAILED: تعذر فتح نسخة واتساب المحددة")
            return false
        }

        val accessDeadline = SystemClock.elapsedRealtime() + minOf(timeoutMs, 1_800L)
        while (SystemClock.elapsedRealtime() < accessDeadline) {
            val live = recoverLiveService()
            if (live != null && adapter.isWhatsAppRoot(live.currentRoot(), packageName)) {
                shizukuMode = false
                _state.value = _state.value.copy(message = "READY_ACCESSIBILITY: واتساب ظاهر لخدمة Accessibility")
                return true
            }
            withTimeoutOrNull(150L) { uiEvents.first() }
            delay(20L)
        }

        val sh = ShizukuBridge.status()
        if (sh.ready) {
            if (!ShizukuBridge.ensureBound(appContext, 4_500L)) {
                _state.value = _state.value.copy(message = "SHIZUKU_BIND_FAILED: الإذن موجود لكن UserService لم يرتبط")
            } else {
                ShizukuBridge.launchPackage(appContext, packageName)
                var lastDetail = "NO_SNAPSHOT"
                var resetTried = false
                val deadline = SystemClock.elapsedRealtime() + (timeoutMs - 1_800L).coerceAtLeast(5_000L)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val tree = shizukuUi.snapshot(packageName)
                    lastDetail = "${tree.state}:${tree.detail.take(140)}"
                    if (tree.state == "OK" && tree.nodes.isNotEmpty() && shizukuUi.isWhatsApp(tree, packageName)) {
                        shizukuMode = true
                        _state.value = _state.value.copy(message = "READY_SHIZUKU: UIAutomation يرى واتساب (${tree.nodes.size} node)")
                        return true
                    }
                    if (!resetTried && tree.state in setOf("UNAVAILABLE", "ERROR", "NO_ROOT")) {
                        resetTried = true
                        ShizukuBridge.reset(appContext)
                        ShizukuBridge.launchPackage(appContext, packageName)
                    }
                    delay(110L)
                }
                _state.value = _state.value.copy(message = "SHIZUKU_UI_NOT_READY: $lastDetail")
            }
        } else {
            _state.value = _state.value.copy(
                message = when {
                    !sh.binderAlive -> "SHIZUKU_BINDER_OFF: شغّل Shizuku"
                    !sh.permissionGranted -> "SHIZUKU_PERMISSION_DENIED: امنح التطبيق إذن Shizuku"
                    else -> "RUNTIME_NO_BACKEND: لا يوجد محرك تحكم جاهز"
                }
            )
        }
        shizukuMode = false
        return false
    }''',
    "Preflight الفحص الحقيقي"
)

replace_once(
    "app/src/main/java/com/althmany/extractor/engine/ScanController.kt",
'''            if (!ensureRuntimeReady()) {
                _state.value = _state.value.copy(
                    status = ScanEngineStatus.ERROR,
                    running = false,
                    message = "تعذر فتح واتساب أو توصيل Accessibility داخل نفس البيئة"
                )
                return
            }
            repository.resetScanRunningItems()
            val configuredScope = _state.value.scope''',
'''            repository.resetScanRunningItems()
            val configuredScope = _state.value.scope
            val preflightScope = if (configuredScope == ScanScope.RECHECK_ALL) ScanScope.PENDING_ONLY else configuredScope
            if (configuredScope != ScanScope.RECHECK_ALL && repository.scanItemsForScope(preflightScope).isEmpty()) {
                _state.value = _state.value.copy(
                    status = ScanEngineStatus.COMPLETED,
                    running = false,
                    message = "NO_SCAN_ITEMS: أضف روابط للفحص أولاً"
                )
                refreshStats()
                return
            }
            if (!ensureRuntimeReady()) {
                val detail = _state.value.message
                _state.value = _state.value.copy(
                    status = ScanEngineStatus.ERROR,
                    running = false,
                    message = if (detail.isBlank()) "RUNTIME_NOT_READY: تعذر تجهيز محرك الفحص" else detail
                )
                return
            }''',
    "فحص Queue قبل فتح واتساب"
)

replace_all_text("app/src/main/java/com/althmany/extractor/engine/ScanController.kt", [
    ('adapter.isGroupVisible(current, it, packageName)', 'adapter.isConversationOpenForTarget(current, it, packageName)'),
    ('shizukuUi.isGroupVisible(tree,it,packageName)', 'shizukuUi.isConversationOpenForTarget(tree,it,packageName)'),
    ('if (tree.state == "OK" && shizukuUi.isWhatsApp(tree, packageName)) return tree',
     'if (tree.state == "OK" && tree.nodes.isNotEmpty() && shizukuUi.isWhatsApp(tree, packageName)) return tree'),
], "تقوية تحقق الفحص/الانضمام")

# 4) Publish runtime preflight
replace_once(
    "app/src/main/java/com/althmany/extractor/engine/PublishController.kt",
'''    private suspend fun ensureRuntimeReady(timeoutMs: Long = 5_000L): Boolean {
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage ?: return false
        var opened = ExtractionController.openWhatsApp()
        if (!opened && ShizukuBridge.status().ready) {
            opened = ShizukuBridge.launchPackage(appContext, packageName)
        }
        if (!opened) return false
        val accessDeadline = SystemClock.elapsedRealtime() + minOf(timeoutMs, 3_000L)
        while (SystemClock.elapsedRealtime() < accessDeadline) {
            val live = recoverLiveService()
            if (live != null && adapter.isWhatsAppRoot(live.currentRoot(), packageName)) {
                shizukuMode = false
                return true
            }
            withTimeoutOrNull(180L) { uiEvents.first() }
            delay(20L)
        }
        if (ShizukuBridge.status().ready && ShizukuBridge.ensureBound(appContext)) {
            val deadline = SystemClock.elapsedRealtime() + (timeoutMs - 3_000L).coerceAtLeast(1_500L)
            while (SystemClock.elapsedRealtime() < deadline) {
                val tree = shizukuUi.snapshot(packageName)
                if (tree.state == "OK" && shizukuUi.isWhatsApp(tree, packageName)) { shizukuMode = true; return true }
                delay(90L)
            }
        }
        shizukuMode = false
        return false
    }''',
'''    private suspend fun ensureRuntimeReady(timeoutMs: Long = 8_500L): Boolean {
        val packageName = ExtractionController.state.value.selectedWhatsAppPackage ?: run {
            _state.value = _state.value.copy(info = "RUNTIME_NO_TARGET: اختر نسخة واتساب أولاً")
            return false
        }
        var opened = ExtractionController.openWhatsApp()
        if (!opened && ShizukuBridge.status().ready) {
            opened = ShizukuBridge.launchPackage(appContext, packageName)
        }
        if (!opened) {
            _state.value = _state.value.copy(info = "RUNTIME_OPEN_FAILED: تعذر فتح نسخة واتساب المحددة")
            return false
        }

        val accessDeadline = SystemClock.elapsedRealtime() + minOf(timeoutMs, 1_800L)
        while (SystemClock.elapsedRealtime() < accessDeadline) {
            val live = recoverLiveService()
            if (live != null && adapter.isWhatsAppRoot(live.currentRoot(), packageName)) {
                shizukuMode = false
                _state.value = _state.value.copy(info = "READY_ACCESSIBILITY: محرك النشر يرى واتساب")
                return true
            }
            withTimeoutOrNull(150L) { uiEvents.first() }
            delay(20L)
        }

        val sh = ShizukuBridge.status()
        if (sh.ready) {
            if (!ShizukuBridge.ensureBound(appContext, 4_500L)) {
                _state.value = _state.value.copy(info = "SHIZUKU_BIND_FAILED: الإذن موجود لكن UserService لم يرتبط")
            } else {
                ShizukuBridge.launchPackage(appContext, packageName)
                var lastDetail = "NO_SNAPSHOT"
                var resetTried = false
                val deadline = SystemClock.elapsedRealtime() + (timeoutMs - 1_800L).coerceAtLeast(5_000L)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val tree = shizukuUi.snapshot(packageName)
                    lastDetail = "${tree.state}:${tree.detail.take(140)}"
                    if (tree.state == "OK" && tree.nodes.isNotEmpty() && shizukuUi.isWhatsApp(tree, packageName)) {
                        shizukuMode = true
                        _state.value = _state.value.copy(info = "READY_SHIZUKU: UIAutomation يرى واتساب (${tree.nodes.size} node)")
                        return true
                    }
                    if (!resetTried && tree.state in setOf("UNAVAILABLE", "ERROR", "NO_ROOT")) {
                        resetTried = true
                        ShizukuBridge.reset(appContext)
                        ShizukuBridge.launchPackage(appContext, packageName)
                    }
                    delay(110L)
                }
                _state.value = _state.value.copy(info = "SHIZUKU_UI_NOT_READY: $lastDetail")
            }
        } else {
            _state.value = _state.value.copy(
                info = when {
                    !sh.binderAlive -> "SHIZUKU_BINDER_OFF: شغّل Shizuku أو Accessibility"
                    !sh.permissionGranted -> "SHIZUKU_PERMISSION_DENIED: امنح التطبيق إذن Shizuku"
                    else -> "RUNTIME_NO_BACKEND: لا يوجد محرك تحكم جاهز"
                }
            )
        }
        shizukuMode = false
        return false
    }''',
    "Preflight النشر الحقيقي"
)

replace_once(
    "app/src/main/java/com/althmany/extractor/engine/PublishController.kt",
'''            _state.value = _state.value.copy(status = PublishEngineStatus.PREPARING, running = true, info = "Preflight: التحقق من واتساب ومحرك الإرسال")
            if (!ensureRuntimeReady()) {
                _state.value = _state.value.copy(status = PublishEngineStatus.ERROR, running = false, info = "فشل Preflight: واتساب/Accessibility غير جاهز في نفس البيئة")
                return@launch
            }
            val selectedGroups = repository.selectedGroups().filter {''',
'''            _state.value = _state.value.copy(status = PublishEngineStatus.PREPARING, running = true, info = "Preflight: التحقق من القروبات ثم محرك الإرسال")
            val selectedGroups = repository.selectedGroups().filter {''',
    "إزالة Preflight النشر المكرر"
)

replace_once(
    "app/src/main/java/com/althmany/extractor/engine/PublishController.kt",
'''            if (!ensureRuntimeReady()) {
                _state.value = _state.value.copy(
                    status = PublishEngineStatus.ERROR,
                    running = false,
                    paused = false,
                    info = "تعذر فتح واتساب أو توصيل Accessibility داخل نفس البيئة"
                )
                return
            }''',
'''            if (!ensureRuntimeReady()) {
                val detail = _state.value.info
                _state.value = _state.value.copy(
                    status = PublishEngineStatus.ERROR,
                    running = false,
                    paused = false,
                    info = if (detail.isBlank()) "RUNTIME_NOT_READY: تعذر تجهيز محرك النشر" else detail
                )
                return
            }''',
    "الاحتفاظ بسبب فشل النشر الحقيقي"
)

replace_all_text("app/src/main/java/com/althmany/extractor/engine/PublishController.kt", [
    ('adapter.isGroupVisible(svc.currentRoot(), item.groupName, run.targetPackage)',
     'adapter.isConversationOpenForTarget(svc.currentRoot(), item.groupName, run.targetPackage)'),
    ('adapter.isGroupVisible(service?.currentRoot(), item.groupName, run.targetPackage)',
     'adapter.isConversationOpenForTarget(service?.currentRoot(), item.groupName, run.targetPackage)'),
    ('if (group.verifiedGroup) return adapter.isGroupVisible(svc.currentRoot(), group.name, packageName)',
     'if (group.verifiedGroup) return adapter.isConversationOpenForTarget(svc.currentRoot(), group.name, packageName)'),
    ('waitUntil(3_000L) { adapter.isGroupVisible(service?.currentRoot(), group.name, packageName) }',
     'waitUntil(3_000L) { adapter.isConversationOpenForTarget(service?.currentRoot(), group.name, packageName) }'),
    ('return groupInfo && adapter.isGroupVisible(svc.currentRoot(), group.name, packageName)',
     'return groupInfo && adapter.isConversationOpenForTarget(svc.currentRoot(), group.name, packageName)'),
    ('shizukuUi.isGroupVisible(tree, item.groupName, run.targetPackage)',
     'shizukuUi.isConversationOpenForTarget(tree, item.groupName, run.targetPackage)'),
    ('if (group.verifiedGroup) return shizukuUi.isGroupVisible(initial, group.name, packageName)',
     'if (group.verifiedGroup) return shizukuUi.isConversationOpenForTarget(initial, group.name, packageName)'),
    ('val backInChat = shizukuUi.isGroupVisible(chatTree, group.name, packageName)',
     'val backInChat = shizukuUi.isConversationOpenForTarget(chatTree, group.name, packageName)'),
    ('shizukuUi.isGroupVisible(tree, group.name, packageName)',
     'shizukuUi.isConversationOpenForTarget(tree, group.name, packageName)'),
    ('shizukuUi.isGroupVisible(initial, group.name, packageName)',
     'shizukuUi.isConversationOpenForTarget(initial, group.name, packageName)'),
    ('if (tree.state == "OK" && shizukuUi.isWhatsApp(tree, packageName)) return tree',
     'if (tree.state == "OK" && tree.nodes.isNotEmpty() && shizukuUi.isWhatsApp(tree, packageName)) return tree'),
], "تقوية تحقق القروب في النشر")

# 5) Extraction backend must verify live root
replace_once(
    "app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt",
'''            val liveAccessibility = awaitRuntimeService(1_200L)
            if (liveAccessibility == null) {
                if (ShizukuBridge.status().ready) {
                    runExtractionViaShizuku(groups, prefs, targetPackage)
                    return
                }
                failRun("تم فتح واتساب لكن لا Accessibility محلية ولا Shizuku جاهز داخل نفس البيئة")
                return
            }
            awaitUiChange(ExtractionPolicy.timing(prefs.speed).groupOpenMs)''',
'''            val liveAccessibility = awaitRuntimeService(1_500L)
            val accessibilityReady = liveAccessibility != null &&
                awaitWhatsAppRoot(liveAccessibility, targetPackage, 1_800L)

            if (!accessibilityReady) {
                if (ShizukuBridge.status().ready) {
                    _state.value = _state.value.copy(message = "Accessibility لا ترى واتساب — التحويل الفعلي إلى Shizuku")
                    runExtractionViaShizuku(groups, prefs, targetPackage)
                    return
                }
                failRun(
                    if (liveAccessibility == null)
                        "RUNTIME_NO_BACKEND: لا Accessibility محلية ولا Shizuku جاهز"
                    else
                        "ACCESSIBILITY_ROOT_NOT_READY: الخدمة متصلة لكن rootInActiveWindow لا يرى واتساب المحدد"
                )
                return
            }
            awaitUiChange(ExtractionPolicy.timing(prefs.speed).groupOpenMs)''',
    "اختيار Backend الاستخراج بواسطة root حقيقي"
)

# 6) Shizuku row-by-row Group Info fallback
extract_rel = "app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt"
extract_s = read(extract_rel)
if "scanAndVerifyConversationListViaShizuku" not in extract_s:
    marker = "    private suspend fun syncGroupsViaShizuku(timing: TimingPolicy): Int {"
    helper = r'''    /**
     * Safe Shizuku fallback when WhatsApp hides the Groups filter.
     * Each visible row is opened and Group Info must be proven before persistence.
     * Search is never used.
     */
    private suspend fun scanAndVerifyConversationListViaShizuku(
        packageName: String,
        timing: TimingPolicy,
        found: MutableMap<String, GroupSyncCandidate>,
        initialTree: ShizukuUiTree,
        label: String
    ): ShizukuUiTree {
        var tree = initialTree
        val checked = hashSetOf<String>()
        var stable = 0
        var page = 0
        val deadline = SystemClock.elapsedRealtime() + 70_000L

        while (
            page++ < 180 &&
            SystemClock.elapsedRealtime() < deadline &&
            found.size < ExtractionPolicy.MAX_SYNC_ITEMS
        ) {
            if (syncCancelRequested) throw CancellationException("تم إيقاف مزامنة Shizuku")
            awaitIfPaused()

            if (!shizukuUi.isConversationListVisible(tree, packageName)) {
                if (shizukuUi.clickChatsTab(tree, packageName)) {
                    delay(maxOf(timing.searchOpenMs, 140L).coerceAtMost(420L))
                    tree = awaitShizukuTree(packageName, 1_000L) ?: tree
                }
            }

            val visible = shizukuUi.collectChatCandidates(tree, packageName).toList()
            val beforeFound = found.size

            for (candidate in visible) {
                val key = candidate.name.trim().lowercase()
                if (!checked.add(key)) continue
                if (!shizukuUi.openVisibleChat(tree, candidate.name, packageName)) continue

                delay(maxOf(timing.groupOpenMs, 180L).coerceAtMost(420L))
                tree = awaitShizukuTree(packageName, 1_000L) ?: tree
                if (!shizukuUi.isConversationOpenForTarget(tree, candidate.name, packageName)) {
                    shizukuUi.back()
                    delay(maxOf(timing.searchOpenMs, 130L).coerceAtMost(360L))
                    tree = awaitShizukuTree(packageName, 900L) ?: tree
                    continue
                }

                var isGroup = false
                if (shizukuUi.clickHeader(tree, candidate.name, packageName)) {
                    delay(maxOf(timing.groupOpenMs, 180L).coerceAtMost(420L))
                    val infoTree = awaitShizukuTree(packageName, 1_000L)
                    if (infoTree != null) {
                        tree = infoTree
                        isGroup = shizukuUi.isGroupInfo(tree)
                    }
                    shizukuUi.back()
                    delay(maxOf(timing.searchOpenMs, 130L).coerceAtMost(360L))
                    tree = awaitShizukuTree(packageName, 900L) ?: tree
                }

                if (isGroup) {
                    found[key] = candidate.copy(
                        whatsappPackage = packageName,
                        lastKnownAccessMethod = GroupAccessMethod.VISIBLE_LIST,
                        verifiedGroupHint = true
                    )
                    _state.value = _state.value.copy(
                        syncFound = found.size,
                        message = "Shizuku: $label • ${found.size} قروب مؤكّد"
                    )
                }

                if (!shizukuUi.isConversationListVisible(tree, packageName)) {
                    shizukuUi.back()
                    delay(maxOf(timing.searchOpenMs, 130L).coerceAtMost(360L))
                    tree = awaitShizukuTree(packageName, 900L) ?: tree
                }
            }

            val beforeSignature = tree.signature
            val moved = shizukuUi.swipeListForward(tree, timing.gestureDurationMs.toInt())
            delay(maxOf(timing.eventQuietMs, 70L).coerceAtMost(180L))
            val next = awaitShizukuTree(packageName, 800L) ?: tree
            stable = if (!moved || next.signature == beforeSignature ||
                (visible.isEmpty() && found.size == beforeFound)) stable + 1 else 0
            tree = next
            if (stable >= 3) break
        }

        repository.log(
            null,
            "INFO",
            "sync-shizuku-verified-fallback",
            "$label checked=${checked.size} groups=${found.size} pages=$page"
        )
        return tree
    }

'''
    if marker not in extract_s:
        raise SystemExit("❌ تعذر إدراج Shizuku fallback")
    extract_s = extract_s.replace(marker, helper + marker, 1)
    write(extract_rel, extract_s)
    print("✅ Shizuku Group Info fallback")

replace_once(
    extract_rel,
'''        if (!filterClicked) {
            throw IllegalStateException("Shizuku لم يجد فلتر المجموعات؛ أوقفت المزامنة لحماية قاعدة GroupRecord")
        }

        val found = linkedMapOf<String, GroupSyncCandidate>()''',
'''        val found = linkedMapOf<String, GroupSyncCandidate>()

        if (!filterClicked) {
            repository.log(
                null,
                "WARN",
                "sync-shizuku-groups-filter-fallback",
                "فلتر Groups غير مكشوف؛ إثبات كل صف عبر Group Info بدون Search"
            )
            tree = scanAndVerifyConversationListViaShizuku(
                packageName, timing, found, tree, "الدردشات"
            )
        }''',
    "عدم إيقاف Shizuku sync عند غياب Groups"
)

replace_once(
    extract_rel,
'''        scanCurrentList("الدردشات")''',
'''        if (filterClicked) {
            scanCurrentList("الدردشات")
        }''',
    "منع حفظ private chats في Shizuku fallback"
)

replace_once(
    extract_rel,
'''                if (archiveFilter) scanCurrentList("المؤرشفة")

                shizukuUi.back()''',
'''                if (archiveFilter) {
                    scanCurrentList("المؤرشفة")
                } else {
                    tree = scanAndVerifyConversationListViaShizuku(
                        packageName, timing, found, tree, "المؤرشفة"
                    )
                }

                shizukuUi.back()''',
    "Shizuku archived fallback"
)

# Long chat lists: give Archived enough backward passes after synchronization.
replace_all_text(extract_rel, [
    ("for (archivePass in 0 until 20) {", "for (archivePass in 0 until 80) {"),
], "توسيع البحث عن المؤرشفة في القوائم الطويلة")

# 7) Scan UI: pasted input starts immediately
ui_rel = "app/src/main/java/com/althmany/extractor/ui/SmartWorkspaceScreens.kt"
ui = read(ui_rel)
a = ui.find("fun WorkspaceScanScreen(")
b = ui.find("@Composable\nfun WorkspacePublishScreen(", a)
if a < 0 or b < 0:
    raise SystemExit("❌ WorkspaceScanScreen غير موجود")
sec = ui[a:b]
if "onStart: (String) -> Unit," not in sec:
    if "onStart: () -> Unit," not in sec:
        raise SystemExit("❌ Scan onStart signature مختلف")
    sec = sec.replace("onStart: () -> Unit,", "onStart: (String) -> Unit,", 1)

old = 'Surface(modifier = Modifier.fillMaxWidth().height(56.dp).clickable(enabled = items.isNotEmpty() && engine.selectedWhatsAppPackage != null, onClick = onStart),'
new = '''Surface(modifier = Modifier.fillMaxWidth().height(56.dp).clickable(
                enabled = (items.isNotEmpty() || text.isNotBlank()) && engine.selectedWhatsAppPackage != null,
                onClick = {
                    val pending = text
                    text = ""
                    onStart(pending)
                }
            ),'''
if old in sec:
    sec = sec.replace(old, new, 1)
elif new not in sec:
    raise SystemExit("❌ زر Scan Start مختلف")

old = 'item { WsGlobalControls(running, paused, items.isNotEmpty() && engine.selectedWhatsAppPackage != null, onStart, onPause, onResume, onStopAll) }'
new = '''item {
            WsGlobalControls(
                running,
                paused,
                (items.isNotEmpty() || text.isNotBlank()) && engine.selectedWhatsAppPackage != null,
                {
                    val pending = text
                    text = ""
                    onStart(pending)
                },
                onPause,
                onResume,
                onStopAll
            )
        }'''
if old in sec:
    sec = sec.replace(old, new, 1)
elif new not in sec:
    raise SystemExit("❌ Scan GlobalControls مختلف")

write(ui_rel, ui[:a] + sec + ui[b:])
print("✅ Scan Start يضيف النص الملصوق تلقائيًا")

replace_all_text(ui_rel, [
    ('if (engine.shizukuReady) "متصل" else "غير متصل"', 'if (engine.shizukuReady) "إذن متاح" else "غير متصل"'),
    ('if (engine.shizukuReady) "جاهز" else "—"', 'if (engine.shizukuReady) "إذن" else "—"'),
    ('if (engine.shizukuReady) "متصل وجاهز" else "غير جاهز"', 'if (engine.shizukuReady) "إذن متاح — يختبر عند التشغيل" else "غير جاهز"'),
    ('"Shizuku\\n${if (engine.shizukuReady) "جاهز" else "إعداد"}"',
     '"Shizuku\\n${if (engine.shizukuReady) "اختبار/إذن" else "إعداد"}"'),
], "وصف Shizuku الصادق في الواجهة")

# 8) AppViewModel smart starts + global sequential pipeline
vm_rel = "app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt"
vm = read(vm_rel)
if "import kotlinx.coroutines.Job" not in vm:
    vm = vm.replace(
        "import kotlinx.coroutines.flow.MutableStateFlow",
        "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.flow.MutableStateFlow",
        1
    )

if "private var globalJob: Job? = null" not in vm:
    marker = '''    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
'''
    if marker not in vm:
        raise SystemExit("❌ موضع globalJob غير موجود")
    vm = vm.replace(marker, marker + "\n    private var globalJob: Job? = null\n", 1)

if "fun startScanWithInput(" not in vm:
    marker = "    fun setMode(mode: ExtractionMode) = ExtractionController.setMode(mode)"
    methods = r'''    private suspend fun ensureGroupsReady(): Boolean {
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

'''
    if marker not in vm:
        raise SystemExit("❌ تعذر إدراج Smart Pipeline")
    vm = vm.replace(marker, methods + marker, 1)

old = '''    fun stopAllOperations() {
        ExtractionController.stop()
        ScanController.stop()
        PublishController.stop()
        _message.value = "تم إيقاف جميع العمليات"
    }'''
new = '''    fun stopAllOperations() {
        globalJob?.cancel()
        globalJob = null
        ExtractionController.stop()
        ScanController.stop()
        PublishController.stop()
        _message.value = "تم إيقاف جميع العمليات والمسار العام"
    }'''
if new not in vm:
    if old not in vm:
        raise SystemExit("❌ stopAllOperations مختلف")
    vm = vm.replace(old, new, 1)

write(vm_rel, vm)
print("✅ Smart Sync + Scan input + Global Pipeline")

# 9) MainActivity actual wiring
main_rel = "app/src/main/java/com/althmany/extractor/MainActivity.kt"
main = read(main_rel)

old = '''            val startEnabled = when (screen) {
                AppScreen.SCAN -> scanItems.isNotEmpty() && engine.selectedWhatsAppPackage != null
                AppScreen.PUBLISH -> publishState.messageText.isNotBlank() && engine.selectedWhatsAppPackage != null
                else -> engine.selectedWhatsAppPackage != null
            }'''
new = '''            // Persistent mini bar = real global sequential pipeline.
            val startEnabled = engine.selectedWhatsAppPackage != null'''
if new not in main:
    if old not in main:
        raise SystemExit("❌ global startEnabled مختلف")
    main = main.replace(old, new, 1)

old = '''                    onStart = {
                        when (screen) {
                            AppScreen.SCAN -> ScanController.start()
                            AppScreen.PUBLISH -> PublishController.start(publishState.messageText)
                            else -> ExtractionController.start()
                        }
                    },'''
new = '''                    onStart = viewModel::startAllSmart,'''
if new not in main:
    if old not in main:
        raise SystemExit("❌ global onStart مختلف")
    main = main.replace(old, new, 1)

main = main.replace("onStart = ExtractionController::start,", "onStart = viewModel::startExtractionSmart,")
main = main.replace("onStart = ScanController::start,", "onStart = viewModel::startScanWithInput,")
main = main.replace(
    'onStartExtraction = { screen = AppScreen.EXTRACT; ExtractionController.start() },',
    'onStartExtraction = { screen = AppScreen.EXTRACT; viewModel.startExtractionSmart() },'
)

settings_anchor = '''                    onProbeShizuku = ExtractionController::probeShizuku,
                    onStart = viewModel::startExtractionSmart,'''
if settings_anchor in main:
    main = main.replace(
        settings_anchor,
        '''                    onProbeShizuku = ExtractionController::probeShizuku,
                    onStart = viewModel::startAllSmart,''',
        1
    )

write(main_rel, main)
print("✅ ربط Start/Start All بالوظائف الحقيقية")

# 10) Shizuku readiness wording
replace_all_text("app/src/main/java/com/althmany/extractor/profile/NativeProfileEngineRouter.kt", [
    ('RuntimeBackendKind.SHIZUKU -> "Accessibility غير متصلة محليًا وShizuku جاهز للفحص"',
     'RuntimeBackendKind.SHIZUKU -> "Shizuku لديه Binder+إذن؛ سيتم إثبات UIAutomation وواتساب عند بدء العملية"'),
], "تصحيح معنى Shizuku Ready")

# 11) Runtime regression source check
runtime_check = ROOT / "scripts/runtime_fix_2_20_1_check.py"
runtime_check.write_text(r'''#!/usr/bin/env python3
from pathlib import Path
R = Path(__file__).resolve().parents[1]
def t(p): return (R / p).read_text(encoding="utf-8")
checks = {
    "version 2.20.1": 'versionName = "2.20.1"' in t("app/build.gradle.kts"),
    "strict owner": "return owner.compareAndSet(null, operation)" in t("app/src/main/java/com/althmany/extractor/engine/RuntimeOperationCoordinator.kt"),
    "scan root proof": "READY_ACCESSIBILITY" in t("app/src/main/java/com/althmany/extractor/engine/ScanController.kt"),
    "scan non-empty Shizuku": "tree.nodes.isNotEmpty()" in t("app/src/main/java/com/althmany/extractor/engine/ScanController.kt"),
    "publish diagnostic": "SHIZUKU_UI_NOT_READY" in t("app/src/main/java/com/althmany/extractor/engine/PublishController.kt"),
    "single publish preflight": t("app/src/main/java/com/althmany/extractor/engine/PublishController.kt").count("if (!ensureRuntimeReady())") == 1,
    "extraction root gated": "accessibilityReady" in t("app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt"),
    "Shizuku sync fallback": "scanAndVerifyConversationListViaShizuku" in t("app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt"),
    "scan pasted start": "onStart: (String) -> Unit" in t("app/src/main/java/com/althmany/extractor/ui/SmartWorkspaceScreens.kt"),
    "smart extraction start": "fun startExtractionSmart()" in t("app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt"),
    "global pipeline": "fun startAllSmart()" in t("app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt") and "onStart = viewModel::startAllSmart" in t("app/src/main/java/com/althmany/extractor/MainActivity.kt"),
    "structural publish verify": "isConversationOpenForTarget" in t("app/src/main/java/com/althmany/extractor/engine/PublishController.kt"),
}
bad = []
for k, v in checks.items():
    print(("PASS" if v else "FAIL") + ": " + k)
    if not v:
        bad.append(k)
if bad:
    raise SystemExit("\nRUNTIME FIX CHECK FAILED: " + ", ".join(bad))
print("\nAL-thmany 2.20.1 runtime fix source checks: PASS")
''', encoding="utf-8")
runtime_check.chmod(0o755)
print("✅ runtime_fix_2_20_1_check.py")

print("\n=== VALIDATION ===")
subprocess.run([sys.executable, "scripts/runtime_fix_2_20_1_check.py"], cwd=ROOT, check=True)
subprocess.run([sys.executable, "scripts/validate_release.py"], cwd=ROOT, check=True)

pure = ROOT / "scripts/run_pure_checks.sh"
if pure.exists():
    subprocess.run(["bash", str(pure)], cwd=ROOT, check=False)

print("\n✅ AL-thmany v2.20.1 Root Runtime Fix applied")
print(f"🧰 Backup: {BACKUP}")
print("\nNext:")
print("git status --short")
print("gradle :app:testDebugUnitTest --no-daemon --stacktrace --console=plain")
print("gradle :app:lintDebug --no-daemon --stacktrace --console=plain")
print("gradle :app:assembleDebug --no-daemon --stacktrace --console=plain")
print("git add .")
print('git commit -m "AL-thmany v2.20.1 root runtime fix"')
print("git fetch origin main")
print("git rebase origin/main")
print("git push origin main")
