#!/usr/bin/env bash
set -euo pipefail
out="${1:?output directory required}"
mode="${2:-android}"
arch="${3:-arm64}"
root="$(cd "$(dirname "$0")/.." && pwd)"
node_root="$(dirname "$(dirname "$(command -v node)")")"
headers="${NODE_HEADERS_DIR:-$node_root/include/node}"
test -f "$headers/node_api.h"
mkdir -p "$out"
flags=(-std=c11 -O2 -DNAPI_VERSION=8 -fPIC -shared -Wall -Wextra -Werror -Wl,-z,relro,-z,now -Wl,-soname,libdshm_flock.so)
if [[ "$mode" == android ]]; then
  ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
  test -n "$ndk"
  case "$arch" in arm64) triple=aarch64-linux-android24 ;; x64) triple=x86_64-linux-android24 ;; *) exit 64 ;; esac
  compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/$triple-clang"
  flags+=(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096)
else
  [[ "$mode" == host ]] || exit 64
  compiler="${CC:-cc}"
fi
"$compiler" "${flags[@]}" -I "$headers" "$root/vendor/flock.c" -o "$out/libdshm_flock.so"
sha256sum "$out/libdshm_flock.so"
