<route lang="jsonc" type="page">
{ "needLogin": true, "layout": "default", "style": { "navigationBarTitleText": "额度与用量", "navigationStyle": "custom" } }
</route>
<script setup lang="ts">
import type { AllowanceEntry, AllowanceSummary, WorkspaceLimits } from '@/api/platform'
import type { Workspace } from '@/api/workspace'
import { computed, ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { getAllowanceLedger, getAllowanceSummary, getWorkspaceLimits, saveWorkspaceLimits } from '@/api/platform'
import { getWorkspaceList } from '@/api/workspace'
import { allowanceAmount, allowanceDisplayAmount, allowancePayerLabel, visibleAllowance } from '@/utils/allowance'
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
const ledgerError = ref('')
const limitDialog = ref(false)
const saving = ref(false)
const totalLimit = ref('')
const dailyLimit = ref('')
let generation = 0
let ledgerGeneration = 0
const balance = computed(() => visibleAllowance(summary.value))
const payerLabel = computed(() => allowancePayerLabel(summary.value))
const serviceStates: Record<string, { label: string, tone: string, description: string }> = {
  READY: { label: '模型服务已就绪', tone: 'ready', description: '' },
  PAYER_FROZEN: { label: '服务账户已暂停', tone: 'danger', description: '当前空间负责人的服务账户已暂停，请联系平台处理。' },
  ALLOWANCE_BUSY: { label: '服务正在结算', tone: 'info', description: '服务正在结算，请稍后重试。' },
  ALLOWANCE_EXHAUSTED: { label: '可用额度不足', tone: 'warning', description: '当前空间负责人的可用额度已用完，新的付费调用暂时无法开始。' },
  WORKSPACE_ALLOWANCE_LIMIT: { label: '当前空间已达使用上限', tone: 'warning', description: '当前空间暂无法开始新的付费调用，请稍后重试或联系负责人调整上限。' },
  PLATFORM_SERVICE_NOT_READY: { label: '平台服务待开通', tone: 'info', description: '平台尚未开通可用的模型服务，请联系平台处理。' },
}
const serviceState = computed(() => serviceStates[summary.value?.serviceStatus || ''] || { label: '服务状态待确认', tone: 'info', description: '暂未确认模型服务状态，请刷新后重试。' })
const workspaceName = computed(() => workspaces.value.find(item => item.id === workspaceId.value)?.name || '选择工作空间')
const amount = allowanceDisplayAmount
function showAmount(title: string, value: string | number | null | undefined) {
  const exact = allowanceAmount(value)
  uni.showModal({ title, content: exact === '—' ? '金额未提供' : `精确金额：${exact} 元`, showCancel: false, confirmText: '知道了' })
}
function back() { uni.navigateBack() }
function message(error: unknown) { return error instanceof Error ? error.message : '读取失败，请重试' }
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  summary.value = null
  limits.value = null
  try {
    const availableWorkspaces = await getWorkspaceList()
    if (current !== generation) return
    workspaces.value = availableWorkspaces
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
  const current = ++ledgerGeneration
  if (reset) { page.value = 1; ledger.value = [] }
  ledgerLoading.value = true
  ledgerError.value = ''
  try {
    const result = await getAllowanceLedger(page.value)
    if (current !== ledgerGeneration) return
    const count = Number(result.total)
    if (!Array.isArray(result.list) || !/^\d+$/.test(String(result.total)) || !Number.isSafeInteger(count)) throw new Error('流水记录暂时无法读取，请重试')
    ledger.value = [...ledger.value, ...result.list]; total.value = count; page.value++
  }
  catch (reason) { if (current === ledgerGeneration) ledgerError.value = message(reason) }
  finally { if (current === ledgerGeneration) ledgerLoading.value = false }
}
function showLedger() { tab.value = 'ledger'; loadLedger(true) }
function editLimits() { totalLimit.value = limits.value?.totalLimit ?? ''; dailyLimit.value = limits.value?.dailyLimit ?? ''; limitDialog.value = true }
async function saveLimits() {
  if (!limits.value?.canManage || saving.value) return
  if ([totalLimit.value, dailyLimit.value].some(value => value !== '' && !/^\d+(\.\d{1,6})?$/.test(value))) { uni.showToast({ title: '请输入非负金额，最多 6 位小数', icon: 'none' }); return }
  saving.value = true
  try { await saveWorkspaceLimits({ workspaceId: workspaceId.value, expectedVersion: limits.value.responsibilityVersion, totalLimit: totalLimit.value || null, dailyLimit: dailyLimit.value || null }); limitDialog.value = false; await load() }
  catch (reason) { uni.showToast({ title: message(reason), icon: 'none' }) }
  finally { saving.value = false }
}
onShow(() => { load() })
onUnload(() => { generation++; ledgerGeneration++ })
</script>
<template>
  <view class="allowance-mobile"><wd-navbar title="额度与用量" left-arrow fixed placeholder safe-area-inset-top @click-left="back" /><view class="allowance-body"><picker :range="workspaces" range-key="name" @change="chooseWorkspace"><view class="space-picker">{{ workspaceName }} <text>⌄</text></view></picker><view v-if="error" class="error-panel" @click="load">{{ error }} · 点击重试</view><view v-if="loading" class="empty-panel">正在读取服务额度…</view><template v-else-if="summary"><view class="payer-panel"><text class="section-title">{{ payerLabel }}</text><text class="muted">模型由平台统一提供，当前空间的用量计入负责人额度。额度由平台统一发放。</text><text class="service-status" :class="serviceState.tone">{{ serviceState.label }}</text><text v-if="serviceState.description" class="muted">{{ serviceState.description }}</text></view><view v-if="balance" class="balance-panel"><text>剩余额度</text><view class="balance-number" :class="{ 'balance-range': amount(balance.remaining).includes('小于') }" @click="showAmount('剩余额度', balance.remaining)">{{ amount(balance.remaining) }}<text>元</text></view><view class="balance-detail"><text @click="showAmount('累计消耗', balance.spent)">累计消耗 {{ amount(balance.spent) }}</text></view></view><view v-else class="content-card muted">工作空间负责人的个人总余额仅本人可见。工作空间额度不足时暂停新付费调用，不改扣你的个人额度。</view><view class="tab-row"><text :class="{ active: tab === 'workspace' }" @click="tab='workspace'">当前空间</text><text :class="{ active: tab === 'ledger' }" @click="showLedger">我的额度流水</text></view><template v-if="tab === 'workspace'"><view v-if="limits" class="content-card"><view class="card-heading"><text class="section-title">工作空间使用上限</text><text v-if="limits.canManage" class="link" @click="editLimits">设置</text></view><view class="detail-row"><text>工作空间总上限</text><text v-if="limits.totalLimit == null">未单设上限</text><text v-else @click="showAmount('工作空间总上限', limits.totalLimit)">{{ amount(limits.totalLimit) }} 元</text></view><view class="detail-row"><text>今日使用 / 日上限</text><text><text @click="showAmount('今日使用', limits.dailySpent)">{{ amount(limits.dailySpent) }}</text> / <text v-if="limits.dailyLimit == null">未单设上限</text><text v-else @click="showAmount('每日上限', limits.dailyLimit)">{{ amount(limits.dailyLimit) }}</text></text></view><view class="detail-row"><text>累计使用</text><text @click="showAmount('累计使用', limits.totalSpent)">{{ amount(limits.totalSpent) }} 元</text></view><text class="muted">工作空间上限属于负责人账户内的限制，不额外发放额度。</text></view><view class="content-card"><text class="section-title">工作空间负责人</text><text class="muted">负责人 ID：{{ summary.responsibleUserId }}。工作空间统一使用平台模型服务，扣减负责人的额度。</text></view><view class="content-card muted">消耗按实际用量和平台服务价格结算。</view></template><template v-else-if="tab === 'ledger'"><view v-if="ledgerError" class="error-panel" @click="loadLedger()">{{ ledgerError }} · 点击重试</view><view v-for="entry in ledger" :key="entry.id" class="content-card ledger-row"><view class="card-heading"><text>{{ entry.reason || entry.type }}</text><text @click="showAmount('流水变动', entry.amount)">{{ amount(entry.amount) }} 元</text></view><text class="muted">{{ entry.createdAt }} · <text @click="showAmount('变动后余额', entry.balanceAfter)">余额 {{ amount(entry.balanceAfter) }}</text></text></view><view v-if="!ledger.length && !ledgerLoading && !ledgerError" class="empty-panel">暂无额度流水</view><wd-button v-if="ledger.length < total" block type="text" :loading="ledgerLoading" @click="loadLedger()">加载更多</wd-button></template></template><wd-popup v-model="limitDialog" position="bottom" closable><view class="limit-form"><text class="section-title">设置工作空间使用上限</text><text class="muted">留空不单设上限，0 表示暂停新的付费调用。</text><wd-input v-model="totalLimit" label="工作空间总上限" placeholder="元，留空不限制" /><wd-input v-model="dailyLimit" label="每日上限" placeholder="元，留空不限制" /><wd-button block :loading="saving" @click="saveLimits">保存</wd-button></view></wd-popup></view></view>
</template>
<style scoped>
.service-status.info{background:#edf1f6;color:#718096}.service-status.warning{background:#fff5e4;color:#98763e}.service-status.danger{background:#fff0ed;color:#ac5b55}
.balance-number.balance-range{font-size:32rpx;line-height:1.8;padding:14rpx 0}.balance-detail>text,.detail-row>text,.card-heading>text{min-width:0;overflow-wrap:anywhere}
.allowance-mobile{min-height:100vh;background:#f5f7fa;color:#2b4357}.allowance-body{padding:28rpx 28rpx 70rpx}.space-picker{display:flex;justify-content:space-between;background:white;padding:25rpx 28rpx;border:1px solid #e4eaf0;border-radius:18rpx;font-size:27rpx}.payer-panel{padding:35rpx 4rpx}.section-title{display:block;font-size:29rpx;font-weight:600}.muted{display:block;font-size:23rpx;line-height:1.85;color:#8294a3;margin-top:15rpx}.service-status{display:inline-block;color:#598477;background:#e9f2ed;padding:7rpx 14rpx;font-size:21rpx;border-radius:10rpx;margin-top:18rpx}.balance-panel{background:#254458;border-radius:24rpx;padding:36rpx;color:#afc5d3;font-size:24rpx}.balance-number{font-size:70rpx;color:white;line-height:1.7;word-break:break-all}.balance-number>text{font-size:24rpx;margin-left:14rpx}.balance-detail{display:flex;justify-content:space-between;gap:20rpx;font-size:22rpx;flex-wrap:wrap}.content-card{background:white;border:1px solid #e4eaf0;border-radius:20rpx;padding:28rpx;margin:22rpx 0}.tab-row{display:flex;gap:38rpx;font-size:27rpx;margin:36rpx 0 25rpx;color:#8c9aaa}.tab-row .active{color:#284b62;border-bottom:4rpx solid #284b62;padding-bottom:14rpx}.card-heading,.detail-row{display:flex;justify-content:space-between;gap:16rpx}.detail-row{font-size:25rpx;padding:22rpx 0;border-bottom:1px solid #f1f4f7}.link{font-size:24rpx;color:#4d819c}.empty-panel{padding:40rpx;text-align:center;color:#8b9aa5;font-size:25rpx}.error-panel{padding:25rpx;background:#fff0ed;color:#b46955;margin:20rpx 0;border-radius:15rpx;font-size:24rpx}.limit-form{padding:40rpx 30rpx 70rpx}.limit-form .section-title{margin-bottom:20rpx}.ledger-row{font-size:25rpx}
</style>
