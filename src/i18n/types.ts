import 'i18next';
import type zh from './locales/zh';

// Maps all leaf string values to `string`, preserving the key structure for t() autocomplete
type DeepString<T> = {
  [K in keyof T]: T[K] extends string ? string : DeepString<T[K]>;
};

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation';
    // Use DeepString so i18next accepts all languages (string values, not Chinese literals)
    // Key structure is preserved, so t('nav.home') etc. still have autocomplete
    resources: {
      translation: DeepString<typeof zh>;
    };
  }
}
