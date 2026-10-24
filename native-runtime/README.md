# Android 原生 DSH 0.1.7 候选

目标为 Android/bionic Node 直接运行 DSH 0.1.7-rc.1；不是 Linux Node，不伪装 process.platform。

当前先验证文件锁。vendor/flock.c 与 DeepSeek node-addon-system@0.1.2 的 MIT 上游源码逐字节相同，仅由 Android NDK 编译为 libdshm_flock.so。上游的异步回调、errno、FD 所有权及环境退出清理保持不变。

Android JS 分支从显式的原生库路径加载该真实 Node-API 模块；缺失、错误 ABI 或加载失败一律报错，绝不将失败当作已获取锁。Linux/macOS 保留上游行为。apply-flock.cjs 只允许带独立候选标记、固定 SDK 版本与原始文件哈希的目录。

两个参考项目均为 MIT，已固定提交供架构研究；其空锁方案不采用。此处复用的是 DeepSeek 原生 C 实现，许可证见 vendor/LICENSE-DeepSeek。参考结论见 docs/native017-references.md。

本阶段尚未证明 Android 真机、完整会话或插件业务通过，未改当前生产运行时、数据或 App。主机侧静态/契约测试不得称 Android 实测。
