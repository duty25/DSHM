#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:?Use a new isolated WORK directory}"
OUT="${OUT:?Set output directory}"
mkdir -p "$WORK" "$OUT"
STAGE="$WORK/linux017"
test ! -e "$STAGE"
mkdir -p "$STAGE/node" "$STAGE/bootstrap"
ARCHIVE="$WORK/node-v24.22.0-linux-arm64.tar.gz"
curl --fail --location --retry 2 --output "$ARCHIVE" https://nodejs.org/dist/v24.22.0/node-v24.22.0-linux-arm64.tar.gz
printf '%s  %s\n' b7e7991d5407815adfa74a2a9cd4db683552c8ce32ff4a295c22e972849c3f5d "$ARCHIVE" | sha256sum --check
# Correct pinned checksum is also recorded in the baseline. Never accept a mismatch.
tar -xzf "$ARCHIVE" --strip-components=1 -C "$STAGE/node"
cp "$ROOT/managed-runtime/core-source/package.json" "$ROOT/managed-runtime/core-source/package-lock.json" "$STAGE/"
npm ci --prefix "$STAGE" --ignore-scripts --omit=dev --os=linux --cpu=arm64 --libc=glibc --no-audit --no-fund --registry=https://registry.npmjs.org/
node "$ROOT/managed-runtime/tools/apply-linux-compat.cjs" "$STAGE"
mkdir -p "$STAGE/tools/pnpm" "$STAGE/bin"
npm install --prefix "$STAGE/tools/pnpm" --ignore-scripts --omit=dev --no-audit --no-fund --save-exact --registry=https://registry.npmjs.org/ pnpm@10.34.7
node - "$STAGE/tools/pnpm/package-lock.json" <<'JS'
const f=require(process.argv[2]);
const pkg=f.packages['node_modules/pnpm'];
if(pkg.version!=='10.34.7'||pkg.integrity!=='sha512-upirxYVTmTM3W1lx9LxtbHOhtLftxKh559EC/lQj0eAjCdZ8YKEfBJVQk6luUhrZw7E4zdZUylvlY/N4s65IvQ==')throw Error('pnpm differs from reviewed baseline');
JS
cat > "$STAGE/bin/pnpm" <<'PNPM'
#!/bin/sh
exec /opt/dshm/current/node/bin/node /opt/dshm/current/tools/pnpm/node_modules/pnpm/bin/pnpm.cjs "$@"
PNPM
chmod 700 "$STAGE/bin/pnpm"
cp "$ROOT/managed-runtime/bootstrap/guest-guard.mjs" "$STAGE/bootstrap/guest-guard.mjs"
python3 "$ROOT/managed-runtime/tools/pack-bundle.py" --tree "$STAGE" --output "$OUT/dsh-linux017.runtime.zip" --id "linux017-${GITHUB_SHA:-local}"
