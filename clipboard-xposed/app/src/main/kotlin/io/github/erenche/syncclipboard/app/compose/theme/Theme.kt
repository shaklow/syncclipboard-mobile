package io.github.erenche.syncclipboard.app.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import io.github.erenche.syncclipboard.app.util.AppThemeUtils
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors

object CurrentThemeConfigs {
    var isDark: Boolean = false
    var primary: Color = Color.Transparent
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = view.context as? Activity
    val dark = resolveDarkMode(context)
    val colors = resolveColors(context, dark)

    CurrentThemeConfigs.isDark = dark

    SideEffect {
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }

    MiuixTheme(
        colors = colors,
        content = {
            CurrentThemeConfigs.primary = MiuixTheme.colorScheme.primary
            content()
        }
    )
}

@Composable
private fun resolveDarkMode(context: android.content.Context): Boolean =
    when (AppThemeUtils.getMode(context)) {
        AppThemeUtils.MODE_LIGHT -> false
        AppThemeUtils.MODE_DARK -> true
        AppThemeUtils.MODE_SYSTEM -> isSystemInDarkTheme()
        else -> isSystemInDarkTheme()
    }

@Composable
private fun resolveColors(context: android.content.Context, dark: Boolean): Colors =
    if (AppThemeUtils.isEnableMonet(context)) {
        platformDynamicColors(dark)
    } else if (dark) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }
