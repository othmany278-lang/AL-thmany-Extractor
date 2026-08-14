#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

required = [
    'settings.gradle.kts', 'build.gradle.kts', 'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/althmany/extractor/MainActivity.kt',
    'app/src/main/java/com/althmany/extractor/ui/Screens.kt',
    'app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt',
    'app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt',
    'app/src/main/java/com/althmany/extractor/engine/ScanController.kt',
    'app/src/main/java/com/althmany/extractor/engine/PublishController.kt',
    'app/src/main/java/com/althmany/extractor/engine/GroupAccessRouter.kt',
    'app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt',
    'app/src/main/java/com/althmany/extractor/engine/RuntimeOperationCoordinator.kt',
    'app/src/main/java/com/althmany/extractor/accessibility/AccessibilityRuntimeBridge.kt',
    'app/src/main/java/com/althmany/extractor/data/Models.kt',
    'app/src/main/java/com/althmany/extractor/data/ScanModels.kt',
    'app/src/main/java/com/althmany/extractor/data/ExtractorDatabase.kt',
    'app/src/main/java/com/althmany/extractor/data/ExtractorRepository.kt',
    'app/src/main/java/com/althmany/extractor/export/ExportManager.kt',
    '.github/workflows/build-apk.yml',
    'app/src/main/java/com/althmany/extractor/profile/ProfileEnvironment.kt',
    'app/src/main/java/com/althmany/extractor/profile/ProfileAccessibilityRuntime.kt',
    'app/src/main/java/com/althmany/extractor/profile/NativeProfileEngineRouter.kt',
    'app/src/main/java/com/althmany/extractor/profile/DualMessengerMatcher.kt',
    'app/src/main/java/com/althmany/extractor/profile/WhatsAppInstanceRegistry.kt',
    'app/src/main/java/com/althmany/extractor/shizuku/ShizukuBridge.kt',
    'app/src/main/java/com/althmany/extractor/shizuku/ShizukuShellUserService.kt',
    'app/src/main/java/com/althmany/extractor/shizuku/PersistentUiAutomationBridge.kt',
    'app/src/main/java/com/althmany/extractor/shizuku/ShizukuUiRuntime.kt',
    'app/src/main/aidl/com/althmany/extractor/shizuku/IShizukuShellService.aidl',
]
for rel in required:
    if not (ROOT / rel).exists():
        errors.append(f'MISSING: {rel}')

def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding='utf-8')

root_build = read('build.gradle.kts')
app_build = read('app/build.gradle.kts')
manifest = read('app/src/main/AndroidManifest.xml')
screens = read('app/src/main/java/com/althmany/extractor/ui/Screens.kt')
dashboard = read('app/src/main/java/com/althmany/extractor/ui/DashboardScreen.kt')
main = read('app/src/main/java/com/althmany/extractor/MainActivity.kt')
models = read('app/src/main/java/com/althmany/extractor/data/Models.kt')
scan_models = read('app/src/main/java/com/althmany/extractor/data/ScanModels.kt')
db = read('app/src/main/java/com/althmany/extractor/data/ExtractorDatabase.kt')
repo = read('app/src/main/java/com/althmany/extractor/data/ExtractorRepository.kt')
extract = read('app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt')
scan = read('app/src/main/java/com/althmany/extractor/engine/ScanController.kt')
scan_ui = read('app/src/main/java/com/althmany/extractor/engine/ScanUiState.kt')
publish = read('app/src/main/java/com/althmany/extractor/engine/PublishController.kt')
adapter = read('app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt')
router = read('app/src/main/java/com/althmany/extractor/engine/GroupAccessRouter.kt')
service = read('app/src/main/java/com/althmany/extractor/accessibility/WhatsAppAccessibilityService.kt')
bridge = read('app/src/main/java/com/althmany/extractor/accessibility/AccessibilityRuntimeBridge.kt')
link_extractor = read('app/src/main/java/com/althmany/extractor/engine/LinkExtractor.kt')
workflow = read('.github/workflows/build-apk.yml')
exporter = read('app/src/main/java/com/althmany/extractor/export/ExportManager.kt')
profile_env = read('app/src/main/java/com/althmany/extractor/profile/ProfileEnvironment.kt')
profile_access = read('app/src/main/java/com/althmany/extractor/profile/ProfileAccessibilityRuntime.kt')
profile_router = read('app/src/main/java/com/althmany/extractor/profile/NativeProfileEngineRouter.kt')
dual = read('app/src/main/java/com/althmany/extractor/profile/DualMessengerMatcher.kt')
registry = read('app/src/main/java/com/althmany/extractor/profile/WhatsAppInstanceRegistry.kt')
shizuku_bridge = read('app/src/main/java/com/althmany/extractor/shizuku/ShizukuBridge.kt')
shizuku_service = read('app/src/main/java/com/althmany/extractor/shizuku/ShizukuShellUserService.kt')
shizuku_uia = read('app/src/main/java/com/althmany/extractor/shizuku/PersistentUiAutomationBridge.kt')
shizuku_runtime = read('app/src/main/java/com/althmany/extractor/shizuku/ShizukuUiRuntime.kt')
access_xml = read('app/src/main/res/xml/accessibility_service_config.xml')

checks = {
    'AGP 8.13.2': 'version "8.13.2"' in root_build,
    'Kotlin 2.3.21': root_build.count('version "2.3.21"') >= 2,
    'compileSdk 36': 'compileSdk = 36' in app_build,
    'targetSdk 36': 'targetSdk = 36' in app_build,
    'versionCode 2170': 'versionCode = 2170' in app_build,
    'versionName 2.17.0': 'versionName = "2.17.0"' in app_build,
    'Compose API36 BOM': 'compose-bom:2026.04.01' in app_build,
    'modern compilerOptions': 'compilerOptions {' in app_build and 'kotlinOptions {' not in app_build,
    'Known Compose weight import fixed': 'import androidx.compose.foundation.layout.weight' not in screens,

    'Unified GroupRecord access metadata': all(x in models for x in [
        'enum class GroupAccessMethod', 'jidOrGroupId', 'whatsappPackage', 'preferredAccessMethod',
        'lastSuccessfulOpenMethod', 'accessSuccessCount', 'accessFailureCount', 'syncOrder'
    ]),
    'No AccessibilityNodeInfo persistence model': 'AccessibilityNodeInfo' not in models and 'import android.view.accessibility.AccessibilityNodeInfo' not in db,
    'SQLite group memory DB v11': 'DB_VERSION = 11' in db,
    'Unified package-scoped group database': all(x in db for x in [
        'whatsapp_package', 'jid_or_group_id', 'preferred_access_method', 'last_successful_open_method',
        'sync_order', 'UNIQUE(name, whatsapp_package)'
    ]),
    'Legacy group row coalescing': 'target_groups_legacy' in db and 'legacyId' in db,
    'Package-scoped extraction queue': 'pendingSelectedGroups(targetPackage)' in extract,
    'Package-scoped selection presets': 'setSelectionPreset(preset: GroupSelectionPreset, whatsappPackage: String?' in db,
    'Discovered groups start unselected': 'put("selected", if (discovered) 0 else 1)' in db,
    'Stale sync generation guard': all(x in db for x in ['sync_generation', 'stale', 'finalizeGroupSync', 'selected=0, stale=1']),
    'Old discovered garbage hidden on v11 migration': 'UPDATE target_groups SET selected=0, stale=1 WHERE discovered=1' in db,
    'Group-only WhatsApp filter sync': all(x in extract for x in ['activateGroupsOnlyFilter', 'verifiedGroupHint = true', 'فلتر المجموعات']),
    'Safe empty-sync preservation': 'تم الحفاظ على قاعدة القروبات القديمة بدون تعديل' in extract,
    'System UI labels excluded from group names': all(x in adapter for x in ['جهات اتصالك غير متزامنة', 'المجموعات', 'اسأل meta ai أو ابحث']),
    'Chat-list container scoped scanning': 'findChatListContainer' in adapter and 'collectChatListCandidatesDetailed' in adapter,

    'Shared GroupAccessRouter': 'class GroupAccessRouter' in router and 'GroupAccessRouter(adapter)' in extract and 'GroupAccessRouter(adapter)' in publish,
    'Visible-list open without Search': 'openVisibleChatListRow' in adapter and 'GroupAccessMethod.VISIBLE_LIST' in router,
    'Groups filter prepared before route': 'prepareGroupList(service, timing, waitForUi)' in router and 'activateGroupsFilter' in router,
    'Bidirectional list match': 'forward = false' in router and 'scrollChatListBackward' in router,
    'Post-click header verification': 'verifyChatOpen' in router and 'Never start scrolling while an unverified chat is open' in router,
    'Scroll + match before Search': router.find('priority += GroupAccessMethod.SCROLL_MATCH') < router.find('priority += GroupAccessMethod.SEARCH_FALLBACK'),
    'Search remains router-only fallback': 'SEARCH_FALLBACK("Search كحل أخير")' in models and 'allowSearchFallback' in router,
    'Extraction never types group names': 'allowSearchFallback = false' in extract and 'بدون Search' in extract,
    'Structural chat confirmation': 'isConversationOpenForTarget' in adapter and 'isConversationOpenForTarget' in extract,
    'Conversation-list bottom-nav recovery': 'bottomNavLabels' in adapter and 'prevent' in adapter,
    'Shizuku normalized group matching': 'normalizeGroupName' in shizuku_runtime and 'groupNamesEquivalent' in shizuku_runtime,
    'No fake Android JID direct route': 'does not expose an official stable JID-to-chat intent' in router and 'GroupAccessMethod.JID_DIRECT' in router,
    'Access success memory': 'recordGroupAccessSuccess' in repo and 'updateGroupAccessSuccess' in db,
    'Access failure recovery memory': 'recordGroupAccessFailure' in repo and 'updateGroupAccessFailure' in db,

    'Event-first extraction': 'uiEvents.first()' in extract and 'awaitUiChange' in extract,
    'Interleaved extraction capture': 'captureVisibleLinks(group, seen)' in extract and 'captureBurst(group, seen, timing)' in extract,
    'Strict end proof': 'proveQuietEnd(group, seen, timing, directionForward = false)' in extract and 'EndProofTracker()' in extract,
    'Extraction checkpoint': 'saveCheckpoint' in extract and 'completed = true' in extract,
    'Android URL multi-surface capture': all(token in adapter for token in ['node.hintText', 'node.tooltipText', 'URLSpan::class.java', 'node.extras.keySet()']),
    'Rich link classifier': all(x in models for x in ['WHATSAPP_GROUP_OR_COMMUNITY','WHATSAPP_CHANNEL','WA_ME','TELEGRAM','INSTAGRAM','FACEBOOK','WEB_URL']),
    'Invite code extraction': 'fun inviteCode(raw: String)' in link_extractor,
    'Link source group/package persistence': all(x in db for x in ['source_group_id', 'whatsapp_package', 'invite_code', 'category']),
    'Link source metadata export': all(x in exporter for x in ['Source Group ID', 'WhatsApp Package', 'Invite Code', 'r.category.labelAr']),

    'Scanner action modes': 'enum class ScanActionMode' in scan_ui and all(x in scan_ui for x in ['SCAN_ONLY','JOIN_ONLY','SCAN_AND_JOIN']),
    'Scanner mode UI': 'ScanActionMode.entries' in screens and 'onScanActionMode' in screens and 'onRequestToJoinEnabled' in screens,
    'Open-once scan action path': 'maybeApplyMembershipAction' in scan and 'clickInviteAction' in adapter,
    'Request-to-join explicit setting': 'requestToJoinEnabled' in scan_ui and 'APPROVAL_ACTION_DISABLED' in scan,
    'Join verified state': 'ScanStatus.JOINED' in scan and 'JOIN_VERIFIED' in scan,
    'Uncertain action duplicate guard': 'ScanStatus.ACTION_UNCERTAIN' in scan and 'ACTION_UNCERTAIN' in scan_models,
    'Scan connectivity guard': 'awaitNetworkAvailability()' in scan and 'WAITING_NETWORK' in scan and 'ACCESS_NETWORK_STATE' in manifest,
    'Scan stable result gate': 'definitiveStableThreshold' in scan and 'stableThreshold' in scan,
    'Scan adaptive ceiling 5.6s': '5_600L' in scan_ui,

    'GroupRecord publish memory': all(x in db for x in ['last_publish_status', 'last_published_at', 'last_publish_error', 'updateGroupPublishState']) and 'updateGroupPublishState' in publish,
    'Publisher reuses GroupRecord': 'repository.groupByName(item.groupName, run.targetPackage)' in publish and 'accessRouter.open(' in publish,
    'Publisher package guard': 'it.whatsappPackage.isBlank() || it.whatsappPackage == packageName' in publish,
    'Publish run token': 'UUID.randomUUID().toString()' in publish and 'run_token' in db,
    'Publish uncertain duplicate guard': 'PublishStatus.UNCERTAIN' in publish and "status='UNCERTAIN'" in db,
    'Truthful Turbo 1.0s': 'TURBO("Turbo • 1.0s", 1_000L' in read('app/src/main/java/com/althmany/extractor/engine/PublishUiState.kt'),

    'Extractor/Scan/Publish event backpressure': all('BufferOverflow.DROP_OLDEST' in read(f'app/src/main/java/com/althmany/extractor/engine/{name}Controller.kt') for name in ['Extraction','Scan','Publish']),
    'Active-engine event routing': 'RuntimeOperationCoordinator.current()' in service,
    'Runtime bridge live instance': 'currentEvenIfQuiet' in bridge,
    '95ms fallback poll': 'FALLBACK_POLL_MS = 95L' in service,
    'SQLite WAL tuning': 'PRAGMA synchronous=NORMAL' in db,
    'No backup': 'android:allowBackup="false"' in manifest,
    'No cleartext traffic': 'android:usesCleartextTraffic="false"' in manifest,
    'Profile-local identity key': all(x in profile_env for x in ['profileKey', 'Process.myUserHandle()', 'isManagedProfile', 'SAMSUNG_ISOLATED']),
    'Work/Secure same-profile safety': 'لا يتم تجاوز Knox' in profile_env and 'يجب أن تكون نسخة AL-thmany وWhatsApp في ملف العمل نفسه' in profile_env,
    'Profile-local Accessibility heartbeat': all(x in profile_access for x in ['profile_accessibility_runtime_v215', 'recordServiceConnected', 'recordEvent', 'recordRoot']),
    'AUTO backend router': all(x in profile_router for x in ['RuntimeBackendKind.ACCESSIBILITY', 'RuntimeBackendKind.SHIZUKU', 'ProfileAccessibilityRuntime.snapshot']),
    'Shizuku API/provider 13.1.5': all(x in app_build for x in ['dev.rikka.shizuku:api:13.1.5', 'dev.rikka.shizuku:provider:13.1.5']) and 'rikka.shizuku.ShizukuProvider' in manifest,
    'Shizuku AIDL enabled': 'aidl = true' in app_build,
    'Persistent Shizuku UiAutomation': all(x in shizuku_uia for x in ['rootInActiveWindow', 'setOnAccessibilityEventListener', 'injectInputEvent', 'ACTION_SET_TEXT']),
    'Shizuku probe-first bridge': all(x in shizuku_bridge for x in ['fun probe', 'fastSnapshot', 'waitAndSnapshot', 'ensureBound']),
    'Shizuku extraction fallback': all(x in extract for x in ['syncGroupsViaShizuku', 'runExtractionViaShizuku', 'extractDeepViaShizuku', 'Shizuku Event-first']),
    'Shizuku publish fallback': 'shizukuMode' in publish and 'ShizukuBridge.ensureBound' in publish and 'openVerifiedGroupShizuku' in publish,
    'Shizuku scan fallback': 'shizukuMode' in scan and 'ShizukuBridge.ensureBound' in scan and 'scanOneShizuku' in scan,
    'Dynamic WhatsApp/clone discovery': all(x in registry for x in ['WHATSAPP_CLONED', 'queryIntentActivities', 'looksLikeWhatsApp', 'cacheProfile']),
    'Vendor clone Accessibility events supported': 'android:packageNames=' not in access_xml and 'cache.any { it.packageName == pkg }' in registry,
    'Dual Messenger matcher ported': all(x in dual for x in ['dual messenger', 'المراسل المزدوج', 'cloned whatsapp']),
    'No legacy joiner service merged': 'QuickJoinAccessibilityService' not in service,
    'Workflow artifact v2.17.0': 'ExactDashboard-RootFix-2.17.0' in workflow,
    'Exact dashboard UI': all(x in dashboard for x in ['AL-thmany', 'Ultimate WhatsApp Tool', 'الخصائص السريعة', 'محرك التشغيل', 'إعدادات متقدمة', 'تبديل النسخ']),
    'Hybrid backend UI start gating': screens.count('runtimeReady &&') >= 3 and 'engine.shizukuReady' in screens,
}

for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + label)
    if not ok:
        errors.append(label)

# Do not merge the old joiner application lineage. Scan+Join is intentionally implemented inside
# the scanner state machine, not by importing old queue/controller components.
kt_files = list((ROOT / 'app/src/main/java').rglob('*.kt'))
for bad in ['QuickJoinAccessibilityService', 'JoinQueueController', 'AutoJoinController']:
    if any(bad in p.read_text(encoding='utf-8', errors='ignore') for p in kt_files):
        errors.append(f'Unexpected legacy joiner component: {bad}')

if errors:
    print('\nVALIDATION FAILED')
    for e in errors:
        print(' -', e)
    sys.exit(1)

print('\nAL-thmany Extractor 2.17.0 Exact Dashboard + Root Fix source contract: PASS')
