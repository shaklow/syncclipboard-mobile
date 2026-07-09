/**
 * SettingNavigationItem - MIUI X 风格导航设置项
 * 左侧图标+标签+描述，右侧箭头指示器
 */

import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { ChevronRight } from 'react-native-feather';
import { useTheme } from '@/hooks/useTheme';

export interface SettingNavigationItemProps {
  /** 主标签 */
  label: string;

  /** 描述文字，可选 */
  description?: string;

  /** 左侧图标，可选 */
  icon?: React.ReactNode;

  /** 点击回调 */
  onPress: () => void;

  /** 是否显示底部分隔线，默认 true */
  showBorder?: boolean;

  /** 禁用状态 */
  disabled?: boolean;
}

export const SettingNavigationItem: React.FC<SettingNavigationItemProps> = ({
  label,
  description,
  icon,
  onPress,
  showBorder = true,
  disabled = false,
}) => {
  const { theme } = useTheme();

  return (
    <TouchableOpacity
      style={[
        styles.container,
        { borderBottomColor: theme.colors.divider },
        !showBorder && styles.noBorder,
      ]}
      onPress={onPress}
      activeOpacity={0.6}
      disabled={disabled}
    >
      {icon && <View style={styles.iconContainer}>{icon}</View>}
      <View style={styles.content}>
        <Text
          style={[
            styles.label,
            { color: disabled ? theme.colors.textTertiary : theme.colors.text },
          ]}
          numberOfLines={1}
        >
          {label}
        </Text>
        {description ? (
          <Text
            style={[styles.description, { color: theme.colors.textTertiary }]}
            numberOfLines={2}
          >
            {description}
          </Text>
        ) : null}
      </View>
      <ChevronRight
        color={disabled ? theme.colors.textDisabled : theme.colors.textTertiary}
        width={20}
        height={20}
        style={styles.chevron}
      />
    </TouchableOpacity>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  noBorder: {
    borderBottomWidth: 0,
  },
  iconContainer: {
    marginRight: 12,
  },
  content: {
    flex: 1,
    marginRight: 8,
  },
  label: {
    fontSize: 16,
    fontWeight: '400',
  },
  description: {
    fontSize: 13,
    lineHeight: 18,
    marginTop: 3,
  },
  chevron: {
    marginLeft: 4,
  },
});
