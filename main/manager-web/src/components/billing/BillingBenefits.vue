<template>
  <section class="benefits-panel">
    <div class="billing-panel-heading"><div><h2>余额与资源包</h2><p>当前范围的最新官方快照，独立于账期和调用日期。</p></div><div class="benefit-actions"><el-button size="small" icon="el-icon-refresh" :loading="loading" :disabled="syncing" @click="load">刷新</el-button><el-button v-if="canManage && canSync" size="small" :loading="syncing" :disabled="loading || syncing" @click="sync">同步额度</el-button></div></div>
    <div v-if="error || syncError" class="billing-inline-error">{{ error || syncError }}</div>
    <div v-if="loading" class="billing-skeleton"><i /><i /></div>
    <template v-if="data && !error">
      <div class="benefit-sources"><div v-for="(source,index) in data.sources || []" :key="index"><span class="source-dot" :class="source.status === 'READY' ? 'ready' : 'attention'" /><strong>{{ label(source.provider) }}</strong><span>{{ label(source.status) }}</span><small>成功快照 {{ time(source.lastSyncedAt) }}</small><p v-if="source.message">{{ source.message }}</p><p v-if="source.status === 'SYNC_FAILED'" class="source-failed">最近同步失败，保留此前快照。</p></div></div>
      <div v-if="showBalances" class="billing-metrics balance-section"><article v-for="(balance,index) in data.balances || []" :key="index" class="billing-metric"><span class="billing-kicker">{{ label(balance.provider) }} · 账户资金</span><div class="amount">{{ money(balance.totalBalance,balance.currency) }}</div><p>充值余额 {{ money(balance.toppedUpBalance,balance.currency) }} · 赠送余额 {{ money(balance.grantedBalance,balance.currency) }}</p><p>服务状态：{{ balance.isAvailable === true ? '可用' : balance.isAvailable === false ? '不可用' : '供应商未提供' }}</p></article></div>
      <p class="benefit-separation">账号余额是可用资金，资源包是服务额度，均不是已发生消费。不同单位及来源不相加。</p>
      <details v-if="(data.notices || []).length" class="benefit-explanation"><summary>查看数据覆盖与统计说明</summary><p v-for="(notice,index) in data.notices || []" :key="index" class="benefit-source-note">{{ notice }}</p></details>
      <section class="asset-section"><div class="billing-panel-heading"><div><h2>可抵扣额度 <span class="asset-count">{{ quotas.length }}</span></h2><p>{{ accountView ? '按购买实例展示，多个来源保留各自的官方数据。' : '只展示有证据归属本工作空间的资源。共享账号额度不会自动分配到本空间。' }}</p></div></div><div class="billing-toolbar"><el-select v-model="assetProduct" size="small" clearable placeholder="全部产品" @change="updateFilters"><el-option v-for="product in products" :key="product" :label="product" :value="product" /></el-select><el-select v-model="assetStatus" size="small" @change="updateFilters"><el-option label="当前资源（不含过期 / 用尽）" value="current" /><el-option label="全部状态" value="all" /><el-option label="已耗尽" value="exhausted" /><el-option label="已到期" value="expired" /></el-select></div><div v-if="!quotas.length" class="billing-empty"><i class="el-icon-tickets" /><h2>当前范围暂无可展示的额度包</h2><p>可切换产品或查看全部状态。没有资源记录不代表零余额，请结合上方同步状态与查询覆盖。</p></div><div v-else class="asset-grid"><BillingAssetCard v-for="asset in quotas.slice(0, quotaLimit)" :key="asset.assetId" :asset="asset" /></div><el-button v-if="quotas.length > quotaLimit" class="show-more-assets" @click="quotaLimit += 8">继续查看（还有 {{ quotas.length - quotaLimit }} 项）</el-button></section>
      <details v-if="entitlements.length" class="asset-disclosure" @toggle="entitlementsOpen = $event.target.open"><summary><i class="el-icon-mic" /> 音色与功能权益 <span>{{ entitlements.length }} 项</span><i class="el-icon-arrow-down" /></summary><p>使用许可与功能权益单独展示，不计入可抵扣额度包数量。</p><div v-if="entitlementsOpen" class="asset-grid"><BillingAssetCard v-for="asset in entitlements.slice(0, entitlementLimit)" :key="asset.assetId" :asset="asset" /></div><el-button v-if="entitlementsOpen && entitlements.length > entitlementLimit" class="show-more-assets" @click="entitlementLimit += 12">继续查看（还有 {{ entitlements.length - entitlementLimit }} 项）</el-button></details>
      <details v-if="unknownAssets.length" class="asset-disclosure"><summary><i class="el-icon-document" /> 类型待确认的资源 <span>{{ unknownAssets.length }} 项</span><i class="el-icon-arrow-down" /></summary><p>供应商字段不足以确认资源类型，保留原始来源供核对。</p><div class="asset-grid"><BillingAssetCard v-for="asset in unknownAssets" :key="asset.assetId" :asset="asset" /></div></details>
      <details class="deduction-guide"><summary><i class="el-icon-question" /> 有资源包，为什么还产生费用？ <i class="el-icon-arrow-down" /></summary><p>先核对实际消费与资源包是否同产品、同账号及适用项目，再查看消费当时的有效期、可用额度和计量单位。</p><p>当前余额不能证明当时可抵扣；金额为零也不能证明某个资源包已抵扣。只有取得官方抵扣凭证，才可确认关联。</p><span>当前为只读核对，不执行购买、续费或模型切换。</span></details>
    </template>
  </section>
</template>
<script>
import Api from '@/apis/api';
import BillingAssetCard from './BillingAssetCard.vue';
import { assetProduct, filterAssets } from '@/utils/billingAssets';
import { amountText, billingLabel, billingTime, decimalText } from '@/utils/billing';

export default {
  components: { BillingAssetCard },
  props: { scope: { type: String, default: 'workspace' }, account: { type: Object, default: null }, workspaceId: { type: String, required: true }, canManage: Boolean, standaloneAccount: Boolean, canSync: { type: Boolean, default: true } },
  data() { return { quotaLimit: 8, entitlementLimit: 12, entitlementsOpen: false, assetProduct: '', assetStatus: 'current', accountView: false, data: null, loading: false, syncing: false, error: '', syncError: '', generation: 0, syncGeneration: 0 }; },
  computed: {
    assets() { return this.data && this.data.assets || []; },
    products() { return [...new Set(this.assets.map(assetProduct))]; },
    filteredAssets() { return filterAssets(this.assets, this.assetProduct, this.assetStatus); },
    quotas() { return this.filteredAssets.filter(asset => asset.category === 'QUOTA'); },
    entitlements() { return this.filteredAssets.filter(asset => asset.category === 'ENTITLEMENT'); },
    unknownAssets() { return this.filteredAssets.filter(asset => !['QUOTA','ENTITLEMENT'].includes(asset.category)); },
    showBalances() { return this.canManage && this.accountView && this.data && this.data.canManage === true && this.data.accountView === true; } },
  watch: {
    scope(value) { this.accountView = this.canManage && value === 'account'; this.load(); },
    '$route.query.assetStatus'() { this.readFilters(); },
    '$route.query.assetProduct'() { this.readFilters(); },
    workspaceId() { this.invalidate(); this.accountView = false; this.load(); },
    canManage(value) { if (!value && this.accountView) { this.invalidate(); this.accountView = false; this.load(); } }
  },
  created() { this.accountView = this.canManage && this.scope === 'account'; this.readFilters(); this.load(); },
  beforeDestroy() { this.invalidate(); },
  methods: {
    readFilters() { const q = this.$route && this.$route.query || {}; this.assetStatus = ['current','all','expired','exhausted'].includes(q.assetStatus) ? q.assetStatus : 'current'; this.assetProduct = typeof q.assetProduct === 'string' ? q.assetProduct : ''; },
    updateFilters() { this.quotaLimit = 8; this.entitlementLimit = 12; this.$router.replace({ path: '/model-billing', query: { ...this.$route.query, view: 'benefits', scope: this.accountView ? 'account' : 'workspace', assetStatus: this.assetStatus, assetProduct: this.assetProduct } }).catch(error => { if (error.name !== 'NavigationDuplicated') this.error = '筛选更新失败，请重试'; }); },
    label: billingLabel, time: billingTime, money: amountText,
    packageKey(row) {
      // A display key identifies a table row; it never becomes a supplier instance ID.
      return JSON.stringify([row.provider, row.accountRef, ...(row.rowKey ? ['display', row.rowKey] : ['instance', row.instanceNo])]);
    },
    instanceLabel(row) { return row.instanceNo || '未知（供应商未返回）'; },
    quantity(value, unit) {
      const amount = decimalText(value);
      if (amount === '—') return typeof value === 'string' && value.trim() ? billingLabel(value) : '—';
      return unit ? `${amount} ${billingLabel(unit)}` : `${amount}（单位：—）`;
    },
    yesNo(value) { return value === true || value === 'true' ? '是' : value === false || value === 'false' ? '否' : '未提供'; },
    shareability(value) { return value === true ? '可共享' : value === false ? '不可共享' : '未知'; },
    sourceType(status) { return status === 'READY' ? 'success' : status === 'SYNC_FAILED' ? 'danger' : status === 'NOT_CONFIGURED' || status === 'NEVER_SYNCED' ? 'warning' : 'info'; },
    invalidate() { this.generation++; this.syncGeneration++; this.data = null; this.loading = false; this.syncing = false; this.error = ''; this.syncError = ''; },
    current(generation, workspace, accountView) { return generation === this.generation && workspace === this.workspaceId && (this.standaloneAccount && accountView || workspace === localStorage.getItem('currentWorkspaceId')) && accountView === this.accountView; },
    async load() {
      const generation = ++this.generation; const workspace = this.workspaceId;
      if (!this.canManage) this.accountView = false;
      const accountView = this.accountView;
      this.error = ''; this.data = null;
      if (!(this.standaloneAccount && accountView) && (!workspace || workspace !== localStorage.getItem('currentWorkspaceId'))) { this.loading = false; return; }
      this.loading = true;
      try {
        const data = await Api.billing.benefits({ accountView, ...(accountView && this.account ? { provider: this.account.provider, accountRef: this.account.accountRef } : {}) });
        if (!this.current(generation, workspace, accountView)) return;
        const validScope = data && (accountView ? data.scope === 'ACCOUNT' && data.canManage === true : data.scope === 'WORKSPACE' && data.workspaceId === workspace);
        if (!validScope || data.accountView !== accountView) throw new Error('额度快照的工作空间或权限不匹配，请重新加载');
        this.data = data;
      } catch (error) { if (this.current(generation, workspace, accountView)) { this.error = error.message; this.data = null; } }
      finally { if (this.current(generation, workspace, accountView)) this.loading = false; }
    },
    async sync() {
      if (!this.canManage || this.canSync === false || this.syncing || this.loading || (!this.standaloneAccount && this.workspaceId !== localStorage.getItem('currentWorkspaceId'))) return;
      const generation = ++this.syncGeneration; const workspace = this.workspaceId;
      this.syncing = true; this.syncError = '';
      try {
        await Api.billing.syncBenefits();
        if (generation === this.syncGeneration && this.canManage && workspace === this.workspaceId && workspace === localStorage.getItem('currentWorkspaceId')) await this.load();
      } catch (error) {
        if (generation === this.syncGeneration && this.canManage && workspace === this.workspaceId && workspace === localStorage.getItem('currentWorkspaceId')) this.syncError = `额度同步未完成：${error.message}。当前显示的数据可能仍为此前快照。`;
      } finally { if (generation === this.syncGeneration) this.syncing = false; }
    }
  }
};
</script>
<style scoped>
.benefits-panel{min-width:0}.benefit-explanation{margin:12px 0;color:#8a97a9;font-size:12px}.benefit-explanation summary{cursor:pointer;color:#6e83a4}.show-more-assets{display:block;margin:18px auto 0}.benefit-actions{display:flex;gap:8px}.benefit-actions .el-button+.el-button{margin-left:0}.benefit-sources{display:flex;gap:12px;flex-wrap:wrap;margin:18px 0}.benefit-sources>div{border:1px solid #e6ebf2;background:white;border-radius:9px;padding:12px 15px;font-size:11px;color:#8a97a9;flex:1;min-width:min(220px,100%)}.benefit-sources strong{font-weight:500;color:#586b85;margin-right:10px}.benefit-sources small{display:block;font-size:11px;margin-top:7px}.benefit-sources p{font-size:11px;line-height:1.7;margin-top:7px;overflow-wrap:anywhere}.source-dot{width:6px;height:6px;border-radius:50%;display:inline-block;margin-right:7px;background:#a5b0c2}.source-dot.ready{background:#77b79e}.source-dot.attention{background:#d5a261}.source-failed{color:#b6833b}.balance-section{margin:18px 0}.balance-section p+p{margin-top:6px}.benefit-separation,.benefit-source-note{font-size:12px;color:#8a97a9;line-height:1.9;margin:14px 0!important}.benefit-source-note{padding:10px 14px;border-left:3px solid #d5dfed;background:#f1f5fa}.asset-section{margin-top:28px}.asset-count{display:inline-block;font-size:11px;color:#7d8da7;background:#eaf0f9;border-radius:5px;padding:2px 7px;margin-left:7px;vertical-align:middle}.asset-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,390px),1fr));gap:16px}.asset-disclosure,.deduction-guide{background:white;border:1px solid #e6ebf2;padding:18px 20px;border-radius:12px;margin-top:18px}.asset-disclosure summary,.deduction-guide summary{list-style:none;cursor:pointer;display:flex;align-items:center;gap:10px;font-size:13px;color:#526986}.asset-disclosure summary>span{margin-left:auto;font-size:11px;color:#8b98ab}.asset-disclosure>p,.deduction-guide>p{font-size:12px;line-height:1.9;color:#8a97a9;margin:14px 0!important}.deduction-guide{background:#f2f6fc}.deduction-guide summary>.el-icon-arrow-down{margin-left:auto}.deduction-guide>span{font-size:11px;color:#96a2b2}.benefits-panel .billing-toolbar .el-select{max-width:100%}
</style>
