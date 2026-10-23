# DSHM 受管 Linux 运行时工程预览

## 状态与边界

这是独立分支上的工程预览，不是已经验收可替换正式环境的版本。

- 运行时锁定真实 Linux arm64 Node 24.22.0 / DSH 0.1.7-rc.1。
- 预览包名 `com.siliconleap.app.linuxpreview`、端口39017，与当前正式App并存。
- 不启动旧0.1.5 SDK，不从旧SDK导入锁模块；APK仅需要独立原生引导库。
- 当前36插件的**已适配磁盘产物**以版本和代码校验值记录在 `plugins-baseline.json`；这不是未经修改的npm原版，也不是全部业务验收。
- 公开CI不上传私有插件包、账号、会话、签名密钥或当前设备数据；公开产物是SDK基础运行时，不包含已登录的个人插件环境。完整私人插件及数据迁移仍须单独验证，不自动启用jumeng-media。

## 架构

1. 原生预览界面负责导入、版本选择、正常停止及恢复上一受管版本，不依赖Harness网页。
2. 版本槽位于独立App的Debian rootfs内部，清单逐文件校验，拒绝越界和重复成员。
3. 选择记录由原生 `FileChannel` 系统锁和原子文件替换维护；不是“PID锁文件存在即一直锁住”，进程退出后系统会释放锁。
4. 独立Node引导器只依赖Node内置模块；Linux子进程报告实际平台和版本，正常停止核对nonce、PID启动时间、版本槽和入口，拒绝按端口杀未知进程。
5. 代码版本恢复不等于数据反向迁移。预览初次没有上一受管版本时会明确拒绝“恢复”，不会擅自回旧SDK。
6. HTTP响应只是启动观察，不自动标记全部插件、登录、生成、渲染或恢复验收通过。

## 构建与测试

```sh
node --test managed-runtime/test/*.test.cjs
python3 -m unittest discover -s managed-runtime/test -p 'test_*.py'
bash -n runtime-builder/build_runtime.sh
bash -n runtime-builder/build_linux_runtime.sh
```

GitHub Actions使用独立 `managed-preview.yml`：只读仓库权限、仅上传构建产物，不创建Release、不覆盖旧运行时资产。Android编译入口：

```sh
cd android
bash ./gradlew assembleManagedPreview --no-daemon --no-configuration-cache -PruntimeRepository=duty25/DSHM
```

本机没有Java/Gradle/Android SDK。Node和Python测试通过不代表APK已编译，更不代表Android实机启动/退出/恢复通过。

## 原生引导与Linux核心分开

- `BOOTSTRAP_ONLY=1`使原构建器仅打包原生支持，不安装旧DSH、pnpm或Android SDK桩补丁。
- Linux构建器用锁文件和官方Node归档校验值，保留真实flock、Koffi、sharp，不沿用Android跳过锁/桩实现。
- 完整权限运行必须在预览原生界面明确确认；proot不是Android root，也不是已验证的文件系统安全隔离。
- 受管Linux文件发布在专用标志下使用原子 `renameat2(RENAME_NOREPLACE)`，避免proot硬链接模拟造成悬空文件；必须继续做Android实机验证。

## 签名与隐私

原仓库旧工作流曾内置固定签名材料。当前源码已移除该兜底，但删除当前文件不消除Git历史中的既有泄露。没有在本轮更换当前App签名，也不建议继续复用公开过的密钥。正式签名迁移要另行确认。

预览使用CI调试签名，不保证不同构建之间的覆盖安装签名相同。预览数据需要备份时再卸载预览版；不要卸载正式App或清除其数据。

## 尚未完成的验收

- 首次Android完整编译、原生引导库/arm64核心组合和真实设备安装。
- 真实启动、正常停止、无网页恢复和崩溃后恢复。
- 全部36个私人插件及依赖、外部网关工具、用户配置/账号/会话迁移。
- 现有自定义路径迁到独立包名后的逐项业务验证。

这些项目未完成前，不安装覆盖当前正式App，不删除旧运行/恢复材料。
