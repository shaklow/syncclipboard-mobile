package io.github.erenche.syncclipboard.xposed.sync

import android.content.Context
import android.os.Bundle
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.DEFAULT_APP_CONFIG
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.api.ClientFactory
import io.github.erenche.syncclipboard.xposed.api.SyncClipboardApi
import io.github.erenche.syncclipboard.xposed.history.HistoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SyncEngine — 同步引擎核心。
 *
 * 在 system_server 进程中运行，由 GeneralHooker 初始化。
 * 负责监听剪贴板变化（来自 ClipboardServiceHooker）、上传/下载、历史记录、IPC 路由。
 *
 * 去重策略：
 * - onLocalClipboardChanged 的哈希检查在调用线程同步执行，防止竞态
 * - system_server 中不注册 OnPrimaryClipChangedListener / 不轮询本地剪贴板
 *   仅依赖 ClipboardServiceHooker 提供的事件
 */
class SyncEngine private constructor() {

    companion object {
        private const val TAG = "SyncEngine"

        @Volatile
        private var instance: SyncEngine? = null

        fun getInstance(): SyncEngine = instance ?: synchronized(this) {
            instance ?: SyncEngine().also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var config: AppConfig = DEFAULT_APP_CONFIG
    private var apiClient: SyncClipboardApi? = null
    private var appContext: Context? = null
    private var historyService: HistoryService? = null

    private var processName: String = "unknown"

    /** 本地哈希去重 — 同步检查防止竞态 */
    @Volatile
    private var lastLocalHash: String? = null
    @Volatile
    private var lastRemoteHash: String? = null

    @Volatile
    private var isRunning = false

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var lastSyncTime: Long = 0
        private set

    fun initialize(context: Context) {
        if (appContext != null) {
            Logger.info(TAG, "Already initialized, skipping")
            return
        }
        appContext = context.applicationContext
        processName = getProcessName(context)
        Logger.info(TAG, "initialize() process=$processName")

        historyService = HistoryService(context)
        config = Prefs.loadConfig(context)
        // 应用日志开关
        Logger.enabled = config.enableLogging
        Logger.logLevel = config.logLevel
        rebuildApiClient()
        setupBridgeRouting(context)
        start()

        Logger.info(TAG, "SyncEngine initialized, servers=${config.servers.size}, activeIdx=${config.activeServerIndex}")
    }

    private fun getProcessName(context: Context): String {
        return try {
            val pid = android.os.Process.myPid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        // 唯一剪贴板变化源：OnPrimaryClipChangedListener（系统级，捕获全局变化）
        // 不使用 ClipboardHooker / 本地轮询，避免多路径竞态导致重复上传
        registerClipListener()

        // 远程轮询 — 定期从服务器拉取新内容
        scope.launch {
            while (isActive && isRunning) {
                try {
                    fetchRemoteClipboard()
                } catch (e: Exception) {
                    Logger.error(TAG, "Remote fetch error", e)
                }
                delay(config.remotePollingInterval)
            }
        }

        Logger.info(TAG, "SyncEngine started, process=$processName")
    }

    private fun registerClipListener() {
        try {
            val context = appContext ?: return
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
            cm.addPrimaryClipChangedListener(clipChangedListener)
            Logger.info(TAG, "OnPrimaryClipChangedListener registered")
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to register clip listener: ${e.message}")
        }
    }

    private val clipChangedListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        val ctx = appContext ?: return@OnPrimaryClipChangedListener
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return@OnPrimaryClipChangedListener
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        val content = extractFromClip(ctx, clip) ?: return@OnPrimaryClipChangedListener
        onLocalClipboardChanged(content)
    }

    fun stop() {
        isRunning = false
        isConnected = false
        try {
            val cm = appContext?.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cm?.removePrimaryClipChangedListener(clipChangedListener)
        } catch (_: Exception) {}
        Logger.info(TAG, "SyncEngine stopped")
    }

    /**
     * 从 ClipData 提取统一内容 — 优先处理图片/文件（URI），再处理文本。
     *
     * 关键：必须优先检查 item.uri，因为图片剪贴板中 item.text 可能返回文件名而非 null，
     * 导致图片被误当作文本处理。
     */
    private fun extractFromClip(
        context: Context,
        clip: android.content.ClipData
    ): ClipboardContent? {
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)

        // 优先处理 URI（图片/文件）
        val uri = item.uri
        if (uri != null) {
            val isImage = isImageUri(context, clip.description, uri)
            val type = if (isImage) ClipboardContentType.Image else ClipboardContentType.File
            val fileName = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
            val fileSize = queryFileSize(context, uri)

            Logger.debug(TAG, "Extracted URI content: type=$type, name=$fileName, size=$fileSize")

            return ClipboardContent(
                type = type,
                text = "",
                fileUri = uri.toString(),
                fileName = fileName,
                fileSize = fileSize,
                hasData = true,
                timestamp = System.currentTimeMillis()
            )
        }

        // 文本类型
        val text = item.text?.toString()
            ?: item.htmlText?.toString()
            ?: return null

        return ClipboardContent(
            type = ClipboardContentType.Text,
            text = text,
            hasData = false,
            timestamp = System.currentTimeMillis()
        )
    }

    /** 判断 URI 是否为图片 */
    private fun isImageUri(
        context: Context,
        desc: android.content.ClipDescription,
        uri: android.net.Uri
    ): Boolean {
        // 1. 通过 ClipDescription 的 MIME 类型判断
        if (desc.hasMimeType("image/*")) return true
        // 2. 通过 ContentResolver 查询实际 MIME 类型
        try {
            val mime = context.contentResolver.getType(uri)
            if (mime != null && mime.startsWith("image/")) return true
        } catch (_: Exception) {}
        // 3. 通过文件扩展名判断
        val path = uri.lastPathSegment ?: return false
        val lower = path.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    /** 查询 URI 指向文件的大小（字节） */
    private fun queryFileSize(context: Context, uri: android.net.Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (_: Exception) { null }
    }

    /**
     * 当检测到本地剪贴板变化时由 ClipboardServiceHooker / ClipboardHooker / Listener 调用。
     *
     * 哈希去重在调用线程同步执行，防止多个调用者竞态导致重复上传。
     *
     * @param force 是否强制处理（跳过去重），用于手动上传
     */
    fun onLocalClipboardChanged(content: ClipboardContent, force: Boolean = false) {
        // 仅在已初始化的进程（SystemUI）中处理，App 进程的未初始化实例直接跳过
        if (appContext == null) return

        val hash = content.profileHash
            ?: HashUtils.computeLocalHash(content.text)

        if (!force && hash == lastLocalHash) return
        lastLocalHash = hash

        scope.launch {
            try {
                Logger.debug(TAG, "Local clipboard changed: ${content.text.take(50)}...")
                historyService?.addLocalContent(content)
                if (config.enableBackgroundUpload) {
                    uploadContent(content)
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Error handling local clipboard change", e)
            }
        }
    }

    fun onConfigChanged(newConfig: AppConfig) {
        config = newConfig
        rebuildApiClient()
        // 同步日志开关到 Logger
        Logger.enabled = newConfig.enableLogging
        Logger.logLevel = newConfig.logLevel
        Logger.info(TAG, "Config changed, client rebuilt, logging=${newConfig.enableLogging}")
    }

    fun forceSync() {
        scope.launch {
            try {
                fetchRemoteClipboard()
            } catch (e: Exception) {
                Logger.error(TAG, "Force sync failed", e)
            }
        }
    }

    fun forceUpload() {
        scope.launch {
            try {
                val context = appContext ?: return@launch
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return@launch
                val clipData = cm.primaryClip ?: return@launch
                val content = extractFromClip(context, clipData) ?: return@launch
                onLocalClipboardChanged(content, force = true)
            } catch (e: Exception) {
                Logger.error(TAG, "Force upload failed", e)
            }
        }
    }

    // ─── 私有方法 ────────────────────────────────────────────────

    private suspend fun fetchRemoteClipboard() {
        val client = apiClient ?: run {
            Logger.warn(TAG, "fetchRemoteClipboard: apiClient is null")
            return
        }

        try {
            val profile = client.getClipboard()
            if (profile == null) {
                Logger.warn(TAG, "fetchRemoteClipboard: getClipboard returned null")
                return
            }
            Logger.info(TAG, "fetchRemoteClipboard: type=${profile.type}, hash=${profile.hash}, text=${profile.text.take(50)}, hasData=${profile.hasData}")
            val hash = profile.hash ?: run {
                Logger.warn(TAG, "fetchRemoteClipboard: profile.hash is null, skipping")
                return
            }

            if (hash == lastRemoteHash) return
            lastRemoteHash = hash

            isConnected = true
            lastSyncTime = System.currentTimeMillis()

            Logger.info(TAG, "Remote clipboard changed: ${profile.text.take(50)}...")

            if (config.enableBackgroundDownload) {
                downloadAndApplyContent(profile)
            }
        } catch (e: Exception) {
            isConnected = false
            Logger.warn(TAG, "Remote fetch error", e)
        }
    }

    private suspend fun uploadContent(content: ClipboardContent) {
        val client = apiClient ?: run {
            Logger.warn(TAG, "No API client configured, skipping upload")
            return
        }

        try {
            // 如果 fileUri 是 content:// URI，先复制到临时文件（putFile 需要文件路径）
            val fileUri = content.fileUri
            val uploadContent = if (content.hasData && fileUri != null &&
                fileUri.startsWith("content://")) {
                val context = appContext ?: return
                val tempFile = copyUriToTempFile(context, fileUri, content.fileName)
                if (tempFile != null) {
                    content.copy(fileUri = tempFile.absolutePath)
                } else {
                    Logger.warn(TAG, "Failed to copy URI to temp file, uploading as text")
                    content.copy(hasData = false)
                }
            } else {
                content
            }

            client.putContent(uploadContent)
            lastSyncTime = System.currentTimeMillis()
            // 设置 lastRemoteHash，防止轮询循环立即下载刚上传的内容
            val profileHash = HashUtils.computeProfileHash(
                content.type.name.lowercase(),
                content.text
            )
            lastRemoteHash = profileHash
            Logger.info(TAG, "Content uploaded successfully")
        } catch (e: Exception) {
            Logger.error(TAG, "Upload failed", e)
        }
    }

    /** 将 content:// URI 复制到临时文件，返回临时 File */
    private fun copyUriToTempFile(
        context: Context,
        uriString: String,
        fileName: String?
    ): java.io.File? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            val name = fileName ?: "temp_${System.currentTimeMillis()}"
            val tempFile = java.io.File(context.cacheDir, "upload_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Logger.debug(TAG, "Copied URI to temp file: $uriString -> ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to copy URI to temp file: $uriString", e)
            null
        }
    }

    private suspend fun downloadAndApplyContent(profile: ProfileDto) {
        val client = apiClient ?: return
        val context = appContext ?: return

        try {
            var downloadedFileUri: android.net.Uri? = null
            var downloadedFilePath: String? = null
            if (profile.hasData && profile.dataName != null) {
                val name = profile.dataName!!
                val destPath = "${context.filesDir}/downloads/$name"
                client.downloadFile(name, destPath)
                // 设置文件可读，使其他应用能通过 file:// URI 访问
                val destFile = java.io.File(destPath)
                destFile.setReadable(true, false)
                downloadedFileUri = android.net.Uri.fromFile(destFile)
                downloadedFilePath = destPath
                Logger.info(TAG, "File downloaded: $name -> $destPath")
            }

            // 写入剪贴板前设置 lastLocalHash，防止 listener 二次上传
            lastLocalHash = HashUtils.computeLocalHash(profile.text)

            // 图片/文件类型不写入剪贴板（避免输入法只读取到文件名），
            // 用户可在主界面查看/下载。仅文本类型写入剪贴板。
            if (profile.type == ClipboardContentType.Text && downloadedFileUri == null) {
                writeToClipboard(profile.text)
            }

            // 自动保存：若开启且为图片/文件类型，则保存到相册或下载目录
            if (config.enableAutoSave && downloadedFilePath != null &&
                (profile.type == ClipboardContentType.Image || profile.type == ClipboardContentType.File)) {
                try {
                    autoSaveToFile(context, downloadedFilePath!!, profile.type, profile.dataName)
                } catch (e: Exception) {
                    Logger.warn(TAG, "Auto save failed: ${e.message}")
                }
            }

            // 记录到历史（syncStatus = Synced，参考原项目 addRemoteContent）
            val historyContent = ClipboardContent(
                type = profile.type,
                text = profile.text,
                fileUri = downloadedFilePath,
                fileName = profile.dataName,
                fileSize = profile.size,
                hasData = profile.hasData,
                timestamp = System.currentTimeMillis()
            )
            historyService?.addRemoteContent(historyContent, downloadedFilePath)

            lastSyncTime = System.currentTimeMillis()
            Logger.info(TAG, "Remote content applied to local clipboard")
        } catch (e: Exception) {
            Logger.error(TAG, "Download and apply failed", e)
        }
    }

    /** 根据文件名猜测 MIME 类型 */
    private fun guessMimeFromName(name: String?): String {
        if (name == null) return "application/octet-stream"
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".bmp") -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    /** 自动保存文件到相册或下载目录 */
    private fun autoSaveToFile(
        context: Context,
        filePath: String,
        type: ClipboardContentType,
        fileName: String?
    ) {
        val srcFile = java.io.File(filePath)
        if (!srcFile.exists()) return
        val resolver = context.contentResolver
        val name = fileName ?: srcFile.name
        val mime = guessMimeFromName(fileName)

        if (type == ClipboardContentType.Image) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                }
                Logger.info(TAG, "Auto saved image to gallery: $name")
            }
        } else {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                }
                Logger.info(TAG, "Auto saved file to downloads: $name")
            }
        }
    }

    /** 写入文本到剪贴板 */
    private fun writeToClipboard(text: String) {
        try {
            val context = appContext ?: return
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return

            val clipData = android.content.ClipData.newPlainText("SyncClipboard", text)
            clipboardManager.setPrimaryClip(clipData)

            Logger.debug(TAG, "Written to clipboard: ${text.take(50)}...")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to write to clipboard", e)
        }
    }

    /** 写入 URI（图片/文件）到剪贴板 */
    private fun writeToClipboardUri(uri: android.net.Uri, label: String, mime: String) {
        try {
            val context = appContext ?: return
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return

            val clipData = android.content.ClipData.newUri(
                context.contentResolver,
                "SyncClipboard",
                uri
            )
            clipboardManager.setPrimaryClip(clipData)

            Logger.info(TAG, "Written URI to clipboard: $uri, mime=$mime, label=$label")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to write URI to clipboard, falling back to text", e)
            writeToClipboard(label)
        }
    }

    private fun rebuildApiClient() {
        val server = config.servers.getOrNull(config.activeServerIndex)
        apiClient = if (server != null) {
            try {
                ClientFactory.createClient(server)
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to create API client: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    private fun setupBridgeRouting(context: Context) {
        SyncClipboardBridge.routing(context) {
            onQuery(BridgeKeys.GET_CONFIG) {
                val configJson = Json.encodeToString(AppConfig.serializer(), config)
                reply(Bundle().apply { putString("config", configJson) })
            }

            onCommand(BridgeKeys.PUSH_CONFIG) { data ->
                val configJson = data.getString("config") ?: return@onCommand
                try {
                    val newConfig = Json.decodeFromString(AppConfig.serializer(), configJson)
                    onConfigChanged(newConfig)
                    Prefs.saveConfig(context, newConfig)
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to parse config", e)
                }
            }

            onQuery(BridgeKeys.GET_SYNC_STATUS) {
                reply(Bundle().apply {
                    putBoolean("connected", isConnected)
                    putBoolean("running", isRunning)
                    putLong("lastSyncTime", lastSyncTime)
                })
            }

            onCommand(BridgeKeys.TRIGGER_SYNC) {
                forceSync()
            }

            onCommand(BridgeKeys.UPLOAD_NOW) {
                forceUpload()
            }

            onQuery(BridgeKeys.GET_HISTORY) {
                val items = historyService?.getPaged(50, 0) ?: emptyList()
                Logger.info(TAG, "GET_HISTORY: historyService=${if (historyService != null) "exists" else "null"}, items=${items.size}")
                val itemsJson = Json.encodeToString(
                    ListSerializer(HistoryItem.serializer()), items
                )
                reply(Bundle().apply { putString("items", itemsJson) })
            }

            onCommand(BridgeKeys.DELETE_HISTORY_ITEM) { data ->
                val id = data.getString("id") ?: return@onCommand
                historyService?.delete(id)
                Logger.info(TAG, "History item deleted: $id")
            }

            onCommand(BridgeKeys.CLEAR_HISTORY) {
                historyService?.clearAll()
                Logger.info(TAG, "History cleared")
            }

            onQuery(BridgeKeys.TEST_CONNECTION) {
                val serverJson: String = data.getString("server") ?: run {
                    reply(Bundle().apply {
                        putBoolean("success", false)
                        putString("error", "No server configuration provided")
                    })
                    return@onQuery
                }
                try {
                    val serverConfig = Json { ignoreUnknownKeys = true }
                        .decodeFromString(ServerConfig.serializer(), serverJson)
                    val client = ClientFactory.createClient(serverConfig)
                    client.testConnection()
                    reply(Bundle().apply { putBoolean("success", true) })
                } catch (e: Exception) {
                    Logger.error(TAG, "Test connection failed", e)
                    reply(Bundle().apply {
                        putBoolean("success", false)
                        putString("error", e.message ?: "Connection failed")
                    })
                }
            }

            onQuery(BridgeKeys.GET_LOGS) {
                val logs = Logger.getLogs()
                Logger.info(TAG, "GET_LOGS: returning ${logs.length} chars")
                reply(Bundle().apply { putString("logs", logs) })
            }

            onCommand(BridgeKeys.CLEAR_LOGS) {
                Logger.clear()
            }
        }
    }
}
