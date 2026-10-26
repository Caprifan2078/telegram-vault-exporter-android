#!/usr/bin/env bash
# Manual developer-only official TDLib build. NO Telegram account or secrets.
set -euo pipefail
umask 077
export DOCKER_BUILDKIT=1
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TDLIB_COMMIT="${TDLIB_COMMIT:-ea3f69ce11aef458bfbb09869b809ede4447486f}"
UPSTREAM_DOCKERFILE_BLOB=0f00a668585883a403ffe3c63f2567d25af72d49
if [[ ! "$TDLIB_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'ABORT: TDLIB_COMMIT must be a full lowercase 40-digit Git SHA' >&2
  exit 2
fi
# Network-heavy native compilation is opt-in ONLY on a public GitHub runner.
# Neither a private repository nor an unrelated workflow may trigger it.
if [[ -n "${GITHUB_ACTIONS:-}" ]] &&
   [[ "${GITHUB_REPOSITORY_VISIBILITY:-}" != 'public' ||
      "${VAULT_ALLOW_PUBLIC_CI_TDLIB_BUILD:-}" != '1' ]]; then
  echo 'ABORT: Android native CI requires explicit opt-in on a public repository' >&2
  exit 2
fi
for binary in docker git python3 readelf; do
  command -v "$binary" >/dev/null || { echo "ABORT: missing tool: $binary" >&2; exit 2; }
done
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/source" "$WORK/context" "$WORK/output" "$WORK/libs"
git -C "$WORK/source" init -q
git -C "$WORK/source" remote add origin https://github.com/tdlib/td.git
git -C "$WORK/source" fetch -q --depth=1 origin "$TDLIB_COMMIT"
git -C "$WORK/source" checkout -q --detach FETCH_HEAD
ACTUAL="$(git -C "$WORK/source" rev-parse HEAD)"
[[ "$ACTUAL" == "$TDLIB_COMMIT" ]] || { echo 'ABORT: upstream commit changed' >&2; exit 1; }
RECIPE="$(git -C "$WORK/source" rev-parse HEAD:example/android/Dockerfile)"
[[ "$RECIPE" == "$UPSTREAM_DOCKERFILE_BLOB" ]] || {
  echo 'ABORT: official Docker recipe changed; review and re-pin before building' >&2
  exit 1
}
for input in Dockerfile check-environment.sh fetch-sdk.sh build-openssl.sh; do
  cp "$WORK/source/example/android/$input" "$WORK/context/$input"
done
python3 "$ROOT/scripts/prepare_tdlib_android.py" "$WORK/context/Dockerfile"
echo "Building official TDLib JSON API for Android arm64 from $TDLIB_COMMIT"
docker build --platform linux/amd64 \
  --build-arg "COMMIT_HASH=$TDLIB_COMMIT" \
  --build-arg TDLIB_INTERFACE=JSON \
  --build-arg ANDROID_NDK_VERSION=26.3.11579264 \
  --build-arg OPENSSL_VERSION=openssl-3.5.9 \
  --output "type=local,dest=$WORK/output" \
  "$WORK/context"
ZIP="$WORK/output/tdlib.zip"
[[ -s "$ZIP" ]] || { echo 'ABORT: official builder did not produce tdlib.zip' >&2; exit 1; }
# Extract only official native arm64 libraries, never arbitrary ZIP paths.
python3 - "$ZIP" "$WORK/libs" <<'PY'
from pathlib import Path
import sys, zipfile, shutil
src=Path(sys.argv[1]); dest=Path(sys.argv[2]); found=[]
with zipfile.ZipFile(src) as z:
    entries=[i for i in z.infolist() if not i.is_dir() and
             'arm64-v8a' in Path(i.filename).parts and i.filename.endswith('.so')]
    if not entries: raise SystemExit('ABORT: no arm64-v8a libraries in official TDLib ZIP')
    for entry in entries:
        name=Path(entry.filename).name
        if not name.startswith('lib') or not name.endswith('.so') or name in found:
            raise SystemExit('ABORT: unexpected/duplicate ZIP library: '+name)
        found.append(name)
        with z.open(entry) as r, (dest/name).open('xb') as w:
            shutil.copyfileobj(r,w)
if 'libtdjson.so' not in found: raise SystemExit('ABORT: official ZIP lacks libtdjson.so')
print('Official bundled libraries:',', '.join(sorted(found)))
PY
python3 "$ROOT/scripts/verify_tdlib_elf.py" "$WORK/libs/libtdjson.so" \
  --output "$WORK/tdlib-verified.json"
INSTALL="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$INSTALL"
for lib in "$WORK/libs"/*.so; do
  cp "$lib" "$INSTALL/$(basename "$lib")"
done
mkdir -p "$ROOT/build/tdlib-provenance"
python3 - "$WORK/tdlib-verified.json" "$ROOT/build/tdlib-provenance/verified-build.json" "$TDLIB_COMMIT" "$RECIPE" <<'PY'
import json,sys
from pathlib import Path
record=json.loads(Path(sys.argv[1]).read_text())
record.update(upstream='https://github.com/tdlib/td', commit=sys.argv[3],
              recipe_blob_sha=sys.argv[4], openssl='openssl-3.5.9',
              ndk='26.3.11579264', interface='JSON', build='official-android-arm64')
Path(sys.argv[2]).write_text(json.dumps(record, indent=2, sort_keys=True)+'\n')
PY
echo 'PASS: pinned official TDLib installed locally after ELF checks.'
echo 'No credentials or chat data used. Library and provenance ignored by Git.'
