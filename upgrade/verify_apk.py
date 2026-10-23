import argparse, hashlib, json, os, pathlib, re, shutil, struct, subprocess, zipfile, sys, traceback
def report_failure(kind, error, trace):
    message = (kind.__name__ + ': ' + str(error))[:2400]
    for old, new in (('%', '%25'), ('\r', '%0D'), ('\n', '%0A')):
        message = message.replace(old, new)
    print('::error title=APK verification failure::' + message, flush=True)
    traceback.print_exception(kind, error, trace)
sys.excepthook = report_failure
ROOT = pathlib.Path(__file__).resolve().parents[1]
CFG = json.loads((ROOT / 'upgrade/config.json').read_text())
def digest(data):
    return hashlib.sha256(data).hexdigest()
def tools():
    sdk = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    return sdk / 'build-tools/37.0.0'
def parse_single_signer(output):
    found = set()
    for line in output.splitlines():
        if not re.match(r'^\s*(?:V[1-4](?:\.\d+)?\s+)?Signer\b', line, re.I):
            continue
        if 'certificate' not in line.lower() or not re.search(r'SHA-?256', line, re.I):
            continue
        match = re.search(r':\s*([0-9a-fA-F:]{64,95})\s*$', line)
        if match:
            value = match[1].replace(':', '').lower()
            if len(value) == 64:
                found.add(value)
    assert len(found) == 1, 'Expected exactly one APK signing certificate; public verifier output: ' + output[:1600]
    return found.pop()
def metadata(apk):
    info = subprocess.check_output([str(tools() / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", info)
    assert match, 'APK package metadata missing'
    certs = subprocess.check_output([str(tools() / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True, stderr=subprocess.STDOUT)
    signer = parse_single_signer(certs)
    return {'package': match[1], 'versionCode': int(match[2]), 'versionName': match[3], 'signerSha256': signer}
def checked_base(apk):
    assert digest(apk.read_bytes()) == CFG['baseApk']['sha256'], 'Base APK digest differs from pinned release'
    meta = metadata(apk)
    assert meta['package'] == CFG['package'] and meta['versionCode'] == CFG['baseApk']['versionCode'], 'Unexpected base package/version'
    assert meta['signerSha256'] == CFG['signerSha256'], 'Base signing identity differs from recorded certificate'
    return meta
def native(z):
    result = {}
    for name in z.namelist():
        if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
            short = name.removeprefix('lib/arm64-v8a/')
            assert '/' not in short and short not in result, 'Invalid/duplicate native-library name'
            b = z.read(name)
            if short in CFG['requiredNativeArm64']:
                assert b[:6] == b'\x7fELF\x02\x01' and struct.unpack('<H', b[18:20])[0] == 183, 'Required native executable is not arm64 ELF: ' + short
            result[short] = digest(b)
    for name in CFG['requiredNativeArm64']:
        assert name in result, 'Required original native library missing: ' + name
    for name, expected in CFG['nativeLibraries'].items():
        assert result.get(name) == expected, 'Native library differs from existing APK reference: ' + name
    return result
p = argparse.ArgumentParser()
p.add_argument('mode', choices=['prepare', 'verify'])
p.add_argument('--base', type=pathlib.Path, required=True)
p.add_argument('--apk', type=pathlib.Path)
p.add_argument('--report', type=pathlib.Path, required=True)
a = p.parse_args()
base_meta = checked_base(a.base)
with zipfile.ZipFile(a.base) as z:
    libraries = native(z)
    if a.mode == 'prepare':
        dest = ROOT / 'android/app/src/main/jniLibs/arm64-v8a'
        dest.mkdir(parents=True, exist_ok=True)
        for name in libraries:
            with z.open('lib/arm64-v8a/' + name) as src, (dest / name).open('xb') as output:
                shutil.copyfileobj(src, output)
        report = {'base': base_meta, 'baseApkSha256': CFG['baseApk']['sha256'], 'reusedNativeLibraries': libraries, 'runtimeRebuilt': False}
    else:
        assert a.apk and a.apk.is_file(), 'New release APK missing'
        target = metadata(a.apk)
        assert target['package'] == CFG['package'] and target['versionCode'] == CFG['versionCode'] > base_meta['versionCode'], 'Not an upgrade of the original app'
        assert target['versionName'] == CFG['versionName'], 'Wrong release version name'
        assert target['signerSha256'] == base_meta['signerSha256'], 'New APK cannot cover the original signing identity'
        with zipfile.ZipFile(a.apk) as candidate:
            assert native(candidate) == libraries, 'Native runtime changed during app-only upgrade'
            forbidden = [n for n in candidate.namelist() if n in ('assets/runtime.zip', 'assets/managed-native-support.zip') or n.startswith('assets/managed-bootstrap/')]
            assert not forbidden, 'App-only upgrade must not carry a replacement runtime'
        report = {'candidate': target, 'base': base_meta, 'apkSha256': digest(a.apk.read_bytes()), 'apkBytes': a.apk.stat().st_size, 'sameCertificateAsBaseRelease': True, 'sameNativeLibrariesAsBaseRelease': True, 'runtimeRebuilt': False, 'dataMigrationPerformed': False, 'installedDeviceVerified': False}
        a.apk.with_suffix(a.apk.suffix + '.sha256').write_text(report['apkSha256'] + '  ' + a.apk.name + '\n')
        print('::notice title=In-place APK verified::' + json.dumps(report, separators=(',', ':')))
a.report.parent.mkdir(parents=True, exist_ok=True)
a.report.write_text(json.dumps(report, indent=2) + '\n')
print('Package, version, certificate and native-library verification passed.')
