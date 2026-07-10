package io.github.erenche.syncclipboard.app.viewmodel

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erenche.syncclipboard.app.SyncClipboardApp
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge

import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.launch

/**
 * MainViewModel — 主界面状态管理。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncClipboardApp>()

    val isModuleActive = mutableStateOf(false)

    private val _syncStatus = mutableStateOf("Checking...")
    val syncStatus: State<String> = _syncStatus

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        android.util.Log.w("SyncClipboard", "[MainVM] refreshStatus() called")
        viewModelScope.launch {
            try {
                android.util.Log.w("SyncClipboard", "[MainVM] Sending GET_SYNC_STATUS via bridge...")
                val bundle = SyncClipboardBridge.with(app)
                                        .key(BridgeKeys.GET_SYNC_STATUS)
                    .await()
                android.util.Log.w("SyncClipboard", "[MainVM] GET_SYNC_STATUS reply: connected=${bundle.getBoolean("connected", false)}, running=${bundle.getBoolean("running", false)}, isEmpty=${bundle.isEmpty}")

                val connected = bundle.getBoolean("connected", false)
                val running = bundle.getBoolean("running", false)
                _syncStatus.value = when {
                    !running -> "Stopped"
                    connected -> "Connected"
                    else -> "Disconnected"
                }
                android.util.Log.w("SyncClipboard", "[MainVM] syncStatus set to: ${_syncStatus.value}")
            } catch (e: Exception) {
                android.util.Log.w("SyncClipboard", "[MainVM] Failed to get sync status: ${e.message}", e)
                Logger.warn("MainVM", "Failed to get sync status: ${e.message}")
                _syncStatus.value = "Unavailable"
            }
        }
    }

    fun triggerSync() {
        android.util.Log.w("SyncClipboard", "[MainVM] triggerSync() called")
        viewModelScope.launch {
            SyncClipboardBridge.with(app)
                                .key(BridgeKeys.TRIGGER_SYNC)
                .send()
            android.util.Log.w("SyncClipboard", "[MainVM] TRIGGER_SYNC send() done, refreshing status...")
            refreshStatus()
        }
    }

    fun uploadNow() {
        android.util.Log.w("SyncClipboard", "[MainVM] uploadNow() called")
        viewModelScope.launch {
            SyncClipboardBridge.with(app)
                                .key(BridgeKeys.UPLOAD_NOW)
                .send()
            android.util.Log.w("SyncClipboard", "[MainVM] UPLOAD_NOW send() done")
        }
    }
}
