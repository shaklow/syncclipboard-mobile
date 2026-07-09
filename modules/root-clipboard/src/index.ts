import { Platform } from 'react-native';
import { requireNativeModule, type EventSubscription } from 'expo-modules-core';

const MODULE_NAME = 'RootClipboardModule';

interface RootClipboardModuleInterface {
  isRootAvailable(): boolean;
  checkRootPermission(): boolean;
  getStringViaRoot(): Promise<string>;
  hasStringViaRoot(): Promise<boolean>;
  hasImageViaRoot(): Promise<boolean>;
  getImageUriViaRoot(): Promise<string | null>;
  bypassBatteryOptimization(): boolean;
  isBatteryOptimizationBypassed(): boolean;
  setWatchdogAlarm(): boolean;
  cancelWatchdogAlarm(): boolean;
  addListener(
    eventName: 'onWatchdogTick',
    listener: (event: WatchdogTickEvent) => void
  ): EventSubscription;
}

export interface WatchdogTickEvent {
  timestamp: number;
}

const NativeModule: RootClipboardModuleInterface | null =
  Platform.OS === 'android' ? requireNativeModule(MODULE_NAME) : null;

/**
 * 检查 Root 权限是否可用
 */
export function isRootAvailable(): boolean {
  if (NativeModule) {
    return NativeModule.isRootAvailable();
  }
  return false;
}

/**
 * 验证 Root 权限
 */
export function checkRootPermission(): boolean {
  if (NativeModule) {
    return NativeModule.checkRootPermission();
  }
  return false;
}

/**
 * 通过 Root 获取剪贴板文本
 */
export async function getStringViaRoot(): Promise<string> {
  if (NativeModule) {
    return NativeModule.getStringViaRoot();
  }
  return '';
}

/**
 * 通过 Root 检查剪贴板是否有文本
 */
export async function hasStringViaRoot(): Promise<boolean> {
  if (NativeModule) {
    return NativeModule.hasStringViaRoot();
  }
  return false;
}

/**
 * 通过 Root 检查剪贴板是否有图片
 */
export async function hasImageViaRoot(): Promise<boolean> {
  if (NativeModule) {
    return NativeModule.hasImageViaRoot();
  }
  return false;
}

/**
 * 通过 Root 获取剪贴板图片 URI
 */
export async function getImageUriViaRoot(): Promise<string | null> {
  if (NativeModule) {
    return NativeModule.getImageUriViaRoot();
  }
  return null;
}

// ─── 静默后台增强 ────────────────────────────────────────────

/**
 * 通过 Root 将本应用加入 Android 省电优化白名单。
 *
 * 在后台静默运行时，设备 Doze 模式会冻结 Handler 定时器，
 * 导致剪贴板轮询停止。此方法使用多种 root 策略将应用加入
 * 省电白名单，确保定时器在深度休眠时仍能正常触发。
 *
 * 调用时机：Root 权限验证通过后自动调用，用户无需手动操作。
 *
 * @returns true 表示至少一种策略执行成功
 */
export function bypassBatteryOptimization(): boolean {
  if (NativeModule) {
    return NativeModule.bypassBatteryOptimization();
  }
  return false;
}

/**
 * 检查本应用是否已在省电白名单中。
 * 可用于 UI 显示或诊断。
 */
export function isBatteryOptimizationBypassed(): boolean {
  if (NativeModule) {
    return NativeModule.isBatteryOptimizationBypassed();
  }
  return false;
}

/**
 * 设置 AlarmManager 保活闹钟。
 *
 * 这是防止 Doze 冻结的最后防线：即使省电白名单失效，
 * AlarmManager 的 setAndAllowWhileIdle 仍能在深度休眠时唤醒设备。
 * 闹钟每 5 分钟触发一次 onWatchdogTick 事件，
 * JS 层收到事件后执行轮询健康检查。
 *
 * @returns true 表示闹钟设置成功
 */
export function setWatchdogAlarm(): boolean {
  if (NativeModule) {
    return NativeModule.setWatchdogAlarm();
  }
  return false;
}

/**
 * 取消保活闹钟。
 */
export function cancelWatchdogAlarm(): boolean {
  if (NativeModule) {
    return NativeModule.cancelWatchdogAlarm();
  }
  return false;
}

/**
 * 监听保活闹钟触发事件。
 * 闹钟触发时表示设备刚从深度休眠中唤醒，应执行轮询健康检查。
 *
 * @param listener 闹钟触发回调
 * @returns EventSubscription，用于取消监听
 */
export function addWatchdogTickListener(
  listener: (event: WatchdogTickEvent) => void
): EventSubscription | null {
  if (NativeModule) {
    return NativeModule.addListener('onWatchdogTick', listener);
  }
  return null;
}
