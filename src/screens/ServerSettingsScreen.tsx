/**
 * 服务器设置页面
 * 管理服务器列表：添加、编辑、删除、切换激活服务器
 */

import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTheme } from '@/hooks/useTheme';
import { useTranslation } from 'react-i18next';
import { useSettingsStore } from '@/stores';
import { ServerConfigModal, ServerListItem, MessageToast } from '@/components';
import { SettingsSection } from '@/components/settings';
import type { ServerConfig } from '@/types/api';
import { useMessageToast } from '@/hooks/useMessageToast';
import { Plus, ChevronDown, ChevronUp } from 'react-native-feather';

export const ServerSettingsScreen: React.FC = () => {
  const { theme } = useTheme();
  const { t } = useTranslation();
  const { config, addServer, updateServer, deleteServer, setActiveServer, updateConfig } =
    useSettingsStore();

  const [showServerModal, setShowServerModal] = useState(false);
  const [editingServerIndex, setEditingServerIndex] = useState<number | null>(null);
  const [serversCollapsed, setServersCollapsed] = useState(true);
  const { message, showMessage, handleMessageShown } = useMessageToast();

  const servers = config?.servers || [];
  const activeServerIndex = config?.activeServerIndex ?? -1;
  const activeServer = activeServerIndex >= 0 ? servers[activeServerIndex] : null;

  const handleAddServer = () => {
    setEditingServerIndex(null);
    setShowServerModal(true);
  };

  const handleEditServer = (index: number) => {
    setEditingServerIndex(index);
    setShowServerModal(true);
  };

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

  const handleDeleteServer = async (index: number) => {
    try {
      await deleteServer(index);
      showMessage(t('settings.serverDeleted'), 'success');
    } catch (error: unknown) {
      showMessage(error instanceof Error ? error.message : t('common.operationFailed'), 'error');
    }
  };

  const handleSetActiveServer = async (index: number) => {
    if (index === activeServerIndex) {
      if (servers.length > 1) setServersCollapsed(true);
      return;
    }
    if (servers.length > 1) setServersCollapsed(true);

    try {
      const { getHistorySyncService } = await import('@/services/history/HistorySyncService');
      getHistorySyncService().cancelAll();
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

  return (
    <SafeAreaView
      style={[styles.container, { backgroundColor: theme.colors.background }]}
      edges={[]}
    >
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.scrollContent}>
        <SettingsSection
          title={t('settings.serverSection')}
          headerRight={
            <View style={styles.headerActions}>
              {servers.length > 1 && (
                <TouchableOpacity
                  style={styles.iconButton}
                  onPress={() => setServersCollapsed(!serversCollapsed)}
                >
                  {serversCollapsed ? (
                    <ChevronDown color={theme.colors.primary} width={18} height={18} />
                  ) : (
                    <ChevronUp color={theme.colors.primary} width={18} height={18} />
                  )}
                </TouchableOpacity>
              )}
              <TouchableOpacity style={styles.iconButton} onPress={handleAddServer}>
                <Plus color={theme.colors.primary} width={20} height={20} />
              </TouchableOpacity>
            </View>
          }
        >
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
        </SettingsSection>
      </ScrollView>

      <MessageToast message={message} onMessageShown={handleMessageShown} />

      <ServerConfigModal
        visible={showServerModal}
        onClose={() => setShowServerModal(false)}
        onSave={handleSaveServer}
        initialConfig={editingServerIndex !== null ? servers[editingServerIndex] : undefined}
        isEditing={editingServerIndex !== null}
      />
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
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  iconButton: {
    width: 34,
    height: 34,
    borderRadius: 8,
    justifyContent: 'center',
    alignItems: 'center',
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
});
