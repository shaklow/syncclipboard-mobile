import { Platform } from 'react-native';
import i18n from '@/i18n';

/**
 * 更新前台服务通知文本。
 * @param isUpload true 表示上传，false 表示下载
 * @param preview 内容预览文本
 */
export function updateForegroundNotification(isUpload: boolean, preview: string): void {
  if (Platform.OS !== 'android') return;
  const content = isUpload
    ? i18n.t('common.uploaded', { preview })
    : i18n.t('common.downloaded', { preview });
  import('foreground-service')
    .then((ForegroundService) => {
      ForegroundService.updateNotification(content);
    })
    .catch(() => {
      // foreground service module not available
    });
}
