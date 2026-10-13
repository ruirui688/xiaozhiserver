<route lang="jsonc" type="page">
{
  "layout": "default",
  "style": {
    "navigationBarTitleText": "工作空间",
    "navigationStyle": "custom"
  }
}
</route>

<script lang="ts" setup>
import type { Workspace, WorkspaceInvitation, WorkspaceMember, WorkspaceRole } from '@/api/workspace'
import { computed, onMounted, reactive, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import {
  acceptWorkspaceInvitation,
  getWorkspaceInvitations,
  getWorkspaceList,
  getWorkspaceMembers,
  inviteWorkspaceMember,
  leaveWorkspace,
  removeWorkspaceMember,
  revokeWorkspaceInvitation,
  updateWorkspaceMemberRole,
} from '@/api/workspace'
import { useUserStore } from '@/store'
import { parseDateTime } from '@/utils'
import { clearCurrentWorkspaceId, getCurrentWorkspaceId, setCurrentWorkspaceId } from '@/utils/workspace'

defineOptions({
  name: 'OrganizationPage',
})

const maxMembers = 5
const toast = useToast()
const userStore = useUserStore()

const loading = ref(false)
const memberLoading = ref(false)
const loadError = ref('')
const workspaceList = ref<Workspace[]>([])
const currentWorkspaceId = ref(getCurrentWorkspaceId())
const members = ref<WorkspaceMember[]>([])
const pendingInvitations = ref<WorkspaceInvitation[]>([])
const inviteCode = ref('')
const lastInviteCode = ref('')
const showInvitePanel = ref(false)
const inviteForm = reactive({
  username: '',
  role: 'MEMBER' as WorkspaceRole,
})

const currentWorkspace = computed(() => {
  return workspaceList.value.find(item => item.id === currentWorkspaceId.value)
})

const canWrite = computed(() => !!currentWorkspace.value?.canWrite)
const isOwner = computed(() => currentWorkspace.value?.currentUserRole === 'OWNER')
const canLeaveWorkspace = computed(() => !!currentWorkspace.value && currentWorkspace.value.currentUserRole !== 'OWNER')
const currentUserId = computed(() => userStore.userInfo.id)
const occupiedSlots = computed(() => members.value.length + pendingInvitations.value.length)

function roleLabel(role: WorkspaceRole | string) {
  const labels: Record<string, string> = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '普通成员' }
  return labels[role] || role
}

function roleClass(role: WorkspaceRole | string) {
  const classes: Record<string, string> = {
    OWNER: 'bg-[#fff1f0] text-[#cf1322]',
    ADMIN: 'bg-[#fff7e6] text-[#ad6800]',
    MEMBER: 'bg-[#eef3ff] text-[#336cff]',
  }
  return classes[role] || 'bg-[#f0f2f5] text-[#65686f]'
}

function formatJoinedAt(value?: string) {
  if (!value)
    return ''

  const date = parseDateTime(value)
  if (Number.isNaN(date.getTime()))
    return value

  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hour}:${minute}`
}

function getErrorMessage(error: unknown, fallback: string) {
  const message = error instanceof Error ? error.message : String(error || '')
  if (/timeout/i.test(message))
    return '网络请求超时，请稍后重试'
  return message || fallback
}

function goBack() {
  uni.navigateBack()
}

function isSelf(member: WorkspaceMember) {
  return String(member.userId) === String(currentUserId.value)
}

function canChangeRole(member: WorkspaceMember) {
  return isOwner.value && member.role !== 'OWNER' && !isSelf(member)
}

function canRemoveMember(member: WorkspaceMember) {
  // Removing another member stays an owner action; a writer without ownership cannot do it.
  if (!canWrite.value || !isOwner.value || member.role === 'OWNER' || isSelf(member))
    return false
  return true
}

async function loadWorkspaces(preferredWorkspaceId = currentWorkspaceId.value) {
  loading.value = true
  loadError.value = ''
  try {
    workspaceList.value = await getWorkspaceList()
    const preferred = preferredWorkspaceId && workspaceList.value.some(item => item.id === preferredWorkspaceId)
      ? preferredWorkspaceId
      : workspaceList.value[0]?.id || ''

    if (preferred) {
      await selectWorkspace(preferred)
    }
    else {
      currentWorkspaceId.value = ''
      members.value = []
      pendingInvitations.value = []
      clearCurrentWorkspaceId()
    }
  }
  catch (error) {
    loadError.value = getErrorMessage(error, '工作空间加载失败')
    toast.warning(loadError.value)
  }
  finally {
    loading.value = false
  }
}

async function selectWorkspace(workspaceId: string) {
  currentWorkspaceId.value = workspaceId
  setCurrentWorkspaceId(workspaceId)
  await loadMembers()
}

async function loadMembers() {
  if (!currentWorkspaceId.value)
    return
  memberLoading.value = true
  try {
    members.value = await getWorkspaceMembers(currentWorkspaceId.value)
    pendingInvitations.value = canWrite.value
      ? await getWorkspaceInvitations(currentWorkspaceId.value)
      : []
  }
  catch (error) {
    members.value = []
    pendingInvitations.value = []
    toast.warning(getErrorMessage(error, '成员加载失败'))
  }
  finally {
    memberLoading.value = false
  }
}

async function acceptInvitation() {
  const code = inviteCode.value.trim()
  if (!code) {
    toast.warning('请输入邀请码')
    return
  }
  try {
    const workspace = await acceptWorkspaceInvitation(code)
    inviteCode.value = ''
    toast.success('已加入工作空间')
    await loadWorkspaces(workspace.id)
  }
  catch (error) {
    toast.warning(getErrorMessage(error, '加入工作空间失败'))
  }
}

async function inviteMember() {
  if (!canWrite.value)
    return
  const username = inviteForm.username.trim()
  if (!currentWorkspaceId.value || !username)
    return toast.warning('请输入用户名')
  if (occupiedSlots.value >= maxMembers)
    return toast.warning('工作空间名额已满，请先撤销待接受邀请或移除成员')

  try {
    const invitation = await inviteWorkspaceMember(currentWorkspaceId.value, {
      username,
      role: inviteForm.role,
    })
    lastInviteCode.value = invitation.code || ''
    toast.success('邀请已生成')
    await loadMembers()
  }
  catch (error) {
    toast.warning(getErrorMessage(error, '邀请生成失败'))
  }
}


function confirmRevokeInvitation(invitation: WorkspaceInvitation) {
  if (!currentWorkspaceId.value || !canWrite.value)
    return
  const invitationTarget = invitation.inviteeUsername
    ? `对 ${invitation.inviteeUsername} 的邀请`
    : '这条邀请链接'
  uni.showModal({
    title: '撤销邀请',
    content: `确定撤销${invitationTarget}吗？`,
    confirmText: '撤销',
    confirmColor: '#e5484d',
    success: async (res) => {
      if (!res.confirm)
        return
      try {
        await revokeWorkspaceInvitation(currentWorkspaceId.value, invitation.id)
        toast.success('邀请已撤销')
        await loadMembers()
      }
      catch (error) {
        toast.warning(getErrorMessage(error, '撤销邀请失败'))
      }
    },
  })
}

function closeInvitePanel() {
  showInvitePanel.value = false
  inviteForm.username = ''
  inviteForm.role = 'MEMBER'
  lastInviteCode.value = ''
}

function chooseInviteRole(role: WorkspaceRole) {
  inviteForm.role = role
}

function chooseMemberRole(member: WorkspaceMember) {
  if (!canChangeRole(member))
    return
  uni.showActionSheet({
    itemList: ['管理员', '普通成员'],
    success: async (res) => {
      const nextRole: WorkspaceRole = res.tapIndex === 0 ? 'ADMIN' : 'MEMBER'
      if (nextRole === member.role)
        return
      try {
        await updateWorkspaceMemberRole(currentWorkspaceId.value, member.userId, nextRole)
        toast.success('角色已更新')
        await loadMembers()
      }
      catch (error) {
        toast.warning(getErrorMessage(error, '角色更新失败'))
      }
    },
  })
}

function confirmRemoveMember(member: WorkspaceMember) {
  if (!canRemoveMember(member))
    return
  uni.showModal({
    title: '移除成员',
    content: `确定移除 ${member.username} 吗？`,
    confirmText: '移除',
    confirmColor: '#e5484d',
    success: async (res) => {
      if (!res.confirm)
        return
      try {
        await removeWorkspaceMember(currentWorkspaceId.value, member.userId)
        toast.success('成员已移除')
        await loadMembers()
      }
      catch (error) {
        toast.warning(getErrorMessage(error, '移除成员失败'))
      }
    },
  })
}

function confirmLeaveWorkspace() {
  if (!currentWorkspaceId.value || !canLeaveWorkspace.value)
    return
  uni.showModal({
    title: '退出工作空间',
    content: '确定退出当前工作空间吗？',
    confirmText: '退出',
    confirmColor: '#e5484d',
    success: async (res) => {
      if (!res.confirm)
        return
      try {
        await leaveWorkspace(currentWorkspaceId.value)
        toast.success('已退出工作空间')
        currentWorkspaceId.value = ''
        clearCurrentWorkspaceId()
        await loadWorkspaces('')
      }
      catch (error) {
        toast.warning(getErrorMessage(error, '退出工作空间失败'))
      }
    },
  })
}

onMounted(async () => {
  uni.setNavigationBarTitle({ title: '工作空间' })
  if (!userStore.userInfo.id)
    await userStore.getUserInfo()
  await loadWorkspaces()
})
</script>

<template>
  <view class="min-h-screen bg-[#f5f7fb]">
    <wd-navbar title="工作空间" placeholder safe-area-inset-top fixed left-arrow @click-left="goBack" />

    <view class="px-[24rpx] pb-[40rpx] pt-[20rpx]">
      <view class="mb-[22rpx] rounded-[18rpx] bg-white p-[20rpx]">
        <view class="mb-[14rpx] flex items-center justify-between">
          <text class="text-[26rpx] font-semibold text-[#232338]">
            加入工作空间
          </text>
          <text class="text-[22rpx] text-[#8b8f9b]">
            邀请码加入
          </text>
        </view>
        <view class="flex items-center gap-[14rpx]">
          <view class="min-w-0 flex-1 overflow-hidden rounded-[14rpx] bg-[#f5f7fb] px-[20rpx]">
            <wd-input
              v-model="inviteCode"
              clearable
              :maxlength="64"
              placeholder="输入邀请码"
              custom-class="!border-none h-[72rpx] items-center"
              input-class="text-[28rpx] text-[#232338]"
            />
          </view>
          <wd-button type="primary" custom-class="h-[72rpx] rounded-[14rpx] px-[32rpx] bg-[#336cff] border-none" @click="acceptInvitation">
            加入
          </wd-button>
        </view>
      </view>

      <view v-if="loading" class="py-[80rpx] text-center text-[26rpx] text-[#8b8f9b]">
        加载中...
      </view>

      <view v-else-if="loadError" class="rounded-[22rpx] bg-white p-[48rpx] text-center">
        <text class="block text-[28rpx] font-semibold text-[#232338]">
          加载失败
        </text>
        <text class="mt-[10rpx] block text-[24rpx] text-[#8b8f9b]">
          {{ loadError }}
        </text>
        <wd-button type="primary" size="small" custom-class="mt-[24rpx] h-[64rpx] rounded-[14rpx] bg-[#336cff] border-none px-[32rpx]" @click="loadWorkspaces()">
          重试
        </wd-button>
      </view>

      <template v-else>
        <scroll-view v-if="workspaceList.length > 0" scroll-x class="mb-[20rpx] whitespace-nowrap">
          <view
            v-for="workspace in workspaceList"
            :key="workspace.id"
            class="mr-[14rpx] inline-flex max-w-[430rpx] items-center gap-[10rpx] rounded-[16rpx] px-[22rpx] py-[16rpx]"
            :class="workspace.id === currentWorkspaceId ? 'bg-[#336cff] text-white shadow-sm' : 'bg-white text-[#232338]'"
            @click="selectWorkspace(workspace.id)"
          >
            <text class="truncate text-[28rpx] font-semibold">
              {{ workspace.name }}
            </text>
            <text
              class="shrink-0 rounded-full px-[14rpx] py-[4rpx] text-[22rpx]"
              :class="workspace.id === currentWorkspaceId ? 'bg-white/20 text-white' : roleClass(workspace.currentUserRole)"
            >
              {{ roleLabel(workspace.currentUserRole) }}
            </text>
          </view>
        </scroll-view>

        <view v-if="currentWorkspace" class="mb-[22rpx] rounded-[22rpx] bg-white p-[26rpx]">
          <view class="mb-[22rpx]">
            <view class="mb-[16rpx] flex items-start justify-between gap-[20rpx]">
              <view class="min-w-0 flex-1">
                <text class="block truncate text-[34rpx] font-bold text-[#232338]">
                  {{ currentWorkspace.name }}
                </text>
                <text class="mt-[8rpx] block text-[24rpx] text-[#8b8f9b]">
                  名额 {{ occupiedSlots }}/{{ maxMembers }}（成员 {{ members.length }}，待接受 {{ pendingInvitations.length }}）
                </text>
              </view>
              <text class="shrink-0 rounded-full px-[16rpx] py-[6rpx] text-[22rpx]" :class="roleClass(currentWorkspace.currentUserRole)">
                {{ roleLabel(currentWorkspace.currentUserRole) }}
              </text>
            </view>

            <view class="flex gap-[14rpx]">
              <wd-button
                v-if="canWrite"
                type="primary"
                size="small"
                :disabled="occupiedSlots >= maxMembers"
                custom-class="flex-1 h-[68rpx] rounded-[14rpx] bg-[#336cff] border-none"
                @click="showInvitePanel = true"
              >
                邀请成员
              </wd-button>
              <wd-button
                v-if="canLeaveWorkspace"
                type="default"
                size="small"
                custom-class="h-[68rpx] rounded-[14rpx] px-[28rpx] text-[#65686f]"
                @click="confirmLeaveWorkspace"
              >
                退出
              </wd-button>
            </view>
          </view>
        </view>

        <view v-if="currentWorkspace" class="rounded-[22rpx] bg-white p-[24rpx]">
          <view class="mb-[18rpx] flex items-center justify-between">
            <text class="text-[30rpx] font-bold text-[#232338]">
              成员
            </text>
            <text class="text-[22rpx] text-[#8b8f9b]">
              {{ members.length }}/{{ maxMembers }}
            </text>
          </view>

          <view v-if="memberLoading" class="py-[48rpx] text-center text-[26rpx] text-[#8b8f9b]">
            加载成员中...
          </view>

          <view v-else class="space-y-[16rpx]">
            <view
              v-for="member in members"
              :key="member.userId"
              class="rounded-[18rpx] bg-[#f5f7fb] p-[22rpx]"
            >
              <view class="mb-[16rpx] flex items-start justify-between gap-[16rpx]">
                <view class="min-w-0 flex-1">
                  <text class="block truncate text-[29rpx] font-semibold text-[#232338]">
                    {{ member.username }}
                  </text>
                  <text v-if="member.joinedAt" class="mt-[6rpx] block text-[23rpx] text-[#8b8f9b]">
                    加入于 {{ formatJoinedAt(member.joinedAt) }}
                  </text>
                </view>
                <view
                  class="shrink-0 rounded-full px-[16rpx] py-[6rpx] text-[22rpx]"
                  :class="roleClass(member.role)"
                  @click="chooseMemberRole(member)"
                >
                  {{ roleLabel(member.role) }}
                </view>
              </view>

              <view v-if="canRemoveMember(member) || canChangeRole(member)" class="flex justify-end gap-[12rpx]">
                <wd-button
                  v-if="canChangeRole(member)"
                  type="default"
                  size="small"
                  custom-class="h-[58rpx] rounded-[12rpx] px-[24rpx] text-[#336cff]"
                  @click="chooseMemberRole(member)"
                >
                  调整角色
                </wd-button>
                <wd-button
                  v-if="canRemoveMember(member)"
                  type="error"
                  size="small"
                  custom-class="h-[58rpx] rounded-[12rpx] px-[24rpx]"
                  @click="confirmRemoveMember(member)"
                >
                  移除
                </wd-button>
              </view>
            </view>

            <view v-if="pendingInvitations.length" class="mt-[24rpx] border-t border-[#eceef3] pt-[22rpx]">
              <text class="mb-[14rpx] block text-[27rpx] font-semibold text-[#232338]">
                待接受邀请
              </text>
              <view
                v-for="invitation in pendingInvitations"
                :key="invitation.id"
                class="mb-[12rpx] flex items-center justify-between gap-[16rpx] rounded-[16rpx] bg-[#fff8e8] p-[20rpx]"
              >
                <view class="min-w-0 flex-1">
                  <text class="block truncate text-[27rpx] font-semibold text-[#232338]">
                    {{ invitation.inviteeUsername || '邀请链接（待领取）' }} · {{ roleLabel(invitation.role) }}
                  </text>
                  <text v-if="invitation.expiresAt" class="mt-[4rpx] block text-[22rpx] text-[#8b8f9b]">
                    有效期至 {{ formatJoinedAt(invitation.expiresAt) }}
                  </text>
                </view>
                <wd-button
                  type="error"
                  size="small"
                  custom-class="h-[56rpx] rounded-[12rpx] px-[22rpx]"
                  @click="confirmRevokeInvitation(invitation)"
                >
                  撤销
                </wd-button>
              </view>
            </view>
          </view>
        </view>

        <view v-else class="rounded-[22rpx] bg-white p-[48rpx] text-center">
          <text class="block text-[28rpx] font-semibold text-[#232338]">
            暂无工作空间
          </text>
          <text class="mt-[10rpx] block text-[24rpx] text-[#8b8f9b]">
            输入邀请码后即可加入
          </text>
        </view>
      </template>
    </view>

    <view v-if="showInvitePanel" class="fixed inset-0 z-20 bg-black/35" @click="closeInvitePanel">
      <view class="absolute bottom-0 left-0 right-0 rounded-t-[28rpx] bg-white p-[28rpx]" @click.stop>
        <view class="mb-[24rpx] flex items-center justify-between">
          <text class="text-[32rpx] text-[#232338] font-bold">
            邀请成员
          </text>
          <wd-icon name="close" custom-class="text-[34rpx] text-[#8b8f9b]" @click="closeInvitePanel" />
        </view>

        <view class="mb-[20rpx] overflow-hidden rounded-[16rpx] bg-[#f5f7fb] px-[20rpx]">
          <wd-input
            v-model="inviteForm.username"
            clearable
            :maxlength="64"
            placeholder="请输入已注册用户名"
            custom-class="!border-none h-[80rpx] items-center"
            input-class="text-[28rpx] text-[#232338]"
          />
        </view>

        <view class="mb-[24rpx] flex gap-[16rpx]">
          <view
            class="flex-1 rounded-[16rpx] px-[20rpx] py-[18rpx] text-center text-[26rpx] font-semibold"
            :class="inviteForm.role === 'ADMIN' ? 'bg-[#fff7e6] text-[#ad6800]' : 'bg-[#f5f7fb] text-[#65686f]'"
            @click="chooseInviteRole('ADMIN')"
          >
            管理员
          </view>
          <view
            class="flex-1 rounded-[16rpx] px-[20rpx] py-[18rpx] text-center text-[26rpx] font-semibold"
            :class="inviteForm.role === 'MEMBER' ? 'bg-[#eef3ff] text-[#336cff]' : 'bg-[#f5f7fb] text-[#65686f]'"
            @click="chooseInviteRole('MEMBER')"
          >
            普通成员
          </view>
        </view>

        <view v-if="lastInviteCode" class="mb-[24rpx] rounded-[16rpx] bg-[#f5f7fb] p-[20rpx]">
          <text class="block text-[22rpx] text-[#8b8f9b]">
            邀请码
          </text>
          <text class="mt-[6rpx] block break-all text-[30rpx] font-semibold text-[#232338]">
            {{ lastInviteCode }}
          </text>
        </view>

        <wd-button block type="primary" custom-class="h-[84rpx] rounded-[18rpx] bg-[#336cff] border-none" @click="inviteMember">
          生成邀请
        </wd-button>
      </view>
    </view>
  </view>
</template>
