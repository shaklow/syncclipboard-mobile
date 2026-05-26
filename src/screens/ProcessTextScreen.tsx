/**
 * Process Text Screen
 * 处理来自 Android 文字选中菜单（PROCESS_TEXT）的上传请求。
 */

import React, { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { QuickLoadingPage } from '@/components/QuickLoadingPage';
import { useSettingsStore } from '@/stores/settingsStore';
import { createContentFromText } from '@/utils/clipboard/clipboardContentUtils';
import { setRemoteClipboard } from '@/services/sync/ClipboardSyncActions';

interface ProcessTextScreenProps {
  text: string;
  onComplete: () => void;
}

export const ProcessTextScreen: React.FC<ProcessTextScreenProps> = ({ text, onComplete }) => {
  const { t } = useTranslation();
  const activeServer = useSettingsStore((s) => s.getActiveServer());

  const task = useCallback(
    async (signal: AbortSignal) => {
      if (!activeServer) throw new Error(t('common.serverNotConfigured'));
      const content = await createContentFromText(text, { signal });
      await setRemoteClipboard(content, signal);
    },
    [text, activeServer, t]
  );

  return (
    <QuickLoadingPage
      task={task}
      loadingText={t('processText.uploadingText')}
      successText={t('processText.uploadSuccess')}
      failureText={t('processText.uploadFailed')}
      onComplete={onComplete}
      previewText={text.length > 50 ? `${text.slice(0, 50)}…` : text}
    />
  );
};
