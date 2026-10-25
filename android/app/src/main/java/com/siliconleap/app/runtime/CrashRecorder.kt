package com.siliconleap.app.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃与退出记录：
 *  - App 进程未捕获异常 → logs/crash/app-crash-<时间>.txt（写完交还系统默认处理器）；
 *  - 系统记录的历史退出原因（ApplicationExitInfo：低内存查杀 / ANR / 原生崩溃 / 被冻结…）
 *    → 每次启动追加到 logs/crash/exit-reasons.txt（按时间戳去重）；
 *  - node 服务进程意外退出 → logs/crash/server-exit-<时间>.txt（含退出码与最后的服务日志）。
 *
 * node 是 App 进程的子进程：App 进程一旦死亡（界面崩溃、被系统回收），系统会连同整个进程组
 * 一起结束，DSH 也随之退出。这里留下的记录用于区分是哪一种情况。
 */
object CrashRecorder {
    private const val PREFS = "siliconleap_crash"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val MAX_FILES = 30
    private const val MAX_EXIT_LOG_BYTES = 256 * 1024L
    private const val MAX_TRACE_CHARS = 16_000

    private lateinit var appContext: Context

    /** 本次启动时发现的、上一次 App 进程的退出原因（一行摘要，供写进运行日志）。 */
    @Volatile
    var lastExitSummary: String? = null
        private set

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeAppCrash(thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
        Thread({ runCatching { collectExitReasons() } }, "dshm-exit-reasons").apply {
            isDaemon = true
            start()
        }
    }

    fun crashDir(): File = File(TermuxEnv.logs(appContext), "crash").apply { mkdirs() }

    /** 进程状态摘要会随下一次退出记录一起由系统保存（上限 128 字节）。 */
    fun setStateSummary(text: String) {
        if (!::appContext.isInitialized) return
        runCatching {
            val am = appContext.getSystemService(ActivityManager::class.java) ?: return
            am.setProcessStateSummary(text.toByteArray(Charsets.UTF_8).take(128).toByteArray())
        }
    }

    fun recordServerExit(exitCode: Int?, phaseBefore: String, logTail: String, willRestart: Boolean) {
        if (!::appContext.isInitialized) return
        runCatching {
            val text = buildString {
                appendLine("time=${stamp(System.currentTimeMillis())}")
                appendLine("event=node 服务进程意外退出")
                appendLine("exit=${exitCode ?: "?"}${signalHint(exitCode)}")
                appendLine("phaseBefore=$phaseBefore")
                appendLine("autoRestart=$willRestart")
                appendLine(memorySnapshot())
                if (exitCode == 137) appendLine(processCensus())
                appendLine()
                appendLine("----- 服务日志（最后部分）-----")
                append(logTail)
            }
            File(crashDir(), "server-exit-${fileStamp()}.txt").writeText(text)
            prune()
        }
    }

    /** 被捕获、但原本会让 App 崩溃的异常（例如后台协程里的异常）。 */
    fun recordHandled(where: String, error: Throwable) {
        if (!::appContext.isInitialized) return
        runCatching {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val text = buildString {
                appendLine("time=${stamp(System.currentTimeMillis())}")
                appendLine("event=已拦截的异常（未导致崩溃）")
                appendLine("where=$where")
                appendLine(memorySnapshot())
                appendLine()
                append(sw.toString())
            }
            File(crashDir(), "handled-${fileStamp()}.txt").writeText(text)
            prune()
        }
    }

    /** 设置页展示用：最近的退出原因 + 最近几份崩溃文件的开头。 */
    fun report(maxFiles: Int = 5): String = runCatching {
        val dir = crashDir()
        buildString {
            appendLine("记录目录：${dir.absolutePath}")
            appendLine()
            val exitLog = File(dir, "exit-reasons.txt")
            appendLine("===== 系统记录的退出原因（最近）=====")
            if (exitLog.isFile) {
                val blocks = exitLog.readText().split("\n\n").filter { it.isNotBlank() }
                if (blocks.isEmpty()) appendLine("(暂无)") else blocks.takeLast(8).reversed().forEach {
                    appendLine(it.trim().lineSequence().take(14).joinToString("\n"))
                    appendLine()
                }
            } else {
                appendLine("(暂无)")
                appendLine()
            }
            val files = dir.listFiles { f -> f.isFile && f.name != "exit-reasons.txt" }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            appendLine("===== 崩溃 / 服务退出文件（${files.size} 份，显示最近 $maxFiles 份）=====")
            if (files.isEmpty()) appendLine("(暂无)")
            for (f in files.take(maxFiles)) {
                appendLine("--- ${f.name} ---")
                appendLine(f.readText().take(3000))
                appendLine()
            }
        }
    }.getOrElse { "读取崩溃记录失败：${it.message ?: it.javaClass.simpleName}" }

    fun clear() {
        runCatching { crashDir().listFiles()?.forEach { it.delete() } }
        lastExitSummary = null
    }

    private fun writeAppCrash(thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = buildString {
            appendLine("time=${stamp(System.currentTimeMillis())}")
            appendLine("event=App 未捕获异常（App 进程退出，DSH 服务会随之结束）")
            appendLine("thread=${thread.name}")
            appendLine("app=${appVersion()} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("serverPhase=${runCatching { RuntimeManager.state.value.phase.name }.getOrDefault("?")}")
            appendLine(memorySnapshot())
            appendLine()
            append(sw.toString())
        }
        File(crashDir(), "app-crash-${fileStamp()}.txt").writeText(text)
        prune()
    }

    private fun collectExitReasons() {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return
        val infos = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 0)
        if (infos.isEmpty()) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        val fresh = infos.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        val out = File(crashDir(), "exit-reasons.txt")
        if (out.length() > MAX_EXIT_LOG_BYTES) {
            runCatching { out.writeText(out.readText().takeLast((MAX_EXIT_LOG_BYTES / 2).toInt())) }
        }
        val sb = StringBuilder()
        for (info in fresh) sb.append(describe(info)).append("\n\n")
        out.appendText(sb.toString())
        prefs.edit().putLong(KEY_LAST_EXIT_TS, fresh.last().timestamp).apply()
        val latest = fresh.last()
        lastExitSummary = "[DSHM] 上次 App 进程退出：${stamp(latest.timestamp)} · ${reasonName(latest.reason)}" +
            (latest.description?.let { " · $it" } ?: "") +
            " · 优先级=${importanceName(latest.importance)}" +
            " · rss=${latest.rss / 1024}MB"
    }

    private fun describe(info: ApplicationExitInfo): String = buildString {
        appendLine("time=${stamp(info.timestamp)}")
        appendLine("reason=${reasonName(info.reason)} (${info.reason})")
        appendLine("description=${info.description ?: "-"}")
        appendLine("status=${info.status}${if (info.reason == ApplicationExitInfo.REASON_SIGNALED) signalHint(info.status) else ""}")
        appendLine("process=${info.processName} pid=${info.pid}")
        appendLine("importance=${importanceName(info.importance)} (${info.importance})")
        appendLine("pss=${info.pss / 1024}MB rss=${info.rss / 1024}MB")
        info.processStateSummary?.let { appendLine("stateSummary=${String(it, Charsets.UTF_8)}") }
        if (info.reason == ApplicationExitInfo.REASON_ANR ||
            info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        ) {
            val trace = runCatching {
                info.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!trace.isNullOrBlank()) {
                appendLine("trace:")
                append(trace.take(MAX_TRACE_CHARS))
            }
        }
    }.trimEnd()

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "自行退出"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号结束"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "低内存查杀"
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
        ApplicationExitInfo.REASON_ANR -> "ANR（界面无响应）"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户结束（如强行停止）"
        ApplicationExitInfo.REASON_USER_STOPPED -> "用户停用"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程死亡"
        ApplicationExitInfo.REASON_OTHER -> "系统其它原因"
        14 -> "后台冻结后被杀"
        15 -> "应用状态变更"
        16 -> "应用更新"
        else -> "未知"
    }

    private fun importanceName(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "前台"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "前台服务"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "可见"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "可感知"
        importance <= 300 -> "后台服务"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "后台缓存"
        else -> "已不在运行"
    }

    private fun signalHint(code: Int?): String = when (code) {
        null -> ""
        9, 137 -> "（SIGKILL：通常是被系统/低内存查杀）"
        6, 134 -> "（SIGABRT：进程自身中止）"
        11, 139 -> "（SIGSEGV：原生崩溃）"
        15, 143 -> "（SIGTERM：被要求结束）"
        else -> ""
    }

    private fun memorySnapshot(): String = runCatching {
        val am = appContext.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        "mem: 系统可用=${mi.availMem / 1048576}MB/${mi.totalMem / 1048576}MB lowMemory=${mi.lowMemory}" +
            " · App Java 堆=${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB/${rt.maxMemory() / 1048576}MB" +
            " · App 原生堆=${Debug.getNativeHeapAllocatedSize() / 1048576}MB"
    }.getOrDefault("mem: ?")

    /**
     * DSHM: SIGKILL (exit=137) with lowMemory=false is almost always Android 12+'s phantom-process
     * limit (all apps together may keep only 32 child processes) or a vendor thermal/cleanup kill.
     * Record how many processes this app UID owned and the phantom monitor flag so the next
     * report proves which one it was instead of guessing.
     */
    private fun processCensus(): String = runCatching {
        val uid = Process.myUid().toString()
        val names = HashMap<String, Int>()
        var total = 0
        File("/proc").listFiles()?.forEach { dir ->
            if (!dir.name.all(Char::isDigit)) return@forEach
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: return@forEach
            val owner = Regex("""Uid:\s+(\d+)""").find(status)?.groupValues?.get(1)
            if (owner != uid) return@forEach
            total++
            val name = Regex("""Name:\s+(\S+)""").find(status)?.groupValues?.get(1) ?: "?"
            names[name] = (names[name] ?: 0) + 1
        }
        val phantom = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java, String::class.java)
                .invoke(null, "persist.sys.fflag.override.settings_enable_monitor_phantom_procs", "") as String
        }.getOrDefault("?")
        val top = names.entries.sortedByDescending { it.value }.take(8).joinToString(", ") { "${it.key}=${it.value}" }
        "procs: appUidProcesses=$total phantomMonitorOverride=${phantom.ifBlank { "unset(默认开启)" }} · $top"
    }.getOrDefault("procs: ?")

    private fun appVersion(): String = com.siliconleap.app.BuildConfig.VERSION_NAME

    private fun prune() {
        val files = crashDir().listFiles { f -> f.isFile && f.name != "exit-reasons.txt" } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
}
