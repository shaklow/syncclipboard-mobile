package io.github.erenche.syncclipboard.app.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * BaseActivity — 所有 Activity 的基类。
 *
 * 提供通用的配置，如边缘到边缘显示。
 */
open class BaseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }
}
