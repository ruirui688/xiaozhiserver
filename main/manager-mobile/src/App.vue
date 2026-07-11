<script setup lang="ts">
import { onHide, onLaunch, onShow } from '@dcloudio/uni-app'
import { usePageAuth } from '@/hooks/usePageAuth'
import { useConfigStore } from '@/store'
import { t } from '@/i18n'
import 'abortcontroller-polyfill/dist/abortcontroller-polyfill-only'

usePageAuth()

const configStore = useConfigStore()

onLaunch(() => {
  console.log('App Launch')
  // 获取公共配置
  configStore.fetchPublicConfig().catch((error) => {
    console.error('获取公共配置失败:', error)
  })
})
onShow(() => {
  console.log('App Show')
  // 使用setTimeout延迟执行，确保tabBar已经初始化
  setTimeout(() => {
    updateTabBarTextIfNeeded()
  }, 100)
})

function isCurrentPageInTabBar() {
  const pages = getCurrentPages()
  const currentPage = pages[pages.length - 1]
  const currentRoute = currentPage?.route
  return [
    'pages/index/index',
    'pages/settings/index',
  ].includes(currentRoute || '')
}

function updateTabBarTextIfNeeded() {
  if (!isCurrentPageInTabBar())
    return

  updateTabBarText()
}

// 动态更新tabBar文本
function updateTabBarText() {
  try {
    // 设置首页tabBar文本
    uni.setTabBarItem({
      index: 0,
      text: t('tabBar.home'),
      success: () => {},
      fail: (err) => {
        console.log('设置首页tabBar文本失败:', err)
      }
    })
    
    // 设置系统tabBar文本
    uni.setTabBarItem({
      index: 1,
      text: t('tabBar.settings'),
      success: () => {},
      fail: (err) => {
        console.log('设置系统tabBar文本失败:', err)
      }
    })
  } catch (error) {
    console.log('更新tabBar文本时出错:', error)
  }
}
onHide(() => {
  console.log('App Hide')
})
</script>

<style lang="scss">
swiper,
scroll-view {
  flex: 1;
  height: 100%;
  overflow: hidden;
}

image {
  width: 100%;
  height: 100%;
  vertical-align: middle;
}
</style>
