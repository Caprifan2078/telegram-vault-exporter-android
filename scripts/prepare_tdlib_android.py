#!/usr/bin/env python3
"""Patch ONLY the pinned upstream Android build recipe, never TDLib C++ source.

Upstream example/android/Dockerfile defaults to building four Android ABIs.
Vault currently packages only arm64-v8a, so building others is unnecessary and
increases CI time/cost. Abort on unknown recipes rather than guessing.
"""
import argparse
from pathlib import Path

BEFORE = ('RUN td/example/android/build-tdlib.sh SDK "$ANDROID_NDK_VERSION" '
          'openssl "$ANDROID_STL" "$TDLIB_INTERFACE" && rm -rf td/example/android/build-*')
CACHE_BUSTER = 'ADD "https://api.github.com/repos/tdlib/td/git/refs/heads/master" version.json\n'
AFTER = ('ARG VAULT_ANDROID_API_LEVEL=26\nARG VAULT_ANDROID_ABIS=arm64-v8a\n'
         'RUN td/example/android/build-tdlib.sh SDK "$ANDROID_NDK_VERSION" '
         'openssl "$ANDROID_STL" "$TDLIB_INTERFACE" '
         '"$VAULT_ANDROID_API_LEVEL" "$VAULT_ANDROID_ABIS" '
         '&& rm -rf td/example/android/build-*')


def patch(text: str) -> str:
    if text.count(BEFORE) != 1 or AFTER in text:
        raise ValueError('Pinned upstream TDLib Dockerfile changed, refusing unsafe automatic patch')
    if 'ARG COMMIT_HASH=master' not in text or 'git checkout "$COMMIT_HASH"' not in text:
        raise ValueError('TDLib commit pinning support absent; stop build')
    if text.count(CACHE_BUSTER) != 1:
        raise ValueError('Unexpected upstream Docker network cache-buster; stop build')
    # Unrelated moving-master API requests defeat a pinned, reproducible build.
    return text.replace(BEFORE, AFTER).replace(CACHE_BUSTER, '')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('dockerfile', type=Path)
    args = parser.parse_args()
    src = args.dockerfile.read_text(encoding='utf-8')
    updated = patch(src)
    args.dockerfile.write_text(updated, encoding='utf-8')
    print('PASS: pinned TDLib Docker recipe restricted to arm64-v8a/API 26; upstream C++ untouched')

if __name__ == '__main__':
    main()
