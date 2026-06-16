import Vue from 'vue';
import VueI18n from 'vue-i18n';
import zhCN from './zh_CN';

Vue.use(VueI18n);

const DEFAULT_LANGUAGE = 'zh_CN';
localStorage.setItem('userLanguage', DEFAULT_LANGUAGE);

const i18n = new VueI18n({
  locale: DEFAULT_LANGUAGE,
  fallbackLocale: DEFAULT_LANGUAGE,
  messages: {
    [DEFAULT_LANGUAGE]: zhCN
  }
});

export default i18n;
