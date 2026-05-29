/**
 * Update Service
 * 检查 GitHub/Gitee 最新 Release 版本更新
 */

import i18n from '@/i18n';

const GITHUB_RELEASES_API = 'https://api.github.com/repos/Jeric-X/syncclipboard-mobile/releases';
const GITEE_RELEASES_API =
  'https://gitee.com/api/v5/repos/JericX/syncclipboard-mobile/releases?page=1&per_page=20&direction=desc';
const RELEASES_PAGE_URL = 'https://github.com/Jeric-X/syncclipboard-mobile/releases';
const GITEE_RELEASES_PAGE_URL = 'https://gitee.com/JericX/syncclipboard-mobile/releases';
const GITEE_DOWNLOAD_BASE = 'https://gitee.com/JericX/syncclipboard-mobile/releases/download';

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
  githubDownloadUrl: string;
  /** Gitee 直接下载 URL */
  giteeDownloadUrl: string;
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
    // 有 build 段的字段数更多，视为更大
    if (a.build !== undefined && b.build === undefined) return 1;
    if (a.build === undefined && b.build !== undefined) return -1;
    return aBuild - bBuild;
  }

  // beta：正式版 (beta === undefined) > beta 版
  if (a.beta === undefined && b.beta === undefined) return 0;
  if (a.beta === undefined) return 1; // a 是正式版，更大
  if (b.beta === undefined) return -1; // b 是正式版，更大
  return a.beta - b.beta;
}

export interface UpdateCheckResult {
  hasUpdate: boolean;
  latestVersion: string;
  tagName: string;
  releaseUrl: string;
  giteeReleaseUrl: string;
  /** APK 资源列表（含各 ABI 的下载 URL 和哈希值） */
  assets: ReleaseAssetInfo[];
  /** GitHub Release 更新说明 */
  releaseNotes?: string;
}

/**
 * 从 GitHub API 获取最新版本
 */
async function checkForUpdateFromGitHub(
  currentVersionStr: string,
  includeBeta: boolean
): Promise<UpdateCheckResult> {
  console.log(
    `[UpdateCheck] GitHub: 开始检查更新, 当前版本=${currentVersionStr}, 包含Beta=${includeBeta}`
  );

  const response = await fetch(GITHUB_RELEASES_API, {
    headers: { Accept: 'application/vnd.github+json' },
  });

  if (!response.ok) {
    console.log(`[UpdateCheck] GitHub: API 请求失败, status=${response.status}`);
    throw new Error(i18n.t('error.githubApiFailed', { status: response.status }));
  }

  console.log('[UpdateCheck] GitHub: API 请求成功');

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

  console.log(`[UpdateCheck] GitHub: 获取到 ${releases.length} 个 Release`);
  console.log(`[UpdateCheck] GitHub: Release 列表: ${releases.map((r) => r.tag_name).join(', ')}`);

  const candidates = releases.filter((r) => {
    if (r.draft) return false;
    if (!includeBeta && r.prerelease) return false;
    return true;
  });

  const latest = candidates[0];
  if (!latest) {
    console.log('[UpdateCheck] GitHub: 未找到可用的 Release');
    return {
      hasUpdate: false,
      latestVersion: currentVersionStr,
      tagName: '',
      releaseUrl: RELEASES_PAGE_URL,
      giteeReleaseUrl: GITEE_RELEASES_PAGE_URL,
      assets: [],
      releaseNotes: undefined,
    };
  }

  console.log(`[UpdateCheck] GitHub: 最新版本=${latest.tag_name}`);

  const latestParsed = parseVersion(latest.tag_name);
  const currentParsed = parseVersion(currentVersionStr);

  const apkAssets: ReleaseAssetInfo[] = latest.assets
    .filter((a) => a.name.endsWith('.apk'))
    .map((a) => ({
      name: a.name,
      githubDownloadUrl: a.browser_download_url,
      giteeDownloadUrl: `${GITEE_DOWNLOAD_BASE}/${latest.tag_name}/${a.name}`,
      sha256: a.digest?.startsWith('sha256:') ? a.digest.slice(7).toLowerCase() : undefined,
    }));

  console.log(`[UpdateCheck] GitHub: 找到 ${apkAssets.length} 个 APK 资源`);

  if (!currentParsed || !latestParsed) {
    console.log('[UpdateCheck] GitHub: 版本解析失败，无法比较');
    return {
      hasUpdate: false,
      latestVersion: latest.tag_name,
      tagName: latest.tag_name,
      releaseUrl: latest.html_url,
      giteeReleaseUrl: `https://gitee.com/JericX/syncclipboard-mobile/releases/tag/${latest.tag_name}`,
      assets: apkAssets,
      releaseNotes: latest.body,
    };
  }

  const hasUpdate = compareVersions(latestParsed, currentParsed) > 0;
  console.log(`[UpdateCheck] GitHub: 检查结果=${hasUpdate ? '有更新' : '已是最新'}`);

  return {
    hasUpdate,
    latestVersion: versionToStr(latestParsed),
    tagName: latest.tag_name,
    releaseUrl: latest.html_url,
    giteeReleaseUrl: `https://gitee.com/JericX/syncclipboard-mobile/releases/tag/${latest.tag_name}`,
    assets: apkAssets,
    releaseNotes: latest.body,
  };
}

/**
 * 从 Gitee API 获取最新版本
 */
async function checkForUpdateFromGitee(
  currentVersionStr: string,
  includeBeta: boolean
): Promise<UpdateCheckResult> {
  console.log(
    `[UpdateCheck] Gitee: 开始检查更新, 当前版本=${currentVersionStr}, 包含Beta=${includeBeta}`
  );

  const response = await fetch(GITEE_RELEASES_API);

  if (!response.ok) {
    console.log(`[UpdateCheck] Gitee: API 请求失败, status=${response.status}`);
    throw new Error(i18n.t('error.giteeApiFailed', { status: response.status }));
  }

  console.log('[UpdateCheck] Gitee: API 请求成功');

  const releases: Array<{
    tag_name: string;
    name?: string;
    prerelease: boolean;
    draft: boolean;
    html_url: string;
    body?: string;
    assets: Array<{
      name: string;
      browser_download_url: string;
    }>;
  }> = await response.json();

  console.log(`[UpdateCheck] Gitee: 获取到 ${releases.length} 个 Release`);
  console.log(`[UpdateCheck] Gitee: Release 列表: ${releases.map((r) => r.tag_name).join(', ')}`);

  const candidates = releases.filter((r) => {
    if (r.draft) return false;
    if (!includeBeta && r.prerelease) return false;
    if (r.name && r.name.startsWith('WIP:')) {
      console.log(`[UpdateCheck] Gitee: 跳过 WIP 版本: ${r.tag_name} (${r.name})`);
      return false;
    }
    return true;
  });

  if (candidates.length === 0) {
    console.log('[UpdateCheck] Gitee: 未找到可用的 Release');
    return {
      hasUpdate: false,
      latestVersion: currentVersionStr,
      tagName: '',
      releaseUrl: RELEASES_PAGE_URL,
      giteeReleaseUrl: GITEE_RELEASES_PAGE_URL,
      assets: [],
      releaseNotes: undefined,
    };
  }

  const latest = candidates[0];

  console.log(`[UpdateCheck] Gitee: 最新版本=${latest.tag_name}`);

  const latestParsed = parseVersion(latest.tag_name);
  const currentParsed = parseVersion(currentVersionStr);

  const apkAssets: ReleaseAssetInfo[] = latest.assets
    .filter((a) => a.name.endsWith('.apk'))
    .map((a) => ({
      name: a.name,
      githubDownloadUrl: `https://github.com/Jeric-X/syncclipboard-mobile/releases/download/${latest.tag_name}/${a.name}`,
      giteeDownloadUrl: a.browser_download_url,
      sha256: undefined,
    }));

  console.log(`[UpdateCheck] Gitee: 找到 ${apkAssets.length} 个 APK 资源`);

  if (!currentParsed || !latestParsed) {
    console.log('[UpdateCheck] Gitee: 版本解析失败，无法比较');
    return {
      hasUpdate: false,
      latestVersion: latest.tag_name,
      tagName: latest.tag_name,
      releaseUrl: `https://github.com/Jeric-X/syncclipboard-mobile/releases/tag/${latest.tag_name}`,
      giteeReleaseUrl: latest.html_url,
      assets: apkAssets,
      releaseNotes: latest.body,
    };
  }

  const hasUpdate = compareVersions(latestParsed, currentParsed) > 0;
  console.log(`[UpdateCheck] Gitee: 检查结果=${hasUpdate ? '有更新' : '已是最新'}`);

  return {
    hasUpdate,
    latestVersion: versionToStr(latestParsed),
    tagName: latest.tag_name,
    releaseUrl: `https://github.com/Jeric-X/syncclipboard-mobile/releases/tag/${latest.tag_name}`,
    giteeReleaseUrl: latest.html_url,
    assets: apkAssets,
    releaseNotes: latest.body,
  };
}

/**
 * 检查更新
 * @param currentVersionStr 当前版本字符串
 * @param includeBeta 是否包含 beta 版本，默认 false
 * @param channel 更新通道，默认 'github'
 */
export async function checkForUpdate(
  currentVersionStr: string,
  includeBeta = false,
  channel: 'github' | 'gitee' = 'github'
): Promise<UpdateCheckResult> {
  if (channel === 'github') {
    return checkForUpdateFromGitHub(currentVersionStr, includeBeta);
  }
  return checkForUpdateFromGitee(currentVersionStr, includeBeta);
}
