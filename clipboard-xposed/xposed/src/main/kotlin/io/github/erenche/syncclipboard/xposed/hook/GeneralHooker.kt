package io.github.erenche.syncclipboard.xposed.hook

import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.sync.SyncEngine

/**
 * GeneralHooker — 在所有作用域包中运行的通用 Hook。
 *
 * 负责初始化基础组件（如文件目录、日志等）。
 * 继承自 PackageHooker，自动获取 Application Context。
 */
object GeneralHooker : PackageHooker() {
    const val TAG = "GeneralHooker"

    override fun onHook() {
        doOnAppCreated { app ->
            Logger.info(TAG, "App created: ${app.packageName}")

            // 初始化同步引擎（传入 Context）
            SyncEngine.getInstance().initialize(app)
        }
    }
}
