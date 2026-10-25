#!/usr/bin/env python3
"""Pack the audited 0.1.7 SDK plus the arm64 flock binding into an APK asset.

Only regular files are accepted; symlinks and .bin shims are skipped because the
App launches the dsh entry directly and never needs shell shims.
"""
from pathlib import Path
import sys
import zipfile

sdk = Path(sys.argv[1]).resolve()
flock = Path(sys.argv[2]).resolve()
out = Path(sys.argv[3]).resolve()

root = sdk / 'node_modules'
if not (root / '@deepseek-ai/dsh/lib/bin.js').is_file():
    raise SystemExit('SDK entry missing; run prepare-sdk first')
if not flock.is_file():
    raise SystemExit('arm64 flock binding missing')

out.parent.mkdir(parents=True, exist_ok=True)
count = 0
skipped = 0
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for p in sorted(root.rglob('*')):
        if p.is_symlink():
            skipped += 1
            continue
        if not p.is_file():
            continue
        rel = p.relative_to(root)
        if '.bin' in rel.parts:
            skipped += 1
            continue
        z.write(p, 'lib/node_modules/' + rel.as_posix())
        count += 1
    z.write(flock, 'lib/libdshm_flock.so')
    # DSHM: build stamp lets the App reinstall a re-patched SDK even when the DSH version is unchanged.
    stamp = sys.argv[4] if len(sys.argv) > 4 else ''
    if stamp:
        z.writestr('lib/dshm-runtime-build.txt', stamp + '\n')

print(f'packed {count} sdk files ({skipped} symlinks/shims skipped), {out.stat().st_size} bytes')
