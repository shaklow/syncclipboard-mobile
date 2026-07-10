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
 * 端口自 TypeScript ClipboardSyncService.ts。
 * 在 LSPosed 模块中运行，负责：
 * - 监听本地剪贴板变化（来自 ClipboardHooker）
 * - 定期拉取远程剪贴板变化（HTTP 轮询或 SignalR）
 * - 哈希去重、自动上传/下载
 * - 历史记录跟踪
 * - IPC 通信（通过 Bridge）
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

    // 哈希去重
    private var lastLocalHash: String? = null
    private var lastRemoteHash: String? = null

    // 同步状态
    @Volatile
    private var isRunning = false

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var lastSyncTime: Long = 0
        private set

    /**
     * 初始化同步引擎。
     * 必须在 Application.onCreate() 之后调用。
     */
    fun initialize(context: Context) {
        val processName = getProcessName(context)
        android.util.Log.w("SyncClipboard", "[SyncEngine] initialize() called, process=$processName, appContext=$appContext")

        if (appContext != null) {
            android.util.Log.w("SyncClipboard", "[SyncEngine] Already initialized, skipping")
            return
        }
        appContext = context.applicationContext
        android.util.Log.w("SyncClipboard", "[SyncEngine] appContext set, package=${appContext?.packageName}")

        // 初始化历史记录服务
        historyService = HistoryService(context)
        android.util.Log.w("SyncClipboard", "[SyncEngine] HistoryService created")

        // 加载配置
        config = Prefs.loadConfig(context)
        android.util.Log.w("SyncClipboard", "[SyncEngine] Config loaded, servers=${config.servers.size}, activeIdx=${config.activeServerIndex}")

        // 构建 API 客户端
        rebuildApiClient()
        android.util.Log.w("SyncClipboard", "[SyncEngine] API client rebuilt, client=${apiClient != null}")

        // 设置 IPC 路由
        setupBridgeRouting(context)

        // 启动同步循环
        start()
        android.util.Log.w("SyncClipboard", "[SyncEngine] start() called, isRunning=$isRunning")

        Logger.info(TAG, "SyncEngine initialized")
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

    /**
     * 启动同步循环。
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        // 启动远程轮询（用于 WebDAV/S3 或没有 SignalR 的 SyncClipboard 服务器）
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

        Logger.info(TAG, "SyncEngine started, polling interval: ${config.remotePollingInterval}ms")
    }

    /**
     * 停止同步循环。
     */
    fun stop() {
        isRunning = false
        isConnected = false
        Logger.info(TAG, "SyncEngine stopped")
    }

    /**
     * 当检测到本地剪贴板变化时由 ClipboardHooker 调用。
     */
    fun onLocalClipboardChanged(content: ClipboardContent) {
        scope.launch {
            try {
                // 计算哈希
                val hash = content.profileHash
                    ?: HashUtils.computeLocalHash(content.text)

                // 去重：与上次本地哈希相同则跳过
                if (hash == lastLocalHash) return@launch
                lastLocalHash = hash

                Logger.debug(TAG, "Local clipboard changed: ${content.text.take(50)}...")

                // 添加到历史记录
                historyService?.addOrUpdate(content)

                // 自动上传
                if (config.enableBackgroundUpload) {
                    uploadContent(content)
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Error handling local clipboard change", e)
            }
        }
    }

    /**
     * 配置变更时重新加载。
     */
    fun onConfigChanged(newConfig: AppConfig) {
        config = newConfig
        rebuildApiClient()
        Logger.info(TAG, "Config changed, client rebuilt")
    }

    /**
     * 手动触发同步。
     */
    fun forceSync() {
        scope.launch {
            try {
                fetchRemoteClipboard()
            } catch (e: Exception) {
                Logger.error(TAG, "Force sync failed", e)
            }
        }
    }

    /**
     * 手动触发上传 — 读取当前剪贴板内容并上传。
     */
    fun forceUpload() {
        scope.launch {
            try {
                val context = appContext ?: return@launch
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return@launch
                val clipData = cm.primaryClip ?: return@launch
                if (clipData.itemCount == 0) return@launch
                val item = clipData.getItemAt(0)
                val text = item.text?.toString()
                    ?: item.htmlText?.toString()
                    ?: item.uri?.toString()
                    ?: return@launch
                val content = ClipboardContent(
                    type = if (item.uri != null) ClipboardContentType.File else ClipboardContentType.Text,
                    text = text,
                    fileUri = item.uri?.toString(),
                    hasData = item.uri != null,
                    timestamp = System.currentTimeMillis()
                )
                onLocalClipboardChanged(content)
            } catch (e: Exception) {
                Logger.error(TAG, "Force upload failed", e)
            }
        }
    }

    // ─── 私有方法 ────────────────────────────────────────────────

    /**
     * 拉取远程剪贴板内容。
     */
    private suspend fun fetchRemoteClipboard() {
        val client = apiClient ?: return

        try {
            val profile = client.getClipboard() ?: return
            val hash = profile.hash ?: return

            // 去重
            if (hash == lastRemoteHash) return
            lastRemoteHash = hash

            isConnected = true
            lastSyncTime = System.currentTimeMillis()

            Logger.info(TAG, "Remote clipboard changed: ${profile.text.take(50)}...")

            // 自动下载
            if (config.enableBackgroundDownload) {
                downloadAndApplyContent(profile)
            }
        } catch (e: Exception) {
            isConnected = false
            Logger.warn(TAG, "Remote fetch error", e)
        }
    }

    /**
     * 上传剪贴板内容到服务器。
     */
    private suspend fun uploadContent(content: ClipboardContent) {
        val client = apiClient ?: return

        try {
            client.putContent(content)
            lastSyncTime = System.currentTimeMillis()
            Logger.info(TAG, "Content uploaded successfully")
        } catch (e: Exception) {
            Logger.error(TAG, "Upload failed", e)
        }
    }

    /**
     * 下载远程内容并写入本地剪贴板。
     */
    private suspend fun downloadAndApplyContent(profile: ProfileDto) {
        val client = apiClient ?: return
        val context = appContext ?: return

        try {
            // 如果有数据文件，先下载
            if (profile.hasData && profile.dataName != null) {
                val name = profile.dataName!!
                val destPath = "${context.filesDir}/downloads/$name"
                client.downloadFile(name, destPath)
                Logger.info(TAG, "File downloaded: $name")
            }

            // 写入本地剪贴板
            writeToClipboard(profile.text)

            lastSyncTime = System.currentTimeMillis()
            Logger.info(TAG, "Remote content applied to local clipboard")
        } catch (e: Exception) {
            Logger.error(TAG, "Download and apply failed", e)
        }
    }

    /**
     * 写入内容到本地剪贴板。
     * 在 system_server 进程中可以直接调用 ClipboardService 内部方法。
     */
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

    /**
     * 重建 API 客户端（配置变化时调用）。
     */
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

    /**
     * 设置 IPC 桥接路由 — 处理来自 app 进程的查询和指令。
     */
    private fun setupBridgeRouting(context: Context) {
        android.util.Log.w("SyncClipboard", "[SyncEngine] setupBridgeRouting starting...")
        SyncClipboardBridge.routing(context) {
            android.util.Log.w("SyncClipboard", "[SyncEngine] Bridge routing block executing, registering handlers...")
            // 配置查询
            onQuery(BridgeKeys.GET_CONFIG) {
                val configJson = kotlinx.serialization.json.Json.encodeToString(
                    AppConfig.serializer(), config
                )
                val bundle = android.os.Bundle().apply {
                    putString("config", configJson)
                }
                reply(bundle)
            }

            // 配置更新
            onCommand(BridgeKeys.PUSH_CONFIG) { data ->
                val configJson = data.getString("config") ?: return@onCommand
                try {
                    val newConfig = kotlinx.serialization.json.Json.decodeFromString(
                        AppConfig.serializer(), configJson
                    )
                    onConfigChanged(newConfig)
                    Prefs.saveConfig(context, newConfig)
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to parse config", e)
                }
            }

            // 同步状态查询
            onQuery(BridgeKeys.GET_SYNC_STATUS) {
                android.util.Log.w("SyncClipboard", "[SyncEngine] GET_SYNC_STATUS handler invoked, isRunning=$isRunning, isConnected=$isConnected")
                val bundle = android.os.Bundle().apply {
                    putBoolean("connected", isConnected)
                    putBoolean("running", isRunning)
                    putLong("lastSyncTime", lastSyncTime)
                }
                reply(bundle)
            }

            // 触发立即同步
            onCommand(BridgeKeys.TRIGGER_SYNC) {
                android.util.Log.w("SyncClipboard", "[SyncEngine] TRIGGER_SYNC handler invoked")
                forceSync()
            }

            // 历史记录查询
            onQuery(BridgeKeys.GET_HISTORY) {
                val items = historyService?.getPaged(50, 0) ?: emptyList()
                val itemsJson = Json.encodeToString(
                    ListSerializer(HistoryItem.serializer()), items
                )
                reply(Bundle().apply {
                    putString("items", itemsJson)
                })
            }

            // 删除历史记录
            onCommand(BridgeKeys.DELETE_HISTORY_ITEM) { data ->
                val id = data.getString("id") ?: return@onCommand
                historyService?.delete(id)
                Logger.info(TAG, "History item deleted: $id")
            }

            // 测试服务器连接 — 接收临时 ServerConfig JSON，尝试连接并返回结果
            onQuery(BridgeKeys.TEST_CONNECTION) {
                val serverJson: String = data.getString("server") ?: run {
                    reply(android.os.Bundle().apply {
                        putBoolean("success", false)
                        putString("error", "No server configuration provided")
                    })
                    return@onQuery
                }
                if (serverJson.isBlank()) {
                    reply(android.os.Bundle().apply {
                        putBoolean("success", false)
                        putString("error", "Server configuration is empty")
                    })
                    return@onQuery
                }
                try {
                    val configJson = Json { ignoreUnknownKeys = true }
                    val serverConfig = configJson.decodeFromString(
                        io.github.erenche.syncclipboard.common.model.ServerConfig.serializer(),
                        serverJson
                    )
                    val client = io.github.erenche.syncclipboard.xposed.api.ClientFactory.createClient(serverConfig)
                    client.testConnection()
                    reply(android.os.Bundle().apply { putBoolean("success", true) })
                } catch (e: Exception) {
                    Logger.error(TAG, "Test connection failed", e)
                    reply(android.os.Bundle().apply {
                        putBoolean("success", false)
                        putString("error", e.message ?: "Connection failed")
                    })
                }
            }

            // 触发立即上传 — 读取当前剪贴板并上传
            onCommand(BridgeKeys.UPLOAD_NOW) {
                android.util.Log.w("SyncClipboard", "[SyncEngine] UPLOAD_NOW handler invoked")
                forceUpload()
            }
        }
        android.util.Log.w("SyncClipboard", "[SyncEngine] setupBridgeRouting complete, handlers registered")
    }
}
