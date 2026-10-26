#!/usr/bin/env python3
"""Static denylist checks for accidental privacy regressions. NOT a security audit."""
from pathlib import Path
import xml.etree.ElementTree as ET
import re
root=Path(__file__).resolve().parent.parent
manifest=root/'app/src/main/AndroidManifest.xml'
android='{http://schemas.android.com/apk/res/android}'
tree=ET.parse(manifest).getroot()
perms={p.get(android+'name') for p in tree.findall('uses-permission')}
assert perms=={'android.permission.INTERNET'},f'Extra permissions: {perms}'
app=tree.find('application')
assert app is not None
assert app.get(android+'allowBackup')=='false'
assert app.get(android+'usesCleartextTraffic')=='false'
assert app.get(android+'dataExtractionRules')=='@xml/data_extraction_rules'
assert app.get(android+'label')=='Vault Exporter'
for node in app.iter():
    if node.tag in ('service','receiver','provider') and node.get(android+'exported')=='true':
        raise AssertionError('Unexpected exported component: '+str(node.get(android+'name')))
extract=(root/'app/src/main/res/xml/data_extraction_rules.xml').read_text()
assert '<device-transfer>' in extract and '<cloud-backup>' in extract
source='\n'.join(f.read_text() for f in (root/'app/src/main/java').rglob('*.java'))
for pattern in [r'https?://(?!example\.invalid)',r'\bHttpURLConnection\b',r'\bOkHttpClient\b',r'\bFirebase\w*\b',r'\bCrashlytics\b',r'\bSentry\b',r'\bWebView\b',r'\bAdvertisingIdClient\b',r'\bandroid\.util\.Log\b']:
    if re.search(pattern, source, re.I): raise AssertionError('Unreviewed network/analytics construct: '+pattern)
print('PASS: static manifest, backup and outbound-code safeguards (not a security audit)')
# Ensure real Telegram isn't reachable from the default (test) build.
gradle=(root/'app/build.gradle').read_text()
activity=(root/'app/src/main/java/org/vault/exporter/MainActivity.java').read_text()
assert "orElse('test')" in gradle
assert "approveProductionBuild" in gradle
assert "if (telegramEnvironment == 'production' && !productionApproved)" in gradle
assert "'org.vault.exporter.sandbox'" in gradle
assert '.put("use_test_dc",BuildConfig.TELEGRAM_TEST_DC)' in activity
assert 'private void askBeforeConnect()' in activity
assert 'private void showOfflineDemo()' in activity
assert 'showWelcome();' in activity
print('PASS: test DC default, production opt-in, separate app identities, no auto-connect')


# Avoid unnecessary immutable copies of the database encryption secret in UI startup.
assert 'firstKey' not in activity
assert 'String key = Base64.encodeToString(secret' not in activity
