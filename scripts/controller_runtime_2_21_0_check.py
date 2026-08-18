#!/usr/bin/env python3
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
