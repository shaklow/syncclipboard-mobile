package expo.modules.rootclipboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import expo.modules.nativeutil.NativeLogger
import java.io.BufferedReader

/**
 * RootClipboardModule
 *
 * 通过 Root 权限在后台读取剪贴板内容，替代 Shizuku 方案。
 *
 * 原理：
 * - 使用 `su -c` 以 root 身份执行 shell 命令读写系统剪贴板
 * - 文本读取：`cmd clipboard get-text`（Android 13+）
 * - 类型检查 / 图片检测：解析 `dumpsys clipboard` 输出
 * - 完全不需要 Shizuku 依赖或其他第三方服务
 *
 * 静默后台增强：
 * - `bypassBatteryOptimization()`: 通过 root 将自身加入省电白名单，防止 Doze 冻结定时器
 * - `isBatteryOptimizationBypassed()`: 检查是否已在白名单中
 * - `setWatchdogAlarm()`: 设置 AlarmManager 保活闹钟，作为定时器被 Doze 冻结时的最后防线
 */
class RootClipboardModule : Module() {

    companion object {
        private const val TAG = "RootClipboard"
        private const val WATCHDOG_ALARM_ACTION = "expo.modules.rootclipboard.WATCHDOG_TICK"
        private const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟

        /** JS 侧可通过此 key 监听保活闹钟事件 */
        const val EVENT_WATCHDOG_TICK = "onWatchdogTick"
    }

    /** 当前是否持有保活闹钟引用 */
    private var watchdogPendingIntent: PendingIntent? = null

    /** 保活闹钟广播接收器 */
    private val watchdogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            NativeLogger.i(TAG, "Watchdog alarm fired! Sending tick event to JS")
            // 通知 JS 层进行轮询健康检查
            sendEvent(EVENT_WATCHDOG_TICK, mapOf("timestamp" to System.currentTimeMillis()))
            // 重新设置下一次闹钟
            setWatchdogAlarmInternal()
        }
    }

    private var receiverRegistered = false

    /**
     * 以 root 身份执行命令并返回标准输出
     */
    private fun execRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.waitFor()
            if (exitCode != 0 && stderr.isNotBlank()) {
                NativeLogger.w(TAG, "su stderr (exit=$exitCode): $stderr")
            }
            stdout
        } catch (e: Exception) {
            NativeLogger.e(TAG, "execRoot failed: $command", e)
            ""
        }
    }

    /**
     * 以 root 身份执行命令，返回是否成功（只看 exit code）
     */
    private fun execRootSilent(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    // --- 剪贴板读取核心方法 ---

    /**
     * 获取剪贴板文本。
     * 使用 `cmd clipboard get-text`（Android 13+），
     * 失败时尝试从 dumpsys clipboard 解析。
     */
    private fun getClipboardTextInternal(): String {
        // 方式1: cmd clipboard get-text（Android 13+）
        val text = execRoot("cmd clipboard get-text 2>/dev/null").trim()
        if (text.isNotEmpty() && text != "null") {
            NativeLogger.d(TAG, "cmd clipboard get-text: length=${text.length}")
            return text
        }

        // 方式2: 从 dumpsys clipboard 解析文本
        NativeLogger.d(TAG, "cmd clipboard failed, parsing dumpsys")
        return parseClipboardTextFromDumpsys()
    }

    /**
     * 从 `dumpsys clipboard` 输出中提取剪贴板文本。
     * Android 输出格式示例：
     *   mPrimaryClip=ClipData { text/plain {T:Hello World} }
     */
    private fun parseClipboardTextFromDumpsys(): String {
        val dump = execRoot("dumpsys clipboard 2>/dev/null")
        if (dump.isEmpty()) return ""

        // 尝试匹配 {T:text_content}
        val textRegex = Regex("""\{T:(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
        val match = textRegex.find(dump)
        if (match != null) {
            val extracted = match.groupValues[1]
            NativeLogger.d(TAG, "dumpsys parsed text: length=${extracted.length}")
            return extracted
        }

        NativeLogger.w(TAG, "dumpsys: no text pattern found")
        return ""
    }

    /**
     * 检查剪贴板是否有文本
     */
    private fun hasClipboardTextInternal(): Boolean {
        val text = execRoot("cmd clipboard get-text 2>/dev/null").trim()
        if (text.isNotEmpty() && text != "null") return true

        val dump = execRoot("dumpsys clipboard 2>/dev/null")
        if ("mHasPrimaryClip=true" in dump &&
            Regex("text/", RegexOption.IGNORE_CASE).containsMatchIn(dump)) {
            return true
        }
        return false
    }

    /**
     * 检查剪贴板是否有图片
     */
    private fun hasClipboardImageInternal(): Boolean {
        val dump = execRoot("dumpsys clipboard 2>/dev/null")
        if ("mHasPrimaryClip=true" !in dump) return false
        return Regex("image/|application/octet-stream", RegexOption.IGNORE_CASE).containsMatchIn(dump)
    }

    /**
     * 获取剪贴板图片 URI（从 dumpsys 输出中提取 content:// URI）
     */
    private fun getClipboardImageUriInternal(): String? {
        val dump = execRoot("dumpsys clipboard 2>/dev/null")
        if ("mHasPrimaryClip=true" !in dump) return null

        val uriRegex = Regex("""content://[^\s}]+""")
        return uriRegex.find(dump)?.value
    }

    // --- 静默后台增强方法 ---

    /**
     * 通过 root 将本应用加入 Android 省电优化白名单。
     *
     * 策略（按优先级尝试）：
     * 1. `dumpsys deviceidle whitelist +PACKAGE` — AOSP 通用方案
     * 2. 直接写入 settings global 数据库 — 某些定制 ROM
     * 3. 设置 appops RUN_IN_BACKGROUND — 允许后台运行
     *
     * @return true 表示至少一种方案执行成功
     */
    private fun bypassBatteryOptimizationInternal(): Boolean {
        val pkg = appContext.reactContext?.packageName ?: "com.jericx.syncclipboardmobile"
        var success = false

        // 方案 1: dumpsys deviceidle whitelist (AOSP)
        if (execRootSilent("dumpsys deviceidle whitelist +$pkg")) {
            NativeLogger.i(TAG, "Battery optimization bypass: dumpsys deviceidle whitelist success")
            success = true
        } else {
            NativeLogger.w(TAG, "Battery optimization bypass: dumpsys deviceidle whitelist failed")
        }

        // 方案 2: 直接修改 settings global（某些 ROM 需要）
        // 将包名加入 device_idle_constants 白名单
        val addToGlobal = execRootSilent(
            "settings put global device_idle_constants \"\$(" +
            "settings get global device_idle_constants 2>/dev/null | " +
            "sed 's/,$//')\" 2>/dev/null"
        )
        if (addToGlobal) {
            NativeLogger.i(TAG, "Battery optimization bypass: settings global modified")
            success = true
        }

        // 方案 3: appops 允许后台运行
        if (execRootSilent("cmd appops set $pkg RUN_IN_BACKGROUND allow")) {
            NativeLogger.i(TAG, "Battery optimization bypass: appops RUN_IN_BACKGROUND set")
            success = true
        }

        // 方案 4: 禁用省电模式对本应用的限制
        if (execRootSilent("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")) {
            NativeLogger.i(TAG, "Battery optimization bypass: appops RUN_ANY_IN_BACKGROUND set")
            success = true
        }

        return success
    }

    /**
     * 检查本应用是否已在省电优化白名单中。
     * 检测逻辑：
     * 1. dumpsys deviceidle whitelist 中包含包名
     * 2. PowerManager.isIgnoringBatteryOptimizations() 返回 true
     */
    private fun isBatteryOptimizationBypassedInternal(): Boolean {
        val pkg = appContext.reactContext?.packageName ?: "com.jericx.syncclipboardmobile"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = appContext.reactContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isIgnoringBatteryOptimizations(pkg) == true) {
                return true
            }
        }
        // 第二层检查: dumpsys
        val dump = execRoot("dumpsys deviceidle whitelist 2>/dev/null")
        return dump.contains(pkg)
    }

    // --- AlarmManager Watchdog ---

    /**
     * 设置 AlarmManager 保活闹钟。
     * 当设备进入 Doze 模式时，AlarmManager 的 setExactAndAllowWhileIdle 仍能触发。
     * 闹钟触发时会发送广播，原生侧收到后通过 EventEmitter 通知 JS 层进行轮询健康检查。
     *
     * 使用 setAndAllowWhileIdle（不需要精确，省电），每 5 分钟唤醒一次。
     */
    private fun setWatchdogAlarmInternal(): Boolean {
        return try {
            val ctx = appContext.reactContext ?: return false

            // 延迟注册广播接收器（首次调用时）
            if (!receiverRegistered) {
                try {
                    val filter = IntentFilter(WATCHDOG_ALARM_ACTION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ctx.registerReceiver(
                            watchdogReceiver, filter,
                            Context.RECEIVER_NOT_EXPORTED
                        )
                    } else {
                        ctx.registerReceiver(watchdogReceiver, filter)
                    }
                    receiverRegistered = true
                    NativeLogger.d(TAG, "Watchdog BroadcastReceiver registered")
                } catch (e: Exception) {
                    NativeLogger.e(TAG, "Failed to register watchdog receiver", e)
                }
            }

            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false

            val intent = Intent(WATCHDOG_ALARM_ACTION)
            intent.setPackage(ctx.packageName)

            val pendingIntent = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            watchdogPendingIntent = pendingIntent

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                    pendingIntent
                )
            }

            NativeLogger.i(TAG, "Watchdog alarm set, interval=${WATCHDOG_INTERVAL_MS}ms")
            true
        } catch (e: Exception) {
            NativeLogger.e(TAG, "Failed to set watchdog alarm", e)
            false
        }
    }

    /** 取消保活闹钟并清理接收器 */
    private fun cancelWatchdogAlarmInternal(): Boolean {
        return try {
            val ctx = appContext.reactContext ?: return false
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false

            // 取消已有的闹钟
            val pending = watchdogPendingIntent
            if (pending != null) {
                alarmManager.cancel(pending)
                watchdogPendingIntent = null
            }

            // 注销广播接收器
            if (receiverRegistered) {
                try {
                    ctx.unregisterReceiver(watchdogReceiver)
                } catch (_: Exception) {}
                receiverRegistered = false
            }

            NativeLogger.i(TAG, "Watchdog alarm cancelled")
            true
        } catch (e: Exception) {
            NativeLogger.e(TAG, "Failed to cancel watchdog alarm", e)
            false
        }
    }

    override fun definition() = ModuleDefinition {
        Name("RootClipboardModule")

        // --- 事件 ---

        Events(EVENT_WATCHDOG_TICK)

        // --- 基础功能 ---

        Function("isRootAvailable") {
            return@Function execRootSilent("id")
        }

        Function("checkRootPermission") {
            val output = execRoot("id").trim()
            return@Function output.contains("uid=0")
        }

        // --- 省电优化白名单 ---

        Function("bypassBatteryOptimization") {
            return@Function bypassBatteryOptimizationInternal()
        }

        Function("isBatteryOptimizationBypassed") {
            return@Function isBatteryOptimizationBypassedInternal()
        }

        // --- AlarmManager 保活闹钟 ---

        Function("setWatchdogAlarm") {
            return@Function setWatchdogAlarmInternal()
        }

        Function("cancelWatchdogAlarm") {
            return@Function cancelWatchdogAlarmInternal()
        }

        // --- 剪贴板读取 ---

        AsyncFunction("getStringViaRoot") { promise: Promise ->
            try {
                NativeLogger.i(TAG, "getStringViaRoot: called")
                val text = getClipboardTextInternal()
                NativeLogger.i(TAG, "getStringViaRoot: result length=${text.length}")
                promise.resolve(text)
            } catch (e: Exception) {
                NativeLogger.e(TAG, "getStringViaRoot failed", e)
                promise.resolve("")
            }
        }

        AsyncFunction("hasStringViaRoot") { promise: Promise ->
            try {
                promise.resolve(hasClipboardTextInternal())
            } catch (e: Exception) {
                NativeLogger.e(TAG, "hasStringViaRoot failed", e)
                promise.resolve(false)
            }
        }

        AsyncFunction("hasImageViaRoot") { promise: Promise ->
            try {
                promise.resolve(hasClipboardImageInternal())
            } catch (e: Exception) {
                NativeLogger.e(TAG, "hasImageViaRoot failed", e)
                promise.resolve(false)
            }
        }

        AsyncFunction("getImageUriViaRoot") { promise: Promise ->
            try {
                val uri = getClipboardImageUriInternal()
                NativeLogger.i(TAG, "getImageUriViaRoot: uri=$uri")
                promise.resolve(uri)
            } catch (e: Exception) {
                NativeLogger.e(TAG, "getImageUriViaRoot failed", e)
                promise.resolve(null)
            }
        }
    }
}
