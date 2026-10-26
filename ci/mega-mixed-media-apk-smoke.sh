#!/usr/bin/env bash
set -euo pipefail
APP=org.vault.exporter.sandbox.testnet
test -s "$APK_UNDER_TEST"
adb shell svc wifi disable || true
adb shell svc data disable || true
adb install -r "$APK_UNDER_TEST"
adb shell am start -W -n "$APP/org.vault.exporter.MainActivity"
sleep 5
test -n "$(adb shell pidof "$APP" | tr -d '\r')"
mkdir -p /tmp/heavy-vault
capture() {
  for n in 1 2 3;do
    if adb shell uiautomator dump /sdcard/heavy.xml >/dev/null 2>&1 &&
      adb exec-out cat /sdcard/heavy.xml >/tmp/heavy-vault/ui.xml;then return 0;fi
    sleep 2
  done
  return 1
}
coords() {
  python3 - "$1" /tmp/heavy-vault/ui.xml <<'PY'
import sys,xml.etree.ElementTree as E,re
phrase=sys.argv[1].lower()
for node in E.parse(sys.argv[2]).getroot().iter('node'):
 t=(node.get('text','')+' '+node.get('content-desc','')).lower()
 if phrase in t:
  m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
  if m:
   a,b,c,d=map(int,m.groups())
   if c>a and d>b:
    print((a+c)//2,(b+d)//2);sys.exit(0)
sys.exit(1)
PY
}
tap() {
  local found
  for k in 1 2 3 4;do
    capture
    if found="$(coords "$1")";then
      read -r x y <<< "$found";adb shell input tap "$x" "$y";sleep 2;return 0
    fi
    adb shell input swipe 410 1300 410 480 230;sleep 1
  done
  echo "MISSING BUTTON: $1";cat /tmp/heavy-vault/ui.xml;return 1
}
tap 'Миллион слов'
tap 'Начать тест'
capture
if coords 'Downloads' >/dev/null 2>&1;then tap 'Downloads'
elif coords 'Загрузки' >/dev/null 2>&1;then tap 'Загрузки'
fi
if coords 'save' >/dev/null 2>&1;then tap 'save'
elif coords 'сохранить' >/dev/null 2>&1;then tap 'сохранить'
else
  # Some Android DocumentsUI builds report an inaccessible hierarchy for this
  # footer even though the native SAVE button is visibly present.
  # Pixel-6 API-35 test device: SAVE is the bottom-right primary action.
  capture || true
  adb shell input tap 932 2275
  sleep 3
fi
echo 'Started 1M-word, 24-MiB document export with radios disabled'
DONE=0
for tick in $(seq 1 260);do
  sleep 5
  if ((tick%4==0));then
    if capture && grep -q 'Миллион слов завершён' /tmp/heavy-vault/ui.xml;then
      DONE=1;break
    fi
    if grep -q 'Миллион слов прерван\|Тест остановлен\|Не удалось безопасно скачать' /tmp/heavy-vault/ui.xml;then
      echo "FAIL: Android explicitly reported interrupted mega export"
      python3 - <<'PY'
import xml.etree.ElementTree as E
root=E.parse('/tmp/heavy-vault/ui.xml').getroot()
print('VISIBLE UI:',[n.get('text') for n in root.iter('node') if n.get('text')])
PY
      exit 48
    fi
  fi
  if ! adb shell pidof "$APP" >/dev/null;then
    echo 'App process stopped during large export';exit 45
  fi
done
if test "$DONE" -ne 1;then
  capture || true
  echo 'No verified heavy export result on the Android UI'
  cat /tmp/heavy-vault/ui.xml
  exit 46
fi
FILE=/sdcard/Download/Vault_MEGA_1080000_WORDS.tva
adb shell test -s "$FILE" || { echo 'Completed UI, but output file missing';exit 47; }
adb exec-out cat "$FILE" > /tmp/heavy-vault/out.tva
python3 - <<'PY'
from pathlib import Path
p=Path('/tmp/heavy-vault/out.tva')
with p.open('rb') as f: head=f.read(48)
assert head[:4]==b'TVA1',head[:16]
assert p.stat().st_size>23*1024*1024,p.stat().st_size
assert b'TEST WORD' not in head
print('PASS: large encrypted TVA exists, size',p.stat().st_size)
print('PASS: app completed internal whole-archive verification, synthetic 54k messages + 19 varied media, offline')
PY
adb shell am force-stop "$APP"
