package expo.modules.rootclipboard

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
 */
class RootClipboardModule : Module() {

    companion object {
        private const val TAG = "RootClipboard"
    }

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

    // --- Module Definition ---

    override fun definition() = ModuleDefinition {
        Name("RootClipboardModule")

        Function("isRootAvailable") {
            return@Function execRootSilent("id")
        }

        Function("checkRootPermission") {
            val output = execRoot("id").trim()
            return@Function output.contains("uid=0")
        }

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
