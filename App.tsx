import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { StyleSheet, Linking, BackHandler, ToastAndroid, StatusBar } from 'react-native';
import { useEffect, useState } from 'react';
import { ThemeProvider } from './src/contexts/ThemeContext';
import { AppNavigator } from './src/navigation/AppNavigator';
import { QuickTileLoadingScreen } from './src/screens/QuickTileLoadingScreen';
import { QuickSmsCodeUploadScreen } from './src/screens/QuickSmsCodeUploadScreen';
import { ShareReceiveScreen } from './src/screens/ShareReceiveScreen';
import { SyncDirection } from './src/types/sync';
import { useSettingsStore } from './src/stores';
import { initLogger } from './src/services/Logger';
import { useTheme } from './src/hooks/useTheme';
import { setDynamicShortcuts } from 'shortcut';

const QUICK_UPLOAD_URL = 'syncclipboard://quick-upload';
const QUICK_DOWNLOAD_URL = 'syncclipboard://quick-download';
const QUICK_UPLOAD_SMS_CODE_URL = 'syncclipboard://quick-upload-sms-code';

function parseQuickTileUrl(url: string | null): {
  isQuickTile: boolean;
  fromForeground: boolean;
  direction: SyncDirection;
  isSmsCode?: boolean;
} {
  if (!url) return { isQuickTile: false, fromForeground: false, direction: SyncDirection.Download };
  const fromForeground = url.includes('fg=1');
  if (url.startsWith(QUICK_UPLOAD_SMS_CODE_URL))
    return { isQuickTile: true, fromForeground, direction: SyncDirection.Upload, isSmsCode: true };
  // Check upload first — its URL is a superset of the download prefix
  if (url.startsWith(QUICK_UPLOAD_URL))
    return { isQuickTile: true, fromForeground, direction: SyncDirection.Upload };
  if (url.startsWith(QUICK_DOWNLOAD_URL))
    return { isQuickTile: true, fromForeground, direction: SyncDirection.Download };
  return { isQuickTile: false, fromForeground: false, direction: SyncDirection.Download };
}

function isShareIntentUrl(url: string | null): boolean {
  if (!url) return false;
  try {
    return new URL(url).hostname === 'expo-sharing';
  } catch {
    return false;
  }
}

type AppMode = 'checking' | 'home' | 'quick_tile_loading' | 'quick_sms_upload' | 'share_receive';

export default function App() {
  const [appMode, setAppMode] = useState<AppMode>('checking');
  const [shouldExitAfterSync, setShouldExitAfterSync] = useState(false);
  const [syncDirection, setSyncDirection] = useState<SyncDirection>(SyncDirection.Download);
  const { config, loadConfig, isLoaded } = useSettingsStore();

  useEffect(() => {
    initLogger();
    setDynamicShortcuts();
  }, []);

  useEffect(() => {
    if (!isLoaded) {
      loadConfig();
    }
  }, [isLoaded, loadConfig]);

  useEffect(() => {
    if (!isLoaded) return;

    // Cold start: app launched via URL scheme
    Linking.getInitialURL().then((url) => {
      if (config?.debugUrlScheme) {
        ToastAndroid.show(`getInitialURL: ${url ?? 'null'}`, ToastAndroid.LONG);
      }
      if (isShareIntentUrl(url)) {
        setAppMode('share_receive');
        return;
      }
      const { isQuickTile, fromForeground, direction, isSmsCode } = parseQuickTileUrl(url);
      if (isQuickTile) {
        setShouldExitAfterSync(!fromForeground);
        if (isSmsCode) {
          setAppMode('quick_sms_upload');
        } else {
          setSyncDirection(direction);
          setAppMode('quick_tile_loading');
        }
        return;
      }
      setAppMode('home');
    });

    // Hot start: app already running, receives URL deep link event
    const urlSub = Linking.addEventListener('url', ({ url }) => {
      if (config?.debugUrlScheme) {
        ToastAndroid.show(`addEventListener url: ${url ?? 'null'}`, ToastAndroid.LONG);
      }
      if (isShareIntentUrl(url)) {
        setAppMode('share_receive');
        return;
      }
      const { isQuickTile, fromForeground, direction, isSmsCode } = parseQuickTileUrl(url);
      if (isQuickTile) {
        setShouldExitAfterSync(!fromForeground);
        if (isSmsCode) {
          setAppMode('quick_sms_upload');
        } else {
          setSyncDirection(direction);
          setAppMode('quick_tile_loading');
        }
      }
    });

    return () => urlSub.remove();
  }, [isLoaded, config?.debugUrlScheme]);

  return (
    <GestureHandlerRootView style={styles.container}>
      <ThemeProvider>
        <ThemedStatusBar />
        {appMode === 'checking' ? null : appMode === 'share_receive' ? (
          <ShareReceiveScreen
            onComplete={() => {
              setAppMode('home');
              BackHandler.exitApp();
            }}
          />
        ) : appMode === 'quick_sms_upload' ? (
          <QuickSmsCodeUploadScreen
            onComplete={() => {
              setAppMode('home');
              if (shouldExitAfterSync) {
                BackHandler.exitApp();
              }
            }}
          />
        ) : appMode === 'quick_tile_loading' ? (
          <QuickTileLoadingScreen
            direction={syncDirection}
            onLoadingComplete={() => {
              setAppMode('home');
              if (shouldExitAfterSync) {
                BackHandler.exitApp();
              }
            }}
          />
        ) : (
          <AppNavigator />
        )}
      </ThemeProvider>
    </GestureHandlerRootView>
  );
}

function ThemedStatusBar() {
  const { theme } = useTheme();
  return (
    <StatusBar
      barStyle={theme.isDark ? 'light-content' : 'dark-content'}
      backgroundColor={theme.colors.surface}
    />
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
});
