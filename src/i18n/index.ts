import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zh from './locales/zh';
import en from './locales/en';

export const SUPPORTED_LANGUAGES = ['zh', 'en'] as const;
export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];
export type Language = SupportedLanguage | 'auto';

export const resources = {
  zh: { translation: zh },
  en: { translation: en },
} as const;

i18n.use(initReactI18next).init({
  resources,
  lng: 'zh',
  fallbackLng: 'zh',
  interpolation: {
    escapeValue: false,
  },
});

export default i18n;
