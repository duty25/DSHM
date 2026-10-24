#!/usr/bin/env bash
set -euo pipefail
STAGE=init
trap 'rc=$?; echo "::error title=Native017 stage failed::stage=$STAGE rc=$rc"; exit $rc' ERR

remote="/data/local/tmp/dshm-native017-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
[[ "$remote" =~ ^/data/local/tmp/dshm-native017-[a-zA-Z0-9-]+$ ]] || exit 64
prefix="$PWD/build/android-node/extract/data/data/com.termux/files/usr"

STAGE=stage
python3 native-runtime/tools/stage-android-test.py "$prefix" native-runtime/sdk build/android-test-stage

STAGE=mkdir
adb shell "mkdir -p '$remote/home' '$remote/tmp' '$remote/code' '$remote/reports'"

STAGE=push-node
adb push build/android-test-stage/node "$remote/usr" >/dev/null
STAGE=push-lib
adb push native-runtime/lib "$remote/code/lib" >/dev/null
STAGE=push-test
adb push native-runtime/test "$remote/code/test" >/dev/null
STAGE=push-core
adb push build/android-test-stage/core "$remote/core" >/dev/null
STAGE=push-flock
adb push build/android-x64/libdshm_flock.so "$remote/libdshm_flock.so" >/dev/null

STAGE=chmod
adb shell "chmod 700 '$remote/usr/bin/node'"

base="env HOME=$remote/home TMPDIR=$remote/tmp OPENSSL_CONF=/dev/null LD_LIBRARY_PATH=$remote/usr/lib DSHM_ANDROID_FLOCK_PATH=$remote/libdshm_flock.so DSHM_NATIVE_CORE_ROOT=$remote/core DSHM_NATIVE_REPORT_DIR=$remote/reports DSHM_EXPECT_PLATFORM=android"

STAGE=lock
adb shell "$base '$remote/usr/bin/node' '$remote/code/test/native-contract.mjs' android" | tee build/android-native-lock.json
STAGE=publish
adb shell "$base '$remote/usr/bin/node' '$remote/code/test/publish-contract.mjs' android" | tee build/android-native-publish.json
STAGE=session
adb shell "$base '$remote/usr/bin/node' --expose-internals '$remote/code/test/session-contract.mjs'" | tee build/android-session-stdout.json
adb pull "$remote/reports/native-session-contract.json" build/android-session-contract.json >/dev/null
STAGE=fs
adb shell "$base '$remote/usr/bin/node' --expose-internals '$remote/code/test/fs-contract.mjs'" | tee build/android-fs-stdout.json
adb pull "$remote/reports/native-fs-contract.json" build/android-fs-contract.json >/dev/null
STAGE=host
adb shell "$base '$remote/usr/bin/node' --expose-internals '$remote/code/test/host-contract.mjs'" | tee build/android-host-stdout.json
adb pull "$remote/reports/native-host-contract.json" build/android-host-contract.json >/dev/null

STAGE=verify
python3 - <<'CHECK'
import json, pathlib
for name in ['android-native-lock.json', 'android-native-publish.json', 'android-session-contract.json', 'android-fs-contract.json', 'android-host-contract.json']:
    d = json.loads((pathlib.Path('build') / name).read_text())
    assert d['passed'] is True, (name, d)
    assert d['platform'] == 'android'
    print('::notice title=Android native evidence::' + name + ' platform=' + d['platform'] + ' node=' + d['node'] + ' passed=' + str(len(d.get('checks', d.get('cases', [])))))
CHECK

# CI emulator scratch only; no command in this script targets a physical phone.
STAGE=cleanup
adb shell "rm -rf '$remote'"
