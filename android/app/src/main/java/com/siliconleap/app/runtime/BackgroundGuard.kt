package com.siliconleap.app.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台进程保护：
 * - 请求「忽略电池优化」白名单（Android 系统省电/待机可能杀死后台 node/子系统进程）。
 * - 提供检查与跳转系统设置的方法。
 */
object BackgroundGuard {
    /** 解除 Android 12+「幻影进程」上限的 adb 命令（需电脑执行一次；Android 14+ 也可在开发者选项里关闭）。 */
    const val PHANTOM_ADB_COMMAND =
        "adb shell device_config set_sync_disabled_for_tests persistent; " +
            "adb shell device_config put activity_manager max_phantom_processes 2147483647; " +
            "adb shell settings put global settings_enable_monitor_phantom_procs false"

    /**
     * 系统是否仍在监控「幻影进程」（默认开启：所有 App 合计只允许 32 个子进程，超出直接 SIGKILL，
     * 表现为 node 服务 exit=137、所有工作区会话同时中断）。读不到属性时按开启处理。
     */
    fun phantomMonitorEnabled(): Boolean = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val v = c.getMethod("get", String::class.java, String::class.java)
            .invoke(null, "persist.sys.fflag.override.settings_enable_monitor_phantom_procs", "") as String
        v.isBlank() || v.equals("true", ignoreCase = true)
    }.getOrDefault(true)

    /** 打开开发者选项（Android 14+ 有「停用子进程限制」开关）。 */
    fun openDeveloperOptions(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 应用是否已在系统「忽略电池优化」白名单中。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    /** 能否直接发起「忽略电池优化」请求（Android M+）。 */
    fun canRequestBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        // 需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，声明于 Manifest
        return true
    }

    /** 发起系统「忽略电池优化」请求（弹系统对话框）。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 跳转到应用详情页（用户可手动改电池/自启动/锁定后台）。 */
    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 跳转到系统电池优化列表（Android M+）。 */
    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
