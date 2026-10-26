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
                 'const char*td_receive(double n){(void)n;return "{}";}')
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
