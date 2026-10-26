#!/usr/bin/env python3
"""Verify an actual Android offline QA APK, not merely its Gradle sources.

Fails closed if APK still has INTERNET, wrong package, unexpected ABI, or lacks
TDLib and the application JNI bridge. Requires Android SDK's aapt on PATH.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile

PACKAGE = 'org.vault.exporter.sandbox'
LIBS = {'lib/arm64-v8a/libtdjson.so', 'lib/arm64-v8a/libvaultbridge.so'}


def inspect_apk(path: Path, aapt: str = 'aapt') -> dict:
    if not path.is_file() or path.stat().st_size < 500_000:
        raise ValueError('APK missing or implausibly small')
    package = subprocess.check_output([aapt, 'dump', 'badging', str(path)], text=True)
    match = re.search(r"^package: name='([^']+)'", package, re.MULTILINE)
    if not match or match.group(1) != PACKAGE:
        raise ValueError('APK package identity mismatch')
    permissions = subprocess.check_output([aapt, 'dump', 'permissions', str(path)], text=True)
    # The proof comes from Android's compiled APK manifest, not source files.
    if 'android.permission.INTERNET' in permissions:
        raise ValueError('Offline APK must not request INTERNET')
    if re.search(r"\buses-permission:\s+name=", permissions):
        raise ValueError('Offline APK has unexpected Android permissions')
    with zipfile.ZipFile(path) as z:
        paths = set(z.namelist())
        if not LIBS.issubset(paths):
            raise ValueError('APK missing native official TDLib or JNI bridge')
        so_files = {p for p in paths if p.startswith('lib/') and p.endswith('.so')}
        if not so_files or any(not p.startswith('lib/arm64-v8a/') for p in so_files):
            raise ValueError('APK contains unsupported native ABI')
    with path.open('rb') as handle:
        digest = hashlib.file_digest(handle, 'sha256').hexdigest()
    return {'apk': path.name, 'sha256': digest, 'bytes': path.stat().st_size,
            'package': PACKAGE, 'offline': True, 'arm64_libs': sorted(so_files)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--aapt', default='aapt')
    args = parser.parse_args()
    try:
        result = inspect_apk(args.apk, args.aapt)
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        raise SystemExit('ABORT: offline APK verification failed: ' + str(exc))
    for k, v in result.items():
        print(f'{k}: {v}')
    print('PASS: actual APK package, permissions, native ABI and checksum verified')


if __name__ == '__main__':
    main()
