/** Lazy POSIX flock entry; importing it does not load a native addon. */
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { getSystemErrorName } from 'node:util';
let binding;
function loadBinding() {
    if (binding)
        return binding;
    const { platform, arch } = process;
    // DSHM Android adapter: genuine bionic Node-API flock, never a no-op.
    if (platform === 'android') {
        const require = createRequire(import.meta.url);
        return (binding = require('./dshm-android-flock.cjs').loadBinding());
    }
    if (platform !== 'linux' && platform !== 'darwin') {
        throw Object.assign(new Error(`flock is not supported on ${platform}-${arch}`), {
            code: 'ERR_FLOCK_UNSUPPORTED_PLATFORM',
            syscall: 'flock',
        });
    }
    let filename = 'system.node';
    if (platform === 'linux') {
        // Node's report types omit the libc field supplied by Linux reports.
        const report = process.report.getReport();
        filename = join(report.header.glibcVersionRuntime ? 'glibc' : 'musl', filename);
    }
    const require = createRequire(import.meta.url);
    const manifest = require.resolve(`@deepseek-ai/node-addon-system-${platform}-${arch}/package.json`);
    binding = require(join(dirname(manifest), 'bin', filename));
    return binding;
}
/**
 * Attempt an exclusive, nonblocking POSIX flock on the caller's descriptor.
 * The syscall runs in asynchronous work, so acquisition can occur after this
 * call returns. Keep fd open until the promise settles; the binding never
 * opens, duplicates, or closes it. Closing the locked descriptor releases the
 * lock once all descriptors for its open file description are closed.
 * @param fd - Open file descriptor to lock; ownership remains with the caller.
 * @returns A promise resolving to void on acquisition. Contention rejects with
 *   EAGAIN/EWOULDBLOCK; other syscall failures also reject. Syscall errors carry
 *   code, positive errno, and syscall='flock'. Native setup errors, unsupported
 *   platforms, and addon loading failures reject; importing alone does not load it.
 */
export async function tryLockExclusive(fd) {
    const errno = await new Promise((resolve) => {
        loadBinding().tryLock(fd, resolve);
    });
    if (errno === 0)
        return;
    const code = getSystemErrorName(-errno);
    throw Object.assign(new Error(`${code}: flock failed`), {
        code,
        errno,
        syscall: 'flock',
    });
}

/** Android-only, no-overwrite publication. Never substitutes a plain rename. */
export async function publishNoReplaceAndroid(source, target) {
    if (process.platform !== 'android') throw new Error('Android publication adapter used on a different platform');
    const require = createRequire(import.meta.url);
    const native = require('./dshm-android-flock.cjs').loadBinding();
    if (typeof native.renameNoReplace !== 'function') throw new Error('Native no-replace publication export is missing');
    const errno = await new Promise((resolve) => native.renameNoReplace(source, target, resolve));
    if (errno !== 0) {
        const code = getSystemErrorName(-errno);
        throw Object.assign(new Error(`${code}: no-replace publication failed`), {code, errno, syscall:'renameat2', path:source, dest:target});
    }
}
