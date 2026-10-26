#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/transport-test-classes
mkdir -p "$OUT"
javac -d "$OUT" \
  integration-jvm/fakes/org/json/*.java \
  transport-jvm/fakes/org/vault/exporter/TdNative.java \
  app/src/main/java/org/vault/exporter/{TdGateway,TelegramRateLimit}.java \
  transport-jvm/TestTdGatewayLifecycle.java
java -Xmx32m -cp "$OUT" org.vault.exporter.TestTdGatewayLifecycle
