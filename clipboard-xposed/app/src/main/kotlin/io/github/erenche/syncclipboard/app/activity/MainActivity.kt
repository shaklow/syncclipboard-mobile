package io.github.erenche.syncclipboard.app.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.SyncClipboardApp
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.app.viewmodel.MainViewModel
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
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
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    AppToolBarListContainer(
        title = "SyncClipboard",
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
                text = if (isActive) "Module Activated" else "Module Not Activated",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Sync Status: $syncStatus",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
    }
}

// ─── Auto Sync Toggle ───────────────────────────────────────────

@Composable
fun AutoSyncToggle() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.defaultSharedPreferences
    var autoSync by rememberBooleanPreference(prefs, "auto_sync_enabled", true)

    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        SwitchPreference(
            checked = autoSync,
            title = "Auto Sync",
            summary = "Automatically sync clipboard in background",
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
            title = "Sync Now",
            summary = "Manually trigger clipboard sync",
            onClick = { viewModel.triggerSync() }
        )
        ArrowPreference(
            title = "Upload Now",
            summary = "Push current clipboard to server",
            onClick = { viewModel.uploadNow() }
        )
    }
}

// ─── Navigation ─────────────────────────────────────────────────

@Composable
fun NavigationCard() {
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth()
    ) {
        ArrowPreference(
            title = "Server Configuration",
            summary = "Add and manage sync servers",
            onClick = {
                context.startActivity(android.content.Intent(context, ServerConfigActivity::class.java))
            }
        )
        ArrowPreference(
            title = "Settings",
            summary = "Configure sync behavior",
            onClick = {
                context.startActivity(android.content.Intent(context, SettingsActivity::class.java))
            }
        )
        ArrowPreference(
            title = "Clipboard History",
            summary = "View synced clipboard history",
            onClick = {
                context.startActivity(android.content.Intent(context, HistoryActivity::class.java))
            }
        )
        ArrowPreference(
            title = "About",
            summary = "Version and license info",
            onClick = {
                context.startActivity(android.content.Intent(context, AboutActivity::class.java))
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
                text = "How it works",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This LSPosed module hooks into ClipboardManager to detect " +
                        "clipboard changes instantly without polling. Changes are synced " +
                        "to your configured server (SyncClipboard, WebDAV, or S3).",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
    }
}
