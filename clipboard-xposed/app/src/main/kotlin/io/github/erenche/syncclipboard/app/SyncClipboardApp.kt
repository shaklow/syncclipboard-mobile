package io.github.erenche.syncclipboard.app

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.erenche.syncclipboard.common.util.Logger
import java.util.concurrent.CopyOnWriteArraySet

/**
 * SyncClipboardApp — Application 入口。
 *
 * 遵循 lyricon 项目中 LyriconApp 的模式：
 * - 管理 XposedService 连接生命周期
 * - 提供全局 Application 实例
 * - 封装 SharedPreferences 访问
 */
class SyncClipboardApp : Application() {

    init {
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SyncClipboardApp created")

        // 直接初始化 SyncEngine，不依赖 LSPosed 钩子
        // 确保 bridge 处理器在 app 进程中始终可用
        android.util.Log.w("SyncClipboard", "[SyncClipboardApp] Initializing SyncEngine directly...")
        try {
            io.github.erenche.syncclipboard.xposed.sync.SyncEngine.getInstance().initialize(this)
            android.util.Log.w("SyncClipboard", "[SyncClipboardApp] SyncEngine initialized successfully")
        } catch (e: Exception) {
            android.util.Log.w("SyncClipboard", "[SyncClipboardApp] SyncEngine init failed: ${e.message}", e)
        }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "XposedService bind")
                xposedService = service
                xposedServiceStateListeners.forEach {
                    it.onServiceStateChanged(service)
                }
            }

            override fun onServiceDied(service: XposedService) {
                Log.i(TAG, "XposedService died")
                xposedService = null
                xposedServiceStateListeners.forEach {
                    it.onServiceStateChanged(null)
                }
            }
        })
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        super.getSharedPreferences(name, mode)

    companion object {
        const val TAG: String = "SyncClipboardApp"
        private val xposedServiceStateListeners =
            CopyOnWriteArraySet<XposedServiceStateListener>()

        lateinit var instance: SyncClipboardApp
            private set

        var xposedService: XposedService? = null
            private set

        fun addXposedServiceStateListener(
            listener: XposedServiceStateListener,
            notifyImmediately: Boolean = true
        ) {
            xposedServiceStateListeners.add(listener)
            if (notifyImmediately && xposedService != null) {
                listener.onServiceStateChanged(xposedService)
            }
        }

        fun removeXposedServiceStateListener(listener: XposedServiceStateListener) {
            xposedServiceStateListeners.remove(listener)
        }

        fun get(): SyncClipboardApp = instance
    }

    interface XposedServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }
}
