/**
 * useI18n Hook
 * 用于访问和操作语言设置，参考 useTheme 模式
 */

import { useContext } from 'react';
import { I18nContext } from '@/contexts/I18nContext';

export const useI18n = () => {
  const context = useContext(I18nContext);

  if (!context) {
    throw new Error('useI18n must be used within I18nProvider');
  }

  return context;
};
