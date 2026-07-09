import { Platform } from 'react-native';
import { requireNativeModule } from 'expo-modules-core';

const MODULE_NAME = 'RootClipboardModule';

interface RootClipboardModuleInterface {
  isRootAvailable(): boolean;
  checkRootPermission(): boolean;
  getStringViaRoot(): Promise<string>;
  hasStringViaRoot(): Promise<boolean>;
  hasImageViaRoot(): Promise<boolean>;
  getImageUriViaRoot(): Promise<string | null>;
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
