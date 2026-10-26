#!/usr/bin/env bash
# Million-word, true 48-MiB media, no-network full export and reconstruction.
# Independent of Android SDK/Telegram credentials; real ExportEngine & TVA verifier.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/mega-test-classes
mkdir -p "$OUT"
ROOT=app/src/main/java/org/vault/exporter
javac -d "$OUT" \
  integration-jvm/fakes/org/json/*.java \
  integration-jvm/fakes/android/content/Context.java \
  integration-jvm/fakes/org/vault/exporter/*.java \
  "$ROOT"/{OfflineArchiveFixture,ExportEngine,HistoryCursor,StagingCipher,ChunkedVault,ResumeStore,ArchiveWriter,ArchiveVerifier,TelegramRateLimit}.java \
  integration-jvm/TestMillionWordsStress.java
# Prevent unbounded stress runs; 96-MiB heap is lower than the 48-MiB media
# plus >1m-word history plus staging overhead when naively buffered.
timeout 120s java -Xmx96m -cp "$OUT" TestMillionWordsStress
