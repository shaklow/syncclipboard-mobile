package io.github.erenche.syncclipboard.common.util

import android.util.Log
import io.github.erenche.syncclipboard.common.model.LogLevel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 应用日志工具 — 封装 android.util.Log，同时保留内存环形缓冲区。
 *
 * 日志开关：
 * - [enabled] = false 时仅输出 Error（保证异常可见）
 * - [enabled] = true 时按 [logLevel] 过滤
 *
 * 内存缓冲区始终记录（包含 Error），供 App 内日志查看页读取。
 */
object Logger {

    private const val TAG = "SyncClipboard"
    private const val MAX_BUFFER = 1000

    /** 日志总开关，false 时仅输出 Error */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var logLevel: LogLevel = LogLevel.Info

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val buffer = ConcurrentLinkedDeque<String>()

    private fun timestamp(): String = dateFormat.format(Date())

    private fun record(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val ts = timestamp()
        val line = if (throwable != null) {
            "$ts $level/[$tag] $message\n${Log.getStackTraceString(throwable).trim()}"
        } else {
            "$ts $level/[$tag] $message"
        }
        buffer.addLast(line)
        while (buffer.size > MAX_BUFFER) buffer.pollFirst()
    }

    fun debug(tag: String, message: String) {
        record("D", tag, message)
        if (enabled && logLevel.ordinal <= LogLevel.Debug.ordinal) {
            Log.d(TAG, "[$tag] $message")
        }
    }

    fun info(tag: String, message: String) {
        record("I", tag, message)
        if (enabled && logLevel.ordinal <= LogLevel.Info.ordinal) {
            Log.i(TAG, "[$tag] $message")
        }
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        record("W", tag, message, throwable)
        if (enabled && logLevel.ordinal <= LogLevel.Warn.ordinal) {
            if (throwable != null) {
                Log.w(TAG, "[$tag] $message", throwable)
            } else {
                Log.w(TAG, "[$tag] $message")
            }
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        record("E", tag, message, throwable)
        if (enabled) {
            if (throwable != null) {
                Log.e(TAG, "[$tag] $message", throwable)
            } else {
                Log.e(TAG, "[$tag] $message")
            }
        }
    }

    /** 获取内存缓冲区中的所有日志（按时间顺序） */
    fun getLogs(): String = buffer.joinToString("\n")

    /** 获取最近 N 条日志 */
    fun getRecentLogs(count: Int): String {
        val all = buffer.toList()
        return all.takeLast(count).joinToString("\n")
    }

    /** 清空日志缓冲区 */
    fun clear() {
        buffer.clear()
    }
}
