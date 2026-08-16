#!/usr/bin/env python3
from pathlib import Path
R=Path(__file__).resolve().parents[1]
def t(p): return (R/p).read_text(encoding='utf-8')
checks={
'version 2.20.2':'versionName = "2.20.2"' in t('app/build.gradle.kts'),
'resilient service':'attachControllersSafely' in t('app/src/main/java/com/althmany/extractor/accessibility/WhatsAppAccessibilityService.kt'),
'warmup':'startWarmupProbe' in t('app/src/main/java/com/althmany/extractor/accessibility/WhatsAppAccessibilityService.kt'),
'bridge snapshot':'AccessibilityBridgeSnapshot' in t('app/src/main/java/com/althmany/extractor/accessibility/AccessibilityRuntimeBridge.kt'),
'enabled not bound':'ACCESSIBILITY_ENABLED_NOT_BOUND' in t('app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt'),
'wrong variant':'ACCESSIBILITY_WRONG_VARIANT' in t('app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt'),
'sync busy':'syncInProgress' in t('app/src/main/java/com/althmany/extractor/engine/ExtractionController.kt'),
'operation switch':'prepareExplicitOperation' in t('app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt'),
'smart publish':'startPublishSmart' in t('app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt'),
'poll after settings':'engine.accessibilityEnabledInSettings' in t('app/src/main/java/com/althmany/extractor/MainActivity.kt'),
}
bad=[]
for k,v in checks.items():
 print(('PASS' if v else 'FAIL')+': '+k)
 if not v: bad.append(k)
if bad: raise SystemExit('FAILED: '+', '.join(bad))
print('\nAL-thmany 2.20.2 Accessibility Runtime checks: PASS')
