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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * HistoryService — 剪贴板历史记录服务。
 *
 * 使用 JSON 文件 + MutableStateFlow 存储（无 Room 依赖），
 * 通过 profileHash 去重，支持分页、搜索、软删除。
 */
class HistoryService(context: Context) {

    companion object {
        private const val TAG = "HistoryService"
        private const val MAX_ITEMS = 1000
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val historyFile = java.io.File(context.filesDir, "clipboard_history.json")
    private val lock = Any()  // 保证 addOrUpdate 的线程安全

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: Flow<List<HistoryItem>> = _items.asStateFlow()

    init {
        loadFromDisk()
    }

    fun observeAll(): Flow<List<HistoryItem>> = items

    fun getPaged(limit: Int = 50, offset: Int = 0): List<HistoryItem> {
        val all = _items.value.filter { !it.isDeleted }
            .sortedWith(compareByDescending<HistoryItem> { if (it.pinned) 1 else 0 }
                .thenByDescending { it.timestamp })
        return all.drop(offset).take(limit)
    }

    fun getById(id: String): HistoryItem? =
        _items.value.find { it.id == id && !it.isDeleted }

    fun addOrUpdate(content: ClipboardContent) {
        val hash = content.profileHash
            ?: HashUtils.computeLocalHash(content.text)

        synchronized(lock) {
            val existing = _items.value.find { it.profileHash == hash && !it.isDeleted }
            if (existing != null) {
                replaceItem(existing.copy(lastAccessed = System.currentTimeMillis()))
            } else {
                val newItem = HistoryItem(
                    id = UUID.randomUUID().toString(),
                    type = content.type,
                    text = content.text,
                    profileHash = hash,
                    hasData = content.hasData,
                    dataName = content.fileName,
                    size = content.fileSize,
                    timestamp = content.timestamp,
                    syncStatus = HistorySyncStatus.LocalOnly,
                    fileUri = content.fileUri
                )
                addItem(newItem)
                trimIfNeeded()
            }
        }
    }

    fun delete(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        replaceItem(item.copy(isDeleted = true, lastModified = System.currentTimeMillis()))
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
        _items.value.filter {
            !it.isDeleted && it.text.contains(query, ignoreCase = true)
        }.sortedByDescending { it.timestamp }

    fun count(): Int = _items.value.count { !it.isDeleted }

    // ─── 内部方法 ────────────────────────────────────────────────

    private fun addItem(item: HistoryItem) {
        val current = _items.value.toMutableList()
        current.add(0, item)
        _items.value = current
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
        try { historyFile.writeText(json.encodeToString(_items.value)) }
        catch (e: Exception) { Logger.error(TAG, "Failed to save history", e) }
    }

    private fun loadFromDisk() {
        try {
            if (historyFile.exists())
                _items.value = json.decodeFromString(historyFile.readText())
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to load history", e)
            _items.value = emptyList()
        }
    }
}
