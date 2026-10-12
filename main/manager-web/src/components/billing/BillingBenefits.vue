<template>
  <section class="benefits-panel">
    <div class="benefits-heading">
      <div><h2>额度与资源包</h2><p>供应商最近一次同步的当前额度快照，不受历史日期、模型、智能体或设备筛选影响。</p></div>
      <div class="benefits-actions">
        <el-checkbox v-if="canManage" v-model="accountView" :disabled="syncing" @change="load">平台账号额度</el-checkbox>
        <el-button size="small" :loading="loading" :disabled="syncing" @click="load">刷新</el-button>
        <el-button v-if="canManage" size="small" type="primary" :loading="syncing" :disabled="loading || syncing" @click="sync">同步额度</el-button>
      </div>
    </div>
    <el-alert title="账号余额表示可用资金，资源包表示可抵扣的服务额度；两者不是已发生消费，不能相加。" type="info" show-icon :closable="false" />
    <p class="scope-note">{{ accountView ? '当前查看平台供应商账号额度，可能覆盖多个工作空间。' : '当前仅展示明确归属本工作空间的资源包；共享账号余额仅在管理员平台视图展示。' }}</p>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-alert v-if="syncError" :title="syncError" type="error" show-icon :closable="false" />
    <div v-loading="loading" class="benefits-content">
      <template v-if="data && !error">
        <div class="source-grid">
          <article v-for="(source, index) in data.sources || []" :key="`${source.provider}-${index}`" class="source-card">
            <div><strong>{{ label(source.provider) }}</strong><el-tag size="small" :type="sourceType(source.status)">{{ label(source.status) }}</el-tag></div>
            <small>最近成功同步：{{ time(source.lastSyncedAt) }}</small>
            <p v-if="source.message">{{ source.message }}</p>
            <p v-if="source.status === 'SYNC_FAILED'" class="source-warning">同步失败，以下如有数据仍为此前快照，可能已发生变化。</p>
          </article>
        </div>
        <el-alert v-for="(notice, index) in data.notices || []" :key="`notice-${index}`" :title="notice" type="info" :closable="false" class="benefit-notice" />
        <template v-if="showBalances">
          <h3>平台账号余额</h3>
          <div v-if="data.balances && data.balances.length" class="balance-grid">
            <article v-for="(balance, index) in data.balances" :key="`${balance.provider}-${balance.accountRef}-${balance.currency}-${index}`" class="balance-card">
              <h4>{{ label(balance.provider) }} <small>{{ balance.accountRef }}</small></h4>
              <span>可用余额</span><strong>{{ money(balance.totalBalance, balance.currency) }}</strong>
              <dl><dt>充值余额</dt><dd>{{ money(balance.toppedUpBalance, balance.currency) }}</dd><dt>赠送余额</dt><dd>{{ money(balance.grantedBalance, balance.currency) }}</dd><dt>账号服务状态</dt><dd>{{ balance.isAvailable === true ? '可用' : balance.isAvailable === false ? '不可用' : '未提供' }}</dd></dl>
            </article>
          </div>
          <p v-else class="empty-note">暂无可展示的账号余额，请结合上方供应商同步状态查看原因。</p>
        </template>
        <h3>资源包</h3>
        <el-table :data="data.packages || []" empty-text="当前范围暂无可展示的资源包，请结合上方同步状态确认" :row-key="packageKey">
          <el-table-column type="expand"><template slot-scope="scope"><dl class="package-details"><dt>供应商账号</dt><dd>{{ scope.row.accountRef || '—' }}</dd><dt>资源包实例</dt><dd>{{ instanceLabel(scope.row) }}</dd><dt>产品 / 规格</dt><dd>{{ scope.row.productName || scope.row.product || '—' }} / {{ scope.row.configurationName || '—' }}</dd><dt>包类型</dt><dd>{{ label(scope.row.packageType) }}</dd><dt>计量单位</dt><dd>{{ label(scope.row.unit) }}</dd><dt>共享状态</dt><dd>{{ shareability(scope.row.isShareable) }}</dd><dt>重置周期</dt><dd>{{ label(scope.row.resetPeriod) }}</dd><dt>按自然月重置</dt><dd>{{ yesNo(scope.row.resetByNaturalMonth) }}</dd><dt>归属范围</dt><dd>{{ label(scope.row.attributionLevel) }}</dd></dl></template></el-table-column>
          <el-table-column label="供应商 / 资源包" min-width="190"><template slot-scope="scope"><strong>{{ scope.row.name || scope.row.configurationName || scope.row.instanceNo || '资源包' }}</strong><small class="secondary">{{ label(scope.row.provider) }}</small></template></el-table-column>
          <el-table-column label="供应商购买量" min-width="155"><template slot-scope="scope">{{ quantity(scope.row.purchasedAmount, scope.row.unit) }}</template></el-table-column>
          <el-table-column label="供应商当前用量" min-width="155"><template slot-scope="scope">{{ quantity(scope.row.currentUsage, scope.row.unit) }}</template></el-table-column>
          <el-table-column label="总额度" min-width="150"><template slot-scope="scope">{{ quantity(scope.row.totalAmount, scope.row.unit) }}</template></el-table-column>
          <el-table-column label="剩余额度" min-width="150"><template slot-scope="scope">{{ quantity(scope.row.availableAmount, scope.row.unit) }}</template></el-table-column>
          <el-table-column label="有效期（北京时间）" min-width="185"><template slot-scope="scope">{{ time(scope.row.effectiveTime) }}<small class="secondary">至 {{ time(scope.row.expiryTime) }}</small></template></el-table-column>
          <el-table-column label="状态 / 提醒" min-width="220"><template slot-scope="scope"><el-tag size="small" type="info">{{ label(scope.row.status) }}</el-tag><p v-for="(alert, index) in scope.row.alerts || []" :key="index" class="package-alert">{{ alert.message }}</p></template></el-table-column>
        </el-table>
      </template>
      <el-empty v-else-if="!loading && !error" description="尚未加载额度快照" />
    </div>
  </section>
</template>
<script>
import Api from '@/apis/api';
import { amountText, billingLabel, billingTime, decimalText } from '@/utils/billing';

export default {
  props: { workspaceId: { type: String, required: true }, canManage: Boolean },
  data() { return { accountView: false, data: null, loading: false, syncing: false, error: '', syncError: '', generation: 0, syncGeneration: 0 }; },
  computed: { showBalances() { return this.canManage && this.accountView && this.data && this.data.canManage === true && this.data.accountView === true; } },
  watch: {
    workspaceId() { this.invalidate(); this.accountView = false; this.load(); },
    canManage(value) { if (!value && this.accountView) { this.invalidate(); this.accountView = false; this.load(); } }
  },
  created() { this.load(); },
  beforeDestroy() { this.invalidate(); },
  methods: {
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
    current(generation, workspace, accountView) { return generation === this.generation && workspace === this.workspaceId && workspace === localStorage.getItem('currentWorkspaceId') && accountView === this.accountView; },
    async load() {
      const generation = ++this.generation; const workspace = this.workspaceId;
      if (!this.canManage) this.accountView = false;
      const accountView = this.accountView;
      this.error = ''; this.data = null;
      if (!workspace || workspace !== localStorage.getItem('currentWorkspaceId')) { this.loading = false; return; }
      this.loading = true;
      try {
        const data = await Api.billing.benefits({ accountView });
        if (!this.current(generation, workspace, accountView)) return;
        if (!data || data.workspaceId !== workspace || data.accountView !== accountView || (accountView && data.canManage !== true)) throw new Error('额度快照的工作空间或权限不匹配，请重新加载');
        this.data = data;
      } catch (error) { if (this.current(generation, workspace, accountView)) { this.error = error.message; this.data = null; } }
      finally { if (this.current(generation, workspace, accountView)) this.loading = false; }
    },
    async sync() {
      if (!this.canManage || this.syncing || this.loading || this.workspaceId !== localStorage.getItem('currentWorkspaceId')) return;
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
.benefits-panel { padding: 24px; background: #fff; border: 1px solid #e8ebf3; border-radius: 12px; }.benefits-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; margin-bottom: 18px; }.benefits-heading h2 { font-size: 18px; margin: 0 0 10px; }.benefits-heading p, .scope-note, .empty-note { color: #7d899c; font-size: 12px; line-height: 1.8; margin: 0; }.benefits-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }.benefits-actions .el-button + .el-button { margin-left: 0; }.scope-note { margin: 14px 0; }.benefits-content { min-height: 180px; }.source-grid, .balance-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; margin-top: 18px; }.source-card, .balance-card { border: 1px solid #e7ebf2; border-radius: 8px; padding: 18px; }.source-card>div { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 12px; }.source-card small, .source-card p { font-size: 12px; line-height: 1.8; color: #7d899c; }.source-card p { margin: 8px 0 0; }.source-card .source-warning, .package-alert { color: #a06b25; }.benefit-notice { margin-top: 12px; }h3 { margin: 24px 0 16px; font-size: 15px; }.balance-card h4 { font-size: 14px; margin: 0 0 20px; }.balance-card h4 small { display: block; margin-top: 7px; color: #7d899c; font-weight: 400; overflow-wrap: anywhere; }.balance-card>span { display: block; color: #7d899c; font-size: 12px; }.balance-card>strong { display: block; margin: 8px 0 18px; font-size: 23px; overflow-wrap: anywhere; }.balance-card dl, .package-details { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 12px; font-size: 12px; }.balance-card dt, .package-details dt { color: #7d899c; }.balance-card dd, .package-details dd { margin: 0; overflow-wrap: anywhere; }.package-details { margin: 12px 28px; }.secondary { display: block; color: #7d899c; font-size: 12px; line-height: 1.8; }.package-alert { font-size: 12px; line-height: 1.7; margin: 8px 0 0; }.benefits-panel ::v-deep .el-table th { background: #f8f9fc; }.benefits-panel ::v-deep .el-table .cell { overflow-wrap: anywhere; }@media(max-width: 750px) { .benefits-heading { flex-direction: column; }.benefits-panel { padding: 16px; }.source-grid, .balance-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
