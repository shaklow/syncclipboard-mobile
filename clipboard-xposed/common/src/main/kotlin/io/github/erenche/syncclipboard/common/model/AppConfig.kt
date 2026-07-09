package io.github.erenche.syncclipboard.common.model

import kotlinx.serialization.Serializable

/**
 * 应用配置 — 持久化的完整配置（端口自 TypeScript AppConfig）
 */
@Serializable
data class AppConfig(
    /** 服务器配置列表 */
    val servers: List<ServerConfig> = emptyList(),
    /** 当前激活的服务器索引 */
    val activeServerIndex: Int = -1,
    /** 同步间隔（毫秒） */
    val syncInterval: Long = 5000,
    /** 冲突解决策略 */
    val conflictResolution: ConflictResolution = ConflictResolution.Newest,
    /** 是否同步大文件 */
    val syncLargeFiles: Boolean = true,
    /** 大文件阈值（字节） */
    val largeFileThreshold: Long = 10 * 1024 * 1024, // 10MB
    /** 是否在后台时下载远程剪贴板 */
    val enableBackgroundDownload: Boolean = true,
    /** 是否在后台时上传本地剪贴板 */
    val enableBackgroundUpload: Boolean = true,
    /** 是否启用历史记录同步 */
    val enableHistorySync: Boolean = false,
    /** 自动下载最大文件大小（字节），默认 5MB */
    val autoDownloadMaxSize: Long = 5 * 1024 * 1024,
    /** 远程轮询间隔（毫秒），用于 WebDAV/S3 回退 */
    val remotePollingInterval: Long = 3000,
    /** 日志等级 */
    val logLevel: LogLevel = LogLevel.Info,
    /** 历史记录最大保留条数 */
    val maxHistoryItems: Int = 1000
)

/**
 * 冲突解决策略
 */
@Serializable
enum class ConflictResolution {
    /** 以最新为准 */
    Newest,
    /** 以本地为准 */
    Local,
    /** 以远程为准 */
    Remote
}

/**
 * 默认应用配置
 */
val DEFAULT_APP_CONFIG = AppConfig()
