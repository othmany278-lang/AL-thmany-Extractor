#!/usr/bin/env python3
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
