/**
 * Clipboard Proxy
 * 剪贴板代理 - 在 Android 后台时通过 Root 或悬浮窗获取剪贴板，其他情况直接调用 expo-clipboard
 *
 * 优先级：Root > 悬浮窗 > 直接调用
 *
 * 性能优化：
 * - Root 可用性缓存 5 分钟，避免每轮轮询都 spawn su 进程检测
 * - 首次 Root 可用时自动执行省电优化绕过
 *
 * 当启用后台同步+悬浮窗模式时，悬浮窗按需显示（不可见的 1px 窗口），
 * 每次读取剪贴板时只是 focus 到悬浮窗读取后 unfocus，而非反复创建/销毁。
 * 若持续 10 秒无后台剪贴板调用，自动关闭悬浮窗以节省资源，下次需要时再打开。
 */

import * as Clipboard from 'expo-clipboard';
import { AppState, Platform } from 'react-native';
import { configService } from '@/services/ConfigService';
import { setTimer, clearTimer } from 'native-timer';
import { nativeSaveClipboardImageToFile } from 'native-util';

/** 悬浮窗空闲超时时间（毫秒） */
const OVERLAY_IDLE_TIMEOUT_MS = 10_000;
/** 空闲计时器的固定 tag */
const IDLE_TIMER_TAG = 'clipboard_overlay_idle';

// ─── 模块引用 ─────────────────────────────────────────────────

let overlayModule: typeof import('clipboard-overlay') | null = null;
let rootClipboardModule: typeof import('root-clipboard') | null = null;

// ─── Root 状态缓存 ────────────────────────────────────────────

/**
 * 关键优化：Shizuku 方案成功的关键在于 `isShizukuAvailable()` 和
 * `hasShizukuPermission()` 是纯本地状态检查（零进程开销）。Root 方案的
 * `isRootAvailable()` 和 `checkRootPermission()` 每次都要 spawn `su -c id`，
 * 1000ms 轮询间隔内 3 次 su spawn 根本来不及。
 *
 * 解决方案：缓存 Root 可用性状态，5 分钟内不重新检测。
 * 仅在实际读剪贴板时才 spawn su 进程（1 次而非 3 次）。
 */
let _rootAvailableCache: boolean | null = null;
let _rootCacheTimestamp = 0;
const ROOT_CACHE_TTL_MS = 5 * 60 * 1000;

/** 省电优化是否已执行过 */
let _batteryOptimizationBypassed = false;

/** 缓存 Root 可用性（仅首次或缓存过期时执行 su 检测） */
function isRootAvailableCached(): boolean {
  if (!rootClipboardModule) return false;
  const now = Date.now();
  if (_rootAvailableCache !== null && now - _rootCacheTimestamp < ROOT_CACHE_TTL_MS) {
    return _rootAvailableCache;
  }
  // 仅在这里 spawn su 进程，且只做一次（合并 isRootAvailable + checkRootPermission）
  _rootAvailableCache =
    rootClipboardModule.isRootAvailable() && rootClipboardModule.checkRootPermission();
  _rootCacheTimestamp = now;
  return _rootAvailableCache;
}

// ─── 空闲计时器 ───────────────────────────────────────────────

function resetIdleTimer(): void {
  clearTimer(IDLE_TIMER_TAG);
  setTimer(
    () => {
      clearTimer(IDLE_TIMER_TAG);
      if (overlayModule?.isOverlayShowing()) {
        overlayModule.hideOverlayWindow().catch((e) => {
          console.warn('[ClipboardProxy] Failed to hide overlay on idle timeout:', e);
        });
      }
    },
    OVERLAY_IDLE_TIMEOUT_MS,
    IDLE_TIMER_TAG
  );
}

function clearIdleTimer(): void {
  clearTimer(IDLE_TIMER_TAG);
}

// ─── Android 初始化 ───────────────────────────────────────────

if (Platform.OS === 'android') {
  overlayModule = require('clipboard-overlay');
  rootClipboardModule = require('root-clipboard');

  AppState.addEventListener('change', (nextAppState) => {
    if (nextAppState === 'active') {
      clearIdleTimer();
      if (overlayModule?.isOverlayShowing()) {
        overlayModule.hideOverlayWindow().catch((e) => {
          console.warn('[ClipboardProxy] Failed to dismiss overlay on foreground:', e);
        });
      }
    }
  });
}

// ─── 悬浮窗管理 ───────────────────────────────────────────────

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

async function shouldUseOverlay(): Promise<boolean> {
  if (Platform.OS !== 'android' || !overlayModule) return false;
  if (AppState.currentState !== 'background') return false;
  const config = await configService.getConfig();
  if (!(config?.enableClipboardOverlay ?? false)) return false;
  const isDebug = config?.debugMode ?? false;
  const showOverlay = isDebug && (config?.debugOverlayVisible ?? false);
  overlayModule.setDebugMode(showOverlay);
  overlayModule.setMaxRetries(isDebug ? 20 : 5);
  if (!overlayModule.hasOverlayPermission()) return false;
  await ensureOverlayShowing();
  resetIdleTimer();
  return true;
}

// ─── Root 方案 ────────────────────────────────────────────────

/**
 * 判断是否应该使用 Root 获取剪贴板。
 *
 * 关键优化：使用 isRootAvailableCached() 替代原来的两次独立 su 调用，
 * Root 可用性检测结果缓存 5 分钟。实际剪贴板读取仍需 spawn su，
 * 但从 3 次降至 1 次。
 *
 * 副作用：首次 Root 可用时自动执行省电优化绕过。
 */
async function shouldUseRoot(): Promise<boolean> {
  if (Platform.OS !== 'android' || !rootClipboardModule) return false;
  const config = await configService.getConfig();
  if (!(config?.enableRootClipboard ?? false)) return false;
  if (!isRootAvailableCached()) return false;

  // 首次 Root 可用时自动绕过省电优化
  if (!_batteryOptimizationBypassed) {
    _batteryOptimizationBypassed = true;
    try {
      const { bypassBatteryOptimization } = require('root-clipboard');
      const result = bypassBatteryOptimization();
      console.log(
        '[ClipboardProxy] Auto battery optimization bypass:',
        result ? 'success' : 'partial'
      );
    } catch (e) {
      console.warn('[ClipboardProxy] Battery optimization bypass failed:', e);
    }
  }

  return true;
}

/**
 * 检查 Root 剪贴板模式是否完全激活。
 *
 * 供 ClipboardSyncService / LongRunningTaskManager 判断：
 * Root 模式激活时，隐式启用后台轮询和同步。
 */
export async function isRootClipboardActive(): Promise<boolean> {
  if (Platform.OS !== 'android' || !rootClipboardModule) return false;
  const config = await configService.getConfig();
  if (!(config?.enableRootClipboard ?? false)) return false;
  return isRootAvailableCached();
}

// ─── 公共剪贴板 API ───────────────────────────────────────────

export async function getStringAsync(options?: Clipboard.GetStringOptions): Promise<string> {
  if (await shouldUseRoot()) {
    try {
      const result = await rootClipboardModule!.getStringViaRoot();
      if (result && result.length > 0) return result;
      console.warn('[ClipboardProxy] Root getStringAsync returned empty, falling back');
    } catch (e) {
      console.warn('[ClipboardProxy] Root getStringAsync failed, falling back:', e);
    }
  }
  if (await shouldUseOverlay()) {
    try {
      const result = await overlayModule!.getStringViaOverlay();
      if (result && result.length > 0) return result;
      console.warn('[ClipboardProxy] Overlay getStringAsync returned empty, falling back');
    } catch (e) {
      console.warn('[ClipboardProxy] Overlay getStringAsync failed, falling back:', e);
    }
  }
  return Clipboard.getStringAsync(options);
}

export async function setStringAsync(
  text: string,
  options?: Clipboard.SetStringOptions
): Promise<boolean> {
  return Clipboard.setStringAsync(text, options);
}

export async function hasStringAsync(): Promise<boolean> {
  if (await shouldUseRoot()) {
    try {
      return await rootClipboardModule!.hasStringViaRoot();
    } catch {}
  }
  if (await shouldUseOverlay()) {
    try {
      return await overlayModule!.hasStringViaOverlay();
    } catch {}
  }
  return Clipboard.hasStringAsync();
}

export async function hasImageAsync(): Promise<boolean> {
  if (await shouldUseRoot()) {
    try {
      return await rootClipboardModule!.hasImageViaRoot();
    } catch {}
  }
  if (await shouldUseOverlay()) {
    try {
      return await overlayModule!.hasImageViaOverlay();
    } catch {}
  }
  return Clipboard.hasImageAsync();
}

export async function getImageAsync(
  options: Clipboard.GetImageOptions
): Promise<Clipboard.ClipboardImage | null> {
  if (await shouldUseOverlay()) {
    try {
      const result = await overlayModule!.getImageViaOverlay();
      if (result) return { data: result.data, size: result.size };
      return null;
    } catch {}
  }
  return Clipboard.getImageAsync(options);
}

export async function saveImageToFileAsync(
  destDirPath: string
): Promise<{ filePath: string; mimeType: string } | null> {
  if (await shouldUseOverlay()) {
    try {
      const result = await overlayModule!.saveImageToFileViaOverlay(destDirPath);
      if (result) return { filePath: result.filePath, mimeType: result.mimeType };
    } catch {}
  }
  if (Platform.OS === 'android') {
    const result = await nativeSaveClipboardImageToFile(destDirPath);
    return result ? { filePath: result.filePath, mimeType: result.mimeType } : null;
  }
  return null;
}

export async function setImageAsync(base64Image: string): Promise<void> {
  return Clipboard.setImageAsync(base64Image);
}
