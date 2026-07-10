package io.github.erenche.syncclipboard.app.activity

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.app.compose.AppToolBarListContainer
import io.github.erenche.syncclipboard.app.compose.preference.rememberBooleanPreference
import io.github.erenche.syncclipboard.app.compose.preference.rememberStringPreference
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
        // 1. 语言切换 — 优先显示
        item("language") {
            LanguageCard(context, activity)
        }

        // 2. 同步设置
        item("sync") {
            SyncSettingsCard()
        }

        // 3. 后台设置
        item("background") {
            BackgroundSettingsCard()
        }

        // 4. 历史记录设置
        item("history") {
            HistorySettingsCard()
        }
    }
}

/**
 * 语言切换卡片
 */
@Composable
fun LanguageCard(context: android.content.Context, activity: Activity?) {
    val prefs = context.defaultSharedPreferences
    var language by rememberStringPreference(prefs, "language", "")

    var showDialog by remember { mutableStateOf(false) }

    // 获取系统语言作为显示
    val systemLang = remember {
        when (Locale.getDefault().language) {
            "zh" -> "中文"
            else -> "English"
        }
    }

    val currentLangLabel = when (language) {
        "zh" -> stringResource(R.string.setting_language_zh)
        "en" -> stringResource(R.string.setting_language_en)
        else -> "${stringResource(R.string.setting_language_auto)}（$systemLang）"
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        ArrowPreference(
            title = stringResource(R.string.setting_language),
            summary = currentLangLabel,
            onClick = { showDialog = true }
        )
    }

    if (showDialog) {
        LanguageDialog(
            currentValue = language,
            onSelect = { newLang ->
                language = newLang
                showDialog = false
                // 如果语言改变了，重启 Activity 以应用
                if (language != newLang) {
                    activity?.recreate()
                } else {
                    activity?.recreate()
                }
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
fun LanguageDialog(
    currentValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val systemLang = remember {
        when (Locale.getDefault().language) {
            "zh" -> "中文"
            else -> "English"
        }
    }

    val options = listOf(
        "" to "${stringResource(R.string.setting_language_auto)}（$systemLang）",
        "zh" to stringResource(R.string.setting_language_zh),
        "en" to stringResource(R.string.setting_language_en)
    )

    OverlayDialog(
        show = true,
        title = stringResource(R.string.setting_language),
        onDismissRequest = onDismiss
    ) {
        Column {
            options.forEach { (value, label) ->
                val isSelected = if (currentValue.isBlank()) value.isBlank()
                    else currentValue == value
                val textColor = if (isSelected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurface

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // MIUIX-style selection indicator
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                if (isSelected) MiuixTheme.colorScheme.primary
                                else Color(0x00000000)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color.White)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MiuixTheme.colorScheme.outline)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        fontSize = 15.sp,
                        color = textColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
