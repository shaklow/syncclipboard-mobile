package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.app.compose.preference.rememberStringPreference
import io.github.erenche.syncclipboard.app.util.AppThemeUtils
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.model.AppConfig
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.util.Locale

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val activity = context as? Activity

    AppToolBarListContainer(
        title = stringResource(R.string.activity_settings),
        canBack = true,
        onBack = { activity?.finish() }
    ) {
        // 1. 主题设置
        item("theme") {
            ThemeSettingsCard(context, activity)
        }

        // 2. 语言切换
        item("language") {
            LanguageCard(context, activity)
        }

        // 3. 同步设置
        item("sync") {
            SyncSettingsCard()
        }

        // 4. 后台设置
        item("background") {
            BackgroundSettingsCard()
        }

        // 5. 历史记录设置
        item("history") {
            HistorySettingsCard()
        }

        // 6. 日志设置
        item("logging") {
            LoggingSettingsCard(context)
        }

        // 7. 自动保存设置
        item("auto_save") {
            AutoSaveSettingsCard(context)
        }
    }
}

// ─── 主题设置 ─────────────────────────────────────────────────
@Composable
fun ThemeSettingsCard(context: android.content.Context, activity: Activity?) {
    val themeModeOptions = listOf(
        R.string.option_theme_system to AppThemeUtils.MODE_SYSTEM,
        R.string.option_theme_light to AppThemeUtils.MODE_LIGHT,
        R.string.option_theme_dark to AppThemeUtils.MODE_DARK
    )
    val monetEnabled = remember { AppThemeUtils.isEnableMonet(context) }
    var currentMode by remember { mutableIntStateOf(AppThemeUtils.getMode(context)) }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        val selectedIndex = themeModeOptions.indexOfFirst { it.second == currentMode }
            .coerceAtLeast(0)

        OverlayDropdownPreference(
            title = stringResource(R.string.setting_theme_mode),
            items = themeModeOptions.map { stringResource(it.first) },
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                val newMode = themeModeOptions[index].second
                if (newMode != currentMode) {
                    currentMode = newMode
                    AppThemeUtils.setMode(context, newMode)
                    activity?.recreate()
                }
            }
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            var monetChecked by remember { mutableStateOf(monetEnabled) }
            SwitchPreference(
                title = stringResource(R.string.setting_theme_monet),
                summary = stringResource(R.string.setting_theme_monet_summary),
                checked = monetChecked,
                onCheckedChange = {
                    monetChecked = it
                    AppThemeUtils.setEnableMonet(context, it)
                    activity?.recreate()
                }
            )
        }
    }
}

// ─── 语言切换 ─────────────────────────────────────────────────
@Composable
fun LanguageCard(context: android.content.Context, activity: Activity?) {
    val prefs = context.defaultSharedPreferences
    var language by rememberStringPreference(prefs, "language", "")

    val systemLang = remember {
        when (Locale.getDefault().language) {
            "zh" -> "中文"
            else -> "English"
        }
    }

    val langOptions = listOf(
        "" to "${stringResource(R.string.setting_language_auto)}（$systemLang）",
        "zh" to stringResource(R.string.setting_language_zh),
        "en" to stringResource(R.string.setting_language_en)
    )

    val selectedIndex = remember(language) {
        langOptions.indexOfFirst { it.first == language }.coerceAtLeast(0)
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.setting_language),
            items = langOptions.map { it.second },
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                val newLang = langOptions[index].first
                if (language != newLang) {
                    language = newLang
                    // 语言改变，重启整个 App 让所有 Activity 应用新语言
                    activity?.let { act ->
                        val intent = act.packageManager.getLaunchIntentForPackage(act.packageName)
                        intent?.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                        act.startActivity(intent)
                        act.finishAffinity()
                    }
                }
            }
        )
    }
}

// ─── 同步设置 ─────────────────────────────────────────────────
@Composable
fun SyncSettingsCard() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.defaultSharedPreferences
    var autoSync by rememberBooleanPreference(prefs, "auto_sync_enabled", true)

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = autoSync,
            title = stringResource(R.string.setting_auto_sync),
            summary = stringResource(R.string.setting_auto_sync_summary),
            onCheckedChange = { autoSync = it }
        )
    }
}

// ─── 后台设置 ─────────────────────────────────────────────────
@Composable
fun BackgroundSettingsCard() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.defaultSharedPreferences
    var bgUpload by rememberBooleanPreference(prefs, "background_upload_enabled", true)
    var bgDownload by rememberBooleanPreference(prefs, "background_download_enabled", true)

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = bgUpload,
            title = stringResource(R.string.setting_background_upload),
            summary = stringResource(R.string.setting_background_upload_summary),
            onCheckedChange = { bgUpload = it }
        )
        SwitchPreference(
            checked = bgDownload,
            title = stringResource(R.string.setting_background_download),
            summary = stringResource(R.string.setting_background_download_summary),
            onCheckedChange = { bgDownload = it }
        )
    }
}

// ─── 历史记录设置 ─────────────────────────────────────────────
@Composable
fun HistorySettingsCard() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.defaultSharedPreferences
    var historySync by rememberBooleanPreference(prefs, "history_sync_enabled", false)

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = historySync,
            title = stringResource(R.string.setting_history_sync),
            summary = stringResource(R.string.setting_history_sync_summary),
            onCheckedChange = { historySync = it }
        )
    }
}

// ─── 日志设置 ─────────────────────────────────────────────────
@Composable
fun LoggingSettingsCard(context: android.content.Context) {
    var enableLogging by remember {
        mutableStateOf(Prefs.loadConfig(context).enableLogging)
    }

    val onToggle: (Boolean) -> Unit = { enabled ->
        enableLogging = enabled
        try {
            val config = Prefs.loadConfig(context).copy(enableLogging = enabled)
            Prefs.saveConfig(context, config)
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (_: Exception) {}
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = enableLogging,
            title = stringResource(R.string.setting_enable_logging),
            summary = stringResource(R.string.setting_enable_logging_summary),
            onCheckedChange = onToggle
        )
    }
}

// ─── 自动保存设置 ─────────────────────────────────────────────
@Composable
fun AutoSaveSettingsCard(context: android.content.Context) {
    var enableAutoSave by remember {
        mutableStateOf(Prefs.loadConfig(context).enableAutoSave)
    }

    val onToggle: (Boolean) -> Unit = { enabled ->
        enableAutoSave = enabled
        try {
            val config = Prefs.loadConfig(context).copy(enableAutoSave = enabled)
            Prefs.saveConfig(context, config)
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            val payload = android.os.Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(context)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (_: Exception) {}
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        SwitchPreference(
            checked = enableAutoSave,
            title = stringResource(R.string.setting_auto_save),
            summary = stringResource(R.string.setting_auto_save_summary),
            onCheckedChange = onToggle
        )
    }
}
