#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash tests/RUN_TESTS_OFFLINE.sh
bash integration-jvm/RUN_INTEGRATION_JVM.sh
bash transport-jvm/RUN_TRANSPORT_JVM.sh
bash native-host-test/RUN_NATIVE_HOST_TEST.sh
python3 ci-contract/verify_ci_contract.py

python3 tests/RUN_BUILD_SAFETY_TESTS.py
