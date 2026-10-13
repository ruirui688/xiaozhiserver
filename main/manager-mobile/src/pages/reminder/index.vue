<route lang="jsonc" type="page">
{
  "needLogin": true,
  "layout": "default",
  "style": {
    "navigationBarTitleText": "日程提醒",
    "backgroundColor": "#f5f7fa"
  }
}
</route>

<script lang="ts" setup>
import type { ReminderFormValue } from './reminderForm'
import type { DeviceReminder, ReminderDevice, ReminderSource, ReminderStatus } from '@/api/reminder'
import { computed, ref } from 'vue'
import { cancelReminder as cancelReminderRequest, createReminder, getReminderDevices, getReminders, updateReminder } from '@/api/reminder'
import { getWorkspaceList } from '@/api/workspace'
import { toast } from '@/utils/toast'
import { getCurrentWorkspaceId, setCurrentWorkspaceId } from '@/utils/workspace'
import { createReminderPayload, defaultReminderForm, formatReminderDateTime, reminderToForm } from './reminderForm'

const reminders = ref<DeviceReminder[]>([])
const devices = ref<ReminderDevice[]>([])
const loading = ref(false)
const saving = ref(false)
const canWrite = ref(false)
const editorVisible = ref(false)
const editingId = ref('')
const editingSource = ref<ReminderSource | ''>('')
const activeTab = ref<'pending' | 'history'>('pending')
const form = ref<ReminderFormValue>(defaultReminderForm())

const pendingReminders = computed(() => reminders.value
  .filter(item => item.status === 'PENDING')
  .sort((left, right) => new Date(left.remindAt).getTime() - new Date(right.remindAt).getTime()))

const historyReminders = computed(() => reminders.value
  .filter(item => item.status !== 'PENDING')
  .sort((left, right) => new Date(right.remindAt).getTime() - new Date(left.remindAt).getTime()))

const visibleReminders = computed(() => activeTab.value === 'pending' ? pendingReminders.value : historyReminders.value)
const selectedDevice = computed(() => devices.value.find(item => item.deviceId === form.value.deviceId))
const selectedDeviceIndex = computed(() => devices.value.findIndex(item => item.deviceId === form.value.deviceId))
const pickerDeviceIndex = computed(() => Math.max(0, selectedDeviceIndex.value))

async function load() {
  loading.value = true
  reminders.value = []
  devices.value = []
  canWrite.value = false
  try {
    const workspaceData = await getWorkspaceList()
    const workspaceId = getCurrentWorkspaceId()
    const currentWorkspace = workspaceData.find(item => item.id === workspaceId)
      || workspaceData.find(item => item.defaultWorkspace === 1)
      || (workspaceData.length === 1 ? workspaceData[0] : undefined)
    if (currentWorkspace && workspaceId !== currentWorkspace.id)
      setCurrentWorkspaceId(currentWorkspace.id)
    canWrite.value = Boolean(currentWorkspace?.canWrite)
    const [pendingData, historyData, deviceData] = await Promise.all([
      getReminders('PENDING'),
      getReminders('HISTORY'),
      getReminderDevices(),
    ])
    reminders.value = [...(pendingData || []), ...(historyData || [])]
    devices.value = deviceData || []
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '日程加载失败')
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  if (!canWrite.value) {
    toast.warning('当前工作空间为只读权限')
    return
  }
  editingId.value = ''
  editingSource.value = ''
  form.value = defaultReminderForm()
  if (devices.value.length)
    form.value.deviceId = devices.value[0].deviceId
  editorVisible.value = true
}

function openEdit(item: DeviceReminder) {
  if (!canWrite.value) {
    toast.warning('当前工作空间为只读权限')
    return
  }
  try {
    editingId.value = item.id
    editingSource.value = item.source
    form.value = reminderToForm(item)
    editorVisible.value = true
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '无法编辑该提醒')
  }
}

function selectDevice(event: { detail: { value: string | number } }) {
  const device = devices.value[Number(event.detail.value)]
  if (device)
    form.value.deviceId = device.deviceId
}

function selectDate(event: { detail: { value: string } }) {
  form.value.date = event.detail.value
}

function selectTime(event: { detail: { value: string } }) {
  form.value.time = event.detail.value
}

async function save() {
  if (!canWrite.value) {
    toast.warning('当前工作空间为只读权限')
    return
  }
  if (!devices.value.some(item => item.deviceId === form.value.deviceId)) {
    toast.warning('原提醒设备已不可用，请重新选择设备')
    return
  }
  let payload
  try {
    payload = createReminderPayload(form.value)
  }
  catch (error) {
    toast.warning(error instanceof Error ? error.message : '请检查提醒内容')
    return
  }

  saving.value = true
  try {
    if (editingId.value)
      await updateReminder(editingId.value, payload)
    else
      await createReminder(payload)
    editorVisible.value = false
    toast.success(editingId.value ? '提醒已更新' : '提醒已创建')
    await load()
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '保存失败')
  }
  finally {
    saving.value = false
  }
}

function cancel(item: DeviceReminder) {
  if (!canWrite.value) {
    toast.warning('当前工作空间为只读权限')
    return
  }
  uni.showModal({
    title: '取消提醒',
    content: '取消后设备将不会播报这条提醒，确定继续吗？',
    confirmText: '取消提醒',
    confirmColor: '#d93025',
    success: async (result) => {
      if (!result.confirm)
        return
      try {
        await cancelReminderRequest(item.id)
        toast.success('提醒已取消')
        await load()
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '取消失败')
      }
    },
  })
}

function deviceLabel(device?: ReminderDevice) {
  if (!device)
    return '请选择设备'
  const name = device.deviceName || device.deviceId
  return device.agentName ? `${name} · ${device.agentName}` : name
}

function formatDateTime(value?: string, timezone = 'Asia/Shanghai') {
  if (!value)
    return '-'
  return formatReminderDateTime(value, timezone)
}

function statusLabel(status: ReminderStatus) {
  return {
    PENDING: '待提醒',
    DELIVERED: '已接受排队',
    CANCELLED: '已取消',
    MISSED: '未送达',
  }[status]
}

function statusDescription(item: DeviceReminder) {
  if (item.status === 'MISSED')
    return '设备在提醒宽限期内未能接收播报'
  if (item.status === 'DELIVERED')
    return `服务端于 ${formatDateTime(item.deliveredAt, item.timezone)} 接受排队，不代表设备已播放`
  if (item.status === 'CANCELLED')
    return '这条提醒已取消'
  return item.source === 'VOICE' ? '由语音创建' : '由小程序创建'
}

onShow(load)
</script>

<template>
  <view class="page">
    <view class="notice">
      <wd-icon name="info-circle" custom-class="notice-icon" />
      <text>当前仅支持一次性提醒。服务器只向联网、空闲且使用传统语音链路的设备排队播报；绑定 Native Qwen/Doubao Realtime 智能体的设备仍可创建提醒，但当前到点无法播报。忙碌或短暂离线会在 10 分钟宽限期内重试；断网、关机或深度休眠设备不能被真正唤醒，“已接受排队”也不代表扬声器已经播放。</text>
    </view>

    <view class="summary">
      <view>
        <text class="summary-count">
          {{ pendingReminders.length }}
        </text>
        <text class="summary-label">
          条待提醒日程
        </text>
      </view>
      <button v-if="canWrite" class="create-button" :disabled="devices.length === 0" @click="openCreate">
        <wd-icon name="add" /> 新建提醒
      </button>
      <text v-else class="read-only-label">
        只读
      </text>
    </view>

    <view v-if="canWrite && devices.length === 0 && !loading" class="empty-device">
      当前工作空间没有可用设备，请先绑定设备。
    </view>

    <view class="tabs">
      <button :class="{ active: activeTab === 'pending' }" @click="activeTab = 'pending'">
        待提醒
      </button>
      <button :class="{ active: activeTab === 'history' }" @click="activeTab = 'history'">
        历史记录
      </button>
    </view>

    <view v-if="loading" class="state">
      正在加载日程...
    </view>
    <view v-else-if="visibleReminders.length === 0" class="state">
      {{ activeTab === 'pending' ? '暂无待提醒日程' : '暂无历史记录' }}
    </view>
    <view v-else class="reminder-list">
      <view v-for="item in visibleReminders" :key="item.id" class="reminder-card">
        <view class="card-heading">
          <text class="reminder-time">
            {{ formatDateTime(item.remindAt, item.timezone) }}
          </text>
          <text class="status" :class="item.status.toLowerCase()">
            {{ statusLabel(item.status) }}
          </text>
        </view>
        <text class="timezone-label">
          IANA 时区：{{ item.timezone }}
        </text>
        <text class="reminder-title">
          {{ item.title }}
        </text>
        <text class="device-name">
          <wd-icon name="phone" /> {{ item.deviceName || item.deviceId }}
        </text>
        <text class="description">
          {{ statusDescription(item) }}
        </text>
        <view v-if="canWrite && item.status === 'PENDING'" class="card-actions">
          <button @click="openEdit(item)">
            编辑
          </button>
          <button class="danger" @click="cancel(item)">
            取消
          </button>
        </view>
      </view>
    </view>

    <wd-popup v-model="editorVisible" position="bottom" :close-on-click-modal="!saving" custom-style="border-radius:24rpx 24rpx 0 0;">
      <view class="editor">
        <view class="editor-heading">
          <text>{{ editingId ? '编辑提醒' : '新建提醒' }}</text>
          <wd-icon name="close" @click="editorVisible = false" />
        </view>

        <text class="field-label">
          提醒设备
        </text>
        <picker
          mode="selector"
          :range="devices"
          range-key="deviceName"
          :value="pickerDeviceIndex"
          :disabled="editingSource === 'VOICE'"
          @change="selectDevice"
        >
          <view class="picker-field">
            <text v-if="selectedDevice">
              {{ deviceLabel(selectedDevice) }}
            </text>
            <text v-else class="placeholder">
              {{ form.deviceId ? '原提醒设备已不可用，请重新选择' : '请选择设备' }}
            </text>
            <wd-icon name="arrow-down" />
          </view>
        </picker>
        <text v-if="editingSource === 'VOICE'" class="field-hint">
          语音创建的提醒不能更换设备；如需更换，请取消后重新创建。
        </text>

        <text class="field-label">
          提醒内容
        </text>
        <textarea v-model="form.title" class="title-input" :maxlength="200" placeholder="例如：下午两点开会" />
        <text class="counter">
          {{ form.title.length }}/200
        </text>

        <text class="field-label">
          提醒时间
        </text>
        <view class="datetime-row">
          <picker mode="date" :value="form.date" @change="selectDate">
            <view class="picker-field compact">
              {{ form.date }}
            </view>
          </picker>
          <picker mode="time" :value="form.time" @change="selectTime">
            <view class="picker-field compact">
              {{ form.time }}
            </view>
          </picker>
        </view>
        <text class="timezone-hint">
          IANA 时区：{{ form.timezone }}
        </text>

        <button class="save-button" :disabled="saving" @click="save">
          {{ saving ? '保存中...' : '保存提醒' }}
        </button>
      </view>
    </wd-popup>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  padding-bottom: calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  background: #f5f7fa;
}

.notice {
  display: flex;
  gap: 14rpx;
  padding: 22rpx;
  border: 1rpx solid #dbeafe;
  border-radius: 16rpx;
  background: #eff6ff;
  color: #365b8c;
  font-size: 24rpx;
  line-height: 36rpx;
}

.notice-icon {
  flex: 0 0 auto;
  margin-top: 2rpx;
}

.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20rpx 0;
  padding: 28rpx;
  border-radius: 18rpx;
  background: linear-gradient(135deg, #3977d6, #68a3ff);
  color: #fff;
}

.summary-count {
  margin-right: 12rpx;
  font-size: 50rpx;
  font-weight: 750;
}

.summary-label {
  font-size: 26rpx;
}

.create-button,
.save-button {
  margin: 0;
  border: 0;
  color: #fff;
  background: #266bd2;
  font-size: 26rpx;
}

.read-only-label {
  padding: 8rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.18);
  font-size: 23rpx;
}

.create-button {
  background: rgba(255, 255, 255, 0.2);
}

.create-button::after,
.save-button::after,
.tabs button::after,
.card-actions button::after {
  border: 0;
}

.empty-device {
  margin-bottom: 20rpx;
  padding: 20rpx;
  border-radius: 12rpx;
  background: #fff7e8;
  color: #8b5a12;
  font-size: 25rpx;
}

.tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8rpx;
  margin-bottom: 18rpx;
  padding: 6rpx;
  border-radius: 12rpx;
  background: #e9edf3;
}

.tabs button {
  height: 64rpx;
  margin: 0;
  border: 0;
  border-radius: 9rpx;
  background: transparent;
  color: #677287;
  font-size: 26rpx;
  line-height: 64rpx;
}

.tabs button.active {
  background: #fff;
  color: #245fae;
  font-weight: 650;
  box-shadow: 0 3rpx 12rpx rgba(22, 40, 80, 0.08);
}

.state {
  padding: 100rpx 20rpx;
  text-align: center;
  color: #8791a3;
  font-size: 26rpx;
}

.reminder-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.reminder-card {
  padding: 26rpx;
  border: 1rpx solid #e4e8ef;
  border-radius: 16rpx;
  background: #fff;
}

.card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.reminder-time {
  color: #285f9f;
  font-size: 27rpx;
  font-weight: 650;
}

.status {
  padding: 5rpx 12rpx;
  border-radius: 999rpx;
  background: #e8f2ff;
  color: #2563a6;
  font-size: 21rpx;
}

.status.delivered {
  background: #e8f7ed;
  color: #257a43;
}

.status.cancelled {
  background: #eef0f3;
  color: #6c7480;
}

.status.missed {
  background: #fff0eb;
  color: #b54528;
}

.reminder-title {
  display: block;
  margin-top: 16rpx;
  color: #172033;
  font-size: 31rpx;
  font-weight: 650;
  line-height: 44rpx;
  word-break: break-all;
}

.timezone-label,
.timezone-hint {
  display: block;
  margin-top: 8rpx;
  color: #778195;
  font-size: 22rpx;
  line-height: 32rpx;
}

.timezone-hint {
  margin-top: 12rpx;
}

.field-hint {
  display: block;
  margin-top: 10rpx;
  color: #8a6470;
  font-size: 22rpx;
  line-height: 32rpx;
}

.device-name,
.description {
  display: block;
  margin-top: 10rpx;
  color: #778195;
  font-size: 23rpx;
  line-height: 34rpx;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12rpx;
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #eef1f5;
}

.card-actions button {
  min-width: 110rpx;
  height: 58rpx;
  margin: 0;
  border: 0;
  border-radius: 9rpx;
  color: #3165a6;
  background: #eef5ff;
  font-size: 24rpx;
  line-height: 58rpx;
}

.card-actions button.danger {
  color: #b53b2c;
  background: #fff1ee;
}

.editor {
  padding: 32rpx 28rpx calc(32rpx + env(safe-area-inset-bottom));
}

.editor-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 28rpx;
  color: #172033;
  font-size: 34rpx;
  font-weight: 700;
}

.field-label {
  display: block;
  margin: 22rpx 0 10rpx;
  color: #3e485b;
  font-size: 25rpx;
  font-weight: 600;
}

.picker-field,
.title-input {
  width: 100%;
  box-sizing: border-box;
  border: 1rpx solid #dfe4ec;
  border-radius: 12rpx;
  background: #f9fafc;
  color: #20293a;
  font-size: 27rpx;
}

.picker-field {
  min-height: 80rpx;
  padding: 0 20rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.picker-field.compact {
  min-width: 250rpx;
  justify-content: center;
}

.placeholder {
  color: #a0a8b5;
}

.title-input {
  height: 150rpx;
  padding: 18rpx 20rpx;
  line-height: 38rpx;
}

.counter {
  display: block;
  margin-top: 6rpx;
  text-align: right;
  color: #929aaa;
  font-size: 21rpx;
}

.datetime-row {
  display: flex;
  gap: 16rpx;
}

.save-button {
  width: 100%;
  height: 82rpx;
  margin-top: 34rpx;
  border-radius: 12rpx;
  line-height: 82rpx;
}
</style>
