import json, pathlib, subprocess
r = pathlib.Path(__file__).resolve().parents[1]
c = json.loads((r/'upgrade/config.json').read_text())
g = (r/'android/app/build.gradle.kts').read_text()
assert 'applicationId = "com.siliconleap.app"' in g
assert 'applicationIdSuffix' not in g and 'managedPreview' not in g
assert 'versionCode = 2014300' in g and 'versionName = "v2.1.43"' in g
assert '?: "0.1.5-rc.2"' in g
assert 'signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")' not in g
rm = (r/'android/app/src/main/java/com/siliconleap/app/runtime/RuntimeManager.kt').read_text()
for marker in ('stability-v1', 'token-fix-v6', 'maintenanceHook()', 'SameSite=Lax', 'code in 200..399 || code == 401'):
    assert marker in rm, 'Missing existing app safeguard: ' + marker
assert 'BuildConfig.RUNTIME_VERSION' in rm
assert not subprocess.check_output(['git','ls-files','managed-runtime','android/app/src/managedPreview'],cwd=r).strip(), 'Managed preview code must not be part of the in-place source tree'
flow = (r/'.github/workflows/inplace-upgrade.yml').read_text()
assert 'contents: read' in flow and 'gh release' not in flow and 'assembleRelease' in flow
assert 'ANDROID_KEYSTORE_BASE64' in flow and 'Signing identity' in flow
print('Same-package app-only upgrade and existing launch safeguards checked.')
