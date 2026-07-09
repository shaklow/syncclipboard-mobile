package io.github.erenche.syncclipboard.xposed.api

import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ProfileDto

/**
 * SyncClipboard API 接口 — 对应 TypeScript ISyncClipboardAPI。
 *
 * 所有方法均为 suspend 函数，用于协程环境中进行 HTTP 请求。
 */
interface SyncClipboardApi {

    /** 获取远程剪贴板配置 */
    suspend fun getClipboard(): ProfileDto?

    /** 上传剪贴板配置 */
    suspend fun putClipboard(profile: ProfileDto)

    /** 下载文件到指定路径 */
    suspend fun downloadFile(fileName: String, destinationPath: String, onProgress: ((Float) -> Unit)? = null): String

    /** 上传文件 */
    suspend fun putFile(fileName: String, filePath: String, onProgress: ((Float) -> Unit)? = null)

    /**
     * 上传剪贴板内容（先上传数据文件，再上传配置）
     */
    suspend fun putContent(content: ClipboardContent)

    /** 测试服务器连接 */
    suspend fun testConnection()
}
