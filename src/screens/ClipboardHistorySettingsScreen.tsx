/**
 * 剪贴板历史设置页面
 * 历史记录同步、最大保留条数、图片自动下载等设置
 */

import React, { useState, useEffect } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTheme } from '@/hooks/useTheme';
import { useTranslation } from 'react-i18next';
import { useSettingsStore } from '@/stores';
import { MessageToast } from '@/components';
import {
  SettingsSection,
  SettingSwitch,
  SettingInput,
  SettingDropdown,
} from '@/components/settings';
import { useMessageToast } from '@/hooks/useMessageToast';

export const ClipboardHistorySettingsScreen: React.FC = () => {
  const { theme } = useTheme();
  const { t } = useTranslation();
  const { config, updateConfig, setEnableHistorySync } = useSettingsStore();
  const { message, showMessage, handleMessageShown } = useMessageToast();

  const activeServerIndex = config?.activeServerIndex ?? -1;
  const servers = config?.servers || [];
  const activeServer = activeServerIndex >= 0 ? servers[activeServerIndex] : null;

  const [localHistorySyncEnabled, setLocalHistorySyncEnabled] = useState(
    config?.enableHistorySync ?? false
  );
  const [maxHistoryItemsInput, setMaxHistoryItemsInput] = useState(
    (config?.maxHistoryItems ?? 1000).toString()
  );
  const [localImageAutoDownload, setLocalImageAutoDownload] = useState<'wifi' | 'always' | 'off'>(
    config?.historyImageAutoDownload ?? 'wifi'
  );

  useEffect(() => {
    setLocalHistorySyncEnabled(config?.enableHistorySync ?? false);
  }, [config?.enableHistorySync]);

  useEffect(() => {
    setLocalImageAutoDownload(config?.historyImageAutoDownload ?? 'wifi');
  }, [config?.historyImageAutoDownload]);

  const imageAutoDownloadOptions: { label: string; value: 'wifi' | 'always' | 'off' }[] = [
    { label: t('settings.imageAutoDownloadWifi'), value: 'wifi' },
    { label: t('settings.imageAutoDownloadAlways'), value: 'always' },
    { label: t('settings.imageAutoDownloadOff'), value: 'off' },
  ];

  const handleToggleHistorySync = async (enabled: boolean) => {
    try {
      const { getHistorySyncService } = await import('@/services/history/HistorySyncService');
      const syncService = getHistorySyncService();
      syncService.cancelAll();

      if (!enabled) {
        await syncService.resetSyncCursor();
      }
    } catch {
      // ignore
    }

    setLocalHistorySyncEnabled(enabled);
    try {
      await setEnableHistorySync(enabled);

      if (!enabled) {
        await updateConfig({ needsHistoryReorganize: true });
      }

      showMessage(
        enabled ? t('settings.historySyncEnabled') : t('settings.historySyncDisabled'),
        'success'
      );
    } catch (error: unknown) {
      setLocalHistorySyncEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  const handleMaxHistoryItemsBlur = async () => {
    try {
      const maxItems = parseInt(maxHistoryItemsInput, 10);
      if (isNaN(maxItems) || maxItems < 10) {
        setMaxHistoryItemsInput((config?.maxHistoryItems ?? 1000).toString());
        showMessage(t('settings.numberMin10'), 'error');
        return;
      }
      await updateConfig({ maxHistoryItems: maxItems });
      showMessage(t('settings.maxHistoryItemsSet', { count: maxItems }), 'success');

      const { historyStorage } = await import('@/storage');
      historyStorage.setMaxHistorySize(maxItems);
    } catch (error: unknown) {
      setMaxHistoryItemsInput((config?.maxHistoryItems ?? 1000).toString());
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  const handleImageAutoDownloadChange = async (value: 'wifi' | 'always' | 'off') => {
    setLocalImageAutoDownload(value);
    try {
      await updateConfig({ historyImageAutoDownload: value });
    } catch {
      setLocalImageAutoDownload(config?.historyImageAutoDownload ?? 'wifi');
    }
  };

  return (
    <SafeAreaView
      style={[styles.container, { backgroundColor: theme.colors.background }]}
      edges={[]}
    >
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.scrollContent}>
        <SettingsSection title={t('settings.historySection')}>
          <SettingSwitch
            label={t('settings.historySync')}
            description={
              activeServer?.type !== 'syncclipboard'
                ? t('settings.historySyncNotSupported')
                : t('settings.historySyncDesc')
            }
            value={localHistorySyncEnabled && activeServer?.type === 'syncclipboard'}
            onChange={handleToggleHistorySync}
            disabled={activeServer?.type !== 'syncclipboard'}
          />

          <SettingInput
            label={t('settings.maxHistoryItems')}
            description={t('settings.maxHistoryItemsDesc')}
            value={maxHistoryItemsInput}
            onChangeText={setMaxHistoryItemsInput}
            onBlur={handleMaxHistoryItemsBlur}
            unit={t('settings.unitItem')}
            keyboardType="number-pad"
            placeholder="100"
          />

          <SettingDropdown
            label={t('settings.imageAutoDownload')}
            options={imageAutoDownloadOptions}
            value={localImageAutoDownload}
            onChange={handleImageAutoDownloadChange}
          />

          <SettingSwitch
            label={t('settings.showImageCopyButton')}
            description={t('settings.showImageCopyButtonDesc')}
            value={config?.showImageCopyButton ?? false}
            onChange={(enabled) => updateConfig({ showImageCopyButton: enabled })}
          />
        </SettingsSection>
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
});
