#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
ROOT=app/src/main/java/org/vault/exporter
javac -d build/test-classes \
  "$ROOT"/{HistoryCursor,StagingCipher,ChunkedVault,ResumeStore,ArchiveWriter,ArchiveVerifier,TelegramRateLimit}.java \
  tests/{RunCryptoTests,RunResumeTests,RunPipelineTests,RunRateLimitTests,RunStagingV2HardeningTests,RunHistoryCursorHardeningTests,RunStagingBoundedMemoryTest,RunSemanticArchiveVerifierTests,RunVerifierBoundedMemoryTest,RunExclusiveResumeLockTests,TryResumeLockChild,HoldResumeLockChild}.java
for cls in RunCryptoTests RunResumeTests RunPipelineTests RunRateLimitTests RunStagingV2HardeningTests RunHistoryCursorHardeningTests RunSemanticArchiveVerifierTests RunExclusiveResumeLockTests; do
  java -Xmx64m -cp build/test-classes "$cls"
done
java -Xmx32m -cp build/test-classes RunStagingBoundedMemoryTest
java -Xmx32m -cp build/test-classes RunVerifierBoundedMemoryTest
python3 scripts/security_gate.py

python3 tests/RUN_ACTIVITY_SELECTION_SAFETY_TESTS.py
