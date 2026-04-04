/**
 * Clipboard Proxy
 * 剪贴板代理 - 在 Android 后台时通过悬浮窗获取剪贴板，其他情况直接调用 expo-clipboard
 *
 * 当启用后台同步+悬浮窗模式时，悬浮窗常驻显示（不可见的 1px 窗口），
 * 每次读取剪贴板时只是 focus 到悬浮窗读取后 unfocus，而非反复创建/销毁。
 */

import * as Clipboard from 'expo-clipboard';
import { AppState, Platform } from 'react-native';
import { useSettingsStore } from '@/stores/settingsStore';

let overlayModule: typeof import('clipboard-overlay') | null = null;
if (Platform.OS === 'android') {
  overlayModule = require('clipboard-overlay');

  // 当应用回到前台时，自动销毁常驻悬浮窗
  AppState.addEventListener('change', (nextAppState) => {
    if (nextAppState === 'active' && overlayModule?.isOverlayShowing()) {
      overlayModule.hideOverlayWindow().catch((e) => {
        console.warn('[ClipboardProxy] Failed to dismiss overlay on foreground:', e);
      });
    }
  });
}

/**
 * 确保悬浮窗已显示（仅在需要时创建）
 */
async function ensureOverlayShowing(): Promise<void> {
  if (!overlayModule) return;
  if (!overlayModule.isOverlayShowing()) {
    try {
      await overlayModule.showOverlayWindow();
    } catch (e) {
      console.warn('[ClipboardProxy] Failed to show persistent overlay:', e);
    }
  }
}

/**
 * 隐藏悬浮窗
 */
export async function dismissOverlay(): Promise<void> {
  if (!overlayModule) return;
  if (overlayModule.isOverlayShowing()) {
    try {
      await overlayModule.hideOverlayWindow();
    } catch (e) {
      console.warn('[ClipboardProxy] Failed to hide persistent overlay:', e);
    }
  }
}

/**
 * 判断是否应该使用悬浮窗获取剪贴板
 * 条件：Android + 后台 + 设置启用 + 权限已授予
 * 如果条件满足，确保悬浮窗已常驻显示
 */
async function shouldUseOverlay(): Promise<boolean> {
  if (Platform.OS !== 'android' || !overlayModule) return false;
  if (AppState.currentState !== 'background') return false;
  const config = useSettingsStore.getState().config;
  if (!(config?.enableClipboardOverlay ?? false)) return false;
  // Sync overlay visibility and retry count to native module
  const isDebug = config?.debugMode ?? false;
  const showOverlay = isDebug && (config?.debugOverlayVisible ?? false);
  overlayModule.setDebugMode(showOverlay);
  overlayModule.setMaxRetries(isDebug ? 20 : 5);
  if (!overlayModule.hasOverlayPermission()) return false;
  // Ensure persistent overlay is showing before reading clipboard
  await ensureOverlayShowing();
  return true;
}

/**
 * 获取剪贴板文本
 */
export async function getStringAsync(options?: Clipboard.GetStringOptions): Promise<string> {
  if (await shouldUseOverlay()) {
    try {
      return await overlayModule!.getStringViaOverlay();
    } catch (e) {
      console.warn('[ClipboardProxy] Overlay getStringAsync failed, falling back:', e);
    }
  }
  return Clipboard.getStringAsync(options);
}

/**
 * 设置剪贴板文本
 */
export async function setStringAsync(
  text: string,
  options?: Clipboard.SetStringOptions
): Promise<boolean> {
  return Clipboard.setStringAsync(text, options);
}

/**
 * 检查剪贴板是否有文本
 */
export async function hasStringAsync(): Promise<boolean> {
  if (await shouldUseOverlay()) {
    try {
      return await overlayModule!.hasStringViaOverlay();
    } catch (e) {
      console.warn('[ClipboardProxy] Overlay hasStringAsync failed, falling back:', e);
    }
  }
  return Clipboard.hasStringAsync();
}

/**
 * 检查剪贴板是否有图片
 */
export async function hasImageAsync(): Promise<boolean> {
  if (await shouldUseOverlay()) {
    try {
      return await overlayModule!.hasImageViaOverlay();
    } catch (e) {
      console.warn('[ClipboardProxy] Overlay hasImageAsync failed, falling back:', e);
    }
  }
  return Clipboard.hasImageAsync();
}

/**
 * 获取剪贴板图片
 */
export async function getImageAsync(
  options: Clipboard.GetImageOptions
): Promise<Clipboard.ClipboardImage | null> {
  if (await shouldUseOverlay()) {
    try {
      const result = await overlayModule!.getImageViaOverlay();
      if (result) {
        return {
          data: result.data,
          size: result.size,
        };
      }
      return null;
    } catch (e) {
      console.warn('[ClipboardProxy] Overlay getImageAsync failed, falling back:', e);
    }
  }
  return Clipboard.getImageAsync(options);
}

/**
 * 设置剪贴板图片
 */
export async function setImageAsync(base64Image: string): Promise<void> {
  return Clipboard.setImageAsync(base64Image);
}
