/**
 * 关于页面
 * 版本信息、更新检查、开源信息
 */

import React, { useState, useEffect, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView, Alert, Linking } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useTheme } from '@/hooks/useTheme';
import { useTranslation } from 'react-i18next';
import { useSettingsStore } from '@/stores';
import { useMessageToast } from '@/hooks/useMessageToast';
import { MessageToast } from '@/components';
import { SettingsSection, SettingAction, SettingSwitch, SettingItem } from '@/components/settings';
import {
  checkForUpdate,
  getPreferredAbi,
  findAssetForAbi,
  checkApkCache,
  downloadApk,
  installApk,
  cleanOldApkCache,
} from '@/utils';
import type { ReleaseAssetInfo } from '@/utils';
import { APP_VERSION } from '@/constants';

export const AboutScreen: React.FC = () => {
  const { theme } = useTheme();
  const { t } = useTranslation();
  const { config, isLoaded, setAutoCheckUpdate, setLastUpdateCheckDate, setUpdateToBeta } =
    useSettingsStore();
  const { message, showMessage, handleMessageShown } = useMessageToast();

  const appVersion = APP_VERSION;

  const [localAutoCheckUpdateEnabled, setLocalAutoCheckUpdateEnabled] = useState(
    config?.autoCheckUpdate ?? true
  );
  const [localUpdateToBetaEnabled, setLocalUpdateToBetaEnabled] = useState(
    config?.updateToBeta ?? false
  );

  // 更新检查状态
  const [isCheckingUpdate, setIsCheckingUpdate] = useState(false);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [latestVersion, setLatestVersion] = useState<string | null>(null);
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const downloadAbortRef = useRef<AbortController | null>(null);
  const updateCheckAbortRef = useRef<AbortController | null>(null);
  const latestAssetsRef = useRef<ReleaseAssetInfo[]>([]);
  const latestTagRef = useRef<string>('');
  const releaseNotesRef = useRef<string | undefined>(undefined);

  useEffect(() => {
    setLocalAutoCheckUpdateEnabled(config?.autoCheckUpdate ?? true);
  }, [config?.autoCheckUpdate]);

  useEffect(() => {
    setLocalUpdateToBetaEnabled(config?.updateToBeta ?? false);
  }, [config?.updateToBeta]);

  // 自动检查更新（每天一次）
  useEffect(() => {
    if (!isLoaded) return;
    if (!(config?.autoCheckUpdate ?? true)) return;
    const today = new Date().toISOString().slice(0, 10);
    if (
      !(config?.debugUpdateCheckNoLimit ?? false) &&
      (config?.lastUpdateCheckDate ?? '') === today
    )
      return;
    runUpdateCheck(false, config?.updateToBeta ?? false);
  }, [isLoaded]);

  const runUpdateCheck = async (showNoUpdateToast: boolean, includeBeta?: boolean) => {
    if (updateCheckAbortRef.current) {
      updateCheckAbortRef.current.abort();
    }
    updateCheckAbortRef.current = new AbortController();

    setIsCheckingUpdate(true);
    try {
      const today = new Date().toISOString().slice(0, 10);
      await setLastUpdateCheckDate(today);
      const useBeta = includeBeta ?? config?.updateToBeta ?? false;
      const result = await checkForUpdate(appVersion, useBeta, updateCheckAbortRef.current.signal);
      if (result.hasUpdate) {
        setUpdateAvailable(true);
        setLatestVersion(result.latestVersion);
        latestAssetsRef.current = result.assets;
        latestTagRef.current = result.tagName;
        releaseNotesRef.current = result.releaseNotes;
        showUpdateDialog(result.latestVersion, result.assets, result.releaseNotes);
      } else {
        setUpdateAvailable(false);
        setLatestVersion(null);
        if (showNoUpdateToast) {
          showMessage(t('settings.alreadyLatest'), 'success');
        }
      }
      cleanOldApkCache(appVersion);
    } catch (e) {
      if (e instanceof Error && e.name === 'AbortError') return;
      if (showNoUpdateToast) {
        showMessage(t('settings.checkUpdateFailed'), 'error');
      }
    } finally {
      setIsCheckingUpdate(false);
      updateCheckAbortRef.current = null;
    }
  };

  const handleCancelUpdateCheck = () => {
    if (updateCheckAbortRef.current) {
      updateCheckAbortRef.current.abort();
      setIsCheckingUpdate(false);
      updateCheckAbortRef.current = null;
      showMessage(t('settings.updateCheckCancelled'), 'info');
    }
  };

  const handleUpdateButtonPress = async (
    version: string,
    assets: ReleaseAssetInfo[],
    releaseNotes?: string
  ) => {
    if (isDownloading) return;

    let preferredAbi = 'universal';
    try {
      const { getSupportedAbis } = await import('native-util');
      const abis = getSupportedAbis();
      preferredAbi = getPreferredAbi(abis);
    } catch (e) {
      console.warn('[UpdateDownload] getSupportedAbis failed:', e);
    }

    const asset = findAssetForAbi(assets, preferredAbi as Parameters<typeof findAssetForAbi>[1]);
    if (!asset) {
      showUpdateDialog(version, assets, releaseNotes);
      return;
    }

    const cached = await checkApkCache(version, asset);
    if (cached) {
      await installApk(cached);
    } else {
      showUpdateDialog(version, assets, releaseNotes);
    }
  };

  const showUpdateDialog = (version: string, assets: ReleaseAssetInfo[], releaseNotes?: string) => {
    const body = releaseNotes
      ? t('settings.newVersionMessageWithNotes', {
          newVersion: version,
          currentVersion: appVersion,
          notes: releaseNotes,
        })
      : t('settings.newVersionMessageNoNotes', {
          newVersion: version,
          currentVersion: appVersion,
        });
    Alert.alert(t('settings.newVersionTitle'), body, [
      { text: t('common.later'), style: 'cancel' },
      { text: t('settings.updateNow'), onPress: () => handleDownloadApk(version, assets) },
    ]);
  };

  const handleDownloadApk = async (version: string, assets: ReleaseAssetInfo[]) => {
    if (isDownloading) return;

    let preferredAbi = 'universal';
    try {
      const { getSupportedAbis } = await import('native-util');
      const abis = getSupportedAbis();
      preferredAbi = getPreferredAbi(abis);
    } catch (e) {
      console.warn('[UpdateDownload] getSupportedAbis failed:', e);
    }

    const asset = findAssetForAbi(assets, preferredAbi as Parameters<typeof findAssetForAbi>[1]);
    if (!asset) {
      showMessage(t('settings.noSuitableApk'), 'error');
      return;
    }

    setIsDownloading(true);
    setDownloadProgress(0);

    const abortController = new AbortController();
    downloadAbortRef.current = abortController;

    try {
      const cached = await checkApkCache(version, asset);
      if (cached) {
        await installApk(cached);
        return;
      }

      const fileUri = await downloadApk({
        asset,
        version,
        signal: abortController.signal,
        onProgress: (info) => {
          setDownloadProgress(info.progress);
        },
      });

      setUpdateAvailable(false);
      setLatestVersion(null);
      await installApk(fileUri);
    } catch (err) {
      if (err instanceof Error && err.name === 'AbortError') {
        showMessage(t('settings.downloadCanceled'), 'info');
      } else {
        showMessage(err instanceof Error ? err.message : t('common.operationFailed'), 'error');
      }
    } finally {
      setIsDownloading(false);
      setDownloadProgress(0);
      downloadAbortRef.current = null;
    }
  };

  const handleCancelDownload = () => {
    Alert.alert(t('settings.cancelDownloadTitle'), t('settings.cancelDownloadMessage'), [
      { text: t('settings.continueDownload'), style: 'cancel' },
      {
        text: t('settings.cancelDownloadTitle'),
        style: 'destructive',
        onPress: () => downloadAbortRef.current?.abort(),
      },
    ]);
  };

  const handleToggleAutoCheckUpdate = async (enabled: boolean) => {
    setLocalAutoCheckUpdateEnabled(enabled);
    try {
      await setAutoCheckUpdate(enabled);
    } catch (error: unknown) {
      setLocalAutoCheckUpdateEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  const handleToggleUpdateToBeta = async (enabled: boolean) => {
    setLocalUpdateToBetaEnabled(enabled);
    try {
      await setUpdateToBeta(enabled);
    } catch (error: unknown) {
      setLocalUpdateToBetaEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  const getCheckUpdateButtonText = () => {
    if (isCheckingUpdate) return t('settings.checkingUpdate');
    if (isDownloading)
      return t('settings.downloadingUpdate', { percent: Math.round(downloadProgress * 100) });
    if (updateAvailable) return t('settings.updateAvailable', { version: latestVersion });
    return t('settings.checkUpdate');
  };

  const handleCheckUpdatePress = () => {
    if (isCheckingUpdate) {
      handleCancelUpdateCheck();
    } else if (isDownloading) {
      handleCancelDownload();
    } else if (updateAvailable) {
      handleUpdateButtonPress(
        latestVersion ?? '',
        latestAssetsRef.current,
        releaseNotesRef.current
      );
    } else {
      runUpdateCheck(true, localUpdateToBetaEnabled);
    }
  };

  return (
    <SafeAreaView
      style={[styles.container, { backgroundColor: theme.colors.background }]}
      edges={[]}
    >
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.scrollContent}>
        {/* 版本信息 */}
        <SettingsSection title={t('settings.aboutSection')}>
          <SettingAction
            label={`${t('settings.version')} ${appVersion}`}
            buttonText={getCheckUpdateButtonText()}
            buttonStyle={isDownloading || updateAvailable ? 'primary' : 'secondary'}
            onPress={handleCheckUpdatePress}
          />

          <SettingSwitch
            label={t('settings.autoCheckUpdate')}
            value={localAutoCheckUpdateEnabled}
            onChange={handleToggleAutoCheckUpdate}
          />

          <SettingSwitch
            label={t('settings.updateToBeta')}
            value={localUpdateToBetaEnabled}
            onChange={handleToggleUpdateToBeta}
          />
        </SettingsSection>

        {/* 开源信息 */}
        <SettingsSection title={t('settings.openSource')}>
          <SettingItem description="SyncClipboard is open source software">
            <TouchableOpacity
              onPress={() => Linking.openURL('https://github.com/shaklow/syncclipboard-mobile')}
              hitSlop={{ top: 10, right: 10, bottom: 10, left: 10 }}
            >
              <MaterialCommunityIcons name="github" size={24} color={theme.colors.textTertiary} />
            </TouchableOpacity>
          </SettingItem>
        </SettingsSection>

        {/* 应用图标和描述 */}
        <View style={styles.appInfo}>
          <Text style={[styles.appName, { color: theme.colors.text }]}>SyncClipboard</Text>
          <Text style={[styles.appDesc, { color: theme.colors.textTertiary }]}>
            {t('settings.openSource')}
          </Text>
        </View>
      </ScrollView>

      <MessageToast message={message} onMessageShown={handleMessageShown} />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    paddingTop: 20,
    paddingBottom: 40,
  },
  appInfo: {
    alignItems: 'center',
    paddingVertical: 32,
    paddingHorizontal: 16,
  },
  appName: {
    fontSize: 20,
    fontWeight: '600',
    marginBottom: 8,
  },
  appDesc: {
    fontSize: 14,
    textAlign: 'center',
    lineHeight: 20,
  },
});
