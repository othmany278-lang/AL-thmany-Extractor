#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

required = [
    'settings.gradle.kts',
    'build.gradle.kts',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/althmany/extractor/MainActivity.kt',
    'app/src/main/java/com/althmany/extractor/ui/Screens.kt',
    'app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt',
    'app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt',
    'app/src/main/java/com/althmany/extractor/engine/ScanController.kt',
    'app/src/main/java/com/althmany/extractor/engine/PublishController.kt',
    'app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt',
    'app/src/main/java/com/althmany/extractor/engine/RuntimeOperationCoordinator.kt',
    'app/src/main/java/com/althmany/extractor/accessibility/AccessibilityRuntimeBridge.kt',
    'app/src/main/java/com/althmany/extractor/data/Models.kt',
    'app/src/main/java/com/althmany/extractor/data/PublishModels.kt',
    'app/src/main/java/com/althmany/extractor/data/ExtractorDatabase.kt',
    'app/src/main/java/com/althmany/extractor/data/ExtractorRepository.kt',
    'app/src/main/java/com/althmany/extractor/export/ExportManager.kt',
    '.github/workflows/build-apk.yml',
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
main = read('app/src/main/java/com/althmany/extractor/MainActivity.kt')
models = read('app/src/main/java/com/althmany/extractor/data/Models.kt')
publish_models = read('app/src/main/java/com/althmany/extractor/data/PublishModels.kt')
db = read('app/src/main/java/com/althmany/extractor/data/ExtractorDatabase.kt')
repo = read('app/src/main/java/com/althmany/extractor/data/ExtractorRepository.kt')
extract = read('app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt')
scan = read('app/src/main/java/com/althmany/extractor/engine/ScanController.kt')
scan_ui = read('app/src/main/java/com/althmany/extractor/engine/ScanUiState.kt')
publish = read('app/src/main/java/com/althmany/extractor/engine/PublishController.kt')
publish_ui = read('app/src/main/java/com/althmany/extractor/engine/PublishUiState.kt')
adapter = read('app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt')
service = read('app/src/main/java/com/althmany/extractor/accessibility/WhatsAppAccessibilityService.kt')
bridge = read('app/src/main/java/com/althmany/extractor/accessibility/AccessibilityRuntimeBridge.kt')
workflow = read('.github/workflows/build-apk.yml')

checks = {
    'AGP 8.13.2': 'version "8.13.2"' in root_build,
    'Kotlin 2.3.21': root_build.count('version "2.3.21"') >= 2,
    'compileSdk 36': 'compileSdk = 36' in app_build,
    'targetSdk 36': 'targetSdk = 36' in app_build,
    'versionCode 2130': 'versionCode = 2130' in app_build,
    'versionName 2.13.0': 'versionName = "2.13.0"' in app_build,
    'Compose API36 BOM': 'compose-bom:2026.04.01' in app_build,
    'modern compilerOptions': 'compilerOptions {' in app_build and 'kotlinOptions {' not in app_build,
    'Known Compose weight import fixed': 'import androidx.compose.foundation.layout.weight' not in screens,
    'Extraction UI retained': '"الاستخراج"' in screens and 'GroupsScreen' in screens,
    'Scan UI retained': '"الفحص"' in screens,
    'Publish UI retained': '"النشر"' in screens,
    'Neon real UI retained': all(token in screens for token in ['NeonActionButton', 'NeonCard', 'FeatureSwitcher', 'screenBrush']),
    'Real WhatsApp open action': 'onOpenWhatsApp' in screens,
    'Real engine actions retained': all(token in screens for token in ['onStart', 'onPause', 'onResume', 'onStop']),
    'Group selection presets': 'enum class GroupSelectionPreset' in models and all(x in models for x in ['UNREAD','ACTIVE','PUBLISHABLE','UNVERIFIED']),
    'Detailed group sync metadata': 'GroupSyncCandidate' in models and 'collectChatListCandidatesDetailed' in adapter and 'addDiscoveredGroupCandidates' in extract,
    'Sync restores chat-list position': 'restoreConversationListPosition' in extract and 'scrollChatListBackward' in adapter and 'swipeChatListBackward' in service,
    'Deep extraction interleaved capture': 'captureVisibleLinks(group, seen)' in extract and 'captureBurst(group, seen, timing)' in extract,
    'Strict extraction end proof': 'proveQuietEnd(group, seen, timing, directionForward = false)' in extract and 'EndProofTracker()' in extract,
    'Checkpoint before next group': 'saveCheckpoint' in extract and 'completed = true' in extract,
    'Android URL multi-surface capture': all(token in adapter for token in ['node.hintText', 'node.tooltipText', 'URLSpan::class.java', 'node.extras.keySet()']),
    'Link normalization/categories': 'enum class LinkCategory' in models and 'fun category(raw: String)' in read('app/src/main/java/com/althmany/extractor/engine/LinkExtractor.kt') and '&amp;' in read('app/src/main/java/com/althmany/extractor/engine/LinkExtractor.kt'),
    'Batch link persistence': 'saveLinksBatch' in repo,
    'Batch scan import': 'upsertScanItemsBatch' in repo,
    'Scan connectivity guard': 'awaitNetworkAvailability()' in scan and 'WAITING_NETWORK' in scan and 'ACCESS_NETWORK_STATE' in manifest,
    'Scan same-link reopen after network': 'openInvite(item.normalizedUrl, packageName)' in scan and 'REOPEN_FAILED' in scan,
    'Scan stable result gate': 'definitiveStableThreshold' in scan and 'stableThreshold' in scan,
    'Scan adaptive ceiling 5.6s': '5_600L' in scan_ui,
    'Scanner never joins': not any(token in scan for token in ['clickJoin', 'requestJoin', 'ACTION_JOIN']),
    'Publish content modes': all(token in publish_models for token in ['SINGLE_TEXT','MULTI_TEXT','CONTACT_TEXT','VCF','VCF_WITH_TEXT','IMAGE_WITH_CAPTION']),
    'Publish native Android share path': 'Intent.ACTION_SEND' in publish and 'Intent.EXTRA_STREAM' in publish,
    'Publish round-robin multi text': 'itemIndex % parts.size' in publish,
    'Publish contact-text formatter': 'formatContactsAsText' in publish and 'CONTACT_TEXT' in publish,
    'Image caption share hint': 'Intent.EXTRA_TEXT' in publish and 'IMAGE_WITH_CAPTION' in publish,
    'Publish run token': 'UUID.randomUUID().toString()' in publish and 'run_token' in db,
    'Publish uncertain duplicate guard': 'PublishStatus.UNCERTAIN' in publish and "status='UNCERTAIN'" in db,
    'Truthful Turbo 1.0s': 'TURBO("Turbo • 1.0s", 1_000L' in publish_ui and '0.32' not in publish_ui,
    'Publish attachment picker wired': 'OpenDocument' in main and 'onPickAttachment' in screens,
    'Shared group database capability flags': all(token in db for token in ['unread_count','publishable','community_parent','last_synced_at']),
    'SQLite DB v8': 'DB_VERSION = 8' in db,
    'Extractor/Scan/Publish event backpressure': all('BufferOverflow.DROP_OLDEST' in read(f'app/src/main/java/com/althmany/extractor/engine/{name}Controller.kt') for name in ['Extraction','Scan','Publish']),
    'Active-engine event routing': 'RuntimeOperationCoordinator.current()' in service,
    'Runtime bridge live instance': 'currentEvenIfQuiet' in bridge,
    '95ms fallback poll': 'FALLBACK_POLL_MS = 95L' in service,
    'WhatsApp-first activation': 'awaitRuntimeService(5_000L)' in extract,
    'Adaptive chat rows': 'node.parent?.parent?.isClickable == true' in adapter,
    'SQLite WAL tuning': 'PRAGMA synchronous=NORMAL' in db,
    'No backup': 'android:allowBackup="false"' in manifest,
    'No cleartext traffic': 'android:usesCleartextTraffic="false"' in manifest,
    'Workflow artifact v2.13': 'NativeParity-2.13.0' in workflow,
}

for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + label)
    if not ok:
        errors.append(label)

# This release must remain the Extractor/Scanner/Publisher application; reject accidental Joiner merges.
kt_files = list((ROOT / 'app/src/main/java').rglob('*.kt'))
for bad in ['QuickJoinAccessibilityService', 'JoinQueueController', 'AutoJoinController']:
    if any(bad in p.read_text(encoding='utf-8', errors='ignore') for p in kt_files):
        errors.append(f'Unexpected joiner component: {bad}')

if errors:
    print('\nVALIDATION FAILED')
    for e in errors:
        print(' -', e)
    sys.exit(1)

print('\nAL-thmany Extractor 2.13.0 Native Parity source contract: PASS')
