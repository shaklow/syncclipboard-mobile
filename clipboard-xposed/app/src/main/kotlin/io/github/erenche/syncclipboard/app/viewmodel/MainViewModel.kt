package io.github.erenche.syncclipboard.app.viewmodel

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erenche.syncclipboard.app.SyncClipboardApp
import io.github.erenche.syncclipboard.app.net.ServerApi
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.ProfileDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncClipboardApp>()

    val isModuleActive = mutableStateOf(false)

    private val _syncStatus = mutableStateOf("Checking...")
    val syncStatus: State<String> = _syncStatus

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _isBusy = mutableStateOf(false)
    val isBusy: State<Boolean> = _isBusy

    /** 服务器最新 profile */
    private val _remoteProfile = mutableStateOf<ProfileDto?>(null)
    val remoteProfile: State<ProfileDto?> = _remoteProfile

    /** 下载到本地的图片文件路径 */
    private val _downloadedFile = mutableStateOf<File?>(null)
    val downloadedFile: State<File?> = _downloadedFile

    /** 是否正在加载远程内容 */
    private val _isLoadingRemote = mutableStateOf(false)
    val isLoadingRemote: State<Boolean> = _isLoadingRemote

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            try {
                val bundle = SyncClipboardBridge.with(app)
                    .to("com.android.systemui")
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
                _syncStatus.value = "Unavailable"
            }
        }
    }

    /** 拉取服务器最新内容并下载文件 */
    fun refreshRemoteContent() {
        _isLoadingRemote.value = true
        viewModelScope.launch {
            try {
                val config = Prefs.loadConfig(app)
                val server = config.servers.getOrNull(config.activeServerIndex)
                if (server == null) {
                    _toast.value = "未配置服务器"
                    return@launch
                }
                val api = ServerApi(server)
                val profile = withContext(Dispatchers.IO) { api.getClipboard() }
                _remoteProfile.value = profile

                if (profile != null && profile.hasData && !profile.dataName.isNullOrBlank()) {
                    val destFile = File(app.cacheDir, "preview_${profile.dataName}")
                    val downloaded = withContext(Dispatchers.IO) {
                        api.downloadFile(profile.dataName!!, destFile)
                    }
                    _downloadedFile.value = downloaded
                } else {
                    _downloadedFile.value = null
                }
            } catch (e: Exception) {
                _toast.value = "加载失败: ${e.message}"
            } finally {
                _isLoadingRemote.value = false
            }
        }
    }

    fun triggerSync() {
        SyncClipboardBridge.with(app)
            .to("com.android.systemui")
            .key(BridgeKeys.TRIGGER_SYNC)
            .send()
        _toast.value = "已开始同步"
    }

    fun uploadNow() {
        SyncClipboardBridge.with(app)
            .to("com.android.systemui")
            .key(BridgeKeys.UPLOAD_NOW)
            .send()
        _toast.value = "已开始上传"
    }

    fun onToastShown() {
        _toast.value = null
    }
}
