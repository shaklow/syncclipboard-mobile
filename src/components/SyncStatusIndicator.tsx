/**
 * Sync Status Indicator Component
 * 同步状态指示器
 */

import React from 'react';
import { View, Text, StyleSheet, ActivityIndicator } from 'react-native';
import { useTheme } from '@/hooks/useTheme';
import { useTranslation } from 'react-i18next';
import { SyncStatus } from '@/types/sync';

interface SyncStatusIndicatorProps {
  status: SyncStatus;
  lastSyncTime: number | null;
  serverConnected: boolean;
}

export const SyncStatusIndicator: React.FC<SyncStatusIndicatorProps> = ({
  status,
  lastSyncTime,
  serverConnected,
}) => {
  const { theme } = useTheme();
  const { t } = useTranslation();

  const getStatusColor = (): string => {
    if (!serverConnected) return theme.colors.textTertiary;

    switch (status) {
      case SyncStatus.Syncing:
        return theme.colors.primary;
      case SyncStatus.Success:
        return '#4CAF50'; // Green
      case SyncStatus.Failed:
        return '#F44336'; // Red
      case SyncStatus.Conflict:
        return '#FF9800'; // Orange
      case SyncStatus.Idle:
      default:
        return theme.colors.textSecondary;
    }
  };

  const getStatusIcon = (): string => {
    if (!serverConnected) return '⚠️';

    switch (status) {
      case SyncStatus.Syncing:
        return '🔄';
      case SyncStatus.Success:
        return '✅';
      case SyncStatus.Failed:
        return '❌';
      case SyncStatus.Conflict:
        return '⚠️';
      case SyncStatus.Idle:
      default:
        return '⏸️';
    }
  };

  const getStatusText = (): string => {
    if (!serverConnected) return t('syncStatus.notConnected');

    switch (status) {
      case SyncStatus.Syncing:
        return t('syncStatus.syncing');
      case SyncStatus.Success:
        return t('syncStatus.synced');
      case SyncStatus.Failed:
        return t('syncStatus.failed');
      case SyncStatus.Conflict:
        return t('syncStatus.conflict');
      case SyncStatus.Idle:
      default:
        return t('syncStatus.idle');
    }
  };

  const formatLastSyncTime = (): string => {
    if (!lastSyncTime) return '';

    const now = Date.now();
    const diff = now - lastSyncTime;

    if (diff < 60000) return t('syncStatus.justSynced');
    if (diff < 3600000) return t('common.timeMinutesAgo', { minutes: Math.floor(diff / 60000) });
    if (diff < 86400000) return t('common.timeHoursAgo', { hours: Math.floor(diff / 3600000) });

    return new Date(lastSyncTime).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const statusColor = getStatusColor();
  const statusIcon = getStatusIcon();
  const statusText = getStatusText();
  const timeText = formatLastSyncTime();

  return (
    <View style={[styles.container, { backgroundColor: theme.colors.surface }]}>
      <View style={styles.content}>
        <View style={styles.statusRow}>
          {status === SyncStatus.Syncing ? (
            <ActivityIndicator size="small" color={statusColor} style={styles.icon} />
          ) : (
            <Text style={styles.iconText}>{statusIcon}</Text>
          )}

          <View style={styles.textContainer}>
            <Text style={[styles.statusText, { color: statusColor }]}>{statusText}</Text>
            {timeText && (
              <Text style={[styles.timeText, { color: theme.colors.textSecondary }]}>
                {timeText}
              </Text>
            )}
          </View>
        </View>

        {/* 服务器状态指示点 */}
        <View style={styles.connectionDot}>
          <View
            style={[
              styles.dot,
              {
                backgroundColor: serverConnected ? theme.colors.success : theme.colors.textTertiary,
              },
            ]}
          />
        </View>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  content: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
  },
  icon: {
    marginRight: 8,
  },
  iconText: {
    fontSize: 20,
    marginRight: 8,
  },
  textContainer: {
    flex: 1,
  },
  statusText: {
    fontSize: 15,
    fontWeight: '600',
  },
  timeText: {
    fontSize: 12,
    marginTop: 2,
  },
  connectionDot: {
    marginLeft: 12,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
});
