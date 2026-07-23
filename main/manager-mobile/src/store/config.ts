import type { PublicConfig } from '@/api/auth'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getPublicConfig } from '@/api/auth'
import { createInitialPublicConfig, deserializePublicConfig, normalizePublicConfig } from './configState'

const appTitle = import.meta.env.VITE_APP_TITLE
const initialConfigState = createInitialPublicConfig(appTitle)

export const useConfigStore = defineStore(
  'config',
  () => {
    // 定义全局配置
    const config = ref<PublicConfig>({ ...initialConfigState })

    // 设置配置信息
    const setConfig = (val: PublicConfig) => {
      config.value = normalizePublicConfig(val, appTitle)
    }

    // 获取公共配置
    const fetchPublicConfig = async () => {
      const configData = await getPublicConfig()
      if (!configData || typeof configData !== 'object')
        throw new Error('公共配置响应为空')

      setConfig(configData)
      return config.value
    }

    // 重置配置
    const resetConfig = () => {
      config.value = { ...initialConfigState }
    }

    return {
      config,
      setConfig,
      fetchPublicConfig,
      resetConfig,
    }
  },
  {
    persist: {
      key: 'config',
      serializer: {
        serialize: state => JSON.stringify(state.config),
        deserialize: value => ({ config: deserializePublicConfig(value, appTitle) }),
      },
    },
  },
)
