/**
 * App Navigation
 * 底部 Tab 导航 + 设置页二级 Stack 导航
 */

import React from 'react';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Ionicons } from '@expo/vector-icons';
import { useTheme } from '@/hooks/useTheme';
import { useTranslation } from 'react-i18next';
import { HomeScreen } from '@/screens/HomeScreen';
import { HistoryScreen } from '@/screens/HistoryScreen';
import { SettingsScreen } from '@/screens/SettingsScreen';
import { ServerSettingsScreen } from '@/screens/ServerSettingsScreen';
import { ClipboardHistorySettingsScreen } from '@/screens/ClipboardHistorySettingsScreen';
import { AboutScreen } from '@/screens/AboutScreen';

const Tab = createBottomTabNavigator();
const SettingsStack = createNativeStackNavigator();

/**
 * 设置页 Stack 导航器
 * 主设置页 → 服务器设置 / 剪贴板历史 / 关于 三个二级页面
 */
function SettingsNavigator() {
  const { theme } = useTheme();
  const { t } = useTranslation();

  return (
    <SettingsStack.Navigator
      screenOptions={{
        headerStyle: {
          backgroundColor: theme.colors.surface,
        },
        headerTintColor: theme.colors.text,
        headerTitleStyle: {
          fontWeight: '600',
          fontSize: 17,
        },
        headerShadowVisible: false,
        headerBackTitle: t('common.back'),
        contentStyle: {
          backgroundColor: theme.colors.background,
        },
      }}
    >
      <SettingsStack.Screen
        name="SettingsMain"
        component={SettingsScreen}
        options={{ title: t('nav.settings') }}
      />
      <SettingsStack.Screen
        name="ServerSettings"
        component={ServerSettingsScreen}
        options={{ title: t('settings.serverSection') }}
      />
      <SettingsStack.Screen
        name="ClipboardHistorySettings"
        component={ClipboardHistorySettingsScreen}
        options={{ title: t('settings.historySection') }}
      />
      <SettingsStack.Screen
        name="About"
        component={AboutScreen}
        options={{ title: t('settings.aboutSection') }}
      />
    </SettingsStack.Navigator>
  );
}

export const AppNavigator = () => {
  const { theme } = useTheme();
  const { t } = useTranslation();

  // 创建适应主题的导航主题
  const navigationTheme = theme.isDark
    ? {
        ...DarkTheme,
        colors: {
          ...DarkTheme.colors,
          primary: theme.colors.primary,
          background: theme.colors.background,
          card: theme.colors.surface,
          text: theme.colors.text,
          border: theme.colors.border,
        },
      }
    : {
        ...DefaultTheme,
        colors: {
          ...DefaultTheme.colors,
          primary: theme.colors.primary,
          background: theme.colors.background,
          card: theme.colors.surface,
          text: theme.colors.text,
          border: theme.colors.border,
        },
      };

  return (
    <NavigationContainer theme={navigationTheme}>
      <Tab.Navigator
        screenOptions={({ route }) => ({
          headerStyle: {
            backgroundColor: theme.colors.surface,
            elevation: 0,
            shadowOpacity: 0,
            borderBottomWidth: 0,
          },
          headerTintColor: theme.colors.text,
          headerTitleStyle: {
            fontWeight: 'bold',
          },
          tabBarStyle: {
            backgroundColor: theme.colors.tabBarBackground,
            borderTopColor: theme.colors.tabBarBorder,
            borderTopWidth: 1,
            elevation: 0,
            shadowOpacity: 0,
          },
          tabBarActiveTintColor: theme.colors.tabBarActive,
          tabBarInactiveTintColor: theme.colors.tabBarInactive,
          tabBarIcon: ({ color, size }) => {
            let iconName = 'home';
            if (route.name === 'History') {
              iconName = 'time';
            } else if (route.name === 'Settings') {
              iconName = 'settings';
            }
            return (
              <Ionicons
                name={iconName as keyof typeof Ionicons.glyphMap}
                size={size}
                color={color}
              />
            );
          },
        })}
      >
        <Tab.Screen name="Home" component={HomeScreen} options={{ title: t('nav.home') }} />
        <Tab.Screen
          name="History"
          component={HistoryScreen}
          options={{ title: t('nav.history') }}
        />
        <Tab.Screen
          name="Settings"
          component={SettingsNavigator}
          options={{
            title: t('nav.settings'),
            headerShown: false,
          }}
        />
      </Tab.Navigator>
    </NavigationContainer>
  );
};
