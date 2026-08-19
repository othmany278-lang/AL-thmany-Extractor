#!/usr/bin/env python3
from pathlib import Path

R = Path(__file__).resolve().parents[1]
def t(path):
    return (R / path).read_text(encoding="utf-8")

app = t("app/build.gradle.kts")
scan = t("app/src/main/java/com/althmany/extractor/engine/ScanController.kt")
adapter = t("app/src/main/java/com/althmany/extractor/engine/WhatsAppUiAdapter.kt")
classifier = t("app/src/main/java/com/althmany/extractor/engine/InviteScanClassifier.kt")
tests = t("app/src/test/java/com/althmany/extractor/engine/InviteScanClassifierTest.kt")
workflow = t(".github/workflows/build-apk.yml")
router = t("app/src/main/java/com/althmany/extractor/engine/GroupAccessRouter.kt")

checks = {
    "version 2.21.1": 'versionName = "2.21.1"' in app and 'versionCode = 2211' in app,
    "xlsx importer": "object ScanInputImporter" in t("app/src/main/java/com/althmany/extractor/engine/ScanInputImporter.kt"),
    "same queue import": "repo.addScanLinksFromText(links.joinToString" in t("app/src/main/java/com/althmany/extractor/ui/AppViewModel.kt"),
    "JOIN_ONLY approval action": "mode == ScanActionMode.JOIN_ONLY || _state.value.requestToJoinEnabled" in scan,
    "JOIN_ONLY setting normalization": "loadedMode == ScanActionMode.JOIN_ONLY || loadedRequestToJoin" in scan,
    "Shizuku JOIN_ONLY approval action": "mode==ScanActionMode.JOIN_ONLY || _state.value.requestToJoinEnabled" in scan,
    "clone-aware root": "WhatsAppInstanceRegistry.isSupportedPackage(pkg)" in adapter,
    "exact conversation-list package guard": "isConversationListVisible(root: AccessibilityNodeInfo?, expectedPackage: String? = null)" in adapter,
    "community action semantics": "val communitySignals = listOf(" in classifier and "join this community now" in classifier,
    "group action semantics": "val groupSignals = listOf(" in classifier and "join this group now" in classifier,
    "no generic community kind guess": 'listOf("المجتمع", "مجتمع", "community").any' not in classifier,
    "bottom-nav contamination regression": "communitiesBottomNavDoesNotTurnGroupInviteIntoCommunity" in tests,
    "explicit community regression": "explicitCommunityJoinWinsOverGenericNavigationText" in tests,
    "resource id join": '"join_group_button"' in adapter,
    "continue confirm": "inviteConfirmationAvailable" in scan,
    "same link bound": "MAX_POSITIVE_FOLLOW_UPS" in scan,
    "smart X/back": "clickInviteClose" in scan,
    "visible before search": router.find("priority += GroupAccessMethod.VISIBLE_LIST") < router.find("priority += GroupAccessMethod.SEARCH_FALLBACK"),
    "scroll before search": router.find("priority += GroupAccessMethod.SCROLL_MATCH") < router.find("priority += GroupAccessMethod.SEARCH_FALLBACK"),
    "runtime emulator": "reactivecircus/android-emulator-runner@v2" in workflow,
    "runtime package": "com.althmany.extractor.debug" in workflow,
    "runtime launch": "com.althmany.extractor.MainActivity" in workflow,
    "runtime process check": "pidof" in workflow,
    "runtime crash check": "FATAL EXCEPTION" in workflow and "ANR in" in workflow,
}

bad = []
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
    if not ok:
        bad.append(name)
if bad:
    raise SystemExit("CONTROLLER RUNTIME 2.21.1 CHECK FAILED: " + ", ".join(bad))
print("\nAL-thmany 2.21.1 repaired controller/runtime source checks: PASS")
