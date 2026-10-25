# DSHM v2.1.46 稳定版

基线：v2.1.45（`feat/android-native-dsh017`，DSH `0.1.7-rc.1` 原生运行时，同包名覆盖升级）。
内核版本**不变**，变化只有两类：

1. 把以前在手机上手工改运行时文件的修复，**全部固化进构建**（`native-runtime/tools/apply-stability.cjs`）。
   以前 App 一重装运行时，这些修复就会全部丢掉——这正是「修好了又坏」的主要原因之一。
2. 修掉反复出现的故障的根因，并加固 App 应对系统查杀的处理。

## 运行时补丁（11 项，按 rc.1 原版哈希锁定）

| id | 包 | 解决的现场问题 |
|---|---|---|
| bundle-retention | dsh-app-boot、dsh-plugin-manager | 装/卸任意插件后，Codex、WorkBuddy、桥接等插件从 `dsh.profile.bundles` 消失。依赖还在的包不再因为元数据一时读不到就被剔除；只有真正卸载才移除 |
| stale-writer-lock | dsh-atomic-write | 强制停止后留下 `package.json.lock`，之后所有配置写入都卡死。锁里的 PID 经内核确认（ESRCH）已不存在时，把锁改名移到一边（不删除，留作证据）；锁主人还活着、无权限确认或内容不是 PID 时，行为与官方一致 |
| v4-source | dsh-session | `format v4 message requires a producer-owned source kind`。在 `Session.append` 这一个必经点，把旧写法 `{kind:'plugin'}` 和缺失的 source 转成 v4 形态，映射规则与官方 v3→v4 迁移一致；本来就合规的 source 原样保留 |
| v3-missing-source | dsh-session-format-v3-to-v4 | 早期 v3 会话没有 source，打开时报错。按角色补上隐含的 source |
| config-compose | dsh-config-editor | 设置页保存思考强度要 5–10 秒。改为合并一次，与上游 0.2.1 的修复相同 |
| ptc-ld-path | dsh-ptc-runtime-node | PTC 子进程找不到 `libz.so.1`（只在 android 生效） |
| attachment-fsync | dsh-attachment-local | 发图报 `EACCES /data/user/0`。目录 fsync 遇到沙箱外祖先目录时，只跳过 android 上的 EACCES/EPERM，其他真实 I/O 错误照常报错 |
| sharp-wasm | sharp | android-arm64 没有原生 sharp，改为加载随包的 wasm32 版本（已作为直接依赖锁定） |
| prompt-reason | dsh-api-session-controller | `prompt rejected (session/agent-busy)` 这个提示看不出真正原因，现在会附上真实原因 |
| todo-panel | dsh-client-ui-conversation | 任务列表展开时卡顿，去掉高开销的背景模糊，改用合成层过渡动画 |

约定与原有原生补丁相同：必须有候选标记目录；每个文件必须匹配记录的原版哈希（或已打补丁后的哈希，用于幂等重放）；每个锚点必须唯一命中。全部检查通过前一个字节都不写；补丁后的结果必须等于 `stability-source.json` 里记录的哈希。

行为契约：`native-runtime/test/stability-contract.mjs`，跑的是真实打过补丁的模块，不用 mock。已接入两条 CI。

## 全局会话中断（exit=137）

现象：所有工作区的会话在同一时刻同时中断。
证据：`files/logs/crash/server-exit-*.txt` 中 `exit=137 SIGKILL`，同时 `lowMemory=false`、系统可用内存约 3.4GB；自 10-22 起共 18 次。
手机：Xiaomi HyperOS 3 / Android 16，`persist.sys.fflag.override.settings_enable_monitor_phantom_procs=true`。

结论：Android 12+ 的**幻影进程上限**（所有 App 合计最多 32 个子进程，超出后直接 SIGKILL）叠加 HyperOS 的温控/清理查杀。被查杀时，DSH、proot、Debian bash、无头 Chromium、Xvfb、Blender、WorkBuddy 等子进程同时都在跑。DSH 只有一个 node 进程，它一死，所有工作区的会话就一起中断。

v2.1.46 的处理：

- exit=137 归类为系统查杀，单独计数（10 分钟内最多 12 次），不再消耗原本给配置错误准备的「3 次后停止自动重启」额度，因此会自动恢复；1 秒后重启。
- 崩溃记录增加进程普查：查杀当时本 UID 的进程数，以及幻影进程开关状态。下一次被杀能直接看出是哪种原因。
- 设置 → 后台保护：新增「子进程上限」一项，显示当前状态；点击会复制解除命令，并打开开发者选项。

**唯一能根治的一步需要主人来做**（App 没有权限替主人执行）：在电脑上执行一次：

```
adb shell device_config set_sync_disabled_for_tests persistent
adb shell device_config put activity_manager max_phantom_processes 2147483647
adb shell settings put global settings_enable_monitor_phantom_procs false
```

Android 14 及以上也可以在 **开发者选项 → 停用子进程限制** 里直接打开。另外，在系统任务管理里锁定 DSHM，并把电池设为「无限制」。

## 安装与回退

- 同包名、同签名覆盖安装，数据保留。首次启动时，运行时构建戳 `0.1.7-rc.1+dshm.2.1.46` 和已安装的不一致，会自动重装内置运行时。旧的 `usr/lib/node_modules` 会改名为 `node_modules.prev` 备份。
- 回退运行时：停止服务，把 `node_modules.prev` 改回 `node_modules`，然后删除 `usr/lib/dshm-runtime-build.txt`。
- 回退 App：重新安装 v2.1.45 的 APK（同签名，数据保留）。
- 手机上现有的 `dshm-fix.sh`、startup doctor、profile watcher 保持原样，与本版本兼容。
