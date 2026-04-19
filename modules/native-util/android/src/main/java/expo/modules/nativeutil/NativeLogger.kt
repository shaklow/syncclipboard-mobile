package expo.modules.nativeutil

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 文件日志工具，同时输出到 Logcat 和文件。
 * 日志文件存放在 app filesDir/logs/ 目录下，命名为 kotlin_YYYY-MM-DD.log
 */
object NativeLogger {
    private const val MAX_LOG_DAYS = 3
    private const val LOG_FOLDER = "logs"

    private var logDir: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 初始化 NativeLogger，需在 Application.onCreate 或模块初始化时调用。
     * 多次调用安全，仅首次生效。
     */
    fun init(context: Context) {
        if (logDir != null) return
        logDir = File(context.filesDir, LOG_FOLDER).also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
        cleanOldLogs()
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        writeToFile("DEBUG", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writeToFile("INFO", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        writeToFile("WARN", tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, msg, throwable)
            writeToFile("ERROR", tag, "$msg\n${Log.getStackTraceString(throwable)}")
        } else {
            Log.e(tag, msg)
            writeToFile("ERROR", tag, msg)
        }
    }

    private fun writeToFile(level: String, tag: String, msg: String) {
        val dir = logDir ?: return
        executor.execute {
            try {
                val now = Date()
                val fileName = "kotlin_${dateFormat.format(now)}.log"
                val file = File(dir, fileName)
                val timestamp = timestampFormat.format(now)
                val line = "$timestamp $level [$tag]: $msg\n"
                FileWriter(file, true).use { it.write(line) }
            } catch (e: Exception) {
                Log.e("NativeLogger", "Failed to write log file", e)
            }
        }
    }

    private fun cleanOldLogs() {
        val dir = logDir ?: return
        executor.execute {
            try {
                val cutoff = System.currentTimeMillis() - MAX_LOG_DAYS * 24 * 60 * 60 * 1000L
                dir.listFiles()?.filter { file ->
                    file.name.startsWith("kotlin_") && file.name.endsWith(".log")
                }?.forEach { file ->
                    val dateStr = file.name.removePrefix("kotlin_").removeSuffix(".log")
                    try {
                        val fileDate = dateFormat.parse(dateStr)
                        if (fileDate != null && fileDate.time < cutoff) {
                            file.delete()
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("NativeLogger", "Failed to clean old logs", e)
            }
        }
    }
}
