package io.github.erenche.syncclipboard.app.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import io.github.erenche.syncclipboard.app.util.AppLangUtils

/**
 * BaseActivity — 所有 Activity 的基类。
 *
 * 提供通用的配置，如边缘到边缘显示、语言/区域设置。
 */
open class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLangUtils.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }
}
