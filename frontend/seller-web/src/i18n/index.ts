import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import hi from './hi.json';
import en from './en.json';

// 58.3's own real caveat, honestly carried over rather than silently
// ignored: these Hindi labels are standard-dictionary translations, not
// reviewed with a real native speaker ("ask your mother which language
// she actually prefers for each label... guessing produces an interface
// that reads as translated rather than native" - 56.3). Real deployment
// needs that real review before this ships; this scaffolding makes
// switching trivial once those real labels exist.
void i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    hi: { translation: hi },
  },
  lng: 'hi',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export default i18n;
