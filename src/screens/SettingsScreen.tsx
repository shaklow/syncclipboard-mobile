/**
 * 设置页面
 * 提供主题切换功能、服务器配置、多用户切换
 */

import React, { useEffect, useState, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Switch,
  TextInput,
  Alert,
  Linking,
  Platform,
  Modal,
} from 'react-native';
import { APP_VERSION } from '@/constants';
import { Paths, Directory } from 'expo-file-system';
import { calculateDirectorySize, clearDirectory } from '@/utils/fileStorage';
import { CLIPBOARD_TEMP_DIR } from '@/utils/fileStorage';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTheme } from '@/hooks/useTheme';
import type { ThemeMode } from '@/theme';
import { useSettingsStore } from '@/stores';
import { ServerConfigModal, ServerListItem, MessageToast } from '@/components';
import { ServerConfig } from '@/types/api';
import { useMessageToast } from '@/hooks/useMessageToast';
import {
  shortcut,
  checkForUpdate,
  calculateLogSize,
  clearLogs,
  saveLogsToFile,
  setLogLevel as setLoggerLogLevel,
  type LogLevel,
  getPreferredAbi,
  findAssetForAbi,
  checkApkCache,
  downloadApk,
  installApk,
  cleanOldApkCache,
  type ReleaseAssetInfo,
  type ApkSource,
  formatFileSize,
} from '@/utils';
import { Plus, RefreshCw, Check, ChevronDown, ChevronUp } from 'react-native-feather';
import { hasOverlayPermission, requestOverlayPermission } from 'clipboard-overlay';
import {
  isShizukuAvailable,
  hasShizukuPermission,
  requestShizukuPermission,
} from 'shizuku-clipboard';
import { extractVerificationCode } from '@/tasks/SmsUploadTask';
import { useTranslation } from 'react-i18next';
import { useI18n } from '@/hooks/useI18n';
import type { Language } from '@/i18n';

export const SettingsScreen = () => {
  const { theme, themeMode, setThemeMode } = useTheme();
  const { t } = useTranslation();
  const { language: currentLanguage, systemLanguage, setLanguage } = useI18n();
  const {
    config,
    isLoaded,
    loadConfig,
    addServer,
    updateServer,
    deleteServer,
    setActiveServer,
    setAutoSync,
    setAutoDownloadMaxSize,
    updateConfig,
    setAutoCheckUpdate,
    setLastUpdateCheckDate,
    setUpdateToBeta,
    setEnableHistorySync,
    setLogLevel,
    setRemotePollingInterval,
    setLocalPollingInterval,
    setEnableBackgroundDownload,
    setEnableBackgroundUpload,
    setEnableClipboardOverlay,
    setEnableBackgroundTasks,
    setEnableSmsForwarding,
    setEnableShizukuClipboard,
    isTempDisabledBackgroundTasks,
    setTempDisabledBackgroundTasks,
  } = useSettingsStore();

  const [showServerModal, setShowServerModal] = useState(false);
  const [editingServerIndex, setEditingServerIndex] = useState<number | null>(null);
  const [serversCollapsed, setServersCollapsed] = useState(true);
  const { message, showMessage, handleMessageShown } = useMessageToast();

  // 本地状态用于跟踪Switch的当前值，避免闪烁
  const [localAutoSyncEnabled, setLocalAutoSyncEnabled] = useState(config?.autoSync ?? false);
  const [localDebugModeEnabled, setLocalDebugModeEnabled] = useState(config?.debugMode ?? false);
  const [localAutoCheckUpdateEnabled, setLocalAutoCheckUpdateEnabled] = useState(
    config?.autoCheckUpdate ?? true
  );
  const [localUpdateToBetaEnabled, setLocalUpdateToBetaEnabled] = useState(
    config?.updateToBeta ?? false
  );
  const [localHistorySyncEnabled, setLocalHistorySyncEnabled] = useState(
    config?.enableHistorySync ?? false
  );
  const [localBackgroundDownloadEnabled, setLocalBackgroundDownloadEnabled] = useState(
    config?.enableBackgroundDownload ?? false
  );
  const [localBackgroundUploadEnabled, setLocalBackgroundUploadEnabled] = useState(
    config?.enableBackgroundUpload ?? false
  );
  const [localBackgroundTasksEnabled, setLocalBackgroundTasksEnabled] = useState(
    (config?.enableBackgroundTasks ?? false) && !isTempDisabledBackgroundTasks
  );
  const [localClipboardOverlayEnabled, setLocalClipboardOverlayEnabled] = useState(
    config?.enableClipboardOverlay ?? false
  );
  const [localShizukuClipboardEnabled, setLocalShizukuClipboardEnabled] = useState(
    config?.enableShizukuClipboard ?? false
  );
  const [localSmsForwardingEnabled, setLocalSmsForwardingEnabled] = useState(
    config?.enableSmsForwarding ?? false
  );
  const [localForegroundNotification, setLocalForegroundNotification] = useState(
    config?.enableForegroundNotification ?? true
  );
  const [localSyncToastEnabled, setLocalSyncToastEnabled] = useState(
    config?.syncToastEnabled ?? true
  );
  const [localDebugOverlayVisible, setLocalDebugOverlayVisible] = useState(
    config?.debugOverlayVisible ?? false
  );
  const [localDebugUrlScheme, setLocalDebugUrlScheme] = useState(config?.debugUrlScheme ?? false);
  const [localDebugUpdateCheckNoLimit, setLocalDebugUpdateCheckNoLimit] = useState(
    config?.debugUpdateCheckNoLimit ?? false
  );
  const [showSmsTestModal, setShowSmsTestModal] = useState(false);
  const [smsTestInput, setSmsTestInput] = useState('');
  const [showLogLevelMenu, setShowLogLevelMenu] = useState(false);
  const [showThemeMenu, setShowThemeMenu] = useState(false);
  const [showLanguageMenu, setShowLanguageMenu] = useState(false);
  const [localHideFromRecents, setLocalHideFromRecents] = useState(
    config?.hideFromRecents ?? false
  );
  const [showStatsModal, setShowStatsModal] = useState(false);
  const [showImageAutoDownloadMenu, setShowImageAutoDownloadMenu] = useState(false);
  const [localImageAutoDownload, setLocalImageAutoDownload] = useState<'wifi' | 'always' | 'off'>(
    config?.historyImageAutoDownload ?? 'wifi'
  );
  const [statsText, setStatsText] = useState('');

  // 更新检查状态
  const [isCheckingUpdate, setIsCheckingUpdate] = useState(false);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [latestVersion, setLatestVersion] = useState<string | null>(null);
  // APK 下载状态
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const downloadAbortRef = useRef<AbortController | null>(null);
  const latestAssetsRef = useRef<ReleaseAssetInfo[]>([]);
  const latestTagRef = useRef<string>('');
  const releaseNotesRef = useRef<string | undefined>(undefined);

  const appVersion = APP_VERSION;

  // 加载配置
  useEffect(() => {
    if (!isLoaded) {
      loadConfig();
    }
  }, [isLoaded, loadConfig]);

  // 当配置中的autoSync值变化时，更新本地状态
  useEffect(() => {
    setLocalAutoSyncEnabled(config?.autoSync ?? false);
  }, [config?.autoSync]);

  // 当配置中的debugMode值变化时，更新本地状态
  useEffect(() => {
    setLocalDebugModeEnabled(config?.debugMode ?? false);
  }, [config?.debugMode]);

  // 当配置中的autoCheckUpdate值变化时，更新本地状态
  useEffect(() => {
    setLocalAutoCheckUpdateEnabled(config?.autoCheckUpdate ?? true);
  }, [config?.autoCheckUpdate]);

  useEffect(() => {
    setLocalUpdateToBetaEnabled(config?.updateToBeta ?? false);
  }, [config?.updateToBeta]);

  useEffect(() => {
    setLocalHistorySyncEnabled(config?.enableHistorySync ?? true);
  }, [config?.enableHistorySync]);

  useEffect(() => {
    setLocalBackgroundDownloadEnabled(config?.enableBackgroundDownload ?? false);
  }, [config?.enableBackgroundDownload]);

  useEffect(() => {
    setLocalBackgroundUploadEnabled(config?.enableBackgroundUpload ?? false);
  }, [config?.enableBackgroundUpload]);

  useEffect(() => {
    setLocalBackgroundTasksEnabled(
      (config?.enableBackgroundTasks ?? false) && !isTempDisabledBackgroundTasks
    );
  }, [config?.enableBackgroundTasks, isTempDisabledBackgroundTasks]);

  useEffect(() => {
    setLocalClipboardOverlayEnabled(config?.enableClipboardOverlay ?? false);
  }, [config?.enableClipboardOverlay]);

  useEffect(() => {
    setLocalShizukuClipboardEnabled(config?.enableShizukuClipboard ?? false);
  }, [config?.enableShizukuClipboard]);

  useEffect(() => {
    setLocalSmsForwardingEnabled(config?.enableSmsForwarding ?? false);
  }, [config?.enableSmsForwarding]);

  useEffect(() => {
    setLocalForegroundNotification(config?.enableForegroundNotification ?? true);
  }, [config?.enableForegroundNotification]);

  useEffect(() => {
    setLocalSyncToastEnabled(config?.syncToastEnabled ?? true);
  }, [config?.syncToastEnabled]);

  useEffect(() => {
    setLocalHideFromRecents(config?.hideFromRecents ?? false);
  }, [config?.hideFromRecents]);

  useEffect(() => {
    setLocalImageAutoDownload(config?.historyImageAutoDownload ?? 'wifi');
  }, [config?.historyImageAutoDownload]);

  // 计算存储大小
  useEffect(() => {
    calculateStorageSizes();
  }, []);

  // 刷新权限状态
  const refreshPermissions = async () => {
    if (Platform.OS !== 'android') return;
    setIsRefreshingPermissions(true);
    try {
      const { PermissionsAndroid } = require('react-native');
      const [notif, sms] = await Promise.all([
        PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS),
        PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECEIVE_SMS),
      ]);
      setPermNotification(notif);
      setPermOverlay(hasOverlayPermission());
      setPermSms(sms);
      const { isIgnoringBatteryOptimizations } = await import('native-util');
      setPermBattery(isIgnoringBatteryOptimizations());
      const shizukuUp = isShizukuAvailable();
      setShizukuAvailable(shizukuUp);
      setPermShizuku(shizukuUp && hasShizukuPermission());
    } catch (e) {
      console.warn('[Settings] Failed to check permissions:', e);
    } finally {
      setIsRefreshingPermissions(false);
    }
  };

  useEffect(() => {
    refreshPermissions();
  }, []);

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

  const themeOptions: { label: string; value: ThemeMode }[] = [
    { label: t('settings.themeAuto'), value: 'auto' },
    { label: t('settings.themeLight'), value: 'light' },
    { label: t('settings.themeDark'), value: 'dark' },
  ];

  // 语言选项：zh/en label 用 native 名称（中文/English），auto label 跟随 UI 语言并标注当前系统语言
  const langNativeNames: Record<string, string> = {
    zh: t('settings.languageZh'),
    en: t('settings.languageEn'),
  };
  const languageOptions: { label: string; value: Language }[] = [
    {
      label: `${t('settings.languageAuto')}（${langNativeNames[systemLanguage] ?? systemLanguage}）`,
      value: 'auto',
    },
    { label: langNativeNames['zh'] ?? t('settings.languageZh'), value: 'zh' as Language },
    { label: 'English', value: 'en' },
  ];

  const imageAutoDownloadOptions: { label: string; value: 'wifi' | 'always' | 'off' }[] = [
    { label: t('settings.imageAutoDownloadWifi'), value: 'wifi' },
    { label: t('settings.imageAutoDownloadAlways'), value: 'always' },
    { label: t('settings.imageAutoDownloadOff'), value: 'off' },
  ];

  // 获取服务器列表
  const servers = config?.servers || [];
  const activeServerIndex = config?.activeServerIndex ?? -1;
  const activeServer = activeServerIndex >= 0 ? servers[activeServerIndex] : null;
  const autoDownloadMaxSizeMB = Math.round(
    (config?.autoDownloadMaxSize ?? 5 * 1024 * 1024) / (1024 * 1024)
  );

  // 本地 state 用于输入框
  const [maxSizeInput, setMaxSizeInput] = useState(autoDownloadMaxSizeMB.toString());
  const [maxHistoryItemsInput, setMaxHistoryItemsInput] = useState(
    (config?.maxHistoryItems ?? 1000).toString()
  );
  const [remotePollingInput, setRemotePollingInput] = useState(
    ((config?.remotePollingInterval ?? 3000) / 1000).toString()
  );
  const [localPollingInput, setLocalPollingInput] = useState(
    ((config?.localPollingInterval ?? 1000) / 1000).toString()
  );

  // 存储大小状态
  const [cacheSize, setCacheSize] = useState<number>(0);
  const [historySize, setHistorySize] = useState<number>(0);
  const [logSize, setLogSize] = useState<number>(0);
  const [isCalculating, setIsCalculating] = useState<boolean>(true);
  const [isExportingLogs, setIsExportingLogs] = useState<boolean>(false);
  const exportLogsAbortControllerRef = useRef<AbortController | null>(null);

  // 权限状态
  const [permNotification, setPermNotification] = useState<boolean>(false);
  const [permOverlay, setPermOverlay] = useState<boolean>(false);
  const [permSms, setPermSms] = useState<boolean>(false);
  const [permBattery, setPermBattery] = useState<boolean>(false);
  const [permShizuku, setPermShizuku] = useState<boolean>(false);
  const [shizukuAvailable, setShizukuAvailable] = useState<boolean>(false);
  const [isRefreshingPermissions, setIsRefreshingPermissions] = useState<boolean>(false);
  const hasBatteryOptRequested = useRef<boolean>(false);

  // 目录对象
  const cacheDir = CLIPBOARD_TEMP_DIR;
  const historyDir = new Directory(Paths.document, 'clipboards', 'history');

  // 调试日志
  useEffect(() => {
    try {
      console.log('Cache directory:', cacheDir.uri);
      console.log('History directory:', historyDir.uri);
      console.log('Cache directory exists:', cacheDir.exists);
      console.log('History directory exists:', historyDir.exists);
    } catch (error) {
      console.error('Error checking directories:', error);
    }
  }, []);

  // 处理添加服务器
  const handleAddServer = () => {
    setEditingServerIndex(null);
    setShowServerModal(true);
  };

  // 处理编辑服务器
  const handleEditServer = (index: number) => {
    setEditingServerIndex(index);
    setShowServerModal(true);
  };

  // 处理保存服务器
  const handleSaveServer = async (serverConfig: ServerConfig) => {
    try {
      if (editingServerIndex !== null) {
        await updateServer(editingServerIndex, serverConfig);
        showMessage(t('settings.serverUpdated'), 'success');
      } else {
        await addServer(serverConfig);
        showMessage(t('settings.serverAdded'), 'success');
      }
    } catch (error: unknown) {
      showMessage(error instanceof Error ? error.message : t('common.operationFailed'), 'error');
    }
  };

  // 处理删除服务器
  const handleDeleteServer = async (index: number) => {
    try {
      await deleteServer(index);
      showMessage(t('settings.serverDeleted'), 'success');
    } catch (error: unknown) {
      showMessage(error instanceof Error ? error.message : t('common.operationFailed'), 'error');
    }
  };

  // 处理切换激活服务器
  const handleSetActiveServer = async (index: number) => {
    if (index === activeServerIndex) {
      if (servers.length > 1) {
        setServersCollapsed(true);
      }
      return;
    }

    if (servers.length > 1) {
      setServersCollapsed(true);
    }

    try {
      const { getHistorySyncService } = await import('@/services/history/HistorySyncService');
      const syncService = getHistorySyncService();
      syncService.cancelAll();
    } catch {
      // ignore
    }

    try {
      await setActiveServer(index);
      await updateConfig({ needsHistoryReorganize: true });
      showMessage(t('settings.serverSwitched'), 'success');
    } catch (error: unknown) {
      showMessage(error instanceof Error ? error.message : t('common.operationFailed'), 'error');
    }
  };

  // 处理切换自动复制
  const handleToggleAutoSync = async (enabled: boolean) => {
    // 立即更新本地状态，避免闪烁
    setLocalAutoSyncEnabled(enabled);

    try {
      await setAutoSync(enabled);
      showMessage(
        enabled ? t('settings.autoSyncEnabled') : t('settings.autoSyncDisabled'),
        'success'
      );
    } catch (error: unknown) {
      // 如果设置失败，恢复原来的状态
      setLocalAutoSyncEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换后台任务总开关
  const handleToggleBackgroundTasks = async (enabled: boolean) => {
    if (enabled) {
      // 如果是临时停止状态，直接清除标志，不需要弹窗确认
      if (isTempDisabledBackgroundTasks) {
        setLocalBackgroundTasksEnabled(true);
        setTempDisabledBackgroundTasks(false);
        showMessage(t('settings.backgroundTasksResumed'), 'success');
        return;
      }
      Alert.alert(
        t('settings.enableBackgroundTasksTitle'),
        t('settings.enableBackgroundTasksMessage'),
        [
          { text: t('common.cancel'), style: 'cancel' },
          {
            text: t('common.confirmEnable'),
            onPress: async () => {
              setLocalBackgroundTasksEnabled(true);
              try {
                await setEnableBackgroundTasks(true);
                showMessage(t('settings.backgroundTasksEnabled'), 'success');
              } catch (error: unknown) {
                setLocalBackgroundTasksEnabled(false);
                showMessage(
                  error instanceof Error ? error.message : t('common.setFailed'),
                  'error'
                );
              }
            },
          },
        ]
      );
      return;
    }

    setLocalBackgroundTasksEnabled(false);
    try {
      await setEnableBackgroundTasks(false);
      showMessage(t('settings.backgroundTasksDisabled'), 'success');
    } catch (error: unknown) {
      setLocalBackgroundTasksEnabled(true);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换后台下载远程
  const handleToggleBackgroundDownload = async (enabled: boolean) => {
    if (enabled) {
      setLocalBackgroundDownloadEnabled(true);
      try {
        await setEnableBackgroundDownload(true);
        showMessage(t('settings.backgroundDownloadEnabled'), 'success');
      } catch (error: unknown) {
        setLocalBackgroundDownloadEnabled(false);
        showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
      }
      return;
    }

    setLocalBackgroundDownloadEnabled(false);
    try {
      await setEnableBackgroundDownload(false);
      showMessage(t('settings.backgroundDownloadDisabled'), 'success');
    } catch (error: unknown) {
      setLocalBackgroundDownloadEnabled(true);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换后台上传本地
  const handleToggleBackgroundUpload = async (enabled: boolean) => {
    if (enabled) {
      Alert.alert(
        t('settings.enableBackgroundUploadTitle'),
        t('settings.enableBackgroundUploadMessage'),
        [
          { text: t('common.cancel'), style: 'cancel' },
          {
            text: t('common.confirmEnable'),
            onPress: async () => {
              setLocalBackgroundUploadEnabled(true);
              try {
                await setEnableBackgroundUpload(true);
                showMessage(t('settings.backgroundUploadEnabled'), 'success');
              } catch (error: unknown) {
                setLocalBackgroundUploadEnabled(false);
                showMessage(
                  error instanceof Error ? error.message : t('common.setFailed'),
                  'error'
                );
              }
            },
          },
        ]
      );
      return;
    }

    setLocalBackgroundUploadEnabled(false);
    try {
      await setEnableBackgroundUpload(false);
      showMessage(t('settings.backgroundUploadDisabled'), 'success');
    } catch (error: unknown) {
      setLocalBackgroundUploadEnabled(true);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换悬浮窗获取剪贴板
  const handleToggleClipboardOverlay = async (enabled: boolean) => {
    if (enabled && Platform.OS === 'android') {
      Alert.alert(
        t('settings.enableClipboardOverlayTitle'),
        t('settings.enableClipboardOverlayMessage'),
        [
          { text: t('common.cancel'), style: 'cancel' },
          {
            text: t('settings.confirmOk'),
            onPress: async () => {
              if (!hasOverlayPermission()) {
                requestOverlayPermission();
                return;
              }
              setLocalClipboardOverlayEnabled(true);
              try {
                await setEnableClipboardOverlay(true);
                showMessage(t('settings.clipboardOverlayEnabled'), 'success');
              } catch (error: unknown) {
                setLocalClipboardOverlayEnabled(false);
                showMessage(
                  error instanceof Error ? error.message : t('common.setFailed'),
                  'error'
                );
              }
            },
          },
        ]
      );
      return;
    }

    setLocalClipboardOverlayEnabled(enabled);

    try {
      await setEnableClipboardOverlay(enabled);
      showMessage(
        enabled ? t('settings.clipboardOverlayEnabled') : t('settings.clipboardOverlayDisabled'),
        'success'
      );
    } catch (error: unknown) {
      setLocalClipboardOverlayEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换 Shizuku 获取剪贴板
  const handleToggleShizukuClipboard = async (enabled: boolean) => {
    if (enabled && Platform.OS === 'android') {
      // 检查 Shizuku 是否可用
      if (!isShizukuAvailable()) {
        Alert.alert(t('settings.shizukuNotRunningTitle'), t('settings.shizukuNotRunningMessage'), [
          { text: t('common.cancel'), style: 'cancel' },
          {
            text: t('common.learnMore'),
            onPress: () => Linking.openURL('https://shizuku.rikka.app/guide/setup/'),
          },
        ]);
        return;
      }

      // 检查 Shizuku 权限
      if (!hasShizukuPermission()) {
        const requested = requestShizukuPermission();
        if (!requested) {
          Alert.alert(
            t('settings.permissionRequestFailed'),
            t('settings.shizukuPermissionRequestFailed')
          );
          return;
        }
        showMessage(t('settings.shizukuGrantThenEnable'), 'info');
        return;
      }

      setLocalShizukuClipboardEnabled(true);
      try {
        // 启用 Shizuku 时自动关闭悬浮窗方式
        if (localClipboardOverlayEnabled) {
          setLocalClipboardOverlayEnabled(false);
          await setEnableClipboardOverlay(false);
        }
        await setEnableShizukuClipboard(true);
        showMessage(t('settings.shizukuClipboardEnabled'), 'success');
      } catch (error: unknown) {
        setLocalShizukuClipboardEnabled(false);
        showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
      }
      return;
    }

    setLocalShizukuClipboardEnabled(enabled);
    try {
      await setEnableShizukuClipboard(enabled);
      showMessage(
        enabled ? t('settings.shizukuClipboardEnabled') : t('settings.shizukuClipboardDisabled'),
        'success'
      );
    } catch (error: unknown) {
      setLocalShizukuClipboardEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换自动上传短信验证码
  const handleToggleSmsForwarding = async (enabled: boolean) => {
    if (enabled && Platform.OS === 'android') {
      const { PermissionsAndroid } = require('react-native');
      const granted = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECEIVE_SMS);
      if (!granted) {
        const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECEIVE_SMS);
        if (result !== PermissionsAndroid.RESULTS.GRANTED) {
          Alert.alert(t('settings.smsPermissionTitle'), t('settings.smsPermissionMessage'), [
            { text: t('common.cancel'), style: 'cancel' },
            { text: t('common.goToSettings'), onPress: () => Linking.openSettings() },
          ]);
          return;
        }
      }
    }

    setLocalSmsForwardingEnabled(enabled);
    try {
      await setEnableSmsForwarding(enabled);
      // 同步静态短信接收器状态
      if (Platform.OS === 'android') {
        const { setStaticReceiverEnabled } = await import('sms-forwarder');
        setStaticReceiverEnabled(enabled);
      }
      showMessage(
        enabled ? t('settings.smsForwardingEnabled') : t('settings.smsForwardingDisabled'),
        'success'
      );
    } catch (error: unknown) {
      setLocalSmsForwardingEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换前台服务常驻通知
  const handleToggleForegroundNotification = async (enabled: boolean) => {
    if (!enabled) {
      Alert.alert(
        t('settings.disableForegroundNotifTitle'),
        t('settings.disableForegroundNotifMessage'),
        [
          { text: t('common.cancel'), style: 'cancel' },
          {
            text: t('settings.confirmClose'),
            onPress: async () => {
              setLocalForegroundNotification(false);
              try {
                await updateConfig({ enableForegroundNotification: false });
              } catch (error: unknown) {
                setLocalForegroundNotification(true);
                showMessage(
                  error instanceof Error ? error.message : t('common.setFailed'),
                  'error'
                );
              }
            },
          },
        ]
      );
      return;
    }

    setLocalForegroundNotification(true);
    try {
      await updateConfig({ enableForegroundNotification: true });
      // 检查通知权限，提示但不阻止
      if (Platform.OS === 'android') {
        const { PermissionsAndroid } = require('react-native');
        const granted = await PermissionsAndroid.check(
          PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
        );
        if (!granted) {
          Alert.alert(t('settings.noNotifPermTitle'), t('settings.noNotifPermMessage'), [
            { text: t('common.later'), style: 'cancel' },
            { text: t('common.goToSettings'), onPress: () => Linking.openSettings() },
          ]);
        }
      }
    } catch (error: unknown) {
      setLocalForegroundNotification(false);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理最大文件大小输入
  const handleMaxSizeBlur = async () => {
    try {
      const sizeMB = parseInt(maxSizeInput, 10);
      if (isNaN(sizeMB) || sizeMB < 0) {
        setMaxSizeInput(autoDownloadMaxSizeMB.toString());
        showMessage(t('settings.invalidNumber'), 'error');
        return;
      }
      const sizeInBytes = sizeMB * 1024 * 1024;
      await setAutoDownloadMaxSize(sizeInBytes);
      showMessage(t('settings.maxSizeSet', { size: sizeMB }), 'success');
    } catch (error: unknown) {
      setMaxSizeInput(autoDownloadMaxSizeMB.toString());
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理历史记录最大保留条数输入
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

      // 更新历史记录存储的最大大小
      const { historyStorage } = await import('@/storage');
      historyStorage.setMaxHistorySize(maxItems);
    } catch (error: unknown) {
      setMaxHistoryItemsInput((config?.maxHistoryItems ?? 1000).toString());
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理远程轮询间隔输入
  const handleRemotePollingBlur = async () => {
    try {
      const seconds = parseInt(remotePollingInput, 10);
      if (isNaN(seconds) || seconds < 1) {
        setRemotePollingInput(((config?.remotePollingInterval ?? 3000) / 1000).toString());
        showMessage(t('settings.numberMin1'), 'error');
        return;
      }
      const ms = seconds * 1000;
      await setRemotePollingInterval(ms);
      showMessage(t('settings.remotePollingSet', { seconds }), 'success');
    } catch (error: unknown) {
      setRemotePollingInput(((config?.remotePollingInterval ?? 3000) / 1000).toString());
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理本地轮询间隔输入
  const handleLocalPollingBlur = async () => {
    try {
      const seconds = parseInt(localPollingInput, 10);
      if (isNaN(seconds) || seconds < 1) {
        setLocalPollingInput(((config?.localPollingInterval ?? 1000) / 1000).toString());
        showMessage(t('settings.numberMin1'), 'error');
        return;
      }
      const ms = seconds * 1000;
      await setLocalPollingInterval(ms);
      showMessage(t('settings.localPollingSet', { seconds }), 'success');
    } catch (error: unknown) {
      setLocalPollingInput(((config?.localPollingInterval ?? 1000) / 1000).toString());
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 过滤输入，只允许正整数
  const filterPositiveInteger = (value: string): string => {
    const filtered = value.replace(/[^0-9]/g, '');
    if (filtered === '') return '';
    const num = parseInt(filtered, 10);
    return num > 0 ? filtered : '';
  };

  // 处理切换调试模式
  const handleToggleDebugMode = async (enabled: boolean) => {
    // 立即更新本地状态，避免闪烁
    setLocalDebugModeEnabled(enabled);

    try {
      await updateConfig({ debugMode: enabled });
      showMessage(
        enabled ? t('settings.debugModeEnabled') : t('settings.debugModeDisabled'),
        'success'
      );
    } catch (error: unknown) {
      // 如果设置失败，恢复原来的状态
      setLocalDebugModeEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换调试悬浮窗显示
  const handleToggleDebugOverlayVisible = async (enabled: boolean) => {
    setLocalDebugOverlayVisible(enabled);
    try {
      await updateConfig({ debugOverlayVisible: enabled });
      showMessage(
        enabled ? t('settings.debugOverlayEnabled') : t('settings.debugOverlayDisabled'),
        'success'
      );
    } catch (error: unknown) {
      setLocalDebugOverlayVisible(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换显示 URL Scheme 调用
  const handleToggleDebugUrlScheme = async (enabled: boolean) => {
    setLocalDebugUrlScheme(enabled);
    try {
      await updateConfig({ debugUrlScheme: enabled });
    } catch (error: unknown) {
      setLocalDebugUrlScheme(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换启动时检查更新不限次数
  const handleToggleDebugUpdateCheckNoLimit = async (enabled: boolean) => {
    setLocalDebugUpdateCheckNoLimit(enabled);
    try {
      await updateConfig({ debugUpdateCheckNoLimit: enabled });
    } catch (error: unknown) {
      setLocalDebugUpdateCheckNoLimit(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 测试验证码短信提取
  const handleTestSmsCode = () => {
    const code = extractVerificationCode(smsTestInput);
    if (code) {
      Alert.alert(t('settings.smsExtractSuccess'), t('settings.smsExtractCode', { code }));
    } else {
      Alert.alert(t('settings.smsExtractFailed'), t('settings.smsExtractFailedMessage'));
    }
  };

  // 显示统计信息弹窗
  const handleShowStatistics = async () => {
    const { useStatisticsStore } = await import('@/stores/statisticsStore');
    const store = useStatisticsStore.getState();
    if (!store.isLoaded) {
      await store.load();
    }
    setStatsText(useStatisticsStore.getState().getStatisticsText());
    setShowStatsModal(true);
  };

  // 复制统计信息到剪贴板
  const handleCopyStatistics = async () => {
    const Clipboard = await import('expo-clipboard');
    await Clipboard.setStringAsync(statsText);
    setShowStatsModal(false);
    showMessage(t('settings.statisticsCopied'), 'success');
  };

  // 处理切换自动检查更新
  const handleToggleAutoCheckUpdate = async (enabled: boolean) => {
    setLocalAutoCheckUpdateEnabled(enabled);
    try {
      await setAutoCheckUpdate(enabled);
    } catch (error: unknown) {
      setLocalAutoCheckUpdateEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换更新到测试版
  const handleToggleUpdateToBeta = async (enabled: boolean) => {
    setLocalUpdateToBetaEnabled(enabled);
    try {
      await setUpdateToBeta(enabled);
    } catch (error: unknown) {
      setLocalUpdateToBetaEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换历史记录同步
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

  // 处理历史记录图片自动下载设置变更
  const handleImageAutoDownloadChange = async (value: 'wifi' | 'always' | 'off') => {
    setLocalImageAutoDownload(value);
    try {
      await updateConfig({ historyImageAutoDownload: value });
    } catch {
      setLocalImageAutoDownload(config?.historyImageAutoDownload ?? 'wifi');
    }
  };

  // 处理切换同步 Toast 通知
  const handleToggleSyncToast = async (enabled: boolean) => {
    setLocalSyncToastEnabled(enabled);
    try {
      await updateConfig({ syncToastEnabled: enabled });
    } catch (error: unknown) {
      setLocalSyncToastEnabled(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 处理切换最近任务隐藏
  const handleToggleHideFromRecents = async (enabled: boolean) => {
    setLocalHideFromRecents(enabled);
    try {
      if (Platform.OS === 'android') {
        const { setExcludeFromRecents } = await import('native-util');
        setExcludeFromRecents(enabled);
      }
      await updateConfig({ hideFromRecents: enabled });
    } catch (error: unknown) {
      setLocalHideFromRecents(!enabled);
      showMessage(error instanceof Error ? error.message : t('common.setFailed'), 'error');
    }
  };

  // 执行更新检查逻辑
  const runUpdateCheck = async (showNoUpdateToast: boolean, includeBeta?: boolean) => {
    setIsCheckingUpdate(true);
    try {
      const today = new Date().toISOString().slice(0, 10);
      await setLastUpdateCheckDate(today);
      const useBeta = includeBeta ?? config?.updateToBeta ?? false;
      const result = await checkForUpdate(appVersion, useBeta);
      if (result.hasUpdate) {
        setUpdateAvailable(true);
        setLatestVersion(result.latestVersion);
        latestAssetsRef.current = result.assets;
        latestTagRef.current = result.tagName;
        releaseNotesRef.current = result.releaseNotes;
        showDownloadSourceDialog(result.latestVersion, result.assets, result.releaseNotes);
      } else {
        setUpdateAvailable(false);
        setLatestVersion(null);
        if (showNoUpdateToast) {
          showMessage(t('settings.alreadyLatest'), 'success');
        }
      }
      // 无论是否有更新，清除当前版本及旧版本的 APK 缓存
      cleanOldApkCache(appVersion);
    } catch {
      if (showNoUpdateToast) {
        showMessage(t('settings.checkUpdateFailed'), 'error');
      }
    } finally {
      setIsCheckingUpdate(false);
    }
  };

  // 点击"更新"按钮：先检查缓存，有则直接安装，否则弹渠道选择
  const handleUpdateButtonPress = async (
    version: string,
    assets: ReleaseAssetInfo[],
    releaseNotes?: string
  ) => {
    if (isDownloading) return;

    let preferredAbi: string = 'universal';
    try {
      const { getSupportedAbis } = await import('native-util');
      const abis = getSupportedAbis();
      preferredAbi = getPreferredAbi(abis);
    } catch (e) {
      console.warn('[UpdateDownload] getSupportedAbis failed:', e);
    }

    const asset = findAssetForAbi(assets, preferredAbi as Parameters<typeof findAssetForAbi>[1]);
    if (!asset) {
      showDownloadSourceDialog(version, assets, releaseNotes);
      return;
    }

    const cached = await checkApkCache(version, asset);
    console.log(`[UpdateDownload] pre-check cache=${cached ?? 'miss'}`);
    if (cached) {
      await installApk(cached);
    } else {
      showDownloadSourceDialog(version, assets, releaseNotes);
    }
  };

  // 弹出选择下载渠道的对话框
  const showDownloadSourceDialog = (
    version: string,
    assets: ReleaseAssetInfo[],
    releaseNotes?: string
  ) => {
    const body = releaseNotes
      ? t('settings.newVersionMessage', {
          newVersion: version,
          currentVersion: appVersion,
          notes: releaseNotes,
        })
      : t('settings.newVersionMessageNoNotes', { newVersion: version, currentVersion: appVersion });
    Alert.alert(t('settings.newVersionTitle'), body, [
      { text: t('common.later'), style: 'cancel' },
      {
        text: t('settings.downloadGitee'),
        onPress: () => handleDownloadApk('gitee', version, assets),
      },
      {
        text: t('settings.downloadGitHub'),
        onPress: () => handleDownloadApk('github', version, assets),
      },
    ]);
  };

  // 下载 APK
  const handleDownloadApk = async (
    source: ApkSource,
    version: string,
    assets: ReleaseAssetInfo[]
  ) => {
    if (isDownloading) return;

    // 检测设备 ABI
    let preferredAbi: string = 'universal';
    try {
      const { getSupportedAbis } = await import('native-util');
      const abis = getSupportedAbis();
      preferredAbi = getPreferredAbi(abis);
      console.log(
        `[UpdateDownload] supportedAbis=${JSON.stringify(abis)} preferred=${preferredAbi}`
      );
    } catch (e) {
      console.warn('[UpdateDownload] getSupportedAbis failed:', e);
    }

    const asset = findAssetForAbi(assets, preferredAbi as Parameters<typeof findAssetForAbi>[1]);
    console.log(
      `[UpdateDownload] source=${source} version=${version} assets=${assets
        .map((a) => a.name)
        .join(',')} selectedAsset=${asset?.name ?? 'none'}`
    );
    if (!asset) {
      showMessage(t('settings.noSuitableApk'), 'error');
      return;
    }

    setIsDownloading(true);
    setDownloadProgress(0);

    const abortController = new AbortController();
    downloadAbortRef.current = abortController;

    try {
      // 检查是否已有缓存
      const cached = await checkApkCache(version, asset);
      console.log(`[UpdateDownload] cache check result=${cached ?? 'miss'}`);
      if (cached) {
        await installApk(cached);
        return;
      }

      const fileUri = await downloadApk({
        asset,
        source,
        version,
        signal: abortController.signal,
        onProgress: (info) => {
          setDownloadProgress(info.progress);
        },
      });

      console.log(`[UpdateDownload] download finished fileUri=${fileUri}`);
      setUpdateAvailable(false);
      setLatestVersion(null);
      await installApk(fileUri);
    } catch (err) {
      console.error('[UpdateDownload] error:', err);
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

  // 取消下载对话框
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

  // 计算存储大小
  const calculateStorageSizes = async () => {
    setIsCalculating(true);
    try {
      // 使用setTimeout模拟异步操作，避免UI阻塞
      await new Promise((resolve) => setTimeout(resolve, 100));
      const cacheSizeValue = calculateDirectorySize(cacheDir);
      const historySizeValue = calculateDirectorySize(historyDir);
      const logSizeValue = calculateLogSize();
      setCacheSize(cacheSizeValue);
      setHistorySize(historySizeValue);
      setLogSize(logSizeValue);
    } catch (error) {
      console.error('Failed to calculate storage sizes:', error);
    } finally {
      setIsCalculating(false);
    }
  };

  // 清除缓存
  const handleClearCache = () => {
    Alert.alert(
      t('settings.clearCacheTitle'),
      t('settings.clearCacheMessage'),
      [
        {
          text: t('common.cancel'),
          style: 'cancel',
        },
        {
          text: t('settings.confirmOk'),
          onPress: async () => {
            try {
              clearDirectory(cacheDir);
              await calculateStorageSizes();
              showMessage(t('settings.cacheCleared'), 'success');
            } catch {
              showMessage(t('settings.clearCacheFailed'), 'error');
            }
          },
        },
      ],
      { cancelable: true }
    );
  };

  // 清除日志
  const handleClearLogs = () => {
    Alert.alert(
      t('settings.clearLogsTitle'),
      t('settings.clearLogsMessage'),
      [
        {
          text: t('common.cancel'),
          style: 'cancel',
        },
        {
          text: t('settings.confirmOk'),
          onPress: async () => {
            try {
              clearLogs();
              await calculateStorageSizes();
              showMessage(t('settings.logsCleared'), 'success');
            } catch {
              showMessage(t('settings.clearLogsFailed'), 'error');
            }
          },
        },
      ],
      { cancelable: true }
    );
  };

  // 导出日志
  const handleExportLogs = async () => {
    if (isExportingLogs) {
      exportLogsAbortControllerRef.current?.abort();
      return;
    }

    const abortController = new AbortController();
    exportLogsAbortControllerRef.current = abortController;
    setIsExportingLogs(true);

    try {
      await saveLogsToFile(abortController.signal);
      showMessage(t('settings.logsSaved'), 'success');
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        showMessage(t('settings.exportCanceled'), 'info');
      } else {
        const message = error instanceof Error ? error.message : t('common.operationFailed');
        showMessage(message, 'error');
      }
    } finally {
      setIsExportingLogs(false);
      exportLogsAbortControllerRef.current = null;
    }
  };

  // 设置日志等级
  const handleSetLogLevel = async (level: LogLevel) => {
    try {
      await setLogLevel(level);
      setLoggerLogLevel(level);
      showMessage(t('settings.logLevelSet', { level }), 'success');
    } catch {
      showMessage(t('settings.logLevelSetFailed'), 'error');
    }
  };

  // 处理添加下载快捷方式
  const handleAddDownloadShortcut = async () => {
    try {
      await shortcut.addDownloadShortcut();
    } catch (error: unknown) {
      showMessage(error instanceof Error ? error.message : t('common.operationFailed'), 'error');
    }
  };

  // 处理添加上传快捷方式
  const handleAddUploadShortcut = async () => {
    try {
      await shortcut.addUploadShortcut();
    } catch (error: unknown) {
      showMessage(error instanceof Error ? error.message : t('common.operationFailed'), 'error');
    }
  };

  return (
    <SafeAreaView
      style={[styles.container, { backgroundColor: theme.colors.background }]}
      edges={[]}
    >
      <ScrollView style={styles.scrollView}>
        {/* 服务器配置部分 */}
        <View style={styles.section}>
          <View style={[styles.sectionHeaderBase, styles.sectionHeaderRow]}>
            <TouchableOpacity
              style={styles.sectionTitleContainer}
              onPress={() => servers.length > 1 && setServersCollapsed(!serversCollapsed)}
              disabled={servers.length <= 1}
            >
              <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
                {t('settings.serverSection')}
              </Text>
              {servers.length > 1 && (
                <View style={styles.collapseIcon}>
                  {serversCollapsed ? (
                    <ChevronDown color={theme.colors.textSecondary} width={18} height={18} />
                  ) : (
                    <ChevronUp color={theme.colors.textSecondary} width={18} height={18} />
                  )}
                </View>
              )}
            </TouchableOpacity>
            <TouchableOpacity style={styles.iconButton} onPress={handleAddServer}>
              <Plus color={theme.colors.primary} width={20} height={20} />
            </TouchableOpacity>
          </View>

          {servers.length === 0 ? (
            <View style={[styles.emptyCard, { backgroundColor: theme.colors.surface }]}>
              <Text style={[styles.emptyText, { color: theme.colors.textSecondary }]}>
                {t('settings.noServers')}
              </Text>
              <Text style={[styles.emptyHint, { color: theme.colors.textTertiary }]}>
                {t('settings.noServersHint')}
              </Text>
            </View>
          ) : serversCollapsed && servers.length > 1 ? (
            activeServer && (
              <ServerListItem
                config={activeServer}
                isActive={true}
                onPress={() => {}}
                onEdit={() => handleEditServer(activeServerIndex)}
                onDelete={() => handleDeleteServer(activeServerIndex)}
              />
            )
          ) : (
            servers.map((server, index) => (
              <ServerListItem
                key={index}
                config={server}
                isActive={index === activeServerIndex}
                onPress={() => handleSetActiveServer(index)}
                onEdit={() => handleEditServer(index)}
                onDelete={() => handleDeleteServer(index)}
              />
            ))
          )}
        </View>

        {/* 同步设置部分 */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderBase}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.syncSection')}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.autoSync')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {t('settings.autoSyncDesc')}
                </Text>
              </View>
              <Switch
                value={localAutoSyncEnabled}
                onValueChange={handleToggleAutoSync}
                trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                thumbColor={localAutoSyncEnabled ? theme.colors.surface : theme.colors.textTertiary}
              />
            </View>

            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.syncToast')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {t('settings.syncToastDesc')}
                </Text>
              </View>
              <Switch
                value={localSyncToastEnabled}
                onValueChange={handleToggleSyncToast}
                trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                thumbColor={
                  localSyncToastEnabled ? theme.colors.surface : theme.colors.textTertiary
                }
              />
            </View>

            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.autoDownloadMaxSize')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {t('settings.autoDownloadMaxSizeDesc')}
                </Text>
              </View>
              <View style={styles.inputContainer}>
                <TextInput
                  style={[
                    styles.sizeInput,
                    {
                      color: theme.colors.text,
                      borderColor: theme.colors.divider,
                      backgroundColor: theme.colors.background,
                    },
                  ]}
                  value={maxSizeInput}
                  onChangeText={setMaxSizeInput}
                  onBlur={handleMaxSizeBlur}
                  keyboardType="number-pad"
                  placeholder="5"
                  placeholderTextColor={theme.colors.textTertiary}
                />
                <Text style={[styles.unitLabel, { color: theme.colors.textSecondary }]}>MB</Text>
              </View>
            </View>

            {activeServer?.type !== 'syncclipboard' && (
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.remotePollingInterval')}
                  </Text>
                </View>
                <View style={styles.inputContainer}>
                  <TextInput
                    style={[
                      styles.sizeInput,
                      {
                        color: theme.colors.text,
                        borderColor: theme.colors.divider,
                        backgroundColor: theme.colors.background,
                      },
                    ]}
                    value={remotePollingInput}
                    onChangeText={(text) => setRemotePollingInput(filterPositiveInteger(text))}
                    onBlur={handleRemotePollingBlur}
                    keyboardType="number-pad"
                    placeholder="3"
                    placeholderTextColor={theme.colors.textTertiary}
                  />
                  <Text style={[styles.unitLabel, { color: theme.colors.textSecondary }]}>
                    {t('settings.unitSecond')}
                  </Text>
                </View>
              </View>
            )}

            <View style={styles.settingRowNoBorder}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.localPollingInterval')}
                </Text>
              </View>
              <View style={styles.inputContainer}>
                <TextInput
                  style={[
                    styles.sizeInput,
                    {
                      color: theme.colors.text,
                      borderColor: theme.colors.divider,
                      backgroundColor: theme.colors.background,
                    },
                  ]}
                  value={localPollingInput}
                  onChangeText={(text) => setLocalPollingInput(filterPositiveInteger(text))}
                  onBlur={handleLocalPollingBlur}
                  keyboardType="number-pad"
                  placeholder="1"
                  placeholderTextColor={theme.colors.textTertiary}
                />
                <Text style={[styles.unitLabel, { color: theme.colors.textSecondary }]}>
                  {t('settings.unitSecond')}
                </Text>
              </View>
            </View>
          </View>
        </View>

        {/* 历史记录部分 */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderBase}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.historySection')}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.historySync')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {activeServer?.type !== 'syncclipboard'
                    ? t('settings.historySyncNotSupported')
                    : t('settings.historySyncDesc')}
                </Text>
              </View>
              <Switch
                value={localHistorySyncEnabled && activeServer?.type === 'syncclipboard'}
                onValueChange={handleToggleHistorySync}
                trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                thumbColor={
                  localHistorySyncEnabled && activeServer?.type === 'syncclipboard'
                    ? theme.colors.surface
                    : theme.colors.textTertiary
                }
                disabled={activeServer?.type !== 'syncclipboard'}
              />
            </View>

            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.maxHistoryItems')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {t('settings.maxHistoryItemsDesc')}
                </Text>
              </View>
              <View style={styles.inputContainer}>
                <TextInput
                  style={[
                    styles.sizeInput,
                    {
                      color: theme.colors.text,
                      borderColor: theme.colors.divider,
                      backgroundColor: theme.colors.background,
                    },
                  ]}
                  value={maxHistoryItemsInput}
                  onChangeText={setMaxHistoryItemsInput}
                  onBlur={handleMaxHistoryItemsBlur}
                  keyboardType="number-pad"
                  placeholder="100"
                  placeholderTextColor={theme.colors.textTertiary}
                />
                <Text style={[styles.unitLabel, { color: theme.colors.textSecondary }]}>
                  {t('settings.unitItem')}
                </Text>
              </View>
            </View>

            <TouchableOpacity
              style={styles.settingRowNoBorder}
              onPress={() => setShowImageAutoDownloadMenu(!showImageAutoDownloadMenu)}
            >
              <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                {t('settings.imageAutoDownload')}
              </Text>
              <View style={styles.dropdownValue}>
                <Text style={[styles.dropdownValueText, { color: theme.colors.textSecondary }]}>
                  {imageAutoDownloadOptions.find((o) => o.value === localImageAutoDownload)
                    ?.label ?? t('settings.imageAutoDownloadWifi')}
                </Text>
                {showImageAutoDownloadMenu ? (
                  <ChevronUp color={theme.colors.textSecondary} width={18} height={18} />
                ) : (
                  <ChevronDown color={theme.colors.textSecondary} width={18} height={18} />
                )}
              </View>
            </TouchableOpacity>

            {showImageAutoDownloadMenu && (
              <View style={[styles.dropdownMenu, { borderColor: theme.colors.divider }]}>
                {imageAutoDownloadOptions.map((option, index) => (
                  <TouchableOpacity
                    key={option.value}
                    style={[
                      styles.dropdownItem,
                      index < imageAutoDownloadOptions.length - 1
                        ? {
                            borderBottomWidth: StyleSheet.hairlineWidth,
                            borderBottomColor: theme.colors.divider,
                          }
                        : undefined,
                    ]}
                    onPress={() => {
                      handleImageAutoDownloadChange(option.value);
                      setShowImageAutoDownloadMenu(false);
                    }}
                  >
                    <Text
                      style={[
                        styles.dropdownItemText,
                        {
                          color:
                            localImageAutoDownload === option.value
                              ? theme.colors.primary
                              : theme.colors.text,
                        },
                      ]}
                    >
                      {option.label}
                    </Text>
                    {localImageAutoDownload === option.value && (
                      <Check stroke={theme.colors.primary} width={18} height={18} strokeWidth={3} />
                    )}
                  </TouchableOpacity>
                ))}
              </View>
            )}

            <View
              style={[
                styles.settingRowNoBorder,
                { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.colors.divider },
              ]}
            >
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.showImageCopyButton')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {t('settings.showImageCopyButtonDesc')}
                </Text>
              </View>
              <Switch
                value={config?.showImageCopyButton ?? false}
                onValueChange={(enabled) => updateConfig({ showImageCopyButton: enabled })}
                trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                thumbColor={
                  (config?.showImageCopyButton ?? false)
                    ? theme.colors.surface
                    : theme.colors.textTertiary
                }
              />
            </View>
          </View>
        </View>

        {/* 后台任务部分 */}
        {Platform.OS === 'android' && (
          <View style={styles.section}>
            <View style={styles.sectionHeaderBase}>
              <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
                {t('settings.backgroundTasksSection')}
              </Text>
            </View>

            <View
              style={[
                styles.card,
                { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
              ]}
            >
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.backgroundTasksSection')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {isTempDisabledBackgroundTasks
                      ? t('settings.backgroundTasksTempStopped')
                      : t('settings.backgroundTasksDesc')}
                  </Text>
                </View>
                <Switch
                  value={localBackgroundTasksEnabled}
                  onValueChange={handleToggleBackgroundTasks}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localBackgroundTasksEnabled ? theme.colors.surface : theme.colors.textTertiary
                  }
                />
              </View>

              {/* 后台同步 */}
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text
                    style={[
                      styles.settingLabel,
                      {
                        color: localBackgroundTasksEnabled
                          ? theme.colors.text
                          : theme.colors.textTertiary,
                      },
                    ]}
                  >
                    {t('settings.foregroundNotification')}
                  </Text>
                  <Text
                    style={[
                      styles.settingDescription,
                      {
                        color: localBackgroundTasksEnabled
                          ? theme.colors.textSecondary
                          : theme.colors.textTertiary,
                      },
                    ]}
                  >
                    {t('settings.foregroundNotificationDesc')}
                  </Text>
                </View>
                <Switch
                  value={localBackgroundTasksEnabled && localForegroundNotification}
                  onValueChange={handleToggleForegroundNotification}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localBackgroundTasksEnabled && localForegroundNotification
                      ? theme.colors.surface
                      : theme.colors.textTertiary
                  }
                  disabled={!localBackgroundTasksEnabled}
                />
              </View>

              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text
                    style={[
                      styles.settingLabel,
                      {
                        color: localBackgroundTasksEnabled
                          ? theme.colors.text
                          : theme.colors.textTertiary,
                      },
                    ]}
                  >
                    {t('settings.backgroundDownload')}
                  </Text>
                </View>
                <Switch
                  value={localBackgroundTasksEnabled && localBackgroundDownloadEnabled}
                  onValueChange={handleToggleBackgroundDownload}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localBackgroundTasksEnabled && localBackgroundDownloadEnabled
                      ? theme.colors.surface
                      : theme.colors.textTertiary
                  }
                  disabled={!localBackgroundTasksEnabled}
                />
              </View>

              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text
                    style={[
                      styles.settingLabel,
                      {
                        color: localBackgroundTasksEnabled
                          ? theme.colors.text
                          : theme.colors.textTertiary,
                      },
                    ]}
                  >
                    {t('settings.backgroundUpload')}
                  </Text>
                </View>
                <Switch
                  value={localBackgroundTasksEnabled && localBackgroundUploadEnabled}
                  onValueChange={handleToggleBackgroundUpload}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localBackgroundTasksEnabled && localBackgroundUploadEnabled
                      ? theme.colors.surface
                      : theme.colors.textTertiary
                  }
                  disabled={!localBackgroundTasksEnabled}
                />
              </View>

              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text
                    style={[
                      styles.settingLabel,
                      {
                        color: localBackgroundTasksEnabled
                          ? theme.colors.text
                          : theme.colors.textTertiary,
                      },
                    ]}
                  >
                    {t('settings.clipboardOverlay')}
                  </Text>
                </View>
                <Switch
                  value={localBackgroundTasksEnabled && localClipboardOverlayEnabled}
                  onValueChange={handleToggleClipboardOverlay}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localBackgroundTasksEnabled && localClipboardOverlayEnabled
                      ? theme.colors.surface
                      : theme.colors.textTertiary
                  }
                  disabled={!localBackgroundTasksEnabled}
                />
              </View>

              <View style={styles.settingRowNoBorder}>
                <View style={styles.settingInfo}>
                  <Text
                    style={[
                      styles.settingLabel,
                      {
                        color: localBackgroundTasksEnabled
                          ? theme.colors.text
                          : theme.colors.textTertiary,
                      },
                    ]}
                  >
                    {t('settings.shizukuClipboard')}
                  </Text>
                  <Text
                    style={[styles.settingDescription, { color: theme.colors.primary }]}
                    onPress={() => Linking.openURL('https://shizuku.rikka.app/')}
                  >
                    {t('settings.shizukuWebsite')}
                  </Text>
                </View>
                <Switch
                  value={localBackgroundTasksEnabled && localShizukuClipboardEnabled}
                  onValueChange={handleToggleShizukuClipboard}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localBackgroundTasksEnabled && localShizukuClipboardEnabled
                      ? theme.colors.surface
                      : theme.colors.textTertiary
                  }
                  disabled={!localBackgroundTasksEnabled}
                />
              </View>
            </View>
          </View>
        )}

        {/* 短信自动化部分 */}
        {Platform.OS === 'android' && (
          <View style={styles.section}>
            <View style={styles.sectionHeaderBase}>
              <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
                {t('settings.smsSection')}
              </Text>
            </View>

            <View
              style={[
                styles.card,
                { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
              ]}
            >
              <View style={styles.settingRowNoBorder}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.smsForwarding')}
                  </Text>
                </View>
                <Switch
                  value={localSmsForwardingEnabled}
                  onValueChange={handleToggleSmsForwarding}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localSmsForwardingEnabled ? theme.colors.surface : theme.colors.textTertiary
                  }
                />
              </View>
            </View>
          </View>
        )}

        {/* 权限管理部分 */}
        {Platform.OS === 'android' && (
          <View style={styles.section}>
            <View style={[styles.sectionHeaderBase, styles.sectionHeaderRow]}>
              <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
                {t('settings.permissionsSection')}
              </Text>
              <TouchableOpacity
                style={styles.iconButton}
                onPress={refreshPermissions}
                disabled={isRefreshingPermissions}
              >
                <RefreshCw color={theme.colors.primary} width={16} height={16} />
              </TouchableOpacity>
            </View>

            <View
              style={[
                styles.card,
                { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
              ]}
            >
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.permissionNotification')}
                  </Text>
                </View>
                <Switch
                  value={permNotification}
                  onValueChange={() => Linking.openSettings()}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={permNotification ? theme.colors.surface : theme.colors.textTertiary}
                />
              </View>

              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.permissionOverlay')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {t('settings.permissionOverlayDesc')}
                  </Text>
                </View>
                <Switch
                  value={permOverlay}
                  onValueChange={() => requestOverlayPermission()}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={permOverlay ? theme.colors.surface : theme.colors.textTertiary}
                />
              </View>

              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.permissionSms')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {t('settings.permissionSmsDesc')}
                  </Text>
                </View>
                <Switch
                  value={permSms}
                  onValueChange={() => Linking.openSettings()}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={permSms ? theme.colors.surface : theme.colors.textTertiary}
                />
              </View>

              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.permissionShizuku')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {shizukuAvailable
                      ? t('settings.permissionShizukuDesc')
                      : t('settings.shizukuNotRunningDesc')}
                  </Text>
                </View>
                <Switch
                  value={permShizuku}
                  onValueChange={async () => {
                    if (!shizukuAvailable) {
                      Alert.alert(
                        t('settings.shizukuNotRunningTitle'),
                        t('settings.shizukuNotRunningMessage'),
                        [
                          {
                            text: t('common.learnMore'),
                            onPress: () =>
                              Linking.openURL('https://shizuku.rikka.app/guide/setup/'),
                          },
                          { text: t('common.cancel'), style: 'cancel' },
                        ]
                      );
                      return;
                    }
                    if (!permShizuku) {
                      requestShizukuPermission();
                      // 延迟刷新权限状态（等待用户授权）
                      setTimeout(refreshPermissions, 2000);
                    }
                  }}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={permShizuku ? theme.colors.surface : theme.colors.textTertiary}
                />
              </View>

              <View style={styles.settingRowNoBorder}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.permissionBattery')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {t('settings.permissionBatteryDesc')}
                  </Text>
                </View>
                <Switch
                  value={permBattery}
                  onValueChange={async () => {
                    const { requestIgnoreBatteryOptimizations } = await import('native-util');
                    if (hasBatteryOptRequested.current) {
                      Alert.alert(
                        t('settings.batteryOptDialogTitle'),
                        t('settings.batteryOptDialogMessage'),
                        [
                          {
                            text: t('common.goToSettings'),
                            onPress: () => Linking.openSettings(),
                          },
                          { text: t('common.cancel'), style: 'cancel' },
                        ]
                      );
                      return;
                    }
                    requestIgnoreBatteryOptimizations();
                    hasBatteryOptRequested.current = true;
                  }}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={permBattery ? theme.colors.surface : theme.colors.textTertiary}
                />
              </View>
            </View>
          </View>
        )}

        {/* 快捷操作部分 */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderBase}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.shortcutsSection')}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.addDownloadShortcut')}
                </Text>
              </View>
              <TouchableOpacity
                style={[styles.actionButton, { backgroundColor: theme.colors.primary }]}
                onPress={handleAddDownloadShortcut}
              >
                <Text style={[styles.actionButtonText, { color: theme.colors.white }]}>
                  {t('common.add')}
                </Text>
              </TouchableOpacity>
            </View>

            <View style={styles.settingRowNoBorder}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.addUploadShortcut')}
                </Text>
              </View>
              <TouchableOpacity
                style={[styles.actionButton, { backgroundColor: theme.colors.primary }]}
                onPress={handleAddUploadShortcut}
              >
                <Text style={[styles.actionButtonText, { color: theme.colors.white }]}>
                  {t('common.add')}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>

        {/* 存储部分 */}
        <View style={styles.section}>
          <View style={[styles.sectionHeaderBase, styles.sectionHeaderRow]}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.storageSection')}
            </Text>
            <TouchableOpacity
              style={styles.iconButton}
              onPress={calculateStorageSizes}
              disabled={isCalculating}
            >
              <RefreshCw color={theme.colors.primary} width={16} height={16} />
            </TouchableOpacity>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.cacheSize')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {isCalculating ? t('common.loading') : formatFileSize(cacheSize)}
                </Text>
              </View>
              <TouchableOpacity
                style={[styles.clearButton, { backgroundColor: theme.colors.primary }]}
                onPress={handleClearCache}
                disabled={isCalculating}
              >
                <Text style={[styles.clearButtonText, { color: theme.colors.white }]}>
                  {t('common.clearAction')}
                </Text>
              </TouchableOpacity>
            </View>

            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.logSize')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {isCalculating ? t('common.loading') : formatFileSize(logSize)}
                </Text>
              </View>
              <TouchableOpacity
                style={[styles.clearButton, { backgroundColor: theme.colors.primary }]}
                onPress={handleClearLogs}
                disabled={isCalculating}
              >
                <Text style={[styles.clearButtonText, { color: theme.colors.white }]}>
                  {t('common.clearAction')}
                </Text>
              </TouchableOpacity>
            </View>

            <View style={styles.settingRowNoBorder}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.historySize')}
                </Text>
                <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                  {isCalculating ? t('common.loading') : formatFileSize(historySize)}
                </Text>
              </View>
            </View>
          </View>
        </View>

        {/* 日志设置部分 */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderBase}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.logsSection')}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            <TouchableOpacity
              style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}
              onPress={() => setShowLogLevelMenu(!showLogLevelMenu)}
            >
              <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                {t('settings.logLevel')}
              </Text>
              <View style={styles.dropdownValue}>
                <Text style={[styles.dropdownValueText, { color: theme.colors.textSecondary }]}>
                  {config?.logLevel === 'debug'
                    ? t('settings.logLevelDebug')
                    : config?.logLevel === 'info'
                      ? t('settings.logLevelInfo')
                      : config?.logLevel === 'warn'
                        ? t('settings.logLevelWarn')
                        : t('settings.logLevelError')}
                </Text>
                {showLogLevelMenu ? (
                  <ChevronUp color={theme.colors.textSecondary} width={18} height={18} />
                ) : (
                  <ChevronDown color={theme.colors.textSecondary} width={18} height={18} />
                )}
              </View>
            </TouchableOpacity>

            {showLogLevelMenu && (
              <View style={[styles.dropdownMenu, { borderColor: theme.colors.divider }]}>
                {[
                  { label: t('settings.logLevelDebug'), value: 'debug' as LogLevel },
                  { label: t('settings.logLevelInfo'), value: 'info' as LogLevel },
                  { label: t('settings.logLevelWarn'), value: 'warn' as LogLevel },
                  { label: t('settings.logLevelError'), value: 'error' as LogLevel },
                ].map((option, index) => (
                  <TouchableOpacity
                    key={option.value}
                    style={[
                      styles.dropdownItem,
                      index < 3
                        ? {
                            borderBottomWidth: StyleSheet.hairlineWidth,
                            borderBottomColor: theme.colors.divider,
                          }
                        : undefined,
                    ]}
                    onPress={() => {
                      handleSetLogLevel(option.value);
                      setShowLogLevelMenu(false);
                    }}
                  >
                    <Text
                      style={[
                        styles.dropdownItemText,
                        {
                          color:
                            config?.logLevel === option.value
                              ? theme.colors.primary
                              : theme.colors.text,
                        },
                      ]}
                    >
                      {option.label}
                    </Text>
                    {config?.logLevel === option.value && (
                      <Check stroke={theme.colors.primary} width={18} height={18} strokeWidth={3} />
                    )}
                  </TouchableOpacity>
                ))}
              </View>
            )}

            <View style={styles.settingRowNoBorder}>
              <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                {t('settings.exportLogs')}
              </Text>
              <TouchableOpacity
                style={[styles.clearButton, { backgroundColor: theme.colors.primary }]}
                onPress={handleExportLogs}
                disabled={isCalculating}
              >
                <Text style={[styles.clearButtonText, { color: theme.colors.white }]}>
                  {isExportingLogs ? t('common.cancel') : t('common.export')}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>

        {/* 外观设置部分 */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderBase}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.appearanceSection')}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            {/* 语言设置 */}
            <TouchableOpacity
              style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}
              onPress={() => setShowLanguageMenu(!showLanguageMenu)}
            >
              <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                {t('settings.language')}
              </Text>
              <View style={styles.dropdownValue}>
                <Text style={[styles.dropdownValueText, { color: theme.colors.textSecondary }]}>
                  {languageOptions.find((o) => o.value === currentLanguage)?.label ??
                    t('settings.languageAuto')}
                </Text>
                {showLanguageMenu ? (
                  <ChevronUp color={theme.colors.textSecondary} width={18} height={18} />
                ) : (
                  <ChevronDown color={theme.colors.textSecondary} width={18} height={18} />
                )}
              </View>
            </TouchableOpacity>

            {showLanguageMenu && (
              <View style={[styles.dropdownMenu, { borderColor: theme.colors.divider }]}>
                {languageOptions.map((option, index) => (
                  <TouchableOpacity
                    key={option.value}
                    style={[
                      styles.dropdownItem,
                      index < languageOptions.length - 1
                        ? {
                            borderBottomWidth: StyleSheet.hairlineWidth,
                            borderBottomColor: theme.colors.divider,
                          }
                        : undefined,
                    ]}
                    onPress={() => {
                      setLanguage(option.value);
                      setShowLanguageMenu(false);
                    }}
                  >
                    <Text
                      style={[
                        styles.dropdownItemText,
                        {
                          color:
                            currentLanguage === option.value
                              ? theme.colors.primary
                              : theme.colors.text,
                        },
                      ]}
                    >
                      {option.label}
                    </Text>
                    {currentLanguage === option.value && (
                      <Check stroke={theme.colors.primary} width={18} height={18} strokeWidth={3} />
                    )}
                  </TouchableOpacity>
                ))}
              </View>
            )}

            <TouchableOpacity
              style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}
              onPress={() => setShowThemeMenu(!showThemeMenu)}
            >
              <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                {t('settings.theme')}
              </Text>
              <View style={styles.dropdownValue}>
                <Text style={[styles.dropdownValueText, { color: theme.colors.textSecondary }]}>
                  {themeOptions.find((o) => o.value === themeMode)?.label ??
                    t('settings.themeAuto')}
                </Text>
                {showThemeMenu ? (
                  <ChevronUp color={theme.colors.textSecondary} width={18} height={18} />
                ) : (
                  <ChevronDown color={theme.colors.textSecondary} width={18} height={18} />
                )}
              </View>
            </TouchableOpacity>

            {showThemeMenu && (
              <View style={[styles.dropdownMenu, { borderColor: theme.colors.divider }]}>
                {themeOptions.map((option, index) => (
                  <TouchableOpacity
                    key={option.value}
                    style={[
                      styles.dropdownItem,
                      index < themeOptions.length - 1
                        ? {
                            borderBottomWidth: StyleSheet.hairlineWidth,
                            borderBottomColor: theme.colors.divider,
                          }
                        : undefined,
                    ]}
                    onPress={() => {
                      setThemeMode(option.value);
                      setShowThemeMenu(false);
                    }}
                  >
                    <Text
                      style={[
                        styles.dropdownItemText,
                        {
                          color:
                            themeMode === option.value ? theme.colors.primary : theme.colors.text,
                        },
                      ]}
                    >
                      {option.label}
                    </Text>
                    {themeMode === option.value && (
                      <Check stroke={theme.colors.primary} width={18} height={18} strokeWidth={3} />
                    )}
                  </TouchableOpacity>
                ))}
              </View>
            )}

            {Platform.OS === 'android' && (
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.hideFromRecents')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {t('settings.hideFromRecentsDesc')}
                  </Text>
                </View>
                <Switch
                  value={localHideFromRecents}
                  onValueChange={handleToggleHideFromRecents}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localHideFromRecents ? theme.colors.surface : theme.colors.textTertiary
                  }
                />
              </View>
            )}
          </View>
        </View>

        {/* 应用信息部分 */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderBase}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.aboutSection')}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            <View
              style={[
                styles.versionBlock,
                {
                  borderBottomWidth: StyleSheet.hairlineWidth,
                  borderBottomColor: theme.colors.divider,
                },
              ]}
            >
              <View style={styles.versionTopRow}>
                <View style={styles.versionLabelGroup}>
                  <Text style={[styles.infoLabel, { color: theme.colors.textSecondary }]}>
                    {t('settings.version')}
                  </Text>
                  <Text style={[styles.infoValue, { color: theme.colors.text }]}>{appVersion}</Text>
                </View>
                <View style={styles.versionButtonGroup}>
                  <TouchableOpacity
                    style={[
                      styles.updateButton,
                      {
                        backgroundColor:
                          isDownloading || updateAvailable
                            ? theme.colors.primary
                            : theme.colors.surface,
                        borderColor: theme.colors.primary,
                      },
                    ]}
                    onPress={() => {
                      if (isDownloading) {
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
                    }}
                    disabled={isCheckingUpdate}
                  >
                    <Text
                      style={[
                        styles.updateButtonText,
                        {
                          color:
                            isDownloading || updateAvailable
                              ? theme.colors.white
                              : theme.colors.primary,
                        },
                      ]}
                    >
                      {isCheckingUpdate
                        ? t('settings.checkingUpdate')
                        : isDownloading
                          ? t('settings.downloadingUpdate', {
                              percent: Math.round(downloadProgress * 100),
                            })
                          : updateAvailable
                            ? t('settings.updateAvailable', { version: latestVersion })
                            : t('settings.checkUpdate')}
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[
                      styles.updateButton,
                      {
                        backgroundColor: theme.colors.primary,
                        borderColor: theme.colors.primary,
                      },
                    ]}
                    onPress={() =>
                      Linking.openURL('https://github.com/Jeric-X/syncclipboard-mobile')
                    }
                  >
                    <Text style={[styles.updateButtonText, { color: theme.colors.white }]}>
                      GitHub
                    </Text>
                  </TouchableOpacity>
                </View>
              </View>
            </View>

            <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.autoCheckUpdate')}
                </Text>
              </View>
              <Switch
                value={localAutoCheckUpdateEnabled}
                onValueChange={handleToggleAutoCheckUpdate}
                trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                thumbColor={
                  localAutoCheckUpdateEnabled ? theme.colors.surface : theme.colors.textTertiary
                }
              />
            </View>

            <View style={styles.settingRowNoBorder}>
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.updateToBeta')}
                </Text>
              </View>
              <Switch
                value={localUpdateToBetaEnabled}
                onValueChange={handleToggleUpdateToBeta}
                trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                thumbColor={
                  localUpdateToBetaEnabled ? theme.colors.surface : theme.colors.textTertiary
                }
              />
            </View>
          </View>
        </View>

        {/* 调试部分 */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderBase}>
            <Text style={[styles.sectionTitle, { color: theme.colors.text }]}>
              {t('settings.debugSection')}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.colors.surface, borderColor: theme.colors.divider },
            ]}
          >
            <View
              style={[
                styles.settingRowNoBorder,
                localDebugModeEnabled && {
                  borderBottomWidth: StyleSheet.hairlineWidth,
                  borderBottomColor: theme.colors.divider,
                },
              ]}
            >
              <View style={styles.settingInfo}>
                <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                  {t('settings.debugMode')}
                </Text>
              </View>
              <Switch
                value={localDebugModeEnabled}
                onValueChange={handleToggleDebugMode}
                trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                thumbColor={
                  localDebugModeEnabled ? theme.colors.surface : theme.colors.textTertiary
                }
              />
            </View>

            {localDebugModeEnabled && Platform.OS === 'android' && (
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.debugOverlay')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {t('settings.debugOverlayDesc')}
                  </Text>
                </View>
                <Switch
                  value={localDebugOverlayVisible}
                  onValueChange={handleToggleDebugOverlayVisible}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localDebugOverlayVisible ? theme.colors.surface : theme.colors.textTertiary
                  }
                />
              </View>
            )}

            {localDebugModeEnabled && (
              <View
                style={[
                  styles.settingRowNoBorder,
                  {
                    borderBottomWidth: StyleSheet.hairlineWidth,
                    borderBottomColor: theme.colors.divider,
                  },
                ]}
              >
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.debugUrlScheme')}
                  </Text>
                </View>
                <Switch
                  value={localDebugUrlScheme}
                  onValueChange={handleToggleDebugUrlScheme}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localDebugUrlScheme ? theme.colors.surface : theme.colors.textTertiary
                  }
                />
              </View>
            )}

            {localDebugModeEnabled && (
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.debugSmsTest')}
                  </Text>
                </View>
                <TouchableOpacity
                  style={[styles.actionButton, { backgroundColor: theme.colors.primary }]}
                  onPress={() => {
                    setSmsTestInput('');
                    setShowSmsTestModal(true);
                  }}
                >
                  <Text style={[styles.actionButtonText, { color: theme.colors.white }]}>
                    {t('common.test')}
                  </Text>
                </TouchableOpacity>
              </View>
            )}

            {localDebugModeEnabled && (
              <View style={[styles.settingRow, { borderBottomColor: theme.colors.divider }]}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.debugUpdateCheckNoLimit')}
                  </Text>
                  <Text style={[styles.settingDescription, { color: theme.colors.textTertiary }]}>
                    {t('settings.debugUpdateCheckNoLimitDesc')}
                  </Text>
                </View>
                <Switch
                  value={localDebugUpdateCheckNoLimit}
                  onValueChange={handleToggleDebugUpdateCheckNoLimit}
                  trackColor={{ false: theme.colors.divider, true: theme.colors.primary }}
                  thumbColor={
                    localDebugUpdateCheckNoLimit ? theme.colors.surface : theme.colors.textTertiary
                  }
                />
              </View>
            )}

            {localDebugModeEnabled && (
              <View style={styles.settingRowNoBorder}>
                <View style={styles.settingInfo}>
                  <Text style={[styles.settingLabel, { color: theme.colors.text }]}>
                    {t('settings.statistics')}
                  </Text>
                </View>
                <TouchableOpacity
                  style={[styles.actionButton, { backgroundColor: theme.colors.primary }]}
                  onPress={handleShowStatistics}
                >
                  <Text style={[styles.actionButtonText, { color: theme.colors.white }]}>
                    {t('common.view')}
                  </Text>
                </TouchableOpacity>
              </View>
            )}
          </View>
        </View>

        <View style={styles.bottomPadding} />
      </ScrollView>

      {/* 消息提示 */}
      <MessageToast message={message} onMessageShown={handleMessageShown} />

      {/* 服务器配置模态框 */}
      <ServerConfigModal
        visible={showServerModal}
        onClose={() => setShowServerModal(false)}
        onSave={handleSaveServer}
        initialConfig={editingServerIndex !== null ? servers[editingServerIndex] : undefined}
        isEditing={editingServerIndex !== null}
      />

      {/* 测试验证码短信模态框 */}
      <Modal
        visible={showSmsTestModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowSmsTestModal(false)}
      >
        <View style={[styles.smsTestModalOverlay, { backgroundColor: theme.colors.overlay }]}>
          <View style={[styles.smsTestModalContent, { backgroundColor: theme.colors.surface }]}>
            <Text style={[styles.smsTestModalTitle, { color: theme.colors.text }]}>
              {t('settings.smsTestTitle')}
            </Text>
            <TextInput
              style={[
                styles.smsTestModalInput,
                {
                  color: theme.colors.text,
                  borderColor: theme.colors.divider,
                  backgroundColor: theme.colors.background,
                },
              ]}
              placeholder={t('settings.smsTestInputPlaceholder')}
              placeholderTextColor={theme.colors.textTertiary}
              value={smsTestInput}
              onChangeText={setSmsTestInput}
              multiline
              numberOfLines={4}
              textAlignVertical="top"
            />
            <View style={styles.smsTestModalButtons}>
              <TouchableOpacity
                style={[styles.smsTestModalButton, { backgroundColor: theme.colors.divider }]}
                onPress={() => setShowSmsTestModal(false)}
              >
                <Text style={[styles.smsTestModalButtonText, { color: theme.colors.text }]}>
                  {t('common.cancel')}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.smsTestModalButton, { backgroundColor: theme.colors.primary }]}
                onPress={handleTestSmsCode}
              >
                <Text style={[styles.smsTestModalButtonText, { color: theme.colors.white }]}>
                  {t('common.test')}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* 统计信息模态框 */}
      <Modal
        visible={showStatsModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowStatsModal(false)}
      >
        <View style={[styles.smsTestModalOverlay, { backgroundColor: theme.colors.overlay }]}>
          <View style={[styles.smsTestModalContent, { backgroundColor: theme.colors.surface }]}>
            <Text style={[styles.smsTestModalTitle, { color: theme.colors.text }]}>
              {t('settings.statistics')}
            </Text>
            <Text style={[styles.statsText, { color: theme.colors.text }]} selectable>
              {statsText}
            </Text>
            <View style={styles.smsTestModalButtons}>
              <TouchableOpacity
                style={[styles.smsTestModalButton, { backgroundColor: theme.colors.divider }]}
                onPress={() => setShowStatsModal(false)}
              >
                <Text style={[styles.smsTestModalButtonText, { color: theme.colors.text }]}>
                  {t('common.close')}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.smsTestModalButton, { backgroundColor: theme.colors.primary }]}
                onPress={handleCopyStatistics}
              >
                <Text style={[styles.smsTestModalButtonText, { color: theme.colors.white }]}>
                  {t('common.copy')}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
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
  section: {
    marginTop: 20,
  },
  sectionHeaderBase: {
    marginHorizontal: 16,
    marginBottom: 8,
  },
  sectionHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  sectionTitleContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  collapseIcon: {
    marginTop: 1,
  },
  iconButton: {
    width: 34,
    height: 34,
    borderRadius: 8,
    justifyContent: 'center',
    alignItems: 'center',
  },
  addButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  addButtonText: {
    fontSize: 14,
    fontWeight: '600',
  },
  emptyCard: {
    marginHorizontal: 16,
    borderRadius: 12,
    padding: 32,
    alignItems: 'center',
  },
  emptyText: {
    fontSize: 16,
    fontWeight: '500',
    marginBottom: 8,
  },
  emptyHint: {
    fontSize: 14,
    textAlign: 'center',
  },
  card: {
    marginHorizontal: 16,
    borderRadius: 12,
    overflow: 'hidden',
    borderWidth: 1,
  },
  cardTitle: {
    fontSize: 15,
    fontWeight: '600',
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 8,
  },
  optionItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  optionInfo: {
    flex: 1,
  },
  optionLabel: {
    fontSize: 16,
  },
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  infoLabel: {
    fontSize: 16,
  },
  infoValue: {
    fontSize: 16,
    fontWeight: '500',
  },
  settingRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  settingRowNoBorder: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  settingInfo: {
    flex: 1,
    marginRight: 16,
  },
  settingLabel: {
    fontSize: 16,
    fontWeight: '500',
    marginBottom: 4,
  },
  settingDescription: {
    fontSize: 13,
    lineHeight: 18,
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  sizeInput: {
    width: 80,
    height: 40,
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    fontSize: 16,
    textAlign: 'right',
  },
  unitLabel: {
    fontSize: 16,
    marginLeft: 8,
    fontWeight: '500',
  },
  bottomPadding: {
    height: 40,
  },
  versionBlock: {
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  versionTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  versionButtonGroup: {
    flexDirection: 'row',
    gap: 8,
  },
  versionLabelGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  updateButton: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 8,
    borderWidth: 1,
  },
  updateButtonText: {
    fontSize: 13,
    fontWeight: '600',
  },
  dropdownValue: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  dropdownValueText: {
    fontSize: 16,
  },
  dropdownMenu: {
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  dropdownItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  dropdownItemText: {
    fontSize: 16,
  },
  clearButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  clearButtonText: {
    fontSize: 14,
    fontWeight: '600',
  },
  buttonGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  exportButton: {
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
  },
  refreshButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  refreshButtonText: {
    fontSize: 14,
    fontWeight: '600',
  },
  actionButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  actionButtonText: {
    fontSize: 14,
    fontWeight: '600',
  },
  smsTestModalOverlay: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  smsTestModalContent: {
    width: '100%',
    borderRadius: 12,
    padding: 20,
  },
  smsTestModalTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 16,
  },
  smsTestModalInput: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    minHeight: 100,
    marginBottom: 16,
  },
  statsText: {
    fontSize: 14,
    lineHeight: 22,
    marginBottom: 16,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  smsTestModalButtons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
  },
  smsTestModalButton: {
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 8,
  },
  smsTestModalButtonText: {
    fontSize: 14,
    fontWeight: '600',
  },
});
