package io.github.erenche.syncclipboard.common.util

import android.util.Log

/**
 * 应用日志工具 — 封装 android.util.Log，支持日志等级过滤
 */
object Logger {

    private const val TAG = "SyncClipboard"

    @Volatile
    var logLevel: io.github.erenche.syncclipboard.common.model.LogLevel =
        io.github.erenche.syncclipboard.common.model.LogLevel.Info

    fun debug(tag: String, message: String) {
        if (logLevel.ordinal <= io.github.erenche.syncclipboard.common.model.LogLevel.Debug.ordinal) {
            Log.d(TAG, "[$tag] $message")
        }
    }

    fun info(tag: String, message: String) {
        if (logLevel.ordinal <= io.github.erenche.syncclipboard.common.model.LogLevel.Info.ordinal) {
            Log.i(TAG, "[$tag] $message")
        }
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (logLevel.ordinal <= io.github.erenche.syncclipboard.common.model.LogLevel.Warn.ordinal) {
            if (throwable != null) {
                Log.w(TAG, "[$tag] $message", throwable)
            } else {
                Log.w(TAG, "[$tag] $message")
            }
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (logLevel.ordinal <= io.github.erenche.syncclipboard.common.model.LogLevel.Error.ordinal) {
            if (throwable != null) {
                Log.e(TAG, "[$tag] $message", throwable)
            } else {
                Log.e(TAG, "[$tag] $message")
            }
        }
    }
}
