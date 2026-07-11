package io.github.erenche.syncclipboard.xposed.history

import android.content.Context
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.HistorySyncStatus
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * HistoryService — 剪贴板历史记录服务（参考原项目逻辑）。
 *
 * - 本地剪贴板变化 → [addLocalContent]：syncStatus = LocalOnly，文件复制到持久化历史目录
 * - 从服务器下载 → [addRemoteContent]：syncStatus = Synced，使用已下载的文件路径
 * - 用 profileHash 去重，已存在时更新 syncStatus 和 lastAccessed
 */
class HistoryService(context: Context) {

    companion object {
        private const val TAG = "HistoryService"
        private const val MAX_ITEMS = 1000
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val historyFile = File(context.filesDir, "clipboard_history.json")
    private val historyDir = File(context.filesDir, "history_files").apply { if (!exists()) mkdirs() }
    private val lock = Any()

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: Flow<List<HistoryItem>> = _items.asStateFlow()

    init { loadFromDisk() }

    fun observeAll(): Flow<List<HistoryItem>> = items

    /** 分页查询（置顶优先，按时间倒序），排除软删除 */
    fun getPaged(limit: Int = 50, offset: Int = 0): List<HistoryItem> {
        val result = _items.value.asSequence()
            .filter { !it.isDeleted }
            .sortedWith(compareByDescending<HistoryItem> { if (it.pinned) 1 else 0 }
                .thenByDescending { it.timestamp })
            .drop(offset)
            .take(limit)
            .toList()
        Logger.info(TAG, "getPaged: limit=$limit, offset=$offset, total=${_items.value.size}, active=${_items.value.count { !it.isDeleted }}, returned=${result.size}")
        return result
    }

    fun getById(id: String): HistoryItem? =
        _items.value.find { it.id == id && !it.isDeleted }

    /**
     * 本地剪贴板变化时调用（参考原项目 addLocalContent）。
     * syncStatus = LocalOnly，如有文件复制到持久化历史目录。
     */
    fun addLocalContent(content: ClipboardContent) {
        val hash = content.profileHash ?: HashUtils.computeLocalHash(content.text)
        Logger.info(TAG, "addLocalContent: type=${content.type}, hash=$hash, text=${content.text.take(50)}, hasData=${content.hasData}")
        synchronized(lock) {
            val existing = _items.value.find { it.profileHash == hash && !it.isDeleted }
            if (existing != null) {
                // 已存在，更新 syncStatus 和 lastAccessed
                replaceItem(existing.copy(
                    syncStatus = HistorySyncStatus.LocalOnly,
                    lastAccessed = System.currentTimeMillis()
                ))
            } else {
                // 新增：如有文件，复制到持久化目录
                var fileUri: String? = null
                val srcUri = content.fileUri
                if (content.hasData && srcUri != null) {
                    fileUri = copyToHistoryDir(srcUri, content.fileName, hash)
                }
                addItem(HistoryItem(
                    id = UUID.randomUUID().toString(),
                    type = content.type,
                    text = content.text,
                    profileHash = hash,
                    hasData = content.hasData,
                    dataName = content.fileName,
                    size = content.fileSize,
                    timestamp = content.timestamp,
                    syncStatus = HistorySyncStatus.LocalOnly,
                    fileUri = fileUri
                ))
                trimIfNeeded()
            }
        }
    }

    /**
     * 从服务器下载时调用（参考原项目 addRemoteContent）。
     * syncStatus = Synced，使用已下载的文件路径（downloadPath）。
     */
    fun addRemoteContent(content: ClipboardContent, downloadPath: String? = null) {
        val hash = content.profileHash ?: HashUtils.computeLocalHash(content.text)
        Logger.info(TAG, "addRemoteContent: type=${content.type}, hash=$hash, text=${content.text.take(50)}, hasData=${content.hasData}, downloadPath=$downloadPath")
        synchronized(lock) {
            val existing = _items.value.find { it.profileHash == hash && !it.isDeleted }
            if (existing != null) {
                // 已存在，更新为 Synced（远程同步过来的）
                replaceItem(existing.copy(
                    syncStatus = HistorySyncStatus.Synced,
                    lastAccessed = System.currentTimeMillis(),
                    fileUri = downloadPath ?: existing.fileUri
                ))
            } else {
                addItem(HistoryItem(
                    id = UUID.randomUUID().toString(),
                    type = content.type,
                    text = content.text,
                    profileHash = hash,
                    hasData = content.hasData,
                    dataName = content.fileName,
                    size = content.fileSize,
                    timestamp = content.timestamp,
                    syncStatus = HistorySyncStatus.Synced,
                    fileUri = downloadPath
                ))
                trimIfNeeded()
            }
        }
    }

    fun delete(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        replaceItem(item.copy(isDeleted = true, lastModified = System.currentTimeMillis()))
    }

    /** 清空所有历史记录（软删除） */
    fun clearAll() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _items.value = _items.value.map { it.copy(isDeleted = true, lastModified = now) }
            saveToDisk()
            Logger.info(TAG, "clearAll: marked ${_items.value.size} items as deleted")
        }
    }

    fun toggleStar(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        replaceItem(item.copy(starred = !item.starred))
    }

    fun togglePin(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        replaceItem(item.copy(pinned = !item.pinned))
    }

    fun search(query: String): List<HistoryItem> =
        _items.value.asSequence()
            .filter { !it.isDeleted && it.text.contains(query, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
            .toList()

    fun count(): Int = _items.value.count { !it.isDeleted }

    // ─── 内部方法 ────────────────────────────────────────────────

    /** 复制文件到持久化历史目录，返回新文件路径 */
    private fun copyToHistoryDir(sourceUri: String, fileName: String?, hash: String): String? {
        return try {
            val src = File(sourceUri)
            if (!src.exists()) {
                // sourceUri 可能是 content:// URI，这里无法处理，返回 null
                Logger.warn(TAG, "Source file not exists (may be content:// URI): $sourceUri")
                return null
            }
            val name = fileName ?: "file_$hash"
            val dest = File(historyDir, "${hash}_${name}")
            src.copyTo(dest, overwrite = true)
            dest.setReadable(true, false)
            Logger.debug(TAG, "Copied to history dir: $sourceUri -> ${dest.absolutePath}")
            dest.absolutePath
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to copy to history dir: $sourceUri", e)
            null
        }
    }

    private fun addItem(item: HistoryItem) {
        _items.value = _items.value.toMutableList().apply { add(0, item) }
        saveToDisk()
    }

    private fun replaceItem(item: HistoryItem) {
        val current = _items.value.toMutableList()
        val idx = current.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            current[idx] = item
            _items.value = current
            saveToDisk()
        }
    }

    private fun trimIfNeeded() {
        val active = _items.value.filter { !it.isDeleted }
        if (active.size > MAX_ITEMS) {
            val toRemove = active.sortedBy { it.timestamp }
                .take(active.size - MAX_ITEMS).map { it.id }.toSet()
            _items.value = _items.value.map {
                if (it.id in toRemove) it.copy(isDeleted = true) else it
            }
            saveToDisk()
            Logger.info(TAG, "Trimmed ${toRemove.size} old items")
        }
    }

    private fun saveToDisk() {
        try {
            historyFile.writeText(
                json.encodeToString(ListSerializer(HistoryItem.serializer()), _items.value)
            )
        } catch (e: Exception) { Logger.error(TAG, "Failed to save history", e) }
    }

    private fun loadFromDisk() {
        try {
            if (historyFile.exists()) {
                val text = historyFile.readText()
                Logger.info(TAG, "loadFromDisk: file exists, size=${text.length} bytes")
                _items.value = json.decodeFromString(
                    ListSerializer(HistoryItem.serializer()),
                    text
                )
                Logger.info(TAG, "loadFromDisk: loaded ${_items.value.size} items")
            } else {
                Logger.info(TAG, "loadFromDisk: history file does not exist yet")
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to load history: ${e.message}", e)
            _items.value = emptyList()
        }
    }
}
