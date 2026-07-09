/**
 * ClipboardSyncService
 * 管理远程剪贴板同步的回调订阅与本地自动上传。
 *
 * 职责：
 * - 订阅远程剪贴板变化通知（哈希检测、自动下载、自动复制、历史记录）
 * - 自动上传（本地剪贴板变化时触发）
 * - 通过 useClipboardSyncServiceStore 向 UI 提供状态
 *
 * 远程连接生命周期由 RemoteClipboardMonitorTask 管理。
 * 生命周期由 LongRunningTaskManager 统一控制。
 */

import { ClipboardChangeCallback } from '../../types/clipboard';
import type { ClipboardContent } from '../../types/clipboard';
import { clipboardMonitor } from '../clipboard/ClipboardMonitor';
import { localClipboard } from '../clipboard/LocalClipboard';
import { clipboardSyncState } from './SyncState';
import { configService } from '../ConfigService';
import type { AppConfig } from '../../types/storage';
import { remoteClipboardMonitor } from './RemoteClipboardMonitor';
import type { RemoteClipboardChangedCallback } from './RemoteClipboardMonitor';
import { historyService } from '../history/HistoryService';
import { getHistoryTransferQueue } from '../history/HistoryTransferQueue';
import { getClipboardChangedHandler } from './ClipboardChangedHandler';
import { createHistoryChangedHandler } from './historyChangedHandler';
import { createTransferQueueChangedHandler } from './historyTransferQueueChangedHandler';
import { isRootClipboardActive } from '../../utils/clipboardProxy';
import { setWatchdogAlarm, cancelWatchdogAlarm, addWatchdogTickListener } from 'root-clipboard';
import type { WatchdogTickEvent } from 'root-clipboard';
import type { EventSubscription } from 'expo-modules-core';

class ClipboardSyncService {
  private static instance: ClipboardSyncService | null = null;

  private _isStarted = false;
  private _bgUploadEnabled = false;
  private _bgDownloadEnabled = false;
  private _rootClipboardActive = false;
  private _activeServerKey: string | null = null;
  private readonly _cleanups: Array<() => void> = [];
  private _watchdogSub: EventSubscription | null = null;

  private constructor() {}

  static getInstance(): ClipboardSyncService {
    if (!ClipboardSyncService.instance) {
      ClipboardSyncService.instance = new ClipboardSyncService();
    }
    return ClipboardSyncService.instance;
  }

  public isStarted(): boolean {
    return this._isStarted;
  }

  async start(): Promise<void> {
    if (this._isStarted) return;
    this._isStarted = true;

    const cfg = await configService.getConfig();
    await this._applyConfig(cfg);
    this._registerBgUploadChecker();
    this._registerBgDownloadChecker();
    this._registerRemoteCopiedCallback();
    this._subscribeToRemoteClipboard();
    this._subscribeToClipboardChanges();
    this._subscribeToHistoryChanges();
    this._subscribeToTransferQueue();
  }

  async stop(): Promise<void> {
    this._isStarted = false;
    // 清理保活闹钟
    cancelWatchdogAlarm();
    this._watchdogSub?.remove();
    this._watchdogSub = null;
    // 清理所有订阅
    this._cleanups.forEach((unsub) => unsub());
    this._cleanups.length = 0;
  }

  async onConfigChanged(cfg: AppConfig | null): Promise<void> {
    await this._applyConfig(cfg);
  }

  private async _applyConfig(cfg: AppConfig | null): Promise<void> {
    this._bgUploadEnabled = !!cfg?.enableBackgroundUpload;
    this._bgDownloadEnabled = !!cfg?.enableBackgroundDownload;

    // 动态检查 Root 剪贴板是否激活，用于影响后台轮询决策
    this._rootClipboardActive = await isRootClipboardActive();

    // Root 模式激活时启用保活闹钟，确保 Doze 不冻结定时器
    if (this._rootClipboardActive && !this._watchdogSub) {
      this._setupWatchdog();
    } else if (!this._rootClipboardActive && this._watchdogSub) {
      cancelWatchdogAlarm();
      this._watchdogSub?.remove();
      this._watchdogSub = null;
    }

    const activeServer = cfg?.servers?.[cfg.activeServerIndex] ?? null;
    const newServerKey = activeServer ? JSON.stringify(activeServer) : null;
    if (newServerKey !== this._activeServerKey) {
      this._activeServerKey = newServerKey;
      getClipboardChangedHandler().resetLastRemoteProfileHash();
    }
  }

  clearSyncError(): void {
    clipboardSyncState.clearSyncError();
  }

  private _registerBgUploadChecker(): void {
    // Root 模式激活时，始终保持后台轮询（因为 Root 可以在后台读剪贴板）
    const checker = () => this._bgUploadEnabled || this._rootClipboardActive;
    clipboardMonitor.addBackgroundRunningChecker(checker);
    this._cleanups.push(() => clipboardMonitor.removeBackgroundRunningChecker(checker));
  }

  private _registerBgDownloadChecker(): void {
    // Root 模式激活时，始终保持远程监听（确保后台下载也能工作）
    const checker = () => this._bgDownloadEnabled || this._rootClipboardActive;
    remoteClipboardMonitor.addBackgroundRunningChecker(checker);
    this._cleanups.push(() => remoteClipboardMonitor.removeBackgroundRunningChecker(checker));
  }

  /**
   * 注册保活闹钟监听。
   * 当设备从深度休眠中唤醒时，执行一次轮询健康检查：
   * - 检查并重启本地剪贴板轮询（如果已停止）
   * - 检查并重建远程监听连接（如果已断开）
   */
  private _setupWatchdog(): void {
    if (this._watchdogSub) return;

    // 设置原生保活闹钟（每 5 分钟触发一次）
    const alarmSet = setWatchdogAlarm();
    console.log('[ClipboardSyncService] Watchdog alarm setup:', alarmSet ? 'ok' : 'failed');

    // 监听闹钟事件
    this._watchdogSub = addWatchdogTickListener((_event: WatchdogTickEvent) => {
      console.log('[ClipboardSyncService] Watchdog tick - health check');

      // 检查剪贴板轮询是否存活
      if (clipboardMonitor.isActive()) {
        // 触发一次即时检查，确保轮询循环仍在运行
        clipboardMonitor.triggerCheck().catch(() => {});
      } else {
        // 轮询已停止，重新启动
        console.warn('[ClipboardSyncService] Polling was dead, restarting...');
        clipboardMonitor.start().catch(() => {});
      }

      // 检查远程监听是否存活
      if (!remoteClipboardMonitor.isConnected()) {
        console.warn('[ClipboardSyncService] Remote monitor disconnected, reconnecting...');
        remoteClipboardMonitor.connect().catch(() => {});
      }
    });

    this._cleanups.push(() => {
      cancelWatchdogAlarm();
      this._watchdogSub?.remove();
      this._watchdogSub = null;
    });
  }

  private _registerRemoteCopiedCallback(): void {
    const callback = (content: ClipboardContent) => {
      const hash = content.profileHash || content.text || null;
      if (hash) getClipboardChangedHandler().setLastLocalProfileHash(hash);
    };
    localClipboard.registerRemoteCopiedCallback(callback);
    this._cleanups.push(() => localClipboard.registerRemoteCopiedCallback(null));
  }

  private _subscribeToRemoteClipboard(): void {
    const callback: RemoteClipboardChangedCallback = async (content) => {
      try {
        await getClipboardChangedHandler().processRemoteClipboardContent(content);
      } catch (e) {
        console.error('[ClipboardSyncService] Remote change callback error:', e);
      }
    };
    remoteClipboardMonitor.addCallback(callback);
    this._cleanups.push(() => remoteClipboardMonitor.removeCallback(callback));
  }

  private _subscribeToClipboardChanges(): void {
    const callback: ClipboardChangeCallback = (content) => {
      getClipboardChangedHandler().handleAutoUpload(content);
    };
    clipboardMonitor.addCallback(callback);
    this._cleanups.push(() => clipboardMonitor.removeCallback(callback));
  }

  private _subscribeToHistoryChanges(): void {
    const handler = createHistoryChangedHandler();
    historyService.addChangeCallback(handler);
    this._cleanups.push(() => historyService.removeChangeCallback(handler));
  }

  private _subscribeToTransferQueue(): void {
    const queue = getHistoryTransferQueue();
    const handler = createTransferQueueChangedHandler();
    queue.onTaskStatusChanged(handler);
    this._cleanups.push(() => {
      queue.offTaskStatusChanged(handler);
      clipboardSyncState.clearDownloadState();
    });
  }
}

export function getClipboardSyncService(): ClipboardSyncService {
  return ClipboardSyncService.getInstance();
}
