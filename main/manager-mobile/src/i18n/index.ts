import { ref } from 'vue'
import { useLangStore } from '@/store/lang'
import type { Language } from '@/store/lang'

import zh_CN from './zh_CN'

const messages = {
  zh_CN: zh_CN,
}

const currentLang = ref<Language>('zh_CN')

export function initI18n() {
  const langStore = useLangStore()
  currentLang.value = langStore.currentLang
}

// 获取翻译文本
export function t(key: string, params?: Record<string, string | number>): string {
  const langMessages = messages[currentLang.value]

  // 直接查找扁平键名
  if (langMessages && typeof langMessages === 'object' && key in langMessages) {
    let value = langMessages[key]
    if (typeof value === 'string') {
      // 处理参数替换
      if (params) {
        let result = value
        Object.entries(params).forEach(([paramKey, paramValue]) => {
          const regex = new RegExp(`\{${paramKey}\}`, 'g')
          result = result.replace(regex, String(paramValue))
        })
        return result
      }
      return value
    }
    return key
  }

  return key
}
