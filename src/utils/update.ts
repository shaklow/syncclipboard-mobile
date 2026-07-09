/**
 * Update Service
 * 检查 GitHub 最新 Release 版本更新
 */

import i18n from '@/i18n';

const GITHUB_RELEASES_API = 'https://api.github.com/repos/shaklow/syncclipboard-mobile/releases';
const RELEASES_PAGE_URL = 'https://github.com/shaklow/syncclipboard-mobile/releases';

export interface ParsedVersion {
  major: number;
  minor: number;
  patch: number;
  build?: number;
  beta?: number;
}

export interface ReleaseAssetInfo {
  /** APK 文件名，如 SyncClipboard-1.0.11-arm64-v8a.apk */
  name: string;
  /** GitHub 直接下载 URL */
  downloadUrl: string;
  /** SHA-256 哈希值（十六进制小写），来自 GitHub API digest 字段，可能为 undefined */
  sha256?: string;
}

export function versionToStr(v: ParsedVersion): string {
  let s = `${v.major}.${v.minor}.${v.patch}`;
  if (v.build !== undefined) s += `.${v.build}`;
  if (v.beta !== undefined) s += `-beta${v.beta}`;
  return s;
}

/**
 * 解析版本字符串，支持格式：
 *   v1.2.3, 1.2.3, v1.2.3.4, v1.2.3-beta1, 1.2.3.4-beta2
 */
export function parseVersion(versionStr: string): ParsedVersion | null {
  const match = versionStr.trim().match(/^v?(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?(?:-beta(\d+))?$/i);
  if (!match) return null;
  return {
    major: parseInt(match[1], 10),
    minor: parseInt(match[2], 10),
    patch: parseInt(match[3], 10),
    build: match[4] !== undefined ? parseInt(match[4], 10) : undefined,
    beta: match[5] !== undefined ? parseInt(match[5], 10) : undefined,
  };
}

/**
 * 比较两个版本，返回:
 *   正数 => a > b，负数 => a < b，0 => 相等
 * 规则与 AppVersion.cs 一致：正式版 > beta 版
 */
export function compareVersions(a: ParsedVersion, b: ParsedVersion): number {
  const nums: (keyof ParsedVersion)[] = ['major', 'minor', 'patch'];
  for (const key of nums) {
    const av = (a[key] as number | undefined) ?? 0;
    const bv = (b[key] as number | undefined) ?? 0;
    if (av !== bv) return av - bv;
  }

  // build 段（第四位）
  const aBuild = a.build ?? -1;
  const bBuild = b.build ?? -1;
  if (aBuild !== bBuild) {
    if (a.build !== undefined && b.build === undefined) return 1;
    if (a.build === undefined && b.build !== undefined) return -1;
    return aBuild - bBuild;
  }

  // beta：正式版 (beta === undefined) > beta 版
  if (a.beta === undefined && b.beta === undefined) return 0;
  if (a.beta === undefined) return 1;
  if (b.beta === undefined) return -1;
  return a.beta - b.beta;
}

export interface UpdateCheckResult {
  hasUpdate: boolean;
  latestVersion: string;
  tagName: string;
  releaseUrl: string;
  /** APK 资源列表（含各 ABI 的下载 URL 和哈希值） */
  assets: ReleaseAssetInfo[];
  /** GitHub Release 更新说明 */
  releaseNotes?: string;
}

/**
 * 从 GitHub API 检查更新
 */
export async function checkForUpdate(
  currentVersionStr: string,
  includeBeta = false,
  abortSignal?: AbortSignal
): Promise<UpdateCheckResult> {
  console.log(
    `[UpdateCheck] 开始检查更新, 当前版本=${currentVersionStr}, 包含Beta=${includeBeta}`
  );

  const response = await fetch(GITHUB_RELEASES_API, {
    headers: { Accept: 'application/vnd.github+json' },
    signal: abortSignal,
  });

  if (!response.ok) {
    console.log(`[UpdateCheck] API 请求失败, status=${response.status}`);
    throw new Error(i18n.t('error.githubApiFailed', { status: response.status }));
  }

  const releases: Array<{
    tag_name: string;
    prerelease: boolean;
    draft: boolean;
    html_url: string;
    body?: string;
    assets: Array<{
      name: string;
      browser_download_url: string;
      digest?: string;
    }>;
  }> = await response.json();

  console.log(`[UpdateCheck] 获取到 ${releases.length} 个 Release`);

  const candidates = releases.filter((r) => {
    if (r.draft) return false;
    if (!includeBeta && r.prerelease) return false;
    return true;
  });

  const latest = candidates[0];
  if (!latest) {
    console.log('[UpdateCheck] 未找到可用的 Release');
    return {
      hasUpdate: false,
      latestVersion: currentVersionStr,
      tagName: '',
      releaseUrl: RELEASES_PAGE_URL,
      assets: [],
      releaseNotes: undefined,
    };
  }

  console.log(`[UpdateCheck] 最新版本=${latest.tag_name}`);

  const latestParsed = parseVersion(latest.tag_name);
  const currentParsed = parseVersion(currentVersionStr);

  const apkAssets: ReleaseAssetInfo[] = latest.assets
    .filter((a) => a.name.endsWith('.apk'))
    .map((a) => ({
      name: a.name,
      downloadUrl: a.browser_download_url,
      sha256: a.digest?.startsWith('sha256:') ? a.digest.slice(7).toLowerCase() : undefined,
    }));

  if (!currentParsed || !latestParsed) {
    console.log('[UpdateCheck] 版本解析失败，无法比较');
    return {
      hasUpdate: false,
      latestVersion: latest.tag_name,
      tagName: latest.tag_name,
      releaseUrl: latest.html_url,
      assets: apkAssets,
      releaseNotes: latest.body,
    };
  }

  const hasUpdate = compareVersions(latestParsed, currentParsed) > 0;
  console.log(`[UpdateCheck] 检查结果=${hasUpdate ? '有更新' : '已是最新'}`);

  return {
    hasUpdate,
    latestVersion: versionToStr(latestParsed),
    tagName: latest.tag_name,
    releaseUrl: latest.html_url,
    assets: apkAssets,
    releaseNotes: latest.body,
  };
}
