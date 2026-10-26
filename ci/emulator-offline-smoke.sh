#!/usr/bin/env bash
set -euo pipefail
test -s "$APK_UNDER_TEST" || { echo "Missing quarantined APK"; exit 10; }
APP_ID=org.vault.exporter.sandbox
# No Telegram login and no network traffic from the tested app.
adb shell svc wifi disable || true
adb shell svc data disable || true
echo "Emulated platform and native ABI list:"
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.dalvik.vm.native.bridge
if ! adb install -r "$APK_UNDER_TEST"; then
  echo "FAIL: emulator does not support ARM64-only application or installation failed."
  exit 43
fi
if ! adb shell pm list packages "$APP_ID" | tr -d '\r' | grep -Fqx "package:$APP_ID"; then
  echo "FAIL: expected exact offline APK package not installed"; exit 42
fi
adb logcat -c
adb shell am start -W -n org.vault.exporter.sandbox/org.vault.exporter.MainActivity
sleep 4
PID="$(adb shell pidof "$APP_ID" | tr -d '\r')"
test -n "$PID" || { echo "FAIL: app crashed on launch"; exit 44; }
echo "PASS: app process running $PID"
capture_ui() {
  local out="$1" ok=0
  for attempt in 1 2 3 4 5; do
    if adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 &&
       adb exec-out cat /sdcard/window.xml > "$out" && test -s "$out"; then
      ok=1; break
    fi
    sleep 2
  done
  test "$ok" = 1 || { echo "FAIL: UI hierarchy inaccessible"; exit 45; }
}
capture_ui /tmp/vault-welcome.xml
python3 - /tmp/vault-welcome.xml > /tmp/offline-button-location.txt <<'PY'
import sys, re, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
texts=[n.get('text','') for n in root.iter('node')]
assert any('Vault Exporter' in t for t in texts), 'Welcome title absent'
assert any('ТЕСТ' in t.upper() and 'TELEGRAM' in t.upper() for t in texts), 'Test DC notice absent'
assert not any('Подключено напрямую' in t for t in texts), 'Unexpected authenticated state'
assert not any('Подключить тестовый Telegram' in t for t in texts), 'Network connect button visible in offline demo'
assert any('СТРОГО ОФЛАЙН-ДЕМО' in t for t in texts), 'Mandatory offline notice absent'
for n in root.iter('node'):
    if 'Посмотреть, как работает экспорт' in n.get('text',''):
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
        if m:
            x1,y1,x2,y2=map(int,m.groups())
            print((x1+x2)//2,(y1+y2)//2)
            break
else:
    raise AssertionError('Offline-demo button missing')
PY
read -r X Y < /tmp/offline-button-location.txt
adb shell input tap "$X" "$Y"
sleep 2
capture_ui /tmp/vault-offline-demo.xml
python3 - /tmp/vault-offline-demo.xml <<'PY'
import sys, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
texts=[n.get('text','') for n in root.iter('node')]
assert any('Офлайн-демонстрация' in t for t in texts), 'Offline demo dialog not opened'
assert any('Соединение с Telegram не выполняется' in t for t in texts), 'Missing no-connection disclaimer'
print('PASS: offline-only welcome and demo dialog')
PY
adb shell input keyevent KEYCODE_BACK
# The new on-device fixture must be reachable without ever contacting Telegram.
capture_ui /tmp/vault-fixture-home.xml
python3 - /tmp/vault-fixture-home.xml > /tmp/vault-fixture-tap.txt <<'PY'
import re, sys, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    if 'Создать проверочный архив .tva (офлайн)' in n.get('text',''):
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
        if m:
            a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2); break
else: raise AssertionError('Real encrypted offline archive button missing')
PY
read -r X Y </tmp/vault-fixture-tap.txt
adb shell input tap "$X" "$Y"
sleep 1
capture_ui /tmp/vault-fixture-dialog.xml
python3 - /tmp/vault-fixture-dialog.xml <<'PY'
import sys, xml.etree.ElementTree as ET
texts=[n.get('text','') for n in ET.parse(sys.argv[1]).getroot().iter('node')]
assert any('Создать настоящий учебный архив' in t for t in texts), 'Fixture picker confirmation not visible'
assert any('Никаких данных Telegram или сети' in t for t in texts), 'Fixture safety warning missing'
print('PASS: real encrypted archive fixture is user-accessible offline with no login')
PY
adb shell input keyevent KEYCODE_BACK
# Verify the real JNI bridge and official TDLib client lifecycle, no authentication.
capture_ui /tmp/vault-after-demo.xml
python3 - /tmp/vault-after-demo.xml >/tmp/tdlib-tap.txt <<'PY'
import sys,re,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    if 'Проверить нативную TDLib (офлайн)' in n.get('text',''):
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
        if m:
            a,b,c,d=map(int,m.groups())
            print((a+c)//2,(b+d)//2)
            break
else: raise AssertionError('Native diagnostic button not visible in debug/test build')
PY
read -r X Y </tmp/tdlib-tap.txt
adb shell input tap "$X" "$Y"
sleep 3
capture_ui /tmp/vault-native-probe.xml
python3 - /tmp/vault-native-probe.xml <<'PY'
import sys,xml.etree.ElementTree as ET
texts=[n.get('text','') for n in ET.parse(sys.argv[1]).getroot().iter('node')]
assert any('Диагностика TDLib' in t for t in texts), 'Diagnostic dialog not displayed on button tap: '+repr(texts[-12:])
assert any('Готово!' in t and 'Движок TDLib' in t for t in texts), 'No visible result in dialog: '+repr(texts[-12:])
assert any('TDLIB_OFFLINE_PROBE_PASS' in t for t in texts), 'JNI TDLib offline probe failed or did not complete: '+repr(texts[-12:])
print('PASS: native libtdjson client create, close and receive using real JNI offline')
PY
# Repeat the same native lifecycle on a fresh Activity/process without network.
adb shell am force-stop "$APP_ID"
adb shell am start -W -n "$APP_ID/org.vault.exporter.MainActivity"
sleep 3
capture_ui /tmp/vault-restarted.xml
python3 - /tmp/vault-restarted.xml >/tmp/restart-native-tap.txt <<'PY'
import sys,re,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
texts=[n.get('text','') for n in root.iter('node')]
assert any('Vault Exporter' in t for t in texts), 'Restart failed'
for n in root.iter('node'):
    if 'Проверить нативную TDLib (офлайн)' in n.get('text',''):
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
        if m:
            a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2); break
else: raise AssertionError('Native probe missing after process restart')
PY
read -r X Y </tmp/restart-native-tap.txt
adb shell input tap "$X" "$Y"
sleep 3
capture_ui /tmp/vault-restarted-probe.xml
python3 - /tmp/vault-restarted-probe.xml <<'PY'
import sys,xml.etree.ElementTree as ET
texts=[n.get('text','') for n in ET.parse(sys.argv[1]).getroot().iter('node')]
assert any('Диагностика TDLib' in t for t in texts), 'Diagnostic modal absent after restart'
assert any('TDLIB_OFFLINE_PROBE_PASS' in t for t in texts), 'Native bridge failed after restart'
print('PASS: TDLib JNI client reopened after process restart, offline')
PY
adb shell am force-stop "$APP_ID"
adb logcat -d -v brief >/tmp/vault-smoke-logcat.txt
if grep -E 'FATAL EXCEPTION|UnsatisfiedLinkError|No implementation found for' /tmp/vault-smoke-logcat.txt; then
  echo "FAIL: Android fatal or JNI errors detected"
  exit 46
fi
echo "PASS: emulator install, offline launch and demo without account or network"
