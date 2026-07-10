package io.github.erenche.syncclipboard.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.SyncClipboardApp
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.app.viewmodel.MainViewModel
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge

import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.model.AppConfig
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : BaseActivity(), SyncClipboardApp.XposedServiceStateListener {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen(viewModel) }
        SyncClipboardApp.addXposedServiceStateListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncClipboardApp.removeXposedServiceStateListener(this)
    }

    override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
        viewModel.isModuleActive.value = service != null
        // Push config to xposed process when service connects
        if (service != null) {
            lifecycleScope.launch {
                try {
                    val config = Prefs.loadConfig(this@MainActivity)
                    val configJson = Json.encodeToString(AppConfig.serializer(), config)
                    SyncClipboardBridge.with(this@MainActivity)
                                                .key(BridgeKeys.PUSH_CONFIG)
                        .payload(android.os.Bundle().apply { putString("config", configJson) })
                        .send()
                } catch (_: Exception) {}
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Push current config to xposed process on startup
    LaunchedEffect(Unit) {
        try {
            val config = Prefs.loadConfig(context)
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            SyncClipboardBridge.with(context)
                                .key(BridgeKeys.PUSH_CONFIG)
                .payload(android.os.Bundle().apply { putString("config", configJson) })
                .send()
        } catch (_: Exception) {}
    }

    AppToolBarListContainer(
        title = stringResource(R.string.app_name),
        actions = {}
    ) {
        // 1. Status Card
        item("status") {
            StatusCard(viewModel)
        }

        // 2. Auto Sync Toggle
        item("auto_sync") {
            AutoSyncToggle()
        }

        // 3. Sync Controls
        item("sync_controls") {
            SyncControlsCard(viewModel)
        }

        // 4. Navigation
        item("navigation") {
            NavigationCard()
        }

        // 5. Info
        item("info") {
            InfoCard()
        }
    }
}

// ─── Status Card ────────────────────────────────────────────────

@Composable
fun StatusCard(viewModel: MainViewModel) {
    val isActive by viewModel.isModuleActive
    val syncStatus by viewModel.syncStatus

    val bgColor = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336)

    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardColors(bgColor, Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (isActive) R.string.module_status_activated
                    else R.string.module_status_not_activated
                ),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.main_sync_status, syncStatus),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
    }
}

// ─── Auto Sync Toggle ───────────────────────────────────────────

@Composable
fun AutoSyncToggle() {
    val prefs = LocalContext.current.defaultSharedPreferences
    var autoSync by rememberBooleanPreference(prefs, "auto_sync_enabled", true)

    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        SwitchPreference(
            checked = autoSync,
            title = stringResource(R.string.setting_auto_sync),
            summary = stringResource(R.string.setting_auto_sync_summary),
            onCheckedChange = { autoSync = it }
        )
    }
}

// ─── Sync Controls ──────────────────────────────────────────────

@Composable
fun SyncControlsCard(viewModel: MainViewModel) {
    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        ArrowPreference(
            title = stringResource(R.string.action_sync_now),
            summary = stringResource(R.string.main_sync_now_desc),
            onClick = { viewModel.triggerSync() }
        )
        ArrowPreference(
            title = stringResource(R.string.action_upload_now),
            summary = stringResource(R.string.main_upload_now_desc),
            onClick = { viewModel.uploadNow() }
        )
    }
}

// ─── Navigation ─────────────────────────────────────────────────

@Composable
fun NavigationCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        ArrowPreference(
            title = stringResource(R.string.item_server_settings),
            summary = stringResource(R.string.item_server_settings_summary),
            onClick = {
                context.startActivity(Intent(context, ServerConfigActivity::class.java))
            }
        )
        ArrowPreference(
            title = stringResource(R.string.item_sync_settings),
            summary = stringResource(R.string.item_sync_settings_summary),
            onClick = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        )
        ArrowPreference(
            title = stringResource(R.string.item_history),
            summary = stringResource(R.string.item_history_summary),
            onClick = {
                context.startActivity(Intent(context, HistoryActivity::class.java))
            }
        )
        ArrowPreference(
            title = stringResource(R.string.item_about_app),
            summary = stringResource(R.string.item_about_app_summary),
            onClick = {
                context.startActivity(Intent(context, AboutActivity::class.java))
            }
        )
    }
}

// ─── Info ───────────────────────────────────────────────────────

@Composable
fun InfoCard() {
    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.main_how_it_works_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.main_how_it_works_text),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
    }
}
