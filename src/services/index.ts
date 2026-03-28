/**
 * Services Entry Point
 * Exports all API clients and services
 */

// Error classes
export * from './errors';

// Authentication
export { AuthService, type Credentials } from './AuthService';

// API Clients
export { APIClient, type APIClientConfig, type ISyncClipboardAPI } from './APIClient';
export { SyncClipboardClient } from './SyncClipboardClient';
export { WebDAVClient, type WebDAVConfig } from './WebDAVClient';

// Clipboard Services
export { ClipboardManager, clipboardManager } from './ClipboardManager';
export { ClipboardMonitor, clipboardMonitor } from './ClipboardMonitor';

// SignalR Client
export { SignalRClient, getSignalRClient, resetSignalRClient } from './SignalRClient';
export type { RemoteClipboardChangedCallback, RemoteHistoryChangedCallback } from './SignalRClient';

// Sync Manager
export { SyncManager } from './SyncManager';

// Shortcut Service
export { ShortcutService } from './ShortcutService';

// Update Service
export { checkForUpdate, parseVersion, compareVersions, versionToStr } from './UpdateService';
export type { UpdateCheckResult, ParsedVersion } from './UpdateService';

// Storage Services
export { ConfigStorage, configStorage } from './ConfigStorage';
export { HistoryStorage, historyStorage } from './HistoryStorage';
export { CacheManager, cacheManager } from './CacheManager';
export { SecureStorage, secureStorage } from './SecureStorage';

// Logger Service
export {
  initLogger,
  getLogger,
  setLogLevel,
  getLogDirectory,
  getLogFilePaths,
  getLogFileUris,
  calculateLogSize,
  clearLogs,
  cleanOldLogs,
  log,
  saveLogsToFile,
  type LogConfig,
  type LogLevel,
} from './Logger';

// Factory function to create appropriate API client
import { SyncClipboardClient } from './SyncClipboardClient';
import { WebDAVClient } from './WebDAVClient';
import { AuthService } from './AuthService';
import { ServerConfig } from '../types/api';
import { ConfigurationError } from './errors';

/**
 * 创建 API 客户端工厂函数
 */
export function createAPIClient(config: ServerConfig): SyncClipboardClient | WebDAVClient {
  const { type, url, username, password } = config;

  if (!url) {
    throw new ConfigurationError('Server URL is required');
  }

  if (type === 'webdav') {
    if (!username || !password) {
      throw new ConfigurationError('Username and password are required for WebDAV');
    }
    return new WebDAVClient({ baseURL: url, username, password });
  }

  if (type === 'syncclipboard') {
    const authService = username && password ? new AuthService(username, password) : undefined;

    return new SyncClipboardClient({ baseURL: url, authService });
  }

  throw new ConfigurationError(`Unsupported server type: ${type}`);
}
