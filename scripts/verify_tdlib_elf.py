#!/usr/bin/env python3
"""Fail-closed inspection of official Android TDLib JSON shared object.

This is a static ABI/packaging gate, not a runtime or supply-chain audit.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

REQUIRED = {'td_create_client_id', 'td_send', 'td_receive'}
DANGEROUS_DYNAMIC = {'libcrypto.so', 'libssl.so', 'libc++_shared.so'}


def run(*args):
    result = subprocess.run(['readelf', '-W', *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return result.stdout


def verify(file: Path, bundled_dir: Path):
    if not file.is_file() or file.stat().st_size < 512_000:
        raise ValueError('libtdjson.so missing or implausibly small')
    header = run('-h', str(file))
    if not re.search(r'Class:\s+ELF64\b', header):
        raise ValueError('TDLib not ELF64')
    if not re.search(r'Data:\s+2.s complement, little endian', header):
        raise ValueError('TDLib not little endian')
    if not re.search(r'Machine:\s+AArch64\b', header):
        raise ValueError('TDLib not Android arm64/AArch64')
    symbols = run('--dyn-syms', str(file))
    exported = set()
    for line in symbols.splitlines():
        cols = line.split()
        if len(cols) < 8 or not cols[0].endswith(':'):
            continue
        # Only exported, defined GLOBAL/WEAK functions count.
        if cols[4] not in ('GLOBAL','WEAK') or cols[6] == 'UND':
            continue
        exported.add(cols[7].split('@')[0])
    missing = REQUIRED - exported
    if missing:
        raise ValueError('Missing TDLib JSON exports: ' + ', '.join(sorted(missing)))
    dynamic = run('-d', str(file))
    needed = set(re.findall(r'\(NEEDED\)\s+Shared library: \[([^\]]+)\]', dynamic))
    for dependency in DANGEROUS_DYNAMIC & needed:
        if not (bundled_dir / dependency).is_file():
            raise ValueError('Non-system dependency not packaged: ' + dependency)
    digest=hashlib.sha256()
    with file.open('rb') as handle:
        for chunk in iter(lambda:handle.read(1024*1024),b''):
            digest.update(chunk)
    record = dict(sha256=digest.hexdigest(),
                  bytes=file.stat().st_size, abi='arm64-v8a',
                  required_json_symbols=sorted(REQUIRED), needed=sorted(needed))
    return record


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('library', type=Path)
    ap.add_argument('--output', type=Path)
    args = ap.parse_args()
    try:
        result = verify(args.library, args.library.parent)
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit('ABORT: official TDLib binary not verified: ' + str(exc))
    encoded = json.dumps(result, indent=2, sort_keys=True) + '\n'
    if args.output:
        args.output.write_text(encoded, encoding='utf-8')
    print('PASS: Android AArch64 ELF, TDLib JSON ABI and packaged dynamic deps checked')
    print(encoded)

if __name__ == '__main__':
    main()
