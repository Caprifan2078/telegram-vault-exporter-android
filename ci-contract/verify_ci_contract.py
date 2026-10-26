#!/usr/bin/env python3
"""Check CURRENT source CI safety contracts. Static checks, not release certification."""
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
def read(relative):
    p = root / relative
    assert p.is_file(), "Required tracked file missing: " + relative
    return p.read_text(encoding="utf-8")

build = read(".github/workflows/build-testdc-apk.yml")
network = read(".github/workflows/package-network-testdc.yml")
hardened = read(".github/workflows/hardened-source-ci.yml")
emulator = read(".github/workflows/emulator-offline-smoke.yml")
gradle = read("app/build.gradle")
debug_manifest = read("app/src/debug/AndroidManifest.xml")
all_tests = read("RUN_ALL_OFFLINE.sh")

# Verified pinned upstream TDLib and test-only config. Previously this contract
# referenced build-debug-apk.yml, which was removed during workflow hardening.
sha = "ea3f69ce11aef458bfbb09869b809ede4447486f"
assert sha in build and sha in network, "TDLib source pin missing"
assert "scripts/verify_tdlib_elf.py" in build and network, "Native ABI check missing"
assert "SHA256SUMS.txt" in build and network, "Native integrity check missing"
assert "NOT_READY_FOR_INSTALLATION.txt" in build, "Offline build quarantine missing"
assert "rm -f \"$TEST_APK\"" in build, "Network APK must not be uploaded by offline CI"
assert "telegramEnvironment=test" in build and network, "Test-DC default missing"
assert "ALLOW_PRODUCTION = false" in build and network, "Production denial missing"
assert "TELEGRAM_TEST_DC = true" in build and network, "Test-DC forced flag missing"
assert "org.vault.exporter.sandbox.testnet" in build and network, "Isolated package missing"
assert "TELEGRAM_API_ID" not in build and "TELEGRAM_API_HASH" not in build, "Offline build must not read real API secrets"
assert "secrets.TELEGRAM_API_ID" in network and "secrets.TELEGRAM_API_HASH" in network, "Manual test build missing scoped credentials"
assert "workflow_dispatch" in network, "Test network build requires manual path"
assert "actions/upload-artifact" in network, "Private quarantined output missing"
assert "AndroidManifest.xml" in read("scripts/security_gate.py"), "Static Android manifest gate missing"
assert "tools:node=\"remove\"" in debug_manifest, "Offline debug build may have INTERNET permission"
assert "assembleSandboxDc" in build and network, "Explicit sandboxDc build missing"
assert "productionApproved" in gradle and "approveProductionBuild" in gradle, "Production build guard missing"
assert "telegramEnvironment != 'test'" in gradle, "TestDC must reject production config"
assert "bash tests/RUN_TESTS_OFFLINE.sh" in hardened, "Offline CI suite missing"
assert "bash integration-jvm/RUN_INTEGRATION_JVM.sh" in hardened, "Export engine integration CI missing"
assert "bash native-host-test/RUN_NATIVE_HOST_TEST.sh" in hardened, "JNI CI missing"
assert "bash transport-jvm/RUN_TRANSPORT_JVM.sh" in hardened, "TDLib transport CI missing"
assert "bash native-host-test/RUN_NATIVE_HOST_TEST.sh" in all_tests, "Local JNI smoke missing"
assert "emulator-offline-smoke.sh" in emulator, "Offline emulator smoke missing"
print("PASS CI contracts: pinned TDLib, offline debug, isolated test DC, no production auto-enable")
