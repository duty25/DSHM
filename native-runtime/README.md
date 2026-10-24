# Android 原生 DSH 0.1.7 候选

目标为 Android/bionic Node 直接运行 DSH 0.1.7-rc.1；不是 Linux Node，不伪装 process.platform。

当前先验证文件锁。vendor/flock.c 与 DeepSeek node-addon-system@0.1.2 的 MIT 上游源码逐字节相同，仅由 Android NDK 编译为 libdshm_flock.so。上游的异步回调、errno、FD 所有权及环境退出清理保持不变。

Android JS 分支从显式的原生库路径加载该真实 Node-API 模块；缺失、错误 ABI 或加载失败一律报错，绝不将失败当作已获取锁。Linux/macOS 保留上游行为。apply-flock.cjs 只允许带独立候选标记、固定 SDK 版本与原始文件哈希的目录。

两个参考项目均为 MIT，已固定提交供架构研究；其空锁方案不采用。此处复用的是 DeepSeek 原生 C 实现，许可证见 vendor/LICENSE-DeepSeek。参考结论见 docs/native017-references.md。

本阶段尚未证明 Android 真机、完整会话或插件业务通过，未改当前生产运行时、数据或 App。主机侧静态/契约测试不得称 Android 实测。

Android 模拟器已执行真实锁检查，第二轮在会话文件的硬链接发布处遇到 EACCES。因此新增原生 renameat2(RENAME_NOREPLACE) 绑定；只替换已审核的会话临时文件发布点与迁移发布点，保持 fsync 和 EEXIST 检查，不用普通覆盖 rename/copy。vendor/flock.c 未改字节；构建时仅将其模块初始化入口改名，与新发布导出组合，锁主体不变。当前仍未证明完整 App 或真机通过。

基础原生链路已在 CI 40067902405 通过：Android x86_64 模拟器/Node24.22.1，真实锁10项、原子发布6项、0.1.7会话持久化10项；arm64仅交叉编译，手机尚未验。下一步验证主服务启动与其他依赖。新增内置模块加载适配使用App本来显式启用的 --expose-internals，限五个已审核内部模块，不伪装平台、不静默放宽沙箱。此处仍不是可替换生产的完整APK。
