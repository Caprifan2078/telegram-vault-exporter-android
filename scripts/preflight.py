#!/usr/bin/env python3
"""Fail closed before Android build; an APK without TDLib is not an exporter."""
from pathlib import Path
import struct
import re

root = Path(__file__).resolve().parent.parent
native = root / 'app/src/main/jniLibs/arm64-v8a/libtdjson.so'
if not native.is_file() or native.stat().st_size < 512_000:
    raise SystemExit('ABORT: official Android arm64 TDLib binary is missing (not an installable exporter).')
with native.open('rb') as f:
    hdr = f.read(64)
if len(hdr)<20 or hdr[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H',hdr,18)[0] != 183:
    raise SystemExit('ABORT: TDLib must be a 64-bit little-endian ARM ELF library.')
manifest = (root/'app/src/main/AndroidManifest.xml').read_text()
perms = re.findall(r'<uses-permission\s+android:name="([^"]+)"',manifest)
if perms != ['android.permission.INTERNET']:
    raise SystemExit(f'ABORT: unexpected requested permissions: {perms}')
if 'android:allowBackup="false"' not in manifest or 'android:usesCleartextTraffic="false"' not in manifest:
    raise SystemExit('ABORT: unsafe manifest backup or HTTP policy')
print('PASS: ABI, TDLib presence and manifest policy')
