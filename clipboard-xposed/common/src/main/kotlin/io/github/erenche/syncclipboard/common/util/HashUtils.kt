package io.github.erenche.syncclipboard.common.util

import java.security.MessageDigest

/**
 * 哈希工具 — SHA-256 计算（端口自 src/utils/hash.ts）
 */
object HashUtils {

    /**
     * 计算文本的 SHA-256 哈希
     */
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算字节数组的 SHA-256 哈希
     */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算剪贴板内容的 profileHash（遵循服务器规则）
     * 规则：先计算内容的 SHA-256，然后拼上类型前缀
     */
    fun computeProfileHash(type: String, content: String): String {
        val contentHash = sha256(content)
        return "$type-$contentHash"
    }

    /**
     * 计算剪贴板内容的本地 hash（用于本地变化检测）
     * 对文本内容直接 sha256
     */
    fun computeLocalHash(text: String): String {
        return sha256(text)
    }
}
