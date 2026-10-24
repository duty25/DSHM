# Android 原生候选的参考核对

- woaiys3/deepseek-harness-android-app，MIT，固定 98162a23b39eeeb75b8d4608b3e773bbb13be43d。overlay-017 的 flock.js 使用 noopBinding；缺绑定时 tryLock 总回调0，不采用。
- Soodok/Deepseek-Harness-Local-Android，MIT，固定 5b3e30c6035dbbaaf3c3afbaf8c8373bea6d491a。Termux/bionic Node 是真实 Android 主进程；collect-termux-runtime.sh 将 tryLockExclusive 直接返回，亦不采用。可参考其 ABI、动态库路径、PTY和PTC子进程环境处理，但不执行其 trusted=yes 收集脚本或移除互斥。
- DeepSeek @deepseek-ai/node-addon-system@0.1.2，MIT，复用公开包自带的 src/flock.c，使用 Android NDK 构建真正 flock(LOCK_EX|LOCK_NB) 绑定。原始文件哈希见 native-runtime/upstream.json。

该方案不是照搬参考 App，也不使用其包名、密钥、账号数据或设备特权。现有 DSHM 数据与运行时不在本候选修改范围。
