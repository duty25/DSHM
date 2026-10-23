package com.siliconleap.app.runtime

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.os.StatFs
import com.siliconleap.app.BuildConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ServerPhase {
    NOT_READY,
    DOWNLOADING,
    EXTRACTING,
    STARTING,
    RUNNING,
    ERROR,
}

data class RuntimeState(
    val phase: ServerPhase = ServerPhase.NOT_READY,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val message: String = "",
    val port: Int = 3082,
    val pid: Long? = null,
    val runtimeVersion: String? = null,
    val installed: Boolean = false,
)

/** 存储占用统计。 */
data class StorageStats(
    val runtimeBytes: Long = 0L,
    val workspaceBytes: Long = 0L,
    val dshHomeBytes: Long = 0L,
    val logsBytes: Long = 0L,
) {
    val totalBytes: Long get() = runtimeBytes + workspaceBytes + dshHomeBytes + logsBytes
}

/** 运行时下载元数据（发布侧提供）。 */
data class RuntimeMeta(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mirrors: List<String> = emptyList(),
    val arch: String = "",
    val termuxApp: String = "",
    val dsh: String = "",
    val nodeVersion: String = "",
    val builtAt: String = "",
)

/** 环境页诊断快照（进程监控 / 会话 / 日志 / 存储 / 网络）。 */
data class RuntimeDiagnostics(
    val pid: Long? = null,
    val cpuPercent: Double = 0.0,
    val memRssKb: Long = 0L,
    val threads: Int = 0,
    val fds: Int = 0,
    val sessions: Int = 0,
    val logLines: Int = 0,
    val logBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val lanIps: List<String> = emptyList(),
)

object RuntimeManager {
    private const val READY_TIMEOUT_MS = 120_000L
    private const val DOWNLOAD_TIMEOUT_MS = 20 * 60_000L

    /** 默认元数据地址（GitHub Releases，runtime-latest 资产自动更新）。 */
    const val DEFAULT_META_URL =
        "https://github.com/duty25/DSHM/releases/download/runtime-latest/metadata.json"

    /** 按下载源解析 metadata URL（github / ghproxy_cf / ghproxy_axisnow / custom；auto 先测速解析实际源）。 */
    fun effectiveMetaUrl(context: Context): String = when (SourceManager.resolve(context)) {
        AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/$DEFAULT_META_URL"

        AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/$DEFAULT_META_URL"

        AppSettings.SOURCE_CUSTOM ->
            AppSettings.customMetaUrl(context).ifBlank { DEFAULT_META_URL }

        else -> DEFAULT_META_URL
    }

    /** 按当前下载源刷新 metaUrl（切换源后调用）。 */
    fun refreshSource(context: Context) {
        metaUrl = effectiveMetaUrl(context)
    }

    private lateinit var appContext: Context

    /**
     * 后台流程里未处理的异常：记录并转为 ERROR 状态。没有这个处理器时，异常会交给线程的
     * 默认处理器，直接让整个 App 进程崩溃（node 服务作为子进程也会一起结束）。
     */
    private val scopeErrors = CoroutineExceptionHandler { _, error ->
        CrashRecorder.recordHandled("RuntimeManager 后台任务", error)
        runCatching { appendLog("! 后台任务异常：${error.javaClass.simpleName}: ${error.message ?: ""}") }
        _state.update {
            if (it.phase == ServerPhase.RUNNING) it
            else it.copy(phase = ServerPhase.ERROR, message = "内部错误：${error.message ?: error.javaClass.simpleName}")
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + scopeErrors)

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /** 运行分区（root/container/空=未选）；设置页切换后驱动 App 回到引导页。 */
    private val _runMode = MutableStateFlow("")
    val runMode: StateFlow<String> = _runMode.asStateFlow()

    /** 更新运行分区（持久化 + 通知 UI）。 */
    fun setRunMode(context: Context, mode: String) {
        AppSettings.setRunMode(context, mode)
        _runMode.value = mode
    }

    /** 是否有新版本运行时待更新（应用期望运行时版本 ≠ 已安装版本）。 */
    private val _runtimeUpdateAvailable = MutableStateFlow(false)
    val runtimeUpdateAvailable: StateFlow<Boolean> = _runtimeUpdateAvailable.asStateFlow()

    @Volatile
    private var serverProcess: Process? = null
    private var startedAt: Long = 0L

    /** 本应用拉起的 node 进程 PID（启动时解析一次，避免轮询时反复反射 / 扫描 /proc）。 */
    @Volatile
    private var serverPid: Long? = null

    /** 复用的、非本进程引用的服务进程 PID（按端口反查得到，结果缓存）。 */
    @Volatile
    private var orphanPid: Long? = null
    private var orphanScanAt = 0L

    /** 正在执行的 bootstrap 流程；用于识别「STARTING 但没有任何流程在推进」的残留状态。 */
    @Volatile
    private var bootJob: Job? = null

    /** 后台执行中的停止服务任务；bootstrap 会先等它结束，避免新旧进程交错。 */
    @Volatile
    private var stopJob: Job? = null

    /** 主动停止的进程：它退出不算意外，不触发记录与自动重启。 */
    @Volatile
    private var expectedExit: Process? = null

    /** 自动重启的时间戳（滑动窗口限流，防止配置错误导致无限重启）。 */
    private val autoRestartTimes = ArrayDeque<Long>()
    private const val AUTO_RESTART_WINDOW_MS = 10 * 60_000L
    private const val AUTO_RESTART_MAX = 3

    /** 下载源列表（可由设置页调整）。 */
    var metaUrl: String = DEFAULT_META_URL
        private set

    fun setMetaUrl(url: String) {
        metaUrl = url
    }

    fun attach(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            // auto 源惰性测速：attach 在主线程不阻塞，下载前在 IO 线程 resolve 并刷新
            metaUrl = DEFAULT_META_URL
            _runMode.value = AppSettings.runMode(appContext)
            // 恢复上次运行时状态，避免应用重启后"从零开始"观感
            if (AppSettings.runtimeInstalled(appContext)) {
                _state.update {
                    it.copy(
                        installed = isRuntimeInstalled(),
                        runtimeVersion = readRuntimeVersion() ?: AppSettings.runtimeVersion(appContext),
                    )
                }
            }
        }
    }

    fun uptimeMillis(): Long {
        if (serverProcess?.isAlive == true) return System.currentTimeMillis() - startedAt
        // 进程引用丢失（应用重启后旧 node 进程仍存活）时按进程启动时间反查，
        // 避免"运行时长 0 秒"直到下次重启才恢复
        val pid = orphanPid ?: return 0L
        return processUptimeMillis(pid) ?: 0L
    }

    /**
     * 当前服务进程 PID。优先用启动时缓存的值；引用丢失时才按端口反查，且最多每 30 秒一次
     * （反查要遍历所有进程的 fd，开销大）。scan=false 时只读缓存，可在主线程调用。
     */
    private fun currentPid(scan: Boolean): Long? {
        val proc = serverProcess
        if (proc != null) return if (proc.isAlive) serverPid else null
        val cached = orphanPid
        if (cached != null) {
            if (File("/proc/$cached").exists()) return cached
            orphanPid = null
        }
        if (!scan) return null
        val now = SystemClock.elapsedRealtime()
        if (orphanScanAt != 0L && now - orphanScanAt < 30_000L) return null
        orphanScanAt = now
        orphanPid = findPidByPort(_state.value.port)
        return orphanPid
    }

    /** 从 /proc/<pid>/stat 的 starttime（自 boot 的 jiffies）与 /proc/uptime 计算进程运行时长。 */
    private fun processUptimeMillis(pid: Long): Long? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val idx = stat.lastIndexOf(')')
        if (idx < 0) return@runCatching null
        val parts = stat.substring(idx + 1).trim().split(' ')
        // comm 后 parts[0] 对应字段 3；starttime 是字段 22 → parts[19]（HZ=100）
        val startJiffies = parts.getOrNull(19)?.toLongOrNull() ?: return@runCatching null
        val uptimeSec = File("/proc/uptime").readText().substringBefore(' ').toDoubleOrNull()
            ?: return@runCatching null
        val runMs = ((uptimeSec - startJiffies / 100.0) * 1000.0).toLong()
        runMs.coerceAtLeast(0L)
    }.getOrNull()

    /** 各数据目录占用空间（调用方应在 IO 线程执行）。 */
    fun storageStats(): StorageStats = StorageStats(
        runtimeBytes = dirSize(TermuxEnv.prefix(appContext)),
        workspaceBytes = dirSize(TermuxEnv.workspace(appContext)),
        dshHomeBytes = dirSize(TermuxEnv.dshHome(appContext)),
        logsBytes = dirSize(TermuxEnv.logs(appContext)),
    )

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }.getOrDefault(0L)
    }

    /** DSHM: 从任意日志文本里提取 dsh 打印的带 token 网址（没有就返回 null）。 */
    fun extractHarnessUrl(text: String): String? {
        // 保险：万一输出里带 ANSI 颜色码，先剥掉再匹配
        val clean = text.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"), "")
        // 首选：直接找带 token 的网址（不依赖前缀文案）
        Regex("http://\\S*?\\?token=[A-Za-z0-9_\\-]+={0,2}").find(clean)?.let { return it.value }
        // 退而求其次：dsh 打印的那一行
        Regex("dsh web:\\s*(http://\\S+)").find(clean)?.let { return it.groupValues[1] }
        return null
    }

    /** DSHM: dsh 启动时打印的带 token 网址（内存 + 小文件，随服务启动一起重置）。 */
    @Volatile
    private var capturedHarnessUrl: String? = null

    /** DSHM: 「拿不到网址就重启一次服务」每个 App 进程只做一次。 */
    private var urlRecoveryTried = false

    /** DSHM: 供「打开 Harness」取用；还没捕获到就返回 null。 */
    fun harnessUrl(): String? = capturedHarnessUrl ?: runCatching {
        val f = File(TermuxEnv.logs(appContext), "harness-url.txt")
        if (!f.exists()) return@runCatching null
        val text = f.readText().trim()
        if (text.isEmpty()) null else text
    }.getOrNull()

    fun tailLog(lines: Int = 80): String {
        val log = TermuxEnv.serverLog(appContext)
        return if (log.exists()) LogStore.named(log).tail(lines) else "(暂无日志)"
    }

    /** 检测当前工作区是否实际可写（外部目录需「所有文件访问」权限且授权后重启生效）。 */
    fun workspaceWritable(context: Context): Boolean {
        val dir = TermuxEnv.workspace(context)
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            if (!dir.isDirectory) return false
            val probe = File(dir, ".probe-${System.currentTimeMillis()}")
            probe.createNewFile()
            val ok = probe.exists()
            probe.delete()
            ok
        }.getOrDefault(false)
    }

    fun isRuntimeInstalled(): Boolean = TermuxEnv.dshEntry(appContext).exists()

    /** 仅下载并安装运行时（不自动启动），供环境页点击拉取。 */
    fun installRuntime() {
        if (_state.value.phase == ServerPhase.DOWNLOADING ||
            _state.value.phase == ServerPhase.EXTRACTING
        ) {
            return
        }
        // 已安装时不重复下载（开局自动下载完成后，手动再点不应重下）
        if (isRuntimeInstalled()) {
            _state.update {
                it.copy(
                    phase = ServerPhase.NOT_READY,
                    message = "运行时已安装，无需重复下载",
                )
            }
            return
        }
        scope.launch {
            downloadAndInstall()
            // downloadAndInstall 结束时把阶段留在 STARTING（供 bootstrap 无缝衔接）；
            // 这里只安装不启动，复位为 NOT_READY，否则启动卡片会一直停在「启动服务」。
            if (_state.value.phase == ServerPhase.STARTING) {
                _state.update { it.copy(phase = ServerPhase.NOT_READY, message = "运行时已安装") }
            }
        }
    }

    /** 启动/检查流程：未安装 → 在线下载安装；已安装 → 启动服务。 */
    @Synchronized
    fun bootstrap() {
        // 下载/安装/启动进行中：忽略重复触发（自动启动与手动点击并发时防重入）
        val phaseNow = _state.value.phase
        if (phaseNow == ServerPhase.RUNNING ||
            phaseNow == ServerPhase.DOWNLOADING ||
            phaseNow == ServerPhase.EXTRACTING
        ) {
            return
        }
        // 已有启动流程在推进时忽略重复触发（界面自动启动与前台服务同时调用）；restart() 例外。
        // 没有流程在推进的 STARTING 是残留状态（例如服务被系统重建时只拉起了进程却没人
        // 等待就绪），继续往下走：端口已在监听就直接转为 RUNNING。
        if (bootJob?.isActive == true && !forceRestart) return
        val force = forceRestart
        forceRestart = false
        val installed = isRuntimeInstalled()
        val version = if (installed) readRuntimeVersion() ?: _state.value.runtimeVersion else null
        // 持久化状态，供应用重启后恢复显示
        AppSettings.setRuntimeInstalled(appContext, installed)
        AppSettings.setRuntimeVersion(appContext, version)
        _state.update {
            it.copy(
                installed = installed,
                runtimeVersion = version,
            )
        }
        checkRuntimeUpdate()
        bootJob = scope.launch {
            stopJob?.join()
            // 正常启动时若服务已在监听则直接复用；restart()（强制重启）必须重新装配并拉起新进程，
            // 否则旧进程端口未及时释放会误判为"已在运行"而跳过 dsh-mobile 装配
            // DSHM: 上一代 App / 已安装运行时留下的 cookie 属性会让「打开 Harness」第一次
            // 打开就 401（详见 patchRuntimeAuthCookie 的注释）。先就地修正；确实改动了就重启
            // 一次服务让新代码生效 —— 只可能发生一次，之后幂等跳过，不会反复重启。
            if (patchRuntimeAuthCookie()) {
                appendLog("[DSHM] 已修正运行时 cookie 属性，重启服务使其生效…")
                restart()
                return@launch
            }
            if (!force && ping(_state.value.port)) {
                // DSHM: 端口虽然在监听，但可能是【上一代 App 进程】启动的 harness —— 我们
                // 没读过它的输出、也拿不到它的带 token 网址，「打开 Harness」必然 401。
                // 这种情况强制重启一次，换一个我们能抓到网址的进程（每个 App 进程最多一次；
                // 日志/文件里能取到网址时照旧直接复用，不会打断正在跑的对话）。
                if (!urlRecoveryTried && harnessUrl() == null && extractHarnessUrl(tailLog(600)) == null) {
                    urlRecoveryTried = true
                    restart()
                    return@launch
                }
                _state.update {
                    it.copy(
                        phase = ServerPhase.RUNNING,
                        message = "服务运行中",
                        pid = currentPid(scan = true),
                    )
                }
                return@launch
            }
            if (!installed) {
                downloadAndInstall()
                // 下载/解压失败：保留真实错误，不再进入启动自检（避免被 preflight 诊断覆盖）
                if (_state.value.phase == ServerPhase.ERROR) return@launch
            }
            // 装配 DSH WebUI 移动端适配插件（dsh-mobile-nav），best effort 不阻塞
            if (_state.value.phase != ServerPhase.ERROR) {
                _state.update { it.copy(message = "正在装配 WebUI 优化插件…") }
            }
            AddonManager.ensureBlocking()
            // 容器分区：运行时就绪后自动安装 Debian/Ubuntu 子系统（proot 免 root）；root 分区无需子系统
            if (AppSettings.runMode(appContext) == AppSettings.RUN_MODE_CONTAINER &&
                isRuntimeInstalled() && !SubsystemManager.isInstalled(appContext)
            ) {
                // 复用安装进度 UI：BootScreen 在 DOWNLOADING/EXTRACTING 阶段显示
                _state.update {
                    it.copy(
                        phase = ServerPhase.DOWNLOADING,
                        progress = 0f,
                        message = "正在安装 ${AppSettings.subsystemFlavor(appContext).let { if (it == AppSettings.SUBSYSTEM_UBUNTU) "Ubuntu" else "Debian" }} 子系统…",
                    )
                }
                appendLog("> 自动安装 ${AppSettings.subsystemFlavor(appContext)} 子系统（容器分区）…")
                // 上次会话可能残留 DOWNLOADING/EXTRACTING 状态（进程被杀），先复位再装
                SubsystemManager.resetForAutoInstall()
                SubsystemManager.installAndWait()
                if (!SubsystemManager.isInstalled(appContext)) {
                    // 子系统安装失败不阻塞服务启动，记录日志供排查
                    appendLog("! 子系统安装失败，将直接启动服务（可稍后在环境页重试）")
                }
            }
            // 同步应用工作区到 DSH 的 workspace.json（WebUI 默认工作区跟随设置）
            syncWorkspaceToDsh()
            startServerIfNeeded()
        }
    }

    /** 强制重启：跳过"端口已在监听即复用"的短路，确保装配与启动流程完整执行。 */
    private var forceRestart = false

    fun restart() {
        stopServer()
        forceRestart = true
        _state.update { it.copy(phase = ServerPhase.NOT_READY) }
        bootstrap()
    }

    /** 前台服务（含被系统重建时）调用：没在运行就走完整启动流程（含等待就绪）。 */
    fun ensureRunning() {
        if (_state.value.phase != ServerPhase.RUNNING) bootstrap()
    }

    /** 删除运行时并重新下载安装（用于修复损坏/权限异常）。删除移 IO 线程，避免大目录遍历卡 UI。 */
    fun rebuildRuntime() {
        stopServer()
        scope.launch {
            stopJob?.join()
            withContext(Dispatchers.IO) { TermuxEnv.prefix(appContext).deleteRecursively() }
            clearRuntimeCache()
            _state.update { it.copy(phase = ServerPhase.NOT_READY, installed = false, runtimeVersion = null) }
            bootstrap()
        }
    }

    /** 卸载运行时（保留 dsh-home/workspace）。删除移 IO 线程，避免大目录遍历卡 UI。 */
    fun uninstallRuntime() {
        stopServer()
        scope.launch {
            stopJob?.join()
            withContext(Dispatchers.IO) { TermuxEnv.prefix(appContext).deleteRecursively() }
            clearRuntimeCache()
            _state.update {
                it.copy(phase = ServerPhase.NOT_READY, installed = false, runtimeVersion = null)
            }
        }
    }

    /** 清理运行时状态缓存（卸载/重装时调用）。 */
    private fun clearRuntimeCache() {
        AppSettings.setRuntimeInstalled(appContext, false)
        AppSettings.setRuntimeVersion(appContext, null)
    }

    /**
     * 同步应用工作区到 DSH 的 storages/workspace.json：
     * 更新（title=DSHM）条目的 path，或新增条目，并将其置为默认（workspaceIds 首位），
     * 使 DSH WebUI 默认打开的工作区跟随应用设置。
     *
     * 必须保证写入后的 workspaceIds 与 tables.workspaces 完全一致（自愈）：
     * DSH 启动时 dsh-workspace 的 validateStoredState 校验
     * `initialized=true 时 workspaceIds 集合必须等于 tables.workspaces 的 key 集合`，
     * 不一致会抛 "workspace ... is absent from registry order" 导致整个服务启动失败。
     * 因此这里重建 order：包含全部 table key、剔除失效引用、DSHM 恒在首位，
     * 原有相对顺序尽量保留。
     */
    private fun syncWorkspaceToDsh() {
        runCatching {
            val wsFile = File(TermuxEnv.dshHome(appContext), "storages/workspace.json")
            if (!wsFile.exists()) return
            val newPath = AppSettings.workspacePath(appContext)
            val json = JSONObject(wsFile.readText())
            val tables = json.optJSONObject("tables") ?: return
            val workspaces = tables.optJSONObject("workspaces") ?: return
            val global = json.optJSONObject("global") ?: return
            val now = java.time.Instant.now().toString()

            // 1. 确保 DSHM 条目存在
            var targetKey: String? = null
            val it = workspaces.keys()
            while (it.hasNext()) {
                val k = it.next()
                val w = workspaces.optJSONObject(k) ?: continue
                if (w.optString("title") == "DSHM") {
                    targetKey = k
                    break
                }
            }
            // path 冲突保护：DSH 的 validateStoredState 禁止两个 workspace 使用同一 path，
            // 若新 path 已被其它 workspace 占用，则保留 DSHM 原 path，避免再次让服务启动失败
            val pathTaken = workspaces.keys().asSequence()
                .filter { it != targetKey }
                .map { workspaces.optJSONObject(it)?.optString("path") }
                .any { it == newPath }
            val finalPath = if (pathTaken) {
                val old = targetKey?.let { workspaces.optJSONObject(it)?.optString("path") }
                if (!old.isNullOrBlank()) old else newPath
            } else {
                newPath
            }
            if (targetKey == null) {
                targetKey = java.util.UUID.randomUUID().toString()
                workspaces.put(targetKey, JSONObject().apply {
                    put("path", finalPath)
                    put("title", "DSHM")
                    put("sessionIds", JSONArray())
                    put("createdAt", now)
                    put("updatedAt", now)
                })
            } else {
                workspaces.getJSONObject(targetKey).put("path", finalPath).put("updatedAt", now)
            }

            // 2. 重建 workspaceIds：DSHM 恒在首位；保留原有有效条目相对顺序；剔除失效引用；
            //    补齐 tables 里有但 order 缺失的 key（自愈不一致，避免 DSH 启动校验失败）
            val newIds = JSONArray()
            newIds.put(targetKey)
            val seen = HashSet<String>()
            seen.add(targetKey)
            val old = global.optJSONArray("workspaceIds")
            if (old != null) {
                for (i in 0 until old.length()) {
                    val id = old.optString(i)
                    if (seen.contains(id)) continue
                    if (workspaces.has(id)) {
                        newIds.put(id)
                        seen.add(id)
                    }
                }
            }
            val keys = workspaces.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!seen.contains(k)) newIds.put(k)
            }
            global.put("workspaceIds", newIds)
            wsFile.writeText(json.toString(2))
        }
    }

    fun clearData() {
        stopServer()
        scope.launch {
            stopJob?.join()
            withContext(Dispatchers.IO) { TermuxEnv.dshHome(appContext).deleteRecursively() }
            _state.update { it.copy(phase = ServerPhase.NOT_READY) }
        }
    }

    /**
     * 停止服务。在主线程调用时立即把状态切到 NOT_READY，真正的结束进程（最多等 3 秒 +
     * 按端口清理遗留进程）放到后台执行，避免界面卡住甚至 ANR；后台线程调用时同步执行。
     */
    fun stopServer() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 用户主动停止/重启：结束仍在推进的启动流程，免得它看到进程被停掉后误报 ERROR
            bootJob?.cancel()
            _state.update { it.copy(phase = ServerPhase.NOT_READY, pid = null, message = "服务已停止") }
            val previous = stopJob
            stopJob = scope.launch {
                previous?.join()
                stopServerBlocking()
            }
        } else {
            stopServerBlocking()
        }
    }

    /** 停止服务专用锁（不能用 @Synchronized：会和主线程上的 bootstrap() 争同一把锁，卡住界面）。 */
    private val stopLock = Any()

    private fun stopServerBlocking() = synchronized(stopLock) {
        val proc = serverProcess
        expectedExit = proc
        proc?.destroy()
        runCatching { proc?.waitFor(3, TimeUnit.SECONDS) }
        proc?.destroyForcibly()
        serverProcess = null
        serverPid = null
        orphanPid = null
        startedAt = 0L
        // 应用重启后 serverProcess 引用丢失，旧 node 进程仍可能存活并占用 usr 文件
        // （导致解压时 usr/bin/ls 等无法删除）。按端口反查并强制结束遗留进程。
        killOrphanByPort()
        _state.update { it.copy(phase = ServerPhase.NOT_READY, pid = null) }
    }

    /** 强制结束监听本应用端口且不属于本进程的遗留 node 进程（引用丢失场景）。 */
    private fun killOrphanByPort() {
        val pid = findPidByPort(_state.value.port) ?: return
        if (pid == android.os.Process.myPid().toLong()) return
        appendLog("> 结束遗留服务进程 pid=$pid…")
        runCatching {
            ProcessBuilder("kill", "-9", pid.toString()).start().waitFor(3, TimeUnit.SECONDS)
        }
        // 等内核释放子进程持有的文件句柄（node worker/pty 子进程可能短暂持有 usr 文件）
        Thread.sleep(500)
    }

    // ------------------------------------------------------------------ 下载安装

    /** 下载源的人类可读名（日志/诊断用）。 */
    private fun sourceName(source: String): String = when (source) {
        AppSettings.SOURCE_GHPROXY_AXISNOW -> "AxisNow"
        AppSettings.SOURCE_GHPROXY_CF -> "Cloudflare"
        AppSettings.SOURCE_GITHUB -> "GitHub"
        AppSettings.SOURCE_CUSTOM -> "自定义"
        else -> source
    }

    private suspend fun downloadAndInstall() {
        clearLog()
        // 先置 DOWNLOADING：测速阶段也显示进度，避免 10s "卡住"观感与并发重入
        _state.update {
            it.copy(phase = ServerPhase.DOWNLOADING, progress = 0f, speedBytesPerSec = 0L, message = "正在获取运行时信息…")
        }
        // auto 源：IO 线程内先测速选源并刷新 metaUrl（首次调用阻塞测速，缓存后不再测）
        if (AppSettings.downloadSource(appContext) == AppSettings.SOURCE_AUTO) {
            appendLog("> 自动测速选择下载源…")
            val results = SourceManager.speedTest()
            for (r in results.sortedBy { it.estimatedMs }) {
                val speed = if (r.speedKBps > 0.0) String.format("%.1f MB/s", r.speedKBps / 1024.0) else "未测速"
                appendLog("> 测速 ${sourceName(r.source)}: 延迟 ${r.latencyMs}ms · $speed")
            }
            val picked = SourceManager.pickBest(results, appContext)
            appendLog("> 已选择下载源: ${sourceName(picked)}")
            metaUrl = effectiveMetaUrl(appContext)
        }
        appendLog("> 获取运行时信息…")
        val meta = runCatching { fetchMeta() }.getOrNull()
        if (meta == null) {
            appendLog("! 获取运行时信息失败，请检查网络或镜像源")
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "获取运行时信息失败，请检查网络或镜像源")
            }
            return
        }
        val zip = File(TermuxEnv.filesDir(appContext), "runtime-download.zip")
        appendLog("> 开始下载运行时 v${meta.version}")
        val ok = downloadWithFallback(meta, zip)
        if (!ok) {
            appendLog("! 运行时下载失败，请检查网络或切换镜像源")
            _state.update {
                it.copy(phase = ServerPhase.ERROR, message = "运行时下载失败，请检查网络或切换镜像源")
            }
            return
        }
        appendLog("> 下载完成（${zip.length() / 1024 / 1024} MB），校验 sha256…")
        if (!verifySha256(zip, meta.sha256)) {
            appendLog("! 运行时校验失败（sha256 不匹配）")
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时校验失败（sha256 不匹配）") }
            return
        }
        appendLog("> sha256 校验通过，开始解压安装…")
        val freeBytes = availableSpace(TermuxEnv.filesDir(appContext))
        // 解压需临时目录双份（usr.tmp + 目标 usr）≈ zip 解压体积 ×2，预留 2.5 倍 zip 体积
        val needBytes = (zip.length() * 2.5).toLong()
        if (freeBytes in 1..needBytes) {
            appendLog("! 存储空间不足: 可用 ${freeBytes / 1024 / 1024}MB，需约 ${needBytes / 1024 / 1024}MB")
            _state.update {
                it.copy(
                    phase = ServerPhase.ERROR,
                    message = "存储空间不足（可用 ${freeBytes / 1024 / 1024}MB，需约 ${needBytes / 1024 / 1024}MB），请清理后重试",
                )
            }
            return
        }
        _state.update { it.copy(phase = ServerPhase.EXTRACTING, progress = 0f, message = "正在安装运行时…") }
        // 旧 node 服务进程可能占用 usr 下文件（bin/ls 等），导致删除/重命名失败，
        // 先停服释放文件句柄（不改变当前 EXTRACTING 阶段）
        stopServer()
        _state.update { it.copy(phase = ServerPhase.EXTRACTING, progress = 0f, message = "正在安装运行时…") }
        val installed = extractZip(zip, TermuxEnv.prefix(appContext))
        zip.delete()
        if (!installed) {
            appendLog("! 运行时安装失败")
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "运行时安装失败") }
            return
        }
        appendLog("> 运行时安装完成")
        AppSettings.setRuntimeInstalled(appContext, true)
        AppSettings.setRuntimeVersion(appContext, meta.version)
        _runtimeUpdateAvailable.value = false
        _state.update {
            it.copy(
                // 保持 STARTING：BootScreen 持续显示，避免下载完成后到下一阶段间的"空白无响应"
                phase = ServerPhase.STARTING,
                installed = true,
                runtimeVersion = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
                message = "运行时已就绪，正在准备环境…",
            )
        }
    }

    /** 获取运行时实际大小（字节）；网络不通时返回 null（UI 回退显示约 500 MB）。 */
    suspend fun fetchRuntimeSize(): Long? = withContext(Dispatchers.IO) { fetchMeta()?.sizeBytes }

    /** 获取运行时完整元数据（版本/架构/构建时间）；网络不通时返回 null。 */
    suspend fun fetchRuntimeMeta(): RuntimeMeta? = withContext(Dispatchers.IO) { fetchMeta() }

    // ------------------------------------------------------------- 诊断数据

    private var lastCpuJiffies: Long = 0L
    private var lastCpuSampleAt: Long = 0L

    // 重指标缓存：fd 列目录 / 会话计数 / 磁盘统计 / 网卡枚举每秒做一次开销较大，
    // 5s 采样一次即可（CPU/内存/线程为实时核心指标，保持每秒）。
    private var lastHeavyAt: Long = 0L
    private var cachedFds = 0
    private var cachedSessions = 0
    private var cachedFreeBytes = 0L
    private var cachedTotalBytes = 0L
    private var cachedLanIps: List<String> = emptyList()

    /** 环境页诊断快照（IO 线程执行，每秒轮询）。 */
    suspend fun diagnostics(): RuntimeDiagnostics = withContext(Dispatchers.IO) {
        val pid = currentPid(scan = _state.value.phase == ServerPhase.RUNNING)
        var cpu = 0.0
        var memKb = 0L
        var threads = 0
        if (pid != null) {
            val jiffies = procJiffies(pid)
            val now = System.currentTimeMillis()
            if (lastCpuJiffies > 0L && lastCpuSampleAt > 0L && now > lastCpuSampleAt) {
                val dtSec = (now - lastCpuSampleAt) / 1000.0
                if (dtSec > 0.0) {
                    val dj = (jiffies - lastCpuJiffies).coerceAtLeast(0L)
                    cpu = (dj / 100.0 / dtSec * 100.0).coerceIn(0.0, 100.0)
                }
            }
            lastCpuJiffies = jiffies
            lastCpuSampleAt = now
            val status = procStatusFields(pid, "VmRSS", "Threads")
            memKb = status["VmRSS"] ?: 0L
            threads = status["Threads"]?.toInt() ?: 0
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastHeavyAt >= 5000L) {
            lastHeavyAt = nowMs
            cachedFds = pid?.let {
                runCatching { File("/proc/$it/fd").listFiles()?.size ?: 0 }.getOrDefault(0)
            } ?: 0
            cachedSessions = sessionsCount()
            val stat = android.os.StatFs(appContext.filesDir.absolutePath)
            cachedFreeBytes = runCatching { stat.availableBlocksLong * stat.blockSizeLong }.getOrDefault(0L)
            cachedTotalBytes = runCatching { stat.blockCountLong * stat.blockSizeLong }.getOrDefault(0L)
            cachedLanIps = lanIps()
        }
        val logFile = TermuxEnv.serverLog(appContext)
        val logLines = if (logFile.exists()) LogStore.named(logFile).count() else 0
        val logBytes = runCatching { logFile.length() }.getOrDefault(0L)
        RuntimeDiagnostics(
            pid = pid,
            cpuPercent = cpu,
            memRssKb = memKb,
            threads = threads,
            fds = cachedFds,
            sessions = cachedSessions,
            logLines = logLines,
            logBytes = logBytes,
            freeBytes = cachedFreeBytes,
            totalBytes = cachedTotalBytes,
            lanIps = cachedLanIps,
        )
    }

    /** /proc/<pid>/stat 的 utime+stime（clock ticks），进程不存在时返回 0。 */
    private fun procJiffies(pid: Long): Long {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return 0L
        // comm 可能含空格/括号，从最后一个 ')' 之后取数字字段
        val idx = stat.lastIndexOf(')')
        if (idx < 0) return 0L
        val parts = stat.substring(idx + 1).trim().split(' ')
        // 字段 3(utime)/4(stime) → 索引 11/12
        val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0L
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0L
        return utime + stime
    }

    /** /proc/<pid>/status 一次性读取多个字段（避免每秒读两次文件）。 */
    private fun procStatusFields(pid: Long, vararg names: String): Map<String, Long> {
        if (names.isEmpty()) return emptyMap()
        val wanted = names.toMutableSet()
        val lines = runCatching { File("/proc/$pid/status").readLines() }.getOrDefault(emptyList())
        val result = HashMap<String, Long>()
        for (line in lines) {
            if (wanted.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx)
            if (key in wanted) {
                val v = line.substring(idx + 1).trim().substringBefore(' ').toLongOrNull()
                if (v != null) {
                    result[key] = v
                    wanted.remove(key)
                }
            }
        }
        return result
    }

    /** dsh-home/sessions 下的会话目录数。 */
    private fun sessionsCount(): Int = runCatching {
        File(TermuxEnv.dshHome(appContext), "sessions").listFiles()?.count { it.isDirectory } ?: 0
    }.getOrDefault(0)

    /** 枚举所有启用的 IPv4 局域网地址（排除回环）。 */
    private fun lanIps(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .map { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())

    private fun fetchMeta(): RuntimeMeta? = try {
        // auto 源：首次访问 metadata 前完成测速解析（测速缓存后不重复）
        if (AppSettings.downloadSource(appContext) == AppSettings.SOURCE_AUTO) {
            metaUrl = effectiveMetaUrl(appContext)
        }
        val conn = URL(metaUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        RuntimeMeta(
            version = json.optString("version", "unknown"),
            url = json.getString("url"),
            sha256 = json.optString("sha256", ""),
            sizeBytes = json.optLong("sizeBytes", 0L),
            mirrors = json.optJSONArray("mirrors")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            arch = json.optString("arch", ""),
            termuxApp = json.optString("termuxApp", ""),
            dsh = json.optString("dsh", ""),
            nodeVersion = json.optString("nodeVersion", ""),
            builtAt = json.optString("builtAt", ""),
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun downloadWithFallback(meta: RuntimeMeta, target: File): Boolean {
        val candidates = proxyCandidates(meta)
        for ((index, candidate) in candidates.withIndex()) {
            _state.update {
                it.copy(message = "正在下载运行时（${meta.version}）…", speedBytesPerSec = 0L)
            }
            appendLog("> 尝试下载源 [${index + 1}/${candidates.size}]: $candidate")
            if (downloadFile(candidate, target, meta.sizeBytes)) {
                appendLog("> 下载源 [${index + 1}] 成功")
                return true
            }
            appendLog("! 下载源 [${index + 1}] 失败: $candidate")
            _state.update { it.copy(message = "下载源不可用，尝试切换…", speedBytesPerSec = 0L) }
        }
        return false
    }

    /** 按当前下载源构造候选下载地址。GHProxy 源给 GitHub 地址加优选前缀，直连与自定义镜像兜底。 */
    private fun proxyCandidates(meta: RuntimeMeta): List<String> {
        val prefix = when (SourceManager.resolve(appContext)) {
            AppSettings.SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/"

            AppSettings.SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/"

            else -> ""
        }
        if (prefix.isEmpty()) return listOf(meta.url) + meta.mirrors
        val candidates = (listOf(meta.url) + meta.mirrors)
            .map { url -> if (url.startsWith("https://github.com/")) prefix + url else url }
        // GHProxy 只代理 GitHub，镜像（非 GitHub 前缀）保持原样作为后续兜底
        return candidates + listOf(meta.url)
    }

    private suspend fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var out: OutputStream? = null
        var ok = false
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return false
            val contentLength = if (sizeBytes > 0) sizeBytes else conn.contentLengthLong
            target.parentFile?.mkdirs()
            out = BufferedOutputStream(FileOutputStream(target))
            input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastUpdate = 0L
            var lastLoggedPct = -1
            var speedBps = 0L
            var lastSpeedAt = System.currentTimeMillis()
            var lastSpeedTotal = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                val now = System.currentTimeMillis()
                if (now - lastSpeedAt >= 500) {
                    val dtSec = (now - lastSpeedAt) / 1000.0
                    if (dtSec > 0.0) speedBps = ((total - lastSpeedTotal) / dtSec).toLong()
                    lastSpeedAt = now
                    lastSpeedTotal = total
                }
                if (total - lastUpdate > 512 * 1024 || (contentLength > 0 && total >= contentLength)) {
                    lastUpdate = total
                    if (contentLength > 0) {
                        val pct = (total.toDouble() / contentLength).coerceIn(0.0, 1.0)
                        val pctInt = (pct * 100).toInt()
                        val speed = formatSpeed(speedBps)
                        if (pctInt / 5 > lastLoggedPct) {
                            lastLoggedPct = pctInt / 5
                            appendLog("> 下载中 ${pctInt}%（${speed}）")
                        }
                        _state.update {
                            it.copy(
                                progress = pct.toFloat(),
                                speedBytesPerSec = speedBps,
                                message = "正在下载运行时（${pctInt}%）· ${speed}",
                            )
                        }
                    }
                }
                if (contentLength > 0 && total > contentLength) {
                    return false
                }
            }
            if (contentLength > 0 && total != contentLength) {
                appendLog("! 下载不完整: 预期 $contentLength 实际 $total")
                return false
            }
            ok = true
            true
        } catch (e: Exception) {
            appendLog("! 下载异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            runCatching { input?.close() }
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
            if (!ok) runCatching { target.delete() }
        }
    }

    /** 字节速率人类可读格式化（如 12.3 MB/s）；未采样到时返回省略号。 */
    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "…"

        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)

        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)

        else -> "${bytesPerSec} B/s"
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(file.inputStream(), 256 * 1024).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** 目标目录所在分区的可用字节数；异常返回 -1。 */
    private fun availableSpace(dir: File): Long = runCatching {
        dir.mkdirs()
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes
    }.getOrDefault(-1L)

    private fun extractZip(zip: File, dest: File): Boolean {
        // 调用方（downloadAndInstall）已先 stopServer 释放文件句柄
        return try {
            val tmp = File(dest.parentFile, "usr.tmp")
            tmp.deleteRecursively()
            tmp.mkdirs()
            val input = ZipInputStream(zip.inputStream())
            var entry = input.nextEntry
            var done = 0L
            val total = estimateEntries(zip)
            while (entry != null) {
                val target = safeResolve(tmp, entry.name)
                if (target != null) {
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> input.copyTo(out) }
                    }
                }
                entry = input.nextEntry
                done++
                if (done % 200L == 0L && total > 0) {
                    _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                    appendLog("> 解压中 ${done} 个文件…")
                }
            }
            input.close()
            val nested = File(tmp, "usr")
            if (nested.isDirectory) {
                copyRecursively(nested, tmp)
                nested.deleteRecursively()
            }
            makeExecutable(File(tmp, "bin"))
            makeExecutable(File(tmp, "libexec"))
            // 删除旧 dest：删除失败（残留文件）不阻断，交给下面 overwrite 覆盖
            runCatching { dest.deleteRecursively() }
            if (!tmp.renameTo(dest)) {
                // Android rename 跨分区/被占用时返回 false：用 overwrite 复制兜底
                appendLog("> 目录重命名失败，改用逐文件复制安装…")
                copyRecursively(tmp, dest)
                tmp.deleteRecursively()
            }
            if (!TermuxEnv.dshEntry(appContext).exists()) {
                appendLog("! 解压完成但 dsh 入口缺失（dest=${dest.absolutePath}）")
            }
            true
        } catch (e: Exception) {
            appendLog("! 运行时解压失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun estimateEntries(zip: File): Long = runCatching {
        val input = ZipInputStream(zip.inputStream())
        var count = 0L
        while (input.nextEntry != null) count++
        input.close()
        count
    }.getOrDefault(0L)

    private fun safeResolve(base: File, name: String): File? {
        val cleaned = name.removePrefix("/")
        if (cleaned == "" || cleaned.contains("..") || cleaned.contains("\u0000")) return null
        return File(base, cleaned)
    }

    private fun makeExecutable(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.setExecutable(true, false) }
    }

    /** 容错递归复制：单文件失败（如仍被占用）跳过并继续，不中断整体安装。 */
    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursively(it, File(dst, it.name)) }
        } else {
            try {
                src.copyTo(dst, overwrite = true)
            } catch (e: Exception) {
                appendLog("! 复制失败跳过: ${src.name} (${e.javaClass.simpleName})")
            }
        }
    }

    // ------------------------------------------------------------------ 服务启动

    // DSHM: 运行时 cookie 补丁（就地改 SameSite=Strict → Lax）
    /**
     * DSHM: 把已安装运行时里 dsh 网页会话 cookie 的 SameSite=Strict 就地改成 Lax。
     *
     * 为什么必须改：dsh >= 0.1.1 的网页鉴权是「GET /?token=... → 303 跳回 / 并下发
     * cookie」。从外部 App（「打开 Harness」按钮）发起导航时，浏览器把它当作跨站导航，
     * SameSite=Strict 的 cookie 在 303 之后那一跳不会被带上 —— 于是 token 是对的、cookie
     * 也已经下发，用户看到的却仍是 401 "dsh web authentication required"，手动刷新一次
     * （浏览器自己发起的同站导航）才正常。改成 Lax 后顶层 GET 跳转放行，第一次就能进。
     *
     * 就地改文件而不是重下运行时：运行时约 500 MB；这里只改一处属性，幂等，装上新 APK
     * 后首次启动即生效，重装运行时之后也会自动再补一次。
     *
     * @return true 表示本次真的改写了文件（调用方应重启服务让新代码生效）
     */
    private fun patchRuntimeAuthCookie(): Boolean {
        val rel = "lib/node_modules/@deepseek-ai/dsh-client-connection/lib/index.js"
        val file = File(TermuxEnv.prefix(appContext), rel)
        if (!file.isFile) {
            appendLog("[DSHM] 运行时 cookie 补丁: 还没装运行时，找不到 $rel（跳过）")
            return false
        }
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            appendLog("[DSHM] 运行时 cookie 补丁: 读取 $rel 失败（跳过）")
            return false
        }
        if (!text.contains("SameSite=Strict")) {
            if (text.contains("SameSite=Lax")) {
                appendLog("[DSHM] 运行时 cookie 补丁: 已是 Lax，无需改动")
            } else {
                appendLog("[DSHM] 运行时 cookie 补丁: 没找到 SameSite 属性（未改动）")
            }
            return false
        }
        val wrote = runCatching {
            file.writeText(text.replace("SameSite=Strict", "SameSite=Lax"))
        }.isSuccess
        if (wrote) {
            appendLog("[DSHM] 运行时 cookie 补丁: SameSite Strict → Lax（修「首次打开 401」）")
        } else {
            appendLog("[DSHM] 运行时 cookie 补丁: 写入失败，本次未生效")
        }
        return wrote
    }

    private suspend fun startServerIfNeeded() {
        if (_state.value.phase == ServerPhase.RUNNING) return
        startServer()
        if (_state.value.phase == ServerPhase.RUNNING) return
        waitForReady()
    }

    fun startServer() {
        if (_state.value.phase == ServerPhase.RUNNING || _state.value.phase == ServerPhase.STARTING) return
        if (serverProcess?.isAlive == true) return
        val ctx = appContext
        TermuxEnv.home(ctx).mkdirs()
        TermuxEnv.tmp(ctx).mkdirs()
        TermuxEnv.dshHome(ctx).mkdirs()
        TermuxEnv.workspace(ctx).mkdirs()
        TermuxEnv.logs(ctx).mkdirs()
        TermuxEnv.ensureBinLinks(ctx)

        val port = _state.value.port
        val node = TermuxEnv.nodeBin(ctx)
        val entry = TermuxEnv.dshEntry(ctx)
        val logFile = TermuxEnv.serverLog(ctx)

        // 每次启动服务清空日志，终端日志框只展示本次过程（不做历史累积）。
        clearLog()
        appendLog("[DSHM] 稳定性补丁 stability-v1（崩溃记录 / 停服不阻塞界面 / 意外退出自动重启）")
        CrashRecorder.lastExitSummary?.let { appendLog(it) }

        // credentials-local 要求凭证文件仅属主可读（mode 600），否则拒绝读取
        // 导致 DeepSeek API Key 解析失败。Android 解压可能带 group/other 位，先收敛。
        val credentialsFile = File(TermuxEnv.dshHome(ctx), ".credentials.yaml")
        if (credentialsFile.exists()) {
            runCatching {
                credentialsFile.setReadable(true, true)
                credentialsFile.setWritable(true, true)
            }
        }

        // DSHM: 维护钩子（见 maintenanceHook 的注释）。放在 preflight 之前，
        // 因此即使 preflight 直接判失败返回，日志里也已经有了 profile 配置。
        maintenanceHook()
        val diag = preflight(node, entry)
        if (diag != null) {
            writeDiagnostics(logFile, diag)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = "启动自检失败\n\n$diag") }
            return
        }

        // DSHM: 拉起 node 之前再确认一次 cookie 属性（幂等）。放在 clearLog() 之后，
        // 所以它写的那行会留在「查看运行日志」里 —— 装的运行时是什么状态一眼可见。
        patchRuntimeAuthCookie()

        val command = listOf(node.absolutePath, "--expose-internals", entry.absolutePath, "web", "--port", port.toString())
        val pb = ProcessBuilder(command)
        pb.environment().putAll(TermuxEnv.serverEnv(ctx))
        pb.directory(TermuxEnv.workspace(ctx))
        pb.redirectErrorStream(true)
        // PIPE 逐行转发到 server.log：node stdout 重定向到文件时是块缓冲，
        // 小日志不 flush 会导致启动日志框空白；应用侧逐行读+flush 保证实时可见。
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)

        serverProcess = try {
            pb.start()
        } catch (e: Exception) {
            val detail = "启动失败：${e.message ?: e.javaClass.simpleName}"
            writeDiagnostics(logFile, detail)
            _state.update { it.copy(phase = ServerPhase.ERROR, message = detail) }
            return
        }
        forwardProcessOutput(serverProcess, logFile)
        startedAt = System.currentTimeMillis()
        serverPid = processPid(serverProcess)
        orphanPid = null
        watchProcessExit(serverProcess)
        _state.update {
            it.copy(phase = ServerPhase.STARTING, pid = serverPid, message = "正在启动服务…")
        }
    }

    /**
     * 等待 node 进程退出。非主动停止的退出写一份记录到 logs/crash；若退出前服务已在
     * 正常运行且开启了自动重启，则在限流范围内自动重新拉起。启动阶段就退出的情况交给
     * waitForReady 报错，不自动重启（多半是配置问题，重启也起不来）。
     */
    private fun watchProcessExit(proc: Process?) {
        if (proc == null) return
        scope.launch {
            val code = runCatching { proc.waitFor() }.getOrNull()
            if (proc === expectedExit) return@launch
            val phaseBefore = _state.value.phase
            val enabled = AppSettings.autoRestartServer(appContext)
            val now = SystemClock.elapsedRealtime()
            val allowed = synchronized(autoRestartTimes) {
                while (autoRestartTimes.isNotEmpty() && now - autoRestartTimes.first() > AUTO_RESTART_WINDOW_MS) {
                    autoRestartTimes.removeFirst()
                }
                autoRestartTimes.size < AUTO_RESTART_MAX
            }
            val willRestart = phaseBefore == ServerPhase.RUNNING && enabled && allowed
            appendLog("[DSHM] 服务进程意外退出 exit=${code ?: "?"}（之前状态 ${phaseBefore.name}）")
            CrashRecorder.recordServerExit(code, phaseBefore.name, tailLog(120), willRestart)
            if (phaseBefore != ServerPhase.RUNNING || serverProcess !== proc) return@launch
            serverProcess = null
            serverPid = null
            startedAt = 0L
            if (!willRestart) {
                _state.update {
                    it.copy(
                        phase = ServerPhase.ERROR,
                        pid = null,
                        message = "服务进程意外退出（exit=${code ?: "?"}）" +
                            (if (enabled) "，短时间内重启次数过多，已停止自动重启" else "") +
                            "\n\n${tailLog(40)}",
                    )
                }
                return@launch
            }
            synchronized(autoRestartTimes) { autoRestartTimes.addLast(now) }
            _state.update {
                it.copy(phase = ServerPhase.NOT_READY, pid = null, message = "服务意外退出，正在自动重启…")
            }
            delay(2000)
            appendLog("[DSHM] 自动重启服务…")
            bootstrap()
        }
    }

    /** 将 node 进程的 stdout/stderr 逐行转发到 server.log（LogStore 内存缓冲 + 批量落盘）。 */
    private fun forwardProcessOutput(proc: Process?, logFile: File) {
        if (proc == null) return
        val log = LogStore.named(logFile)
        scope.launch {
            val reader = proc.inputStream.bufferedReader()
            try {
                for (line in reader.lineSequence()) {
                    log.append(line)
                    // DSHM: 读到 dsh 打印的带 token 网址就立刻存下来（内存 + 小文件），
                    // 不依赖日志落盘时机、也不怕日志环形缓冲把它挤掉。
                    runCatching {
                        extractHarnessUrl(line)?.let {
                            capturedHarnessUrl = it
                            runCatching {
                                File(TermuxEnv.logs(appContext), "harness-url.txt").writeText(it)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 进程被销毁时读流可能中断，属预期
            } finally {
                log.flushForExit()
                runCatching { reader.close() }
            }
        }
    }

    /**
     * DSHM: 启动前维护钩子。
     *
     * dsh 因为 profile 配置问题起不来时，应用仍然会走到这里 —— 这是唯一一条不需要
     * 启动服务、也不需要 root 就能读到 DSH_HOME 的通路（本进程就是那些文件的属主）。
     *
     * 每次启动服务都会执行，做两件事：
     *   1) 把 profiles/web 的关键配置打进运行日志，便于定位启动失败的原因；
     *   2) 若工作区里存在 dshm-fix.sh，则以本应用身份、带运行时环境执行它并记录输出。
     */
    private fun maintenanceHook() {
        appendLog("===== DSHM 维护钩子 =====")
        val home = TermuxEnv.dshHome(appContext)
        val profile = File(home, "profiles/web")
        appendLog("dshHome=" + home.absolutePath)
        appendLog("profile=" + profile.absolutePath + " isDir=" + profile.isDirectory)
        val manifest = File(profile, "package.json")
        appendLog("--- profiles/web/package.json ---")
        if (manifest.isFile) {
            appendLog("配置存在，保留原内容；不在启动日志记录配置正文（" + manifest.length() + " bytes）")
        } else {
            appendLog("(不存在)")
        }
        val userPatch = File(profile, "cordis.patch.yml")
        if (userPatch.isFile) {
            appendLog("--- profiles/web/cordis.patch.yml ---")
            appendLog("补丁存在，保留原内容；不在启动日志记录配置正文（" + userPatch.length() + " bytes）")
        }
        val nm = File(profile, "node_modules")
        if (nm.isDirectory) {
            appendLog("--- profiles/web/node_modules ---")
            appendLog(nm.list()?.sorted()?.joinToString(", ") ?: "(list failed)")
        }
        val candidates = listOf(
            File(TermuxEnv.workspace(appContext), "dshm-fix.sh"),
            File("/storage/emulated/0/DSHM/dshm-fix.sh"),
        )
        val script = candidates.firstOrNull { it.isFile && it.canRead() }
        if (script == null) {
            appendLog("--- 未找到 dshm-fix.sh ---")
            for (c in candidates) appendLog("   checked: " + c.absolutePath)
        } else {
            appendLog("--- run " + script.absolutePath + " ---")
            for (line in runMaintenanceScript(script).lineSequence()) appendLog(line)
        }
    }

    /** DSHM: 以本应用身份执行维护脚本（只有 nativeLibraryDir 里的可执行文件允许 exec）。 */
    private fun runMaintenanceScript(script: File): String {
        val bash = File(TermuxEnv.nativeLibDir(appContext), "libbash.so")
        if (!bash.isFile) return "(libbash.so not found)"
        return runCatching {
            val pb = ProcessBuilder(bash.absolutePath, script.absolutePath)
            pb.environment().putAll(TermuxEnv.serverEnv(appContext))
            pb.directory(TermuxEnv.workspace(appContext))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(120000L)
            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            runCatching { p.destroyForcibly() }
            out.append("(exit=").append(code).append(")")
            out.toString()
        }.getOrElse { "(script failed: " + (it.message ?: it.javaClass.simpleName) + ")" }
    }

    private fun preflight(node: File, entry: File): String? {
        val prefix = TermuxEnv.prefix(appContext).absolutePath
        val nativeLib = TermuxEnv.nativeLibDir(appContext).absolutePath
        val lines = mutableListOf<String>()
        lines += "prefix=$prefix"
        lines += "nativeLib=$nativeLib"
        lines += "node=$node | 存在=${node.exists()} | 可执行=${node.canExecute()} | 大小=${runCatching { node.length() }.getOrNull()}"
        lines += "dsh=$entry | 存在=${entry.exists()}"
        lines += "libc++_shared.so=${File(nativeLib, "libc++_shared.so").exists()}"
        lines += "logs=${TermuxEnv.serverLog(appContext).absolutePath}"
        val missing = lines.filter { it.contains("不存在") || it.contains("false") }
        if (missing.isNotEmpty()) return lines.joinToString("\n")
        return null
    }

    private fun writeDiagnostics(logFile: File, content: String) {
        val log = LogStore.named(logFile)
        log.clear()
        for (line in content.split("\n")) {
            log.append(line)
        }
    }

    private fun appendLog(line: String) {
        LogStore.named(TermuxEnv.serverLog(appContext)).append(line)
    }

    /** 清空服务日志文件（每次下载/启动时调用，终端日志框不做历史累积）。 */
    private fun clearLog() {
        LogStore.named(TermuxEnv.serverLog(appContext)).clear()
        // DSHM: 换一个 harness 进程就换一个 token，所以启动时把上次捕获的网址清掉，
        // 免得「打开 Harness」拿到旧 token 又变成 401。
        capturedHarnessUrl = null
        runCatching { File(TermuxEnv.logs(appContext), "harness-url.txt").delete() }
        // DSHM: 版本标记 —— 在「查看运行日志」里能直接看到装的是哪一版补丁
        appendLog("[DSHM] 补丁版本 token-fix-v6 (2026-09-17)")
    }

    private fun processPid(p: Process?): Long? {
        if (p != null) {
            // 1. Java 9+ Process.pid()（部分 Android 实现可用）
            runCatching { (p.javaClass.getMethod("pid").invoke(p) as Number).toLong() }
                .getOrNull()?.let { return it }
            // 2. 反射底层 pid 字段（Android ProcessImpl/JavaProcess）
            runCatching {
                p.javaClass.declaredFields.firstOrNull { it.name == "pid" }?.let { f ->
                    f.isAccessible = true
                    (f.get(p) as Number).toLong()
                }
            }.getOrNull()?.let { return it }
            // 3. /proc 按 cmdline + 父进程匹配
            findChildProcess("node")?.let { return it }
        }
        // 4. serverProcess 引用丢失（应用重启后旧 node 进程仍存活）时，通过监听端口反查 PID
        return findPidByPort(_state.value.port)
    }

    /** 扫描 /proc 下父进程为当前进程、cmdline 含关键字的子进程 PID。 */
    private fun findChildProcess(keyword: String): Long? {
        val myPid = android.os.Process.myPid()
        return runCatching {
            File("/proc").listFiles()?.firstNotNullOfOrNull { dir ->
                val pid = dir.name.toLongOrNull() ?: return@firstNotNullOfOrNull null
                // stat 第 4 字段为 ppid（')' 后第 2 个）
                val ppid = runCatching { File(dir, "stat").readText() }.getOrNull()?.let { stat ->
                    val idx = stat.lastIndexOf(')')
                    if (idx >= 0) stat.substring(idx + 1).trim().split(' ').getOrNull(1)?.toLongOrNull() else null
                }
                if (ppid != myPid.toLong()) return@firstNotNullOfOrNull null
                val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: ""
                if (cmdline.contains(keyword)) pid else null
            }
        }.getOrNull()
    }

    /** 读取已安装运行时的 dsh 版本（本地，离线可用）。 */
    private fun readRuntimeVersion(): String? = runCatching {
        val pkg = File(
            TermuxEnv.prefix(appContext),
            "lib/node_modules/@deepseek-ai/dsh/package.json",
        )
        JSONObject(pkg.readText()).optString("version").ifBlank { null }
    }.getOrNull()

    /** 应用期望的运行时版本（编译期内置，与 runtime 构建的 DSH_VERSION 一致）。 */
    fun expectedRuntimeVersion(): String = BuildConfig.RUNTIME_VERSION

    /**
     * 检查运行时是否需要更新：仅当应用期望运行时版本 ≠ 已安装版本时标记。
     * 纯应用升级（期望版本未变）时保留现有运行时，不触发检查更新。
     */
    fun checkRuntimeUpdate() {
        val installed = readRuntimeVersion() ?: return
        val expected = BuildConfig.RUNTIME_VERSION
        if (expected.isBlank()) return
        _runtimeUpdateAvailable.value = installed != expected
    }

    /** 通过 /proc/net/tcp 监听端口反查进程 PID（用于 serverProcess 引用丢失的场景）。 */
    private fun findPidByPort(port: Int): Long? {
        val inode = findSocketInode(port) ?: return null
        val target = "socket:[$inode]"
        val procDir = File("/proc")
        val pids = procDir.listFiles()?.filter { it.name.all { c -> c.isDigit() } } ?: return null
        for (dir in pids) {
            val fdDir = File(dir, "fd")
            val fds = fdDir.listFiles() ?: continue
            val hit = fds.any { fd ->
                runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString().contains(target) }
                    .getOrDefault(false)
            }
            if (hit) return dir.name.toLong()
        }
        return null
    }

    /** 解析 /proc/net/tcp(+tcp6) 中监听指定端口的 socket inode（仅 LISTEN 状态）。 */
    private fun findSocketInode(port: Int): String? {
        val portHex = String.format("%04X", port)
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val lines = runCatching { File(path).readLines() }.getOrDefault(emptyList())
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 10 &&
                    parts[1].substringAfter(':').equals(portHex, ignoreCase = true) &&
                    parts[3] == "0A"
                ) {
                    return parts[9]
                }
            }
        }
        return null
    }

    private suspend fun waitForReady() {
        val port = _state.value.port
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < READY_TIMEOUT_MS) {
            val proc = serverProcess
            if (proc == null || !proc.isAlive) {
                val exit = proc?.let { runCatching { it.exitValue() }.getOrNull() }
                _state.update {
                    it.copy(
                        phase = ServerPhase.ERROR,
                        message = "服务进程已退出（exit=${exit ?: "?"}），请查看日志\n\n${tailLog(60)}",
                    )
                }
                return
            }
            if (ping(port)) {
                _state.update { it.copy(phase = ServerPhase.RUNNING, message = "服务运行中") }
                CrashRecorder.setStateSummary("server=running pid=${serverPid ?: "?"} port=$port")
                return
            }
            delay(500)
        }
        _state.update {
            it.copy(phase = ServerPhase.ERROR, message = "服务启动超时，请查看日志\n\n${tailLog(40)}")
        }
    }

    private fun ping(port: Int): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            val code = conn.responseCode
            conn.disconnect()
            // 只确认Web入口已响应；404/5xx不再误报启动成功，插件业务仍需另行验证。
            code in 200..399 || code == 401
        } catch (_: Exception) {
            false
        }
    }
}
