export interface SafeAreaInsets {
  top: number
  right: number
  bottom: number
  left: number
}

export function getSafeAreaInsets(): SafeAreaInsets {
  // #ifdef MP-WEIXIN
  {
    const info = uni.getWindowInfo()
    if (info.safeArea) {
      return {
        top: info.safeArea.top,
        right: info.windowWidth - info.safeArea.right,
        bottom: info.windowHeight - info.safeArea.bottom,
        left: info.safeArea.left,
      }
    }
    return { top: 0, right: 0, bottom: 0, left: 0 }
  }
  // #endif

  // #ifndef MP-WEIXIN
  {
    const info = uni.getSystemInfoSync()
    return info.safeAreaInsets || { top: 0, right: 0, bottom: 0, left: 0 }
  }
  // #endif
}

export function getSystemSummary() {
  // #ifdef MP-WEIXIN
  {
    const device = uni.getDeviceInfo()
    const app = uni.getAppBaseInfo()
    return `${device.platform || ''} ${app.version || ''}`.trim()
  }
  // #endif

  // #ifndef MP-WEIXIN
  {
    const info = uni.getSystemInfoSync()
    return `${info.platform} ${info.system}`
  }
  // #endif
}
