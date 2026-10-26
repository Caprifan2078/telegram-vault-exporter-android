#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/integration-test-classes
mkdir -p "$OUT"
ROOT=app/src/main/java/org/vault/exporter
javac -d "$OUT" \
  integration-jvm/fakes/org/json/*.java \
  integration-jvm/fakes/android/content/Context.java \
  integration-jvm/fakes/org/vault/exporter/*.java \
  "$ROOT"/{OfflineArchiveFixture,ExportEngine,HistoryCursor,StagingCipher,ChunkedVault,ResumeStore,ArchiveWriter,ArchiveVerifier,TelegramRateLimit}.java \
  integration-jvm/TestHardenedExportEngine.java integration-jvm/TestEngineBoundedMemory.java \
  integration-jvm/TestOfflineArchiveFixture.java integration-jvm/TestSyntheticTelegramConversation.java
java -Xmx128m -cp "$OUT" TestHardenedExportEngine
java -Xmx32m -cp "$OUT" TestEngineBoundedMemory
java -Xmx64m -cp "$OUT" TestOfflineArchiveFixture

java -Xmx128m -cp "$OUT" TestSyntheticTelegramConversation
