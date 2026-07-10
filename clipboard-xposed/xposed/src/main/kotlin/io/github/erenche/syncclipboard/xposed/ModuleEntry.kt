package io.github.erenche.syncclipboard.xposed

import androidx.annotation.Keep
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.hook.ClipboardHooker
import io.github.erenche.syncclipboard.xposed.hook.GeneralHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed 模块入口 — 遵循 lyricon 的 Hook 模式：
 * - onPackageLoaded 中 Hook SystemUI 和 App 进程
 * - ClipboardHooker 负责在 ClipboardManager.setPrimaryClip 拦截剪贴板变化
 */
@Keep
class ModuleEntry : XposedModule() {

    companion object {
        private const val TAG = "ModuleEntry"
        private val scopes = listOf(PackageNames.APPLICATION, PackageNames.SYSTEM_UI)
        lateinit var instance: ModuleEntry
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        android.util.Log.w("SyncClipboard", "[ModuleEntry] onModuleLoaded: processName=${param.processName}")
        Logger.info(TAG, "onModuleLoaded: processName=${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        android.util.Log.w("SyncClipboard", "[ModuleEntry] onPackageLoaded: pkg=$pkg, inScopes=${pkg in scopes}")
        if (pkg !in scopes) return
        Logger.info(TAG, "onPackageLoaded: $pkg")

        // 通用 Hook：初始化 SyncEngine（在所有作用域进程中运行）
        android.util.Log.w("SyncClipboard", "[ModuleEntry] Calling GeneralHooker.hook() for $pkg")
        GeneralHooker.hook(this, param)

        // 剪贴板 Hook：拦截 ClipboardManager.setPrimaryClip
        android.util.Log.w("SyncClipboard", "[ModuleEntry] Calling ClipboardHooker.hook() for $pkg")
        ClipboardHooker.hook(this, param)
    }
}
