package io.github.erenche.syncclipboard.app.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

@Composable
fun SettingsScreen() {
    AppToolBarListContainer(title = "Settings", canBack = true) {
        item("sync") { SyncSettingsCard() }
        item("background") { BackgroundSettingsCard() }
        item("history") { HistorySettingsCard() }
    }
}

@Composable
fun SyncSettingsCard() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.defaultSharedPreferences
    var autoSync by rememberBooleanPreference(prefs, "auto_sync_enabled", true)
    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        SwitchPreference(checked = autoSync, title = "Auto Sync",
            summary = "Automatically sync clipboard changes in background",
            onCheckedChange = { autoSync = it })
    }
}

@Composable
fun BackgroundSettingsCard() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.defaultSharedPreferences
    var bgUpload by rememberBooleanPreference(prefs, "background_upload_enabled", true)
    var bgDownload by rememberBooleanPreference(prefs, "background_download_enabled", true)
    Card(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()) {
        SwitchPreference(checked = bgUpload, title = "Background Upload",
            summary = "Auto-upload clipboard changes when idle",
            onCheckedChange = { bgUpload = it })
        SwitchPreference(checked = bgDownload, title = "Background Download",
            summary = "Auto-download remote clipboard changes",
            onCheckedChange = { bgDownload = it })
    }
}

@Composable
fun HistorySettingsCard() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.defaultSharedPreferences
    var historySync by rememberBooleanPreference(prefs, "history_sync_enabled", false)
    Card(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()) {
        SwitchPreference(checked = historySync, title = "History Sync",
            summary = "Sync clipboard history with server",
            onCheckedChange = { historySync = it })
    }
}
