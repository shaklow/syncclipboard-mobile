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
        viewModelScope.launch {
            try {
                val bundle = SyncClipboardBridge.with(app)
                                        .key(BridgeKeys.GET_SYNC_STATUS)
                    .await()

                val connected = bundle.getBoolean("connected", false)
                val running = bundle.getBoolean("running", false)
                _syncStatus.value = when {
                    !running -> "Stopped"
                    connected -> "Connected"
                    else -> "Disconnected"
                }
            } catch (e: Exception) {
                Logger.warn("MainVM", "Failed to get sync status: ${e.message}")
                _syncStatus.value = "Unavailable"
            }
        }
    }

    fun triggerSync() {
        viewModelScope.launch {
            SyncClipboardBridge.with(app)
                                .key(BridgeKeys.TRIGGER_SYNC)
                .send()
            refreshStatus()
        }
    }

    fun uploadNow() {
        viewModelScope.launch {
            SyncClipboardBridge.with(app)
                                .key(BridgeKeys.UPLOAD_NOW)
                .send()
        }
    }
}
