#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

required = [
    "whatsapp-simulator/build.gradle.kts",
    "whatsapp-simulator/src/main/AndroidManifest.xml",
    "whatsapp-simulator/src/main/java/com/whatsapp/simulator/InviteSimulatorActivity.kt",
    "app/src/androidTest/java/com/althmany/extractor/e2e/WhatsAppJoinE2ETest.kt",
    ".github/workflows/whatsapp-e2e.yml",
    "scripts/run_whatsapp_e2e.sh",
]
missing = [p for p in required if not (ROOT / p).exists()]
if missing:
    raise SystemExit("MISSING E2E FILES: " + ", ".join(missing))

settings = text("settings.gradle.kts")
app_build = text("app/build.gradle.kts")
sim_build = text("whatsapp-simulator/build.gradle.kts")
sim = text("whatsapp-simulator/src/main/java/com/whatsapp/simulator/InviteSimulatorActivity.kt")
test = text("app/src/androidTest/java/com/althmany/extractor/e2e/WhatsAppJoinE2ETest.kt")
workflow = text(".github/workflows/whatsapp-e2e.yml")
runner = text("scripts/run_whatsapp_e2e.sh")

checks = {
    "simulator module included": 'include(":whatsapp-simulator")' in settings,
    "simulator impersonates only test package route": 'applicationId = "com.whatsapp"' in sim_build,
    "instrumentation dependencies": all(x in app_build for x in ["androidx.test.ext:junit", "androidx.test:runner"]),
    "all invite scenarios": all(x in sim for x in ["E2EDIRECT001", "E2EREQUEST001", "E2ECOMMUNITY001", "E2EINVALID001", "E2EALREADY001", "E2EFULL00001", "E2EREMOVED01", "E2ELIMIT0001"]),
    "join click path": "CLICK_JOIN" in sim and "Join group" in sim,
    "request click path": "CLICK_REQUEST" in sim and "Request to join" in sim,
    "community confirmation path": "CLICK_CONFIRM" in sim and "Join community" in sim and "Continue" in sim,
    "X and Back observability": "CLOSE code=" in sim and "BACK code=" in sim,
    "navigation failure sentinel": "NAVIGATION_FAILURE" in sim,
    "JOIN_ONLY assertions": "joinOnly_executesActionsAndAdvancesEveryLink" in test and "REQUEST_VERIFIED" in test and "JOIN_VERIFIED" in test,
    "SCAN_ONLY no-click assertions": "scanOnly_readsSameScreensWithoutClickingMembershipActions" in test and "ScanStatus.APPROVAL" in test,
    "accessibility enabled by CI": "enabled_accessibility_services" in runner,
    "single-shell E2E runner": 'bash "$GITHUB_WORKSPACE/scripts/run_whatsapp_e2e.sh"' in workflow,
    "instrumentation executed": "am instrument" in runner,
    "E2E report artifact": "WhatsApp-E2E-Report" in workflow,
}

bad = []
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + ": " + name)
    if not ok:
        bad.append(name)
if bad:
    raise SystemExit("WHATSAPP E2E CONTRACT FAILED: " + ", ".join(bad))
print("\nWhatsApp emulator E2E contract: PASS")
