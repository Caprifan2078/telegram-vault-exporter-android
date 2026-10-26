#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v g++ >/dev/null || { echo 'OFFLINE JNI TEST REQUIRE g++' >&2; exit 1; }
command -v javac >/dev/null || { echo 'OFFLINE JNI TEST REQUIRE JDK' >&2; exit 1; }
JAVA_HOME_DETECTED="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
OUT=build/native-host-test
mkdir -p "$OUT"
g++ -shared -fPIC -std=c++17 -O2 -o "$OUT/libtdjson.so" native-host-test/FakeTdJson.cpp
g++ -shared -fPIC -std=c++17 -O2 \
  -I"$JAVA_HOME_DETECTED/include" -I"$JAVA_HOME_DETECTED/include/linux" \
  -o "$OUT/libvaultbridge.so" app/src/main/cpp/vaultbridge.cpp -ldl
javac -d "$OUT" app/src/main/java/org/vault/exporter/TdNative.java native-host-test/TestNativeHost.java
LD_LIBRARY_PATH="$(pwd)/$OUT${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  java -Djava.library.path="$(pwd)/$OUT" -cp "$OUT" TestNativeHost
