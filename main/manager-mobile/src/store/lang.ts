import { ref } from 'vue'
import { defineStore } from 'pinia'

export type Language = 'zh_CN'

export const useLangStore = defineStore(
  'lang',
  () => {
    const currentLang = ref<Language>('zh_CN')

    uni.setStorageSync('app_language', 'zh_CN')

    return {
      currentLang,
    }
  },
  {
    persist: {
      key: 'lang',
      serializer: {
        serialize: state => JSON.stringify(state.currentLang),
        deserialize: value => ({ currentLang: JSON.parse(value) }),
      },
    },
  },
)
