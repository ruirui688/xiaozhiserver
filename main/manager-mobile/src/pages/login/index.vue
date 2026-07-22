<route lang="jsonc" type="home">
{
  "layout": "default",
  "style": {
    "navigationStyle": "custom",
    "navigationBarTitleText": "登录"
  }
}
</route>

<script lang="ts" setup>
import type { LoginData } from '@/api/auth'
import { computed, ref } from 'vue'
import { login, wxBind, wxLogin } from '@/api/auth'
import { initI18n, t } from '@/i18n'
import { useConfigStore, useUserStore } from '@/store'
// 导入SM2加密工具
import { getEnvBaseUrl, sm2Encrypt } from '@/utils'
import { storeLoginToken } from '@/utils/auth'
import { getSafeAreaInsets } from '@/utils/system'
import { toast } from '@/utils/toast'

const safeAreaInsets = getSafeAreaInsets()
// 表单数据
const formData = ref({
  username: '',
  password: '',
  captcha: '',
  captchaId: '',
  areaCode: '+86',
  mobile: '',
})

// 验证码图片
const captchaImage = ref('')
const loading = ref(false)
const wxLoading = ref(false)

// 登录方式：'username' | 'mobile'
const loginType = ref<'username' | 'mobile'>('username')

// 获取配置store
const configStore = useConfigStore()
const userStore = useUserStore()

// 区号选择相关
const showAreaCodeSheet = ref(false)
const selectedAreaCode = ref('+86')
const selectedAreaName = ref('中国大陆')

// 计算属性：是否启用手机号登录
const enableMobileLogin = computed(() => {
  return configStore.config.enableMobileRegister
})

const allowUserRegister = computed(() => {
  return Boolean(configStore.config.allowUserRegister)
})

// 计算属性：区号列表
const areaCodeList = computed(() => {
  return configStore.config.mobileAreaList || [{ name: '中国大陆', key: '+86' }]
})

// 切换登录方式
function toggleLoginType() {
  loginType.value = loginType.value === 'username' ? 'mobile' : 'username'
  // 清空输入框
  formData.value.username = ''
  formData.value.mobile = ''
}

// 打开区号选择弹窗
function openAreaCodeSheet() {
  showAreaCodeSheet.value = true
}

// 选择区号
function selectAreaCode(item: { name: string, key: string }) {
  selectedAreaCode.value = item.key
  selectedAreaName.value = item.name
  formData.value.areaCode = item.key
  showAreaCodeSheet.value = false
}

// 关闭区号选择弹窗
function closeAreaCodeSheet() {
  showAreaCodeSheet.value = false
}

// 跳转到注册页面
function goToRegister() {
  uni.navigateTo({
    url: '/pages/register/index',
  })
}

// 跳转到忘记密码页面
function goToForgotPassword() {
  uni.navigateTo({
    url: '/pages/forgot-password/index',
  })
}

// 跳转到用户协议
function goToUserAgreement() {
  uni.navigateTo({
    url: '/pages/login/user-agreement-zh',
  })
}

// 跳转到隐私政策
function goToPrivacyPolicy() {
  uni.navigateTo({
    url: '/pages/login/privacy-policy-zh',
  })
}

async function enterHome() {
  await userStore.getUserInfo()
  uni.reLaunch({
    url: '/pages/index/index',
  })
}

async function bindCurrentWechatAccount() {
  // #ifdef MP-WEIXIN
  try {
    const result = await uni.login({ provider: 'weixin' })
    if (result.code)
      await wxBind({ code: result.code })
  }
  catch {
    toast.warning(t('login.wxBindFailed'))
  }
  // #endif
}

function loginErrorMessage(error: unknown, fallback: string) {
  if (typeof error === 'string')
    return error
  if (error instanceof Error)
    return error.message || fallback
  if (error && typeof error === 'object' && 'errMsg' in error)
    return String((error as { errMsg?: string }).errMsg || fallback)
  return fallback
}

async function handleWxLogin() {
  // #ifdef MP-WEIXIN
  try {
    wxLoading.value = true
    const result = await uni.login({ provider: 'weixin' })
    if (!result.code)
      throw new Error(t('login.wxCodeMissing'))

    const response = await wxLogin({ code: result.code })
    storeLoginToken(response)
    await enterHome()
    toast.success(t('message.loginSuccess'))
  }
  catch (error) {
    toast.warning(loginErrorMessage(error, t('login.wxLoginFailed')))
  }
  finally {
    wxLoading.value = false
  }
  // #endif
}

// 生成UUID
function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0
    const v = c === 'x' ? r : (r & 0x3 | 0x8)
    return v.toString(16)
  })
}

function arrayBufferToBase64(buffer: ArrayBuffer) {
  // #ifdef MP-WEIXIN
  return wx.arrayBufferToBase64(buffer)
  // #endif

  // #ifndef MP-WEIXIN
  let binary = ''
  const bytes = new Uint8Array(buffer)
  const chunkSize = 0x8000
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize))
  }
  return btoa(binary)
  // #endif
}

// 跳转至服务端设置页面
function goToServerSetting() {
  uni.switchTab({
    url: '/pages/settings/index',
  })
}

// 获取验证码
async function refreshCaptcha() {
  const uuid = generateUUID()
  formData.value.captchaId = uuid
  const captchaUrl = `${getEnvBaseUrl()}/user/captcha?uuid=${uuid}&t=${Date.now()}`

  // #ifdef MP-WEIXIN
  try {
    const response = await uni.request({
      url: captchaUrl,
      method: 'GET',
      responseType: 'arraybuffer',
      timeout: 5000,
    })

    if (response.statusCode === 200 && response.data) {
      captchaImage.value = `data:image/png;base64,${arrayBufferToBase64(response.data as ArrayBuffer)}`
      return
    }
  }
  catch (error) {
    toast.warning(t('login.captchaLoadFailed'))
  }
  captchaImage.value = ''
  return
  // #endif

  // #ifndef MP-WEIXIN
  captchaImage.value = captchaUrl
  // #endif
}

// 登录
async function handleLogin() {
  // 表单验证
  if (loginType.value === 'username') {
    if (!formData.value.username) {
      toast.warning(t('login.enterUsername'))
      return
    }
  }
  else {
    if (!formData.value.mobile) {
      toast.warning(t('login.enterPhone'))
      return
    }
    // 手机号格式验证
    const phoneRegex = /^1[3-9]\d{9}$/
    if (!phoneRegex.test(formData.value.mobile)) {
      toast.warning(t('login.enterPhone'))
      return
    }
  }
  if (!formData.value.password) {
    toast.warning(t('login.enterPassword'))
    return
  }
  if (!formData.value.captcha) {
    toast.warning(t('login.enterCaptcha'))
    return
  }

  if (!formData.value.captchaId) {
    await refreshCaptcha()
    toast.warning(t('login.enterCaptcha'))
    return
  }

  // 检查SM2公钥是否配置
  if (!configStore.config.sm2PublicKey) {
    try {
      await configStore.fetchPublicConfig()
    }
    catch {
      toast.warning(t('login.serverUnavailable'))
      return
    }
  }
  const sm2PublicKey = configStore.config.sm2PublicKey
  if (!sm2PublicKey) {
    toast.warning(t('sm2.publicKeyNotConfigured'))
    return
  }

  try {
    loading.value = true

    // 加密密码
    let encryptedPassword
    try {
      // 拼接验证码和密码
      const captchaAndPassword = formData.value.captcha + formData.value.password
      encryptedPassword = sm2Encrypt(sm2PublicKey, captchaAndPassword)
    }
    catch (error) {
      console.error('密码加密失败:', error)
      toast.warning(t('sm2.encryptionFailed'))
      return
    }

    // 构建登录数据
    const loginData: LoginData = {
      username: '',
      password: encryptedPassword,
      captchaId: formData.value.captchaId,
    }

    // 如果是手机号登录，将区号+手机号拼接到username字段
    if (loginType.value === 'mobile') {
      loginData.username = `${selectedAreaCode.value}${formData.value.mobile}`
    }
    else {
      loginData.username = formData.value.username
    }

    const response = await login(loginData)
    // 存储token
    storeLoginToken(response)
    await bindCurrentWechatAccount()

    toast.success(t('message.loginSuccess'))

    // 跳转到主页
    setTimeout(() => {
      enterHome()
    }, 1000)
  }
  catch (error: any) {
    const errorMessage = error?.message || String(error || '')
    if (errorMessage.includes('请求错误[10067]')) {
      toast.warning(t('login.captchaError'))
    }
    else if (errorMessage.includes('请求错误[10004]')) {
      toast.warning(t('message.loginFail'))
    }
    else {
      toast.warning(errorMessage || t('message.loginFail'))
    }
    // 登录失败重新获取验证码
    refreshCaptcha()
  }
  finally {
    loading.value = false
  }
}

// 初始化国际化
initI18n()
</script>

<template>
  <view class="app-container box-border h-screen w-full" :style="{ paddingTop: `${safeAreaInsets?.top}px` }">
    <view class="header">
      <view class="logo-section">
        <wd-img :width="72" :height="72" round src="/static/logo.png" class="logo" />
        <text class="welcome-text">
          {{ t('login.welcomeBack') }}
        </text>
        <text class="subtitle">
          {{ t('login.pleaseLogin') }}
        </text>
      </view>
    </view>

    <!-- 右上角按钮组 -->
    <view class="top-right-buttons" :style="{ top: `${safeAreaInsets?.top + 10}px` }">
      <!-- 服务端设置按钮 -->
      <view class="server-btn" @click="goToServerSetting">
        <wd-icon name="setting" custom-class="server-icon" />
      </view>
    </view>

    <view class="form-container">
      <view class="form">
        <!-- #ifdef MP-WEIXIN -->
        <view class="wechat-entry" :class="{ disabled: wxLoading }" @click="handleWxLogin">
          <wd-icon name="wechat" custom-class="wechat-icon" />
          <text>{{ wxLoading ? t('login.loggingIn') : t('login.wechatLoginButton') }}</text>
        </view>
        <view class="login-divider">
          <view class="divider-line" />
          <text>{{ t('login.passwordLogin') }}</text>
          <view class="divider-line" />
        </view>
        <!-- #endif -->
        <view class="password-form">
          <!-- 手机号登录 -->
          <template v-if="loginType === 'mobile'">
            <view class="input-group">
              <view class="input-wrapper mobile-wrapper">
                <view class="area-code-selector" @click="openAreaCodeSheet">
                  <text class="area-code-text">
                    {{ selectedAreaCode }}
                  </text>
                  <wd-icon name="arrow-down" custom-class="area-code-arrow" />
                </view>
                <view class="mobile-input-wrapper">
                  <wd-input
                    v-model="formData.mobile"
                    custom-class="styled-input"
                    no-border
                    :placeholder="t('login.enterPhone')"
                    type="number"
                    :maxlength="11"
                  />
                </view>
              </view>
            </view>
          </template>

          <!-- 用户名登录 -->
          <template v-else>
            <view class="input-group">
              <view class="input-wrapper">
                <wd-input
                  v-model="formData.username"
                  custom-class="styled-input"
                  no-border
                  :placeholder="t('login.enterUsername')"
                />
              </view>
            </view>
          </template>

          <view class="input-group">
            <view class="input-wrapper">
              <wd-input
                v-model="formData.password"
                custom-class="styled-input"
                no-border
                :placeholder="t('login.enterPassword')"
                clearable
                show-password
                :maxlength="20"
              />
            </view>
          </view>

          <view class="input-group">
            <view class="input-wrapper captcha-wrapper">
              <wd-input
                v-model="formData.captcha"
                custom-class="styled-input"
                no-border
                :placeholder="t('login.enterCaptcha')"
                :maxlength="6"
              />
              <view class="captcha-image" @click="refreshCaptcha">
                <image v-if="captchaImage" :src="captchaImage" class="captcha-img" />
                <text v-else class="captcha-placeholder">
                  {{ t('login.loadCaptcha') }}
                </text>
              </view>
            </view>
          </view>
          <view
            class="login-btn"
            @click="handleLogin"
          >
            {{ loading ? t('login.loggingIn') : t('login.loginButton') }}
          </view>

          <view class="hint-row">
            <view class="register-hint">
              <text class="register-link" @click="goToRegister">
                {{ t('login.noAccount') }}
              </text>
            </view>

            <view class="forgot-password">
              <text class="forgot-text" @click="goToForgotPassword">
                {{ t('login.forgotPassword') }}
              </text>
            </view>
          </view>

          <view class="policy-links">
            <text class="policy-link" @click="goToUserAgreement">
              {{ t('login.userAgreement') }}
            </text>
            <text class="policy-divider">
              |
            </text>
            <text class="policy-link" @click="goToPrivacyPolicy">
              {{ t('login.privacyPolicy') }}
            </text>
          </view>

          <!-- 登录方式切换 -->
          <view v-if="enableMobileLogin" class="login-type-switch">
            <view class="switch-tabs">
              <view
                class="switch-tab"
                :class="{ active: loginType === 'username' }"
                @click="toggleLoginType"
              >
                <wd-icon name="user" />
              </view>
              <view
                class="switch-tab"
                :class="{ active: loginType === 'mobile' }"
                @click="toggleLoginType"
              >
                <wd-icon name="phone" />
              </view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 区号选择弹窗 -->
    <wd-action-sheet
      v-model="showAreaCodeSheet"
      :title="t('login.selectCountry')"
      :close-on-click-modal="true"
      @close="closeAreaCodeSheet"
    >
      <view class="area-code-sheet">
        <scroll-view scroll-y class="area-code-list">
          <view
            v-for="item in areaCodeList"
            :key="item.key"
            class="area-code-item"
            :class="{ selected: selectedAreaCode === item.key }"
            @click="selectAreaCode(item)"
          >
            <view class="area-info">
              <text class="area-name">
                {{ item.name }}
              </text>
              <text class="area-code">
                {{ item.key }}
              </text>
            </view>
            <wd-icon
              v-if="selectedAreaCode === item.key"
              name="check"
              custom-class="check-icon"
            />
          </view>
        </scroll-view>
        <view class="sheet-footer">
          <wd-button
            type="primary"
            custom-class="confirm-btn"
            @click="closeAreaCodeSheet"
          >
            {{ t('login.confirm') }}
          </wd-button>
        </view>
      </view>
    </wd-action-sheet>
  </view>
</template>

<style lang="scss" scoped>
.app-container {
  background:
    linear-gradient(180deg, rgba(75, 132, 240, 0.78) 0%, rgba(121, 171, 248, 0.58) 34%, rgba(247, 249, 252, 1) 70%),
    #f7f9fc;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  min-height: 100vh;
}

.header {
  flex: 0 0 auto;
  min-height: 246rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56rpx 0 24rpx 0;

  .logo-section {
    text-align: center;

    .logo {
      margin-bottom: 18rpx;
      box-shadow: 0 10rpx 30rpx rgba(28, 78, 150, 0.18);
    }

    .welcome-text {
      display: block;
      color: #ffffff;
      font-size: 38rpx;
      font-weight: 700;
      margin-bottom: 8rpx;
      text-shadow: 0 2rpx 8rpx rgba(39, 91, 161, 0.22);
    }

    .subtitle {
      display: block;
      color: rgba(255, 255, 255, 0.88);
      font-size: 24rpx;
      font-weight: 400;
    }
  }
}

.form-container {
  padding: 0 40rpx 32rpx 40rpx;
  flex: 1;
  display: flex;
  align-items: flex-start;

  .form {
    width: 100%;
    background: #ffffff;
    border-radius: 18rpx;
    padding: 28rpx;
    backdrop-filter: blur(10rpx);
    box-shadow: 0 14rpx 38rpx rgba(35, 51, 83, 0.12);
    border: 1rpx solid rgba(207, 216, 230, 0.8);
    max-height: calc(100vh - 300rpx);
    overflow-y: auto;

    .input-group {
      margin-bottom: 18rpx;

      .input-wrapper {
        position: relative;
        border-radius: 12rpx;
        padding: 15rpx 16rpx;
        border: 1rpx solid #dce4f0;
        background: #f8fafc;
        transition:
          border-color 0.2s ease,
          box-shadow 0.2s ease,
          background 0.2s ease;
        display: flex;
        align-items: center;
        min-height: 58rpx;

        &:focus-within {
          border-color: #4d80f0;
          background: #ffffff;
          box-shadow: 0 0 0 5rpx rgba(77, 128, 240, 0.1);
        }

        &.captcha-wrapper {
          .captcha-image {
            margin-left: 16rpx;
            width: 148rpx;
            height: 58rpx;
            border-radius: 8rpx;
            overflow: hidden;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            background: #e9ecef;
            border: 1rpx solid #ddd;

            .captcha-img {
              width: 100%;
              height: 100%;
            }

            .captcha-placeholder,
            .captcha-loading {
              font-size: 20rpx;
              color: #999;
            }
          }
        }

        &.mobile-wrapper {
          padding: 0;
          background: transparent;
          border: none;
          display: flex;
          gap: 20rpx;

          .area-code-selector {
            flex: 0 0 160rpx;
            background: #f8f9fa;
            border-radius: 12rpx;
            padding: 15rpx 16rpx;
            border: 1rpx solid #dce4f0;
            min-height: 58rpx;
            display: flex;
            align-items: center;
            justify-content: space-between;
            cursor: pointer;
            transition:
              border-color 0.2s ease,
              box-shadow 0.2s ease,
              background 0.2s ease;

            &:hover {
              border-color: #4d80f0;
              background: #ffffff;
              box-shadow: 0 0 0 5rpx rgba(77, 128, 240, 0.1);
            }

            .area-code-text {
              font-size: 28rpx;
              color: #333333;
              font-weight: 500;
            }

            :deep(.area-code-arrow) {
              font-size: 24rpx;
              color: #999999;
              transition: transform 0.3s ease;
            }
          }

          .mobile-input-wrapper {
            flex: 1;
            background: #f8f9fa;
            border-radius: 12rpx;
            padding: 15rpx 16rpx;
            border: 1rpx solid #dce4f0;
            transition:
              border-color 0.2s ease,
              box-shadow 0.2s ease,
              background 0.2s ease;

            &:focus-within {
              border-color: #4d80f0;
              background: #ffffff;
              box-shadow: 0 0 0 5rpx rgba(77, 128, 240, 0.1);
            }
          }
        }

        :deep(.styled-input) {
          background: transparent !important;
          border: none !important;
          outline: none !important;
          font-size: 29rpx;
          color: #1f2937;
          flex: 1;

          &::placeholder {
            color: #9aa4b2;
            font-size: 27rpx;
          }
        }
      }
    }

    .hint-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 22rpx;
    }

    .policy-links {
      display: flex;
      justify-content: center;
      align-items: center;
      gap: 18rpx;
      margin-bottom: 18rpx;

      .policy-link {
        color: #667eea;
        font-size: 26rpx;
        cursor: pointer;

        &:hover {
          text-decoration: underline;
        }
      }

      .policy-divider {
        color: #999999;
        font-size: 26rpx;
      }
    }

    .forgot-password {
      .forgot-text {
        color: #667eea;
        font-size: 26rpx;
        cursor: pointer;

        &:hover {
          text-decoration: underline;
        }
      }
    }

    .login-btn {
      width: 100%;
      height: 76rpx;
      border: none;
      border-radius: 12rpx;
      font-size: 30rpx;
      font-weight: 600;
      color: #ffffff;
      margin-bottom: 22rpx;
      box-shadow: 0 8rpx 18rpx rgba(77, 128, 240, 0.24);
      transition:
        transform 0.2s ease,
        box-shadow 0.2s ease;
      background-color: var(--wot-button-primary-bg-color, var(--wot-color-theme, #4d80f0));
      text-align: center;
      line-height: 76rpx;
      &:active {
        transform: translateY(2rpx);
        box-shadow: 0 4rpx 10rpx rgba(77, 128, 240, 0.24);
      }

      &:disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }
    }

    .register-hint {
      .register-link {
        color: #667eea;
        font-size: 26rpx;
        font-weight: 500;
        cursor: pointer;
        // text-decoration: underline;
        // &:hover {
        //   text-decoration: underline;
        // }
      }
    }

    .login-type-switch {
      margin-top: 20rpx;
      text-align: center;

      .switch-tabs {
        display: flex;
        justify-content: center;
        gap: 60rpx;
        margin-bottom: 20rpx;

        .switch-tab {
          width: 60rpx;
          height: 60rpx;
          border-radius: 50%;
          background: #f0f0f0;
          display: flex;
          align-items: center;
          justify-content: center;
          cursor: pointer;
          transition: all 0.3s ease;
          border: 2rpx solid transparent;

          &.active {
            background: #667eea;
            color: #ffffff;
            border-color: #667eea;
            box-shadow: 0 4rpx 12rpx rgba(102, 126, 234, 0.3);
          }

          :deep(.wd-icon) {
            font-size: 24rpx;
          }
        }
      }

      .switch-hint {
        font-size: 24rpx;
        color: #666666;
      }
    }
  }
}

.password-form {
  display: block;
}

.wechat-entry {
  height: 76rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  border-radius: 12rpx;
  background: #07c160;
  color: #ffffff;
  font-size: 29rpx;
  font-weight: 600;

  &:active {
    background: #06ad56;
  }

  &.disabled {
    opacity: 0.6;
  }
}

:deep(.wechat-icon) {
  font-size: 34rpx;
}

.login-divider {
  margin: 24rpx 0;
  display: flex;
  align-items: center;
  gap: 18rpx;
  color: #8993a3;
  font-size: 23rpx;
}

.divider-line {
  height: 1rpx;
  flex: 1;
  background: #e4e9f1;
}

// 区号选择弹窗样式
.area-code-sheet {
  background: #ffffff;
  border-radius: 24rpx 24rpx 0 0;
  overflow: hidden;

  .sheet-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 40rpx 40rpx 20rpx 40rpx;
    border-bottom: 1rpx solid #f0f0f0;

    .sheet-title {
      font-size: 36rpx;
      font-weight: 600;
      color: #333333;
    }

    :deep(.close-icon) {
      font-size: 32rpx;
      color: #999999;
      cursor: pointer;
      padding: 10rpx;

      &:hover {
        color: #333333;
      }
    }
  }

  .area-code-list {
    max-height: 60vh;
    padding: 0 40rpx;

    .area-code-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 32rpx 0;
      border-bottom: 1rpx solid #f8f9fa;
      cursor: pointer;
      transition: background-color 0.3s ease;

      &:hover {
        background-color: #f8f9fa;
      }

      &.selected {
        background-color: rgba(102, 126, 234, 0.05);

        .area-name {
          color: #667eea;
          font-weight: 500;
        }

        .area-code {
          color: #667eea;
        }
      }

      .area-info {
        display: flex;
        flex-direction: column;
        gap: 8rpx;

        .area-name {
          font-size: 32rpx;
          color: #333333;
        }

        .area-code {
          font-size: 28rpx;
          color: #666666;
        }
      }

      :deep(.check-icon) {
        font-size: 32rpx;
        color: #667eea;
      }
    }
  }

  .sheet-footer {
    padding: 30rpx 40rpx 40rpx 40rpx;
    border-top: 1rpx solid #f0f0f0;

    :deep(.confirm-btn) {
      width: 100%;
      height: 88rpx;
      border-radius: 16rpx;
      font-size: 32rpx;
      font-weight: 600;
    }
  }
}
// 右上角按钮组
.top-right-buttons {
  position: absolute;
  right: 20rpx;
  display: flex;
  gap: 20rpx;
  z-index: 999;
}

// 服务端设置按钮
.server-btn {
  width: 48rpx;
  height: 48rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 24rpx;
  box-shadow: 0 4rpx 12rpx rgba(0, 0, 0, 0.2);

  &:active {
    transform: scale(0.95);
  }

  .server-icon {
    font-size: 28rpx;
    color: #ffffff;
  }

  &:hover {
    background: rgba(255, 255, 255, 0.25);
  }
}
</style>
