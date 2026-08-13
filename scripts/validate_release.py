#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

required = [
    'settings.gradle.kts',
    'build.gradle.kts',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt',
    'app/src/main/java/com/althmany/extractor/engine/ScanController.kt',
    'app/src/main/java/com/althmany/extractor/engine/PublishController.kt',
    'app/src/main/java/com/althmany/extractor/engine/RuntimeOperationCoordinator.kt',
    'app/src/main/java/com/althmany/extractor/accessibility/AccessibilityRuntimeBridge.kt',
    'app/src/main/java/com/althmany/extractor/data/ExtractorDatabase.kt',
    'app/src/main/java/com/althmany/extractor/export/ExportManager.kt',
    '.github/workflows/build-apk.yml',
]
for rel in required:
    if not (ROOT / rel).exists():
        errors.append(f'MISSING: {rel}')

root_build = (ROOT / 'build.gradle.kts').read_text(encoding='utf-8')
app_build = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
screens = (ROOT / 'app/src/main/java/com/althmany/extractor/ui/Screens.kt').read_text(encoding='utf-8')
repo = (ROOT / 'app/src/main/java/com/althmany/extractor/data/ExtractorRepository.kt').read_text(encoding='utf-8')
manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')

checks = {
    'AGP 8.13.2': 'version "8.13.2"' in root_build,
    'Kotlin 2.3.21': root_build.count('version "2.3.21"') >= 2,
    'compileSdk 36': 'compileSdk = 36' in app_build,
    'targetSdk 36': 'targetSdk = 36' in app_build,
    'versionCode 2120': 'versionCode = 2120' in app_build,
    'versionName 2.12.0': 'versionName = "2.12.0"' in app_build,
    'Compose API36 BOM': 'compose-bom:2026.04.01' in app_build,
    'modern compilerOptions': 'compilerOptions {' in app_build and 'kotlinOptions {' not in app_build,
    'Extraction UI': '"الاستخراج"' in screens,
    'Scan UI': '"الفحص"' in screens,
    'Publish UI': '"النشر"' in screens,
    'Neon real UI': all(token in screens for token in ['NeonActionButton', 'NeonCard', 'FeatureSwitcher', 'screenBrush']),
    'Real WhatsApp open action': 'onOpenWhatsApp' in screens,
    'Real engine actions retained': all(token in screens for token in ['onStart', 'onPause', 'onResume', 'onStop']),
    'Batch link persistence': 'saveLinksBatch' in repo,
    'Batch scan import': 'upsertScanItemsBatch' in repo,
    'Extractor/Scan/Publish event backpressure': all('BufferOverflow.DROP_OLDEST' in (ROOT / f'app/src/main/java/com/althmany/extractor/engine/{name}Controller.kt').read_text(encoding='utf-8') for name in ['Extraction','Scan','Publish']),
    'Active-engine event routing': 'RuntimeOperationCoordinator.current()' in (ROOT / 'app/src/main/java/com/althmany/extractor/accessibility/WhatsAppAccessibilityService.kt').read_text(encoding='utf-8'),
    'Runtime bridge live instance': 'currentEvenIfQuiet' in (ROOT / 'app/src/main/java/com/althmany/extractor/accessibility/AccessibilityRuntimeBridge.kt').read_text(encoding='utf-8'),
    '95ms fallback poll': 'FALLBACK_POLL_MS = 95L' in (ROOT / 'app/src/main/java/com/althmany/extractor/accessibility/WhatsAppAccessibilityService.kt').read_text(encoding='utf-8'),
    'WhatsApp-first activation': 'awaitRuntimeService(5_000L)' in (ROOT / 'app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt').read_text(encoding='utf-8'),
    'Adaptive chat rows': 'node.parent?.parent?.isClickable == true' in (ROOT / 'app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt').read_text(encoding='utf-8'),
    'SQLite WAL tuning': 'PRAGMA synchronous=NORMAL' in (ROOT / 'app/src/main/java/com/althmany/extractor/data/ExtractorDatabase.kt').read_text(encoding='utf-8'),
    'No backup': 'android:allowBackup="false"' in manifest,
    'No cleartext traffic': 'android:usesCleartextTraffic="false"' in manifest,
}

for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + label)
    if not ok:
        errors.append(label)

# This release must remain the Extractor application; reject accidental Joiner/QuickJoin merges.
for bad in ['QuickJoinAccessibilityService', 'JoinQueueController', 'AutoJoinController']:
    hits = list((ROOT / 'app/src/main/java').rglob('*.kt'))
    if any(bad in p.read_text(encoding='utf-8', errors='ignore') for p in hits):
        errors.append(f'Unexpected joiner component: {bad}')

if errors:
    print('\nVALIDATION FAILED')
    for e in errors:
        print(' -', e)
    sys.exit(1)

print('\nAL-thmany Extractor 2.12.0 Neon Real UI source contract: PASS')
