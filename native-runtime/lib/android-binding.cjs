// DSHM native POSIX flock loader. No no-op or platform impersonation fallback.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
let cached;
let cachedPath;
function fault(code, message) { return Object.assign(new Error(message), { code }); }
function loadBinding() {
  const file = process.env.DSHM_ANDROID_FLOCK_PATH || (process.platform === 'android' ? path.join(path.dirname(process.execPath), 'libdshm_flock.so') : undefined);
  if (!file || !path.isAbsolute(file) || path.basename(file) !== 'libdshm_flock.so') {
    throw fault('ERR_DSHM_FLOCK_PATH', 'DSHM_ANDROID_FLOCK_PATH must name the absolute signed native library path');
  }
  if (cached) {
    if (file !== cachedPath) throw fault('ERR_DSHM_FLOCK_PATH_CHANGED', 'Cannot replace a loaded flock binding');
    return cached;
  }
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) throw fault('ERR_DSHM_FLOCK_FILE', 'Native flock library must be a regular file');
  const expected = { arm64: 183, x64: 62 }[process.arch];
  const fd = fs.openSync(file, 'r');
  const header = Buffer.alloc(20);
  let count;
  try { count = fs.readSync(fd, header, 0, header.length, 0); } finally { fs.closeSync(fd); }
  if (count !== 20 || !header.subarray(0, 4).equals(Buffer.from([127,69,76,70])) ||
      header[4] !== 2 || header[5] !== 1 || !expected || header.readUInt16LE(18) !== expected) {
    throw fault('ERR_DSHM_FLOCK_ABI', 'Native flock library does not match the current 64-bit Node ABI');
  }
  const nativeModule = { exports: {} };
  process.dlopen(nativeModule, file);
  if (typeof nativeModule.exports.tryLock !== 'function') {
    throw fault('ERR_DSHM_FLOCK_EXPORT', 'Native flock binding has no tryLock export');
  }
  cachedPath = file;
  cached = nativeModule.exports;
  return cached;
}
module.exports = { loadBinding };
