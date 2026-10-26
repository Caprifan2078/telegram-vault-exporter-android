#!/usr/bin/env python3
"""Offline CI tests: deterministic build recipe, untrusted binary checks and UI safety gates.
These are static/unit tests; they do not prove the APK compiles or runs.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import importlib.util
import subprocess
import struct

ROOT = Path(__file__).resolve().parent.parent

def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    imported = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(imported)
    return imported

preparer=module('preparer', ROOT/'scripts/prepare_tdlib_android.py')
verifier=module('verifier', ROOT/'scripts/verify_tdlib_elf.py')

# Reproduce exactly the recipe clause fetched from the pinned official upstream
# example/android/Dockerfile; the C++ source tree is left untouched.
fixture='''FROM ubuntu:24.04 AS build
ADD "https://api.github.com/repos/tdlib/td/git/refs/heads/master" version.json
ARG COMMIT_HASH=master
RUN git clone https://github.com/tdlib/td.git && cd td && git checkout "$COMMIT_HASH"
RUN td/example/android/build-tdlib.sh SDK "$ANDROID_NDK_VERSION" openssl "$ANDROID_STL" "$TDLIB_INTERFACE" && rm -rf td/example/android/build-*
'''
patched=preparer.patch(fixture)
assert patched.count('ARG VAULT_ANDROID_ABIS=arm64-v8a')==1
assert '"$VAULT_ANDROID_API_LEVEL" "$VAULT_ANDROID_ABIS"' in patched
assert 'git checkout "$COMMIT_HASH"' in patched
assert 'refs/heads/master' not in patched
for unreviewed in (fixture.replace('ARG COMMIT_HASH=master','ARG COMMIT_HASH=stable'),
                   fixture.replace(' && rm -rf', ' || rm -rf'), patched):
    try: preparer.patch(unreviewed)
    except ValueError: pass
    else: raise AssertionError('Unknown/unreviewed official build recipe accepted')

# Build a harmless Linux fixture: binary ABI rejection must occur even if the
# right JSON symbols happen to exist. This is NOT official TDLib or Android.
with TemporaryDirectory(prefix='tdlib-elf-test-') as tmp:
    dir=Path(tmp)
    c=dir/'fake.c'
    c.write_text('int td_create_client_id(void){return 1;} '
                 'void td_send(int id,const char*q){(void)id;(void)q;} '
                 'const char*td_receive(double n){(void)n;return "{}";} '
                 'const char*td_execute(const char*q){(void)q;return "{}";}')
    elf=dir/'libtdjson.so'
    subprocess.run(['gcc','-shared','-fPIC',str(c),'-o',str(elf)],check=True)
    with elf.open('ab') as f: f.write(b'\\0'*(512_001-elf.stat().st_size))
    try: verifier.verify(elf,dir)
    except ValueError as e: assert 'AArch64' in str(e)
    else: raise AssertionError('Accepted host x86_64 Linux library as Android')
    # Mock the ELF machine field in the *test fixture* only to exercise
    # remaining export/dependency checks; never publish the mock as TDLib.
    raw=bytearray(elf.read_bytes());struct.pack_into('<H',raw,18,183);elf.write_bytes(raw)
    result=verifier.verify(elf,dir)
    assert set(result['required_json_symbols'])==verifier.REQUIRED
    assert result['abi']=='arm64-v8a'
    c.write_text('int td_create_client_id(void){return 1;}')
    subprocess.run(['gcc','-shared','-fPIC',str(c),'-o',str(elf)],check=True)
    with elf.open('ab') as f: f.write(b'\\0'*(512_001-elf.stat().st_size))
    raw=bytearray(elf.read_bytes());struct.pack_into('<H',raw,18,183);elf.write_bytes(raw)
    try: verifier.verify(elf,dir)
    except ValueError as e: assert 'Missing TDLib JSON exports' in str(e)
    else: raise AssertionError('Accepted incomplete JSON symbol ABI')

# Activity recreation while ACTION_CREATE_DOCUMENT is open cannot preserve
# passwords in Bundles. Reject a provider result without an in-memory secret.
activity=(ROOT/'app/src/main/java/org/vault/exporter/MainActivity.java').read_text()
assert 'if (pendingPassphrase == null || pendingChatId == 0 || selectedChatId != pendingChatId || exporting)' in activity
assert 'final long exportChatId=pendingChatId;' in activity
assert 'export(exportChatId,out,password)' in activity
assert 'Arrays.fill(pendingPassphrase' in activity
assert 'final long resetChatId=selectedChatId;' in activity
assert '"export-progress"),resetChatId,secret)' in activity
print('PASS: pinned arm64-only TDLib build gate, ELF ABI negative fixtures, picker password-loss safety')

# Test-DC NETWORK build is separate from all offline demonstrations and production.
gradle=(ROOT/'app/build.gradle').read_text()
manifest_main=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
manifest_debug=(ROOT/'app/src/debug/AndroidManifest.xml').read_text()
manifest_sandbox=(ROOT/'app/src/sandboxDc/AndroidManifest.xml').read_text()
# Regression: the network networkDc refactor changed MainActivity's offline fence.
# An old Gradle string check silently broke credential-free offline builds.
assert "offlineDemoOnly = telegramEnvironment == 'test'" in gradle
assert "endsWith('assembledebug')" in gradle
assert "endsWith('assemblesandboxdc')" in gradle
assert "tasksRequested.size() == 1" in gradle
assert """offlineManifest.text.contains('tools:node="remove"')""" in gradle
assert "activitySource.contains('BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_TEST_DC_NETWORK')" in gradle
assert "activitySource.contains('if (BuildConfig.DEBUG || (BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_TEST_DC_NETWORK)')" in gradle
assert 'offlineSandboxOnly' not in gradle
assert 'Offline APK must contain NO Telegram API credentials' in gradle
assert 'Missing developer Telegram API credentials' in gradle
print('PASS: zero-secret offline APK build gate; network APK requires developer credentials')

assert "networkDc {" in gradle and "applicationIdSuffix '.networktest'" in gradle
assert "buildConfigField 'boolean', 'ALLOW_TEST_DC_NETWORK', 'false'" in gradle
assert "buildConfigField 'boolean', 'ALLOW_TEST_DC_NETWORK', 'true'" in gradle
assert "buildConfigField 'boolean', 'TELEGRAM_TEST_DC', 'true'" in gradle
assert "buildConfigField 'boolean', 'ALLOW_PRODUCTION', 'false'" in gradle
assert "sandboxDc/networkDc APK must always use telegramEnvironment=test" in gradle
assert 'uses-permission android:name="android.permission.INTERNET"' in manifest_main
for manifest in (manifest_debug, manifest_sandbox):
    assert 'tools:node="remove"' in manifest
assert not (ROOT/'app/src/networkDc/AndroidManifest.xml').exists(), 'Test network manifest must inherit approved main INTERNET only'
assert 'BuildConfig.TELEGRAM_TEST_DC && BuildConfig.ALLOW_TEST_DC_NETWORK' in activity
assert 'BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_TEST_DC_NETWORK' in activity
assert 'private void beginConnect()' in activity
assert '.put("use_test_dc",BuildConfig.TELEGRAM_TEST_DC)' in activity
assert 'if (BuildConfig.DEBUG || (BuildConfig.TELEGRAM_TEST_DC && !BuildConfig.ALLOW_TEST_DC_NETWORK)' in activity
assert 'button("Подключить тестовый Telegram (отдельный аккаунт)", this::askBeforeConnect);' in activity
print('PASS: isolated opt-in test-DC network build; offline variants remain disconnected')

# Pinned native build recipe must use current supported OpenSSL, not old 1.1.1.
native_script=(ROOT/'scripts/build_official_tdlib_android.sh').read_text()
assert 'https://github.com/tdlib/td.git' in native_script
assert 'ea3f69ce11aef458bfbb09869b809ede4447486f' in native_script
assert '0f00a668585883a403ffe3c63f2567d25af72d49' in native_script
assert 'OPENSSL_VERSION=openssl-3.5.9' in native_script
assert 'ANDROID_NDK_VERSION=26.3.11579264' in native_script
assert 'TDLIB_INTERFACE=JSON' in native_script
assert 'verify_tdlib_elf.py' in native_script
assert 'GITHUB_ACTIONS:-' in native_script
assert 'docker build' in native_script and 'git -C "$WORK/source" fetch' in native_script
assert 'app/src/main/jniLibs/**/*.so' in (ROOT/'.gitignore').read_text()
subprocess.run(['bash','-n',str(ROOT/'scripts/build_official_tdlib_android.sh')],check=True)
print('PASS: pinned TDLib/OpenSSL arm64 builder recipe and local-only installation contract')
