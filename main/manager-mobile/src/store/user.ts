import type { UserInfo } from '@/api/auth'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getUserInfo as _getUserInfo,
  logoutCurrentSession,
} from '@/api/auth'
import { clearCurrentWorkspaceId } from '@/utils/workspace'

// 初始化状态
const userInfoState: UserInfo & { avatar?: string, token?: string } = {
  id: 0,
  username: '',
  realName: '',
  email: '',
  mobile: '',
  status: 0,
  audience: 'USER',
  avatar: '/static/images/default-avatar.png',
  token: '',
}

export const useUserStore = defineStore(
  'userInfo',
  () => {
    // 定义用户信息
    const userInfo = ref<UserInfo & { avatar?: string, token?: string }>({ ...userInfoState })
    // 设置用户信息
    const setUserInfo = (val: UserInfo & { avatar?: string, token?: string }) => {
      if (val.audience !== 'USER') throw new Error('此应用仅支持用户身份')
      // 若头像为空则使用默认头像，已有头像保留后端返回值。
      if (!val.avatar)
        val.avatar = userInfoState.avatar

      userInfo.value = val
    }
    const setUserAvatar = (avatar: string) => {
      userInfo.value.avatar = avatar
    }
    // 删除用户信息
    const removeUserInfo = () => {
      userInfo.value = { ...userInfoState }
      uni.removeStorageSync('userInfo')
      uni.removeStorageSync('token')
      clearCurrentWorkspaceId()
    }
    /**
     * 获取用户信息
     */
    const getUserInfo = async () => {
      const userData = await _getUserInfo()
      setUserInfo(userData)
      return userData
    }
    /**
     * 退出登录 并 删除用户信息
     */
    const logout = async () => {
      try {
        await logoutCurrentSession()
      }
      finally {
        removeUserInfo()
      }
    }

    return {
      userInfo,
      getUserInfo,
      setUserInfo,
      setUserAvatar,
      logout,
      removeUserInfo,
    }
  },
  {
    persist: {
      key: 'userInfo',
      serializer: {
        serialize: state => JSON.stringify(state.userInfo),
        deserialize: value => ({ userInfo: JSON.parse(value) }),
      },
    },
  },
)
