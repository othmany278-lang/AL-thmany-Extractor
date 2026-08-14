#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "SKIP: kotlinc is not installed; GitHub Android build remains authoritative."
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

kotlinc \
  app/src/main/java/com/althmany/extractor/data/Models.kt \
  app/src/main/java/com/althmany/extractor/engine/ExtractionPolicy.kt \
  app/src/main/java/com/althmany/extractor/engine/LinkExtractor.kt \
  app/src/main/java/com/althmany/extractor/engine/NodeSnapshot.kt \
  app/src/main/java/com/althmany/extractor/engine/EndProofTracker.kt \
  app/src/main/java/com/althmany/extractor/profile/ProfileLaunchPolicy.kt \
  scripts/PureEngineChecks.kt \
  -include-runtime -d "$TMP/engine.jar"
java -jar "$TMP/engine.jar"

kotlinc \
  app/src/main/java/com/althmany/extractor/data/ScanModels.kt \
  app/src/main/java/com/althmany/extractor/engine/InviteLinkParser.kt \
  app/src/main/java/com/althmany/extractor/engine/InviteScanClassifier.kt \
  app/src/main/java/com/althmany/extractor/engine/ScanRetryPolicy.kt \
  app/src/main/java/com/althmany/extractor/engine/ScanUiState.kt \
  scripts/PureScanChecks.kt \
  -include-runtime -d "$TMP/scan.jar"
java -jar "$TMP/scan.jar"

kotlinc \
  app/src/main/java/com/althmany/extractor/data/PublishModels.kt \
  app/src/main/java/com/althmany/extractor/engine/PublishUiState.kt \
  scripts/PurePublishChecks.kt \
  -include-runtime -d "$TMP/publish.jar"
java -jar "$TMP/publish.jar"

kotlinc \
  app/src/main/java/com/althmany/extractor/engine/RuntimeOperationCoordinator.kt \
  scripts/PureRuntimeChecks.kt \
  -include-runtime -d "$TMP/runtime.jar"
java -jar "$TMP/runtime.jar"

kotlinc \
  app/src/main/java/com/althmany/extractor/profile/ProfileLaunchPolicy.kt \
  app/src/main/java/com/althmany/extractor/profile/ProfileControlPolicy.kt \
  app/src/main/java/com/althmany/extractor/profile/DualMessengerMatcher.kt \
  scripts/PureProfileChecks.kt \
  -include-runtime -d "$TMP/profile.jar"
java -jar "$TMP/profile.jar"

printf '\nALL PURE CHECKS: PASS\n'
