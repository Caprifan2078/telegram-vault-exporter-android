#!/usr/bin/env python3
"""Source-level regression contracts for Android picker/worker races.

This cannot replace Android instrumentation or runtime lifecycle testing.
"""
from pathlib import Path
import re

source = (Path(__file__).resolve().parents[1] /
          'app/src/main/java/org/vault/exporter/MainActivity.java').read_text()

def section(first: str, next_one: str):
    start = source.index(first)
    end = source.index(next_one, start + len(first))
    return source[start:end]

picker = section('private void requestPassphrase()', '@Override protected void onActivityResult')
result = section('@Override protected void onActivityResult', 'private void confirmResetProgress()')
reset = section('private void confirmResetProgress()', 'private void confirmLogout()')
finish = section('@Override protected void onDestroy()', '\n}')

checks = {
    'Chat ID captured before opening picker': 'final long dialogChatId=selectedChatId;' in picker,
    'Second picker blocked while pending': 'exporting || pendingPassphrase!=null' in picker,
    'Chat changes during password dialog fail closed': 'dialogChatId!=selectedChatId' in picker,
    'Pending choice tied to dialog snapshot': 'pendingChatId=dialogChatId;' in picker,
    'Picker restoration cannot use absent password': 'pendingPassphrase == null' in result,
    'Picker restoration cannot use absent chat ID': 'pendingChatId == 0' in result,
    'Picker cannot export a newly selected chat': 'selectedChatId != pendingChatId' in result,
    'Actual export freezes original picker chat': 'final long exportChatId=pendingChatId;' in result,
    'Picker identity cleared when export begins': re.search(r'final long exportChatId=pendingChatId;.*?pendingChatId=0;', result, re.S) is not None,
    'Untrusted document provider placeholder deleted': re.search(r'provider=destination\.getAuthority\(\).*?pendingChatId=0;.*?getContentResolver\(\)\.delete\(destination', result, re.S) is not None,
    'Reset prohibited during active/pending export': 'exporting || pendingPassphrase!=null' in reset,
    'Reset captures target before asynchronous execution': 'final long resetChatId=selectedChatId;' in reset,
    'Reset worker uses frozen target rather than mutable UI state': '"export-progress"),resetChatId,secret)' in reset and '"export-progress"),selectedChatId,secret)' not in reset,
    'No picker password persisted in Bundle': 'savedInstanceState.put' not in picker and 'savedInstanceState.put' not in result,
    'Activity destruction clears pending chat/password': 'pendingChatId=0;' in finish and "Arrays.fill(pendingPassphrase,'\\0')" in finish,
}
for name, passed in checks.items():
    if not passed:
        raise AssertionError('FAIL: ' + name)
print('PASS:', len(checks), 'static Android selection/picker/worker lifecycle safety contracts')
