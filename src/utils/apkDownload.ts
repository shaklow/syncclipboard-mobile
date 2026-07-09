/**
 * APK Download Service
 * 负责检测设备 ABI、下载 APK（含进度回调）、校验 SHA-256 哈希、安装 APK
 */

import { Paths, Directory, File } from 'expo-file-system';
import type { ReleaseAssetInfo } from './update';
import { parseVersion, compareVersions } from './update';
import i18n from '@/i18n';

// APK 缓存目录：CLIPBOARD_TEMP_DIR/updates/v{version}/
// 清除缓存时会被一并清除
function getUpdateCacheDir(version: string): Directory {
  return new Directory(Paths.cache, 'temp_files', 'updates', `v${version}`);
}

/** 确保 APK 缓存目录链存在 */
function ensureUpdateDirExists(version: string): void {
  const dirs = [
    new Directory(Paths.cache, 'temp_files'),
    new Directory(Paths.cache, 'temp_files', 'updates'),
    new Directory(Paths.cache, 'temp_files', 'updates', `v${version}`),
  ];
  for (const dir of dirs) {
    if (!dir.exists) {
      try {
        dir.create();
      } catch (e) {
        if (!dir.exists) throw e;
      }
    }
  }
}

export function getApkCachePath(version: string, fileName: string): File {
  return new File(getUpdateCacheDir(version), fileName);
}

/**
 * 清除当前版本及更旧版本的 APK 缓存目录
 */
export function cleanOldApkCache(currentVersion: string): void {
  const updatesDir = new Directory(Paths.cache, 'temp_files', 'updates');
  if (!updatesDir.exists) return;

  const currentParsed = parseVersion(currentVersion);
  if (!currentParsed) return;

  try {
    for (const item of updatesDir.list()) {
      const name = item.uri.split('/').filter(Boolean).pop() ?? '';
      if (!name.startsWith('v')) continue;
      const versionStr = name.slice(1);
      const parsed = parseVersion(versionStr);
      if (!parsed) continue;
      if (compareVersions(parsed, currentParsed) <= 0) {
        try {
          (item as Directory).delete();
          console.log(`[ApkCache] deleted old cache: ${name}`);
        } catch (e) {
          console.warn(`[ApkCache] failed to delete ${name}:`, e);
        }
      }
    }
  } catch (e) {
    console.warn('[ApkCache] cleanOldApkCache error:', e);
  }
}

const SUPPORTED_ABI_NAMES = ['arm64-v8a', 'armeabi-v7a', 'x86_64'] as const;
type ApkAbi = (typeof SUPPORTED_ABI_NAMES)[number] | 'universal';

/**
 * 根据设备支持的 ABI 列表，返回最优先使用的 ABI
 */
export function getPreferredAbi(supportedAbis: string[]): ApkAbi {
  for (const abi of supportedAbis) {
    if ((SUPPORTED_ABI_NAMES as readonly string[]).includes(abi)) {
      return abi as ApkAbi;
    }
  }
  return 'universal';
}

/**
 * 从 assets 列表中找到匹配指定 ABI 的 APK 资产
 */
export function findAssetForAbi(
  assets: ReleaseAssetInfo[],
  abi: ApkAbi
): ReleaseAssetInfo | undefined {
  const exact = assets.find((a) => a.name.includes(`-${abi}.apk`));
  if (exact) return exact;
  return assets.find((a) => a.name.includes('-universal.apk'));
}

export interface ApkDownloadProgress {
  progress: number; // 0~1
  bytesReceived: number;
  totalBytes: number;
}

export interface ApkDownloadOptions {
  asset: ReleaseAssetInfo;
  version: string;
  onProgress?: (info: ApkDownloadProgress) => void;
  signal?: AbortSignal;
}

/**
 * 检查 APK 是否已缓存且哈希匹配
 */
export async function checkApkCache(
  version: string,
  asset: ReleaseAssetInfo
): Promise<string | null> {
  const file = getApkCachePath(version, asset.name);
  if (!file.exists) return null;

  if (!asset.sha256) return file.uri;

  try {
    const { nativeCalculateFileHash } = await import('native-util');
    const hash = await nativeCalculateFileHash(file.uri);
    if (hash.toLowerCase() === asset.sha256.toLowerCase()) {
      return file.uri;
    }
    file.delete();
    return null;
  } catch {
    return null;
  }
}

/**
 * 下载 APK 并验证哈希
 */
export async function downloadApk(options: ApkDownloadOptions): Promise<string> {
  const { asset, version, onProgress, signal } = options;
  const url = asset.downloadUrl;
  console.log(`[ApkDownload] start url=${url}`);

  const cacheDir = getUpdateCacheDir(version);
  ensureUpdateDirExists(version);

  const destFile = getApkCachePath(version, asset.name);
  if (destFile.exists) {
    destFile.delete();
  }

  const { nativeDownloadFile } = await import('native-util');
  await nativeDownloadFile(
    url,
    {},
    destFile.uri,
    signal,
    onProgress
      ? (info) =>
          onProgress({
            progress: info.progress,
            bytesReceived: info.bytesTransferred,
            totalBytes: info.totalBytes,
          })
      : undefined
  );

  // 校验哈希
  if (asset.sha256) {
    const { nativeCalculateFileHash } = await import('native-util');
    const hash = await nativeCalculateFileHash(destFile.uri, signal);
    if (hash.toLowerCase() !== asset.sha256.toLowerCase()) {
      destFile.delete();
      throw new Error(i18n.t('error.apkHashMismatch', { expected: asset.sha256, actual: hash }));
    }
  }

  return destFile.uri;
}

/**
 * 通过系统 Intent 安装 APK
 */
export async function installApk(fileUri: string): Promise<void> {
  const { openFile } = await import('@/utils/fileActions');
  await openFile(fileUri);
}
