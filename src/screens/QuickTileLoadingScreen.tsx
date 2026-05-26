import React, { useCallback, useMemo, useState } from 'react';
import { ToastAndroid, Linking } from 'react-native';
import { useTranslation } from 'react-i18next';
import { SyncDirection } from '@/types/sync';
import { ClipboardContent } from '@/types/clipboard';
import {
  setRemoteClipboard,
  setLocalClipboardFromRemote,
} from '@/services/sync/ClipboardSyncActions';
import { localClipboard } from '@/services/clipboard/LocalClipboard';
import { openFile, shareFile, saveFile, saveToGallery } from '@/utils/fileActions';
import { isTextInvalid } from '@/utils/index';
import { QuickLoadingPage, SuccessButtonConfig } from '@/components/QuickLoadingPage';
import type { ProgressInfo } from 'native-util';
import * as Clipboard from 'expo-clipboard';

interface QuickTileLoadingScreenProps {
  direction: SyncDirection;
  onLoadingComplete: () => void;
  overlayMode?: boolean;
}

export const QuickTileLoadingScreen: React.FC<QuickTileLoadingScreenProps> = ({
  direction,
  onLoadingComplete,
  overlayMode,
}) => {
  const isUpload = direction === SyncDirection.Upload;
  const { t } = useTranslation();

  // 用 state 存储下载的文件内容，触发重渲染以更新 successButtons prop
  const [fileContent, setFileContent] = useState<ClipboardContent | null>(null);
  const [progress, setProgress] = useState<ProgressInfo | null>(null);
  const [previewText, setPreviewText] = useState<string | undefined>(undefined);

  const task = useCallback(
    async (signal: AbortSignal) => {
      setFileContent(null);
      setProgress(null);
      setPreviewText(undefined);

      let content: ClipboardContent | null | undefined;

      if (isUpload) {
        content = await localClipboard.getClipboardContent();
        if (!content) {
          await new Promise((resolve) => setTimeout(resolve, 500));
          content = await localClipboard.getClipboardContent();
        }
        if (!content) throw new Error(t('quickTile.clipboardEmpty'));
        await setRemoteClipboard(content, signal, (info) => setProgress(info));
      } else {
        content = await setLocalClipboardFromRemote((info) => setProgress(info), signal);
      }

      // 只有文本类型才显示 Toast 提示
      if (content && content.type === 'Text' && !isTextInvalid(content.text)) {
        const preview = content.text.trim().replace(/\s+/g, ' ');
        const toastMessage = preview.length > 40 ? preview.slice(0, 40) + '…' : preview;
        ToastAndroid.show(toastMessage, ToastAndroid.SHORT);

        // 文本中包含 URL 时，存入 state 以显示操作按钮（仅下载时）
        if (!isUpload) {
          const urlRegex = /https?:\/\/[^\s<>"'()\]\[{}]+/i;
          const urlMatch = content.text.match(urlRegex);
          if (urlMatch) {
            setFileContent(content);
          }
        }
      }

      // 下载了非文本文件时，存入 state，触发重渲染更新 successButtons
      if (!isUpload && content && content.type !== 'Text' && content.fileUri) {
        setFileContent(content);
      }
    },
    [direction, isUpload, t]
  );

  // 检测文本中的 URL
  const textUrl = useMemo(() => {
    if (!fileContent || fileContent.type !== 'Text' || !fileContent.text) return null;
    const urlRegex = /https?:\/\/[^\s<>"'()\]\[{}]+/i;
    const match = fileContent.text.match(urlRegex);
    return match ? match[0] : null;
  }, [fileContent]);

  const successButtons: SuccessButtonConfig[] | undefined = fileContent
    ? fileContent.type === 'Text' && textUrl
      ? [
          {
            label: t('common.copy'),
            primary: true,
            onPress: async () => {
              try {
                await Clipboard.setStringAsync(fileContent.text!);
                ToastAndroid.show(t('quickTile.copied'), ToastAndroid.SHORT);
              } catch {}
            },
          },
          {
            label: t('clipboard.openLink'),
            primary: true,
            onPress: async () => {
              try {
                await Linking.openURL(textUrl);
              } catch {}
            },
          },
        ]
      : [
          {
            label: t('clipboard.open'),
            primary: true,
            onPress: async () => {
              try {
                await openFile(fileContent.fileUri!);
              } catch {}
            },
          },
          {
            label: t('clipboard.save'),
            primary: true,
            onPress: async () => {
              try {
                if (fileContent.type === 'Image') {
                  await saveToGallery(fileContent.fileUri!);
                  ToastAndroid.show(t('quickTile.savedToGallery'), ToastAndroid.SHORT);
                } else {
                  await saveFile(fileContent.fileUri!, fileContent.fileName);
                  ToastAndroid.show(t('quickTile.savedToDevice'), ToastAndroid.SHORT);
                }
              } catch (error) {
                console.error('[QuickTileLoadingScreen] Failed to save file:', error);
                if (error instanceof Error && error.message === 'Media library permission denied') {
                  ToastAndroid.show(t('quickTile.galleryPermissionRequired'), ToastAndroid.SHORT);
                  return;
                }
                ToastAndroid.show(t('quickTile.saveFailed'), ToastAndroid.SHORT);
              }
            },
          },
          {
            label: t('clipboard.share'),
            primary: true,
            onPress: async () => {
              try {
                await shareFile(fileContent.fileUri!, fileContent.fileName);
              } catch {}
            },
          },
        ]
    : undefined;

  return (
    <QuickLoadingPage
      task={task}
      loadingText={
        isUpload ? t('quickTile.uploadingClipboard') : t('quickTile.downloadingClipboard')
      }
      successText={isUpload ? t('quickTile.uploadSuccess') : t('quickTile.syncSuccess')}
      failureText={isUpload ? t('quickTile.uploadFailed') : t('quickTile.syncFailed')}
      onComplete={onLoadingComplete}
      successContent={fileContent ?? undefined}
      successButtons={successButtons}
      progress={progress}
      previewText={previewText}
      overlayMode={overlayMode}
    />
  );
};
