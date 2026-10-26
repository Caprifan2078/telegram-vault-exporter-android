#!/usr/bin/env python3
"""Contracts for the CURRENT minimal, zero-secret GitHub migration repository.

This is only a static source/CI guard; it does not prove Android functionality,
Telegram account safety, or a production-ready APK.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent

def read(relative):
    p = root / relative
    assert p.is_file(), 'Required tracked file missing: ' + relative
    return p.read_text(encoding='utf-8')

workflow_path = '.github/workflows/public-offline-qa.yml'
workflow = read(workflow_path)
# The migrated repository intentionally has NO network or APK-building CI.
actual = {p.name for p in (root / '.github/workflows').glob('*.yml')}
assert actual == {'public-offline-qa.yml', 'build-offline-apk.yml'}, 'Unexpected workflow needs independent review: ' + repr(actual)
manual = read('.github/workflows/build-offline-apk.yml')
assert 'github.event.repository.private == false' in manual, 'Native CI must be public only'
assert 'persist-credentials: false' in manual, 'Checkout tokens must not persist'
assert 'timeout-minutes: 60' in manual and 'cancel-in-progress: true' in manual, 'Unbounded native build'
assert 'contents: read' in manual and 'secrets.' not in manual and 'pull_request_target:' not in manual, 'Unexpected CI permissions or secrets'
assert 'VAULT_ALLOW_PUBLIC_CI_TDLIB_BUILD:' in manual and 'scripts/build_official_tdlib_android.sh' in manual
assert ':app:assembleDebug' in manual and '-PtelegramApiId=0' in manual and '-PtelegramApiHash=' in manual
assert 'scripts/verify_offline_apk.py' in manual and 'retention-days: 1' in manual
assert 'app/build/outputs/apk/debug/app-debug.apk' in manual and 'upload-artifact@v4' in manual
assert 'feat/isolated-testdc-android-build-20261026' in manual
assert '    paths:' in manual, 'One-shot native workflow must use a restricted push path'
assert 'github.event.repository.private == false' in workflow, 'Private hosted jobs must be disabled'
assert re.search(r'(?m)^permissions:\s*\n\s+contents:\s*read\s*$', workflow), 'Read-only CI permissions missing'
assert 'cancel-in-progress: true' in workflow and 'timeout-minutes: 15' in workflow, 'Bounded CI missing'
assert 'runs-on: ubuntu-latest' in workflow, 'Only standard runner may be used'
assert 'persist-credentials: false' in workflow, 'Checkout token persistence must be disabled'
assert 'secrets.' not in workflow and 'pull_request_target:' not in workflow, 'Unreviewed secrets/event usage'
for test in ('tests/RUN_TESTS_OFFLINE.sh', 'integration-jvm/RUN_INTEGRATION_JVM.sh',
             'transport-jvm/RUN_TRANSPORT_JVM.sh', 'native-host-test/RUN_NATIVE_HOST_TEST.sh',
             'ci-contract/verify_ci_contract.py', 'tests/RUN_BUILD_SAFETY_TESTS.py'):
    assert test in workflow, 'Missing CI regression suite: ' + test
assert 'upload-artifact' not in workflow, 'No data artifacts from offline CI'

build = read('app/build.gradle')
activity = read('app/src/main/java/org/vault/exporter/MainActivity.java')
debug_manifest = read('app/src/debug/AndroidManifest.xml')
sandbox_manifest = read('app/src/sandboxDc/AndroidManifest.xml')
local_suite = read('RUN_ALL_OFFLINE.sh')
assert "orElse('test')" in build and 'approveProductionBuild' in build, 'Production build guard absent'
assert "telegramEnvironment == 'production' && !productionApproved" in build, 'Production not fail-closed'
assert 'verifyExporterBuild' in build and 'libtdjson.so' in build, 'Native build gate missing'
assert 'tools:node="remove"' in debug_manifest and 'tools:node="remove"' in sandbox_manifest, 'Offline INTERNET exclusion missing'
assert 'BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_TEST_DC_NETWORK' in activity and 'private void showOfflineDemo()' in activity, 'Offline account fence missing'
assert 'AndroidManifest.xml' in read('scripts/security_gate.py'), 'Privacy static guard missing'
for test in ('tests/RUN_TESTS_OFFLINE.sh', 'integration-jvm/RUN_INTEGRATION_JVM.sh',
             'transport-jvm/RUN_TRANSPORT_JVM.sh', 'native-host-test/RUN_NATIVE_HOST_TEST.sh'):
    assert test in local_suite, 'Local full suite missing ' + test
print('PASS CI contracts: public-only offline QA, least-privilege, no secrets, guarded builds, integration regressions')
