package io.github.erenche.syncclipboard.xposed.hook

import android.content.ClipData
import android.content.ClipDescription
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.sync.SyncEngine
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * ClipboardHooker — Hook ClipboardManager.setPrimaryClip() 实现事件驱动的剪贴板监听。
 *
 * 遵循 lyricon PackageHooker 模式，在 onPackageLoaded 中钩入。
 * ClipboardManager 是公开 Android API，在所有 OEM 皮肤上都可用。
 */
object ClipboardHooker {

    private const val TAG = "ClipboardHooker"

    fun hook(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val cmClass = param.defaultClassLoader.loadClass("android.content.ClipboardManager")
            val method = cmClass.declaredMethods.firstOrNull { m ->
                m.name == "setPrimaryClip" && m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == ClipData::class.java
            }

            if (method != null) {
                module.hook(method).intercept(ClipboardHookHandler())
                Logger.info(TAG, "Hooked ClipboardManager.setPrimaryClip in ${param.packageName}")
            } else {
                Logger.warn(TAG, "setPrimaryClip not found in ${param.packageName}")
            }
        } catch (e: Throwable) {
            Logger.error(TAG, "Failed to hook ClipboardManager in ${param.packageName}", e)
        }
    }

    private class ClipboardHookHandler : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val clipData = chain.args.firstOrNull() as? ClipData
            chain.proceed()

            if (clipData != null && clipData.itemCount > 0) {
                val content = extractContent(clipData)
                if (content != null) {
                    SyncEngine.getInstance().onLocalClipboardChanged(content)
                }
            }
            return null
        }
    }

    private fun extractContent(clipData: ClipData): ClipboardContent? {
        if (clipData.itemCount == 0) return null
        val item = clipData.getItemAt(0)
        val desc = clipData.description

        val text = when {
            item.text != null -> item.text.toString()
            item.htmlText != null -> item.htmlText.toString()
            item.uri != null -> item.uri.toString()
            else -> return null
        }

        val type = when {
            desc.hasMimeType("image/*") -> ClipboardContentType.Image
            item.uri != null -> ClipboardContentType.File
            else -> ClipboardContentType.Text
        }

        return ClipboardContent(
            type = type,
            text = text,
            fileUri = item.uri?.toString(),
            hasData = item.uri != null,
            timestamp = System.currentTimeMillis()
        )
    }
}
