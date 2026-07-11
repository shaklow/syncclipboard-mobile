package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.app.util.AppLangUtils
import io.github.erenche.syncclipboard.app.util.AppThemeUtils
import io.github.erenche.syncclipboard.app.util.ThemeColor
import io.github.erenche.syncclipboard.app.util.resolveLanguageName
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.model.AppConfig
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    var currentColorId by remember { mutableStateOf(AppThemeUtils.getThemeColor(context)) }
    var showColorPicker by remember { mutableStateOf(false) }
    val monetAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    // Monet 开启时颜色选择不可用
    val colorEnabled = !monetEnabled || !monetAvailable

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

        // ── 主题颜色选择行 ──
        val currentThemeColor = ThemeColor.fromId(currentColorId)
        val isDark = io.github.erenche.syncclipboard.app.compose.theme.CurrentThemeConfigs.isDark
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = colorEnabled) { showColorPicker = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.setting_theme_color),
                    fontSize = 16.sp,
                    color = if (colorEnabled) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.disabledOnSurface
                )
                Text(
                    text = stringResource(R.string.setting_theme_color_summary),
                    fontSize = 13.sp,
                    color = if (colorEnabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
                        else MiuixTheme.colorScheme.disabledOnSurface
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = if (colorEnabled)
                            (if (isDark) currentThemeColor.darkPrimary else currentThemeColor.lightPrimary)
                        else MiuixTheme.colorScheme.disabledOnSurface,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .border(
                        width = 2.dp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
        }

        if (monetAvailable) {
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

    // ── 颜色选择对话框 ──
    if (showColorPicker) {
        ThemeColorPickerDialog(
            currentColorId = currentColorId,
            onColorSelected = { themeColor ->
                currentColorId = themeColor.id
                AppThemeUtils.setThemeColor(context, themeColor.id)
                activity?.recreate()
            },
            onDismiss = { showColorPicker = false }
        )
    }
}

/**
 * 主题颜色选择对话框 — 以网格形式展示预设颜色。
 */
@Composable
private fun ThemeColorPickerDialog(
    currentColorId: String,
    onColorSelected: (ThemeColor) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = io.github.erenche.syncclipboard.app.compose.theme.CurrentThemeConfigs.isDark
    OverlayDialog(
        show = true,
        title = stringResource(R.string.setting_theme_color),
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 4 列网格
            val rows = ThemeColor.entries.chunked(4)
            rows.forEach { rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowColors.forEach { themeColor ->
                        val isSelected = themeColor.id == currentColorId
                        val color = if (isDark) themeColor.darkPrimary else themeColor.lightPrimary
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                onColorSelected(themeColor)
                                onDismiss()
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(color = color, shape = androidx.compose.foundation.shape.CircleShape)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) MiuixTheme.colorScheme.onSurface
                                            else Color.Transparent,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = MiuixIcons.Ok,
                                        contentDescription = null,
                                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = themeColor.name.lowercase(),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── 语言切换 ─────────────────────────────────────────────────
@Composable
fun LanguageCard(context: android.content.Context, activity: Activity?) {
    val languageCodes = remember {
        buildList {
            addAll(context.resources.getStringArray(R.array.language_codes).toList())
            add(0, AppLangUtils.DEFAULT_LANGUAGE)
        }
    }

    val currentLanguage = remember { AppLangUtils.getCustomizeLang(context) }

    val spinnerItems = remember(languageCodes) {
        languageCodes.map { code ->
            val primaryName = context.resolveLanguageName(code)
            val fallbackName = context.resolveLanguageName(code, AppLangUtils.DEFAULT_LOCALE)
            SpinnerEntry(
                title = primaryName,
                summary = if (primaryName == fallbackName) null else fallbackName
            )
        }
    }

    val selectedIndex = remember(currentLanguage, languageCodes) {
        languageCodes.indexOf(currentLanguage).coerceAtLeast(0)
    }

    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, end = 16.dp)
            .fillMaxWidth()
    ) {
        OverlaySpinnerPreference(
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            title = stringResource(R.string.setting_language),
            items = spinnerItems,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                val newLang = languageCodes[index]
                if (currentLanguage != newLang) {
                    AppLangUtils.saveCustomizeLanguage(context, newLang)
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
