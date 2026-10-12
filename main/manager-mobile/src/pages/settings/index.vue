<route lang="jsonc" type="page">
{ "needLogin": true, "layout": "default", "style": { "navigationBarTitleText": "我的", "navigationStyle": "custom" } }
</route>
<script lang="ts" setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useUserStore } from '@/store/user'
import { clearServerBaseUrlOverride, getEnvBaseUrl, getServerBaseUrlOverride, setServerBaseUrlOverride } from '@/utils'
const user = useUserStore()
const isDevelopment = import.meta.env.DEV
const serverUrl = ref('')
const cacheSize = ref('0 MB')
const username = computed(() => user.userInfo.username || '我的账号')
function navigate(url: string) { uni.navigateTo({ url }) }
function refresh() {
  const info = uni.getStorageInfoSync()
  cacheSize.value = `${((info.currentSize || 0) / 1024).toFixed(2)} MB`
  if (isDevelopment) serverUrl.value = getServerBaseUrlOverride() || getEnvBaseUrl()
}
async function logout() {
  uni.showModal({ title: '退出账号', content: '退出当前用户会话？', success: async result => {
    if (!result.confirm) return
    try { await user.logout() }
    catch { uni.showToast({ title: '本机会话已清除', icon: 'none' }) }
    finally { uni.reLaunch({ url: '/pages/login/index' }) }
  } })
}
function clearCache() {
  uni.showModal({ title: '清理本机缓存', content: '将清除本机登录信息并返回登录页，不影响云端数据。', success: async result => {
    if (!result.confirm) return
    const override = isDevelopment ? getServerBaseUrlOverride() : null
    try { await user.logout() } catch { /* The local session is cleared in the store's finally block. */ }
    uni.clearStorageSync()
    if (override) setServerBaseUrlOverride(override)
    uni.reLaunch({ url: '/pages/login/index' })
  } })
}
async function saveDevelopmentServer(reset = false) {
  if (!isDevelopment) return
  if (!reset && !/^https?:\/\/[^\s]+\/xiaozhi\/?$/.test(serverUrl.value)) { uni.showToast({ title: '填写有效开发服务地址，以 /xiaozhi 结尾', icon: 'none' }); return }
  try { await user.logout() } catch { /* Do not carry an old session to another development origin. */ }
  if (reset) clearServerBaseUrlOverride()
  else setServerBaseUrlOverride(serverUrl.value)
  uni.reLaunch({ url: '/pages/login/index' })
}
onShow(refresh)
</script>
<template>
  <view class="mine-page"><wd-navbar title="我的" placeholder safe-area-inset-top fixed /><view class="mine-body"><view class="profile-card"><image :src="user.userInfo.avatar || '/static/images/default-avatar.png'" class="avatar" /><view><text class="profile-name">{{ username }}</text><text class="muted">个人空间与团队，分别归属、清楚使用</text></view></view><view class="menu-card"><view class="menu-row" @click="navigate('/pages/allowance/index')"><view><text class="menu-title">额度与用量</text><text class="muted">查看服务额度、团队承担关系和我的流水</text></view><text>›</text></view><view class="menu-row" @click="navigate('/pages/organization/index')"><view><text class="menu-title">我的团队</text><text class="muted">成员与协作空间</text></view><text>›</text></view></view><view class="menu-card"><view class="menu-row" @click="navigate('/pages/login/privacy-policy-zh')"><text class="menu-title">隐私政策</text><text>›</text></view><view class="menu-row" @click="navigate('/pages/login/user-agreement-zh')"><text class="menu-title">用户协议</text><text>›</text></view><view class="menu-row" @click="clearCache"><text class="menu-title">清理本机缓存</text><text class="muted">{{ cacheSize }}</text></view></view><view v-if="isDevelopment" class="menu-card dev-card"><text class="menu-title">开发服务地址</text><text class="muted">仅开发构建显示，正式发行固定受信任服务端。</text><wd-input v-model="serverUrl" placeholder="https://…/xiaozhi" /><wd-button size="small" @click="saveDevelopmentServer()">保存并重新登录</wd-button><wd-button size="small" type="text" @click="saveDevelopmentServer(true)">恢复默认</wd-button></view><wd-button block plain type="info" @click="logout">退出登录</wd-button><text class="footer-copy">服务额度由平台发放，无需自行配置模型密钥。</text></view></view>
</template>
<style scoped>
.mine-page{min-height:100vh;background:#f5f7fa;color:#2a4257}.mine-body{padding:30rpx 28rpx 70rpx}.profile-card{display:flex;gap:25rpx;align-items:center;padding:32rpx 6rpx 46rpx}.avatar{width:105rpx;height:105rpx;border-radius:50%;background:#e4edf4;flex-shrink:0}.profile-name{display:block;font-size:36rpx;font-weight:650}.muted{display:block;font-size:23rpx;line-height:1.8;color:#92a0ad;margin-top:8rpx}.menu-card{background:#fff;border:1px solid #e5ebf1;border-radius:23rpx;padding:0 26rpx;margin-bottom:30rpx}.menu-row{display:flex;align-items:center;justify-content:space-between;gap:20rpx;padding:30rpx 0;border-bottom:1px solid #edf1f5}.menu-row:last-child{border-bottom:0}.menu-row>text:last-child{color:#96a6b3}.menu-title{font-size:28rpx}.dev-card{padding:30rpx}.footer-copy{display:block;font-size:23rpx;color:#98a6b3;text-align:center;margin-top:30rpx;line-height:1.8}
</style>
