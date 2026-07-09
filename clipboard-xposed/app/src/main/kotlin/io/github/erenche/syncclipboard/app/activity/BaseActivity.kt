package io.github.erenche.syncclipboard.app.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import java.util.Locale

/**
 * BaseActivity — 所有 Activity 的基类。
 *
 * 提供通用的配置，如边缘到边缘显示、语言/区域设置。
 */
open class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    companion object {
        /**
         * 根据 SharedPreferences 中的 language 设置应用语言。
         * 支持 "auto"（跟随系统）、"zh"、""（默认/系统）、"en"。
         */
        fun applyLocale(context: Context): Context {
            val prefs = context.defaultSharedPreferences
            val lang = prefs.getString("language", null) ?: return context
            if (lang.isBlank() || lang == "auto") return context

            return try {
                val locale = if (lang.contains("-")) {
                    val parts = lang.split("-")
                    Locale(parts[0], parts[1])
                } else {
                    Locale(lang)
                }
                Locale.setDefault(locale)
                val config = context.resources.configuration
                config.setLocale(locale)
                context.createConfigurationContext(config)
            } catch (_: Exception) {
                context
            }
        }
    }
}
