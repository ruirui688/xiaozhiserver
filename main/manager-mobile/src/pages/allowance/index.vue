<route lang="jsonc" type="page">
{ "needLogin": true, "layout": "default", "style": { "navigationBarTitleText": "额度与用量", "navigationStyle": "custom" } }
</route>
<script setup lang="ts">
import type { AllowanceEntry, AllowanceRequest, AllowanceSummary, WorkspaceLimits } from '@/api/platform'
import type { Workspace } from '@/api/workspace'
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getAllowanceLedger, getAllowanceRequests, getAllowanceSummary, getWorkspaceLimits, requestAllowance, saveWorkspaceLimits } from '@/api/platform'
import { getWorkspaceList } from '@/api/workspace'
import { allowanceAmount, allowancePayerLabel, visibleAllowance } from '@/utils/allowance'
import { getCurrentWorkspaceId, setCurrentWorkspaceId } from '@/utils/workspace'

const summary = ref<AllowanceSummary | null>(null)
const limits = ref<WorkspaceLimits | null>(null)
const workspaces = ref<Workspace[]>([])
const workspaceId = ref(getCurrentWorkspaceId())
const ledger = ref<AllowanceEntry[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const tab = ref('workspace')
const ledgerLoading = ref(false)
const limitDialog = ref(false)
const saving = ref(false)
const totalLimit = ref('')
const dailyLimit = ref('')
const requests = ref<AllowanceRequest[]>([])
const requestDialog = ref(false)
const requestSaving = ref(false)
const requestAmount = ref('')
const requestReason = ref('')
const requestId = ref('')
let generation = 0
const balance = computed(() => visibleAllowance(summary.value))
const payerLabel = computed(() => allowancePayerLabel(summary.value))
const workspaceName = computed(() => workspaces.value.find(item => item.id === workspaceId.value)?.name || '选择工作空间')
const hasPendingRequest = computed(() => requests.value.some(item => item.status === 'PENDING'))
const amount = allowanceAmount
function requestStatus(status: AllowanceRequest['status']) { return ({ PENDING: '待审核', APPROVED: '已发放', REJECTED: '未通过' })[status] || status }
function back() { uni.navigateBack() }
function message(error: unknown) { return error instanceof Error ? error.message : '读取失败，请重试' }
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  summary.value = null
  limits.value = null
  try {
    workspaces.value = await getWorkspaceList()
    if (!workspaces.value.some(item => item.id === workspaceId.value)) workspaceId.value = workspaces.value[0]?.id || ''
    if (!workspaceId.value) throw new Error('没有可用工作空间')
    setCurrentWorkspaceId(workspaceId.value)
    const result = await getAllowanceSummary(workspaceId.value)
    if (current !== generation) return
    if (result.workspaceId !== workspaceId.value) throw new Error('工作空间已变化，请刷新')
    summary.value = result
    {
      const team = await getWorkspaceLimits(workspaceId.value)
      if (current === generation && team.workspaceId === workspaceId.value) limits.value = team
    }
  }
  catch (reason) { if (current === generation) error.value = message(reason) }
  finally { if (current === generation) loading.value = false }
}
function chooseWorkspace(event: any) { workspaceId.value = workspaces.value[Number(event.detail.value)]?.id || ''; load() }
async function loadLedger(reset = false) {
  if (ledgerLoading.value) return
  if (reset) { page.value = 1; ledger.value = [] }
  ledgerLoading.value = true
  try { const result = await getAllowanceLedger(page.value); ledger.value = [...ledger.value, ...result.list]; total.value = result.total; page.value++ }
  catch (reason) { uni.showToast({ title: message(reason), icon: 'none' }) }
  finally { ledgerLoading.value = false }
}
function showLedger() { tab.value = 'ledger'; loadLedger(true) }
async function loadRequests() {
  try { const result = await getAllowanceRequests(); requests.value = result.list }
  catch (reason) { uni.showToast({ title: message(reason), icon: 'none' }) }
}
function showRequests() { tab.value = 'request'; loadRequests() }
function openRequest() {
  if (hasPendingRequest.value) return
  requestId.value = `mobile-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
  requestAmount.value = ''
  requestReason.value = ''
  requestDialog.value = true
}
async function submitRequest() {
  if (requestSaving.value) return
  if (!requestReason.value.trim()) { uni.showToast({ title: '请填写申请说明', icon: 'none' }); return }
  if (requestAmount.value && !/^\d+(\.\d{1,6})?$/.test(requestAmount.value)) { uni.showToast({ title: '期望金额请填写非负金额', icon: 'none' }); return }
  requestSaving.value = true
  try {
    await requestAllowance({
      requestId: requestId.value,
      amount: requestAmount.value || null,
      reason: requestReason.value.trim(),
      workspaceId: summary.value?.canViewBalance ? workspaceId.value : null,
    })
    requestDialog.value = false
    uni.showToast({ title: '已提交，等待运营处理', icon: 'none' })
    await loadRequests()
  }
  catch (reason) { uni.showToast({ title: message(reason), icon: 'none' }) }
  finally { requestSaving.value = false }
}
function editLimits() { totalLimit.value = limits.value?.totalLimit ?? ''; dailyLimit.value = limits.value?.dailyLimit ?? ''; limitDialog.value = true }
async function saveLimits() {
  if (!limits.value?.canManage || saving.value) return
  if ([totalLimit.value, dailyLimit.value].some(value => value !== '' && !/^\d+(\.\d{1,6})?$/.test(value))) { uni.showToast({ title: '请输入非负金额，最多 6 位小数', icon: 'none' }); return }
  saving.value = true
  try { await saveWorkspaceLimits({ workspaceId: workspaceId.value, expectedVersion: limits.value.responsibilityVersion, totalLimit: totalLimit.value || null, dailyLimit: dailyLimit.value || null }); limitDialog.value = false; await load() }
  catch (reason) { uni.showToast({ title: message(reason), icon: 'none' }) }
  finally { saving.value = false }
}
onShow(() => { load(); loadRequests() })
</script>
<template>
  <view class="allowance-mobile"><wd-navbar title="额度与用量" left-arrow fixed placeholder safe-area-inset-top @click-left="back" /><view class="allowance-body"><picker :range="workspaces" range-key="name" @change="chooseWorkspace"><view class="space-picker">{{ workspaceName }} <text>⌄</text></view></picker><view v-if="error" class="error-panel" @click="load">{{ error }} · 点击重试</view><view v-if="loading" class="empty-panel">正在读取服务额度…</view><template v-else-if="summary"><view class="payer-panel"><text class="section-title">{{ payerLabel }}</text><text class="muted">在谁的工作空间协作，就使用谁的凭据和额度。服务额度由平台发放，不支持自行充值。</text><text class="service-status">{{ summary.serviceStatus === 'READY' ? '模型服务已就绪' : '模型服务待就绪' }}</text></view><view v-if="balance" class="balance-panel"><text>可用服务额度</text><view class="balance-number">{{ amount(balance.available) }}<text>元</text></view><view class="balance-detail"><text>预占中 {{ amount(balance.reserved) }}</text><text>账户累计已用 {{ amount(balance.spent) }}</text></view></view><view v-else class="content-card muted">工作空间负责人的个人总余额仅本人可见。工作空间额度不足时暂停新付费调用，不改扣你的个人额度。</view><view class="tab-row"><text :class="{ active: tab === 'workspace' }" @click="tab='workspace'">当前空间</text><text :class="{ active: tab === 'ledger' }" @click="showLedger">我的额度流水</text><text :class="{ active: tab === 'request' }" @click="showRequests">额度申请</text></view><template v-if="tab === 'workspace'"><view v-if="limits" class="content-card"><view class="card-heading"><text class="section-title">工作空间使用上限</text><text v-if="limits.canManage" class="link" @click="editLimits">设置</text></view><view class="detail-row"><text>工作空间总上限</text><text>{{ limits.totalLimit == null ? '未单设上限' : amount(limits.totalLimit) + ' 元' }}</text></view><view class="detail-row"><text>今日使用 / 日上限</text><text>{{ amount(limits.dailySpent) }} / {{ limits.dailyLimit == null ? '未单设上限' : amount(limits.dailyLimit) }}</text></view><view class="detail-row"><text>累计使用</text><text>{{ amount(limits.totalSpent) }} 元</text></view><view class="detail-row"><text>预占中</text><text>{{ amount(limits.totalReserved) }} 元</text></view><text class="muted">工作空间上限属于负责人账户内的限制，不额外发放额度。</text></view><view class="content-card"><text class="section-title">工作空间负责人</text><text class="muted">负责人 ID：{{ summary.responsibleUserId }}。工作空间统一使用负责人的密钥与额度。</text></view><view class="content-card muted">每次调用先预占额度，再按平台服务价格结算。供应商采购成本由平台单独管理。</view></template><template v-else-if="tab === 'ledger'"><view v-for="entry in ledger" :key="entry.id" class="content-card ledger-row"><view class="card-heading"><text>{{ entry.reason || entry.type }}</text><text>{{ amount(entry.amount) }} 元</text></view><text class="muted">{{ entry.createdAt }} · 余额 {{ amount(entry.balanceAfter) }}</text></view><view v-if="!ledger.length && !ledgerLoading" class="empty-panel">暂无额度流水</view><wd-button v-if="ledger.length < total" block type="text" :loading="ledgerLoading" @click="loadLedger()">加载更多</wd-button></template><template v-else><view class="content-card"><view class="card-heading"><text class="section-title">额度申请</text><text class="link" @click="openRequest">{{ hasPendingRequest ? '已有待审核申请' : '申请额度' }}</text></view><text class="muted">平台不提供充值。可用额度不足时提交申请，运营审核后发放，结果会显示在下方。</text></view><view v-for="entry in requests" :key="entry.id" class="content-card ledger-row"><view class="card-heading"><text>{{ entry.reason }}</text><text>{{ requestStatus(entry.status) }}</text></view><text class="muted">期望 {{ entry.requestedAmount == null ? '未指定' : amount(entry.requestedAmount) + ' 元' }} · 实际发放 {{ entry.grantedAmount == null ? '—' : amount(entry.grantedAmount) + ' 元' }}</text><text class="muted">{{ entry.createdAt }}{{ entry.decisionReason ? ' · ' + entry.decisionReason : '' }}</text></view><view v-if="!requests.length" class="empty-panel">暂无额度申请记录</view></template></template><wd-popup v-model="requestDialog" position="bottom" closable><view class="limit-form"><text class="section-title">申请额度</text><text class="muted">申请为本人额度账户发放，不会给其他空间负责人充值。实际金额由运营决定。</text><wd-input v-model="requestAmount" label="期望金额" placeholder="元，可留空" /><wd-input v-model="requestReason" label="申请说明" placeholder="说明用途，便于运营审核" /><wd-button block :loading="requestSaving" @click="submitRequest">提交申请</wd-button></view></wd-popup><wd-popup v-model="limitDialog" position="bottom" closable><view class="limit-form"><text class="section-title">设置工作空间使用上限</text><text class="muted">留空不单设上限，0 表示暂停新的付费调用。</text><wd-input v-model="totalLimit" label="工作空间总上限" placeholder="元，留空不限制" /><wd-input v-model="dailyLimit" label="每日上限" placeholder="元，留空不限制" /><wd-button block :loading="saving" @click="saveLimits">保存</wd-button></view></wd-popup></view></view>
</template>
<style scoped>
.allowance-mobile{min-height:100vh;background:#f5f7fa;color:#2b4357}.allowance-body{padding:28rpx 28rpx 70rpx}.space-picker{display:flex;justify-content:space-between;background:white;padding:25rpx 28rpx;border:1px solid #e4eaf0;border-radius:18rpx;font-size:27rpx}.payer-panel{padding:35rpx 4rpx}.section-title{display:block;font-size:29rpx;font-weight:600}.muted{display:block;font-size:23rpx;line-height:1.85;color:#8294a3;margin-top:15rpx}.service-status{display:inline-block;color:#598477;background:#e9f2ed;padding:7rpx 14rpx;font-size:21rpx;border-radius:10rpx;margin-top:18rpx}.balance-panel{background:#254458;border-radius:24rpx;padding:36rpx;color:#afc5d3;font-size:24rpx}.balance-number{font-size:70rpx;color:white;line-height:1.7;word-break:break-all}.balance-number>text{font-size:24rpx;margin-left:14rpx}.balance-detail{display:flex;justify-content:space-between;gap:20rpx;font-size:22rpx;flex-wrap:wrap}.content-card{background:white;border:1px solid #e4eaf0;border-radius:20rpx;padding:28rpx;margin:22rpx 0}.tab-row{display:flex;gap:38rpx;font-size:27rpx;margin:36rpx 0 25rpx;color:#8c9aaa}.tab-row .active{color:#284b62;border-bottom:4rpx solid #284b62;padding-bottom:14rpx}.card-heading,.detail-row{display:flex;justify-content:space-between;gap:16rpx}.detail-row{font-size:25rpx;padding:22rpx 0;border-bottom:1px solid #f1f4f7}.link{font-size:24rpx;color:#4d819c}.empty-panel{padding:40rpx;text-align:center;color:#8b9aa5;font-size:25rpx}.error-panel{padding:25rpx;background:#fff0ed;color:#b46955;margin:20rpx 0;border-radius:15rpx;font-size:24rpx}.limit-form{padding:40rpx 30rpx 70rpx}.limit-form .section-title{margin-bottom:20rpx}.ledger-row{font-size:25rpx}
</style>
