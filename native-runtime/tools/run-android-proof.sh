#!/usr/bin/env bash
set -euo pipefail
remote="/data/local/tmp/dshm-native017-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
[[ "$remote" =~ ^/data/local/tmp/dshm-native017-[a-zA-Z0-9-]+$ ]] || exit 64
prefix="$PWD/build/android-node/extract/data/data/com.termux/files/usr"
python3 native-runtime/tools/stage-android-test.py "$prefix" native-runtime/sdk build/android-test-stage
adb shell "mkdir -p '$remote/home' '$remote/tmp' '$remote/code' '$remote/reports'"
adb push build/android-test-stage/node "$remote/usr" >/dev/null
adb push native-runtime/lib "$remote/code/lib" >/dev/null
adb push native-runtime/test "$remote/code/test" >/dev/null
adb push build/android-test-stage/core "$remote/core" >/dev/null
adb push build/android-x64/libdshm_flock.so "$remote/libdshm_flock.so" >/dev/null
adb shell "chmod 700 '$remote/usr/bin/node'"
base="env HOME=$remote/home TMPDIR=$remote/tmp OPENSSL_CONF=/dev/null LD_LIBRARY_PATH=$remote/usr/lib DSHM_ANDROID_FLOCK_PATH=$remote/libdshm_flock.so DSHM_NATIVE_CORE_ROOT=$remote/core DSHM_NATIVE_REPORT_DIR=$remote/reports DSHM_EXPECT_PLATFORM=android"
adb shell "$base '$remote/usr/bin/node' '$remote/code/test/native-contract.mjs' android" | tee build/android-native-lock.json
adb shell "$base '$remote/usr/bin/node' --expose-internals '$remote/code/test/session-contract.mjs'" | tee build/android-session-stdout.json
adb pull "$remote/reports/native-session-contract.json" build/android-session-contract.json >/dev/null
python3 - <<'CHECK'
import json,pathlib
for name in ['android-native-lock.json','android-session-contract.json']:
 d=json.loads((pathlib.Path('build')/name).read_text());assert d['passed'] is True,(name,d);assert d['platform']=='android';print(name, 'actual Android',d['node'])
CHECK
# CI emulator scratch only; no command in this script targets a physical phone.
adb shell "rm -rf '$remote'"
