<template>
  <div class="billing-page">
    <HeaderBar />
    <main>
      <div class="page-heading">
        <div><h1>计费管理</h1><p>查看当前工作空间的模型用量与供应商确认的消费。</p></div>
        <el-button v-if="canManage" icon="el-icon-setting" @click="adminVisible = true">同步与归属管理</el-button>
      </div>
      <el-alert v-if="optionsError" :title="optionsError" type="error" show-icon :closable="false" />
      <el-button v-if="optionsError" type="text" @click="initialize">重新加载工作空间</el-button>
      <section class="filter-panel" v-loading="initializing">
        <div class="workspace-line"><span>当前工作空间：{{ workspaceName }}</span><small>{{ tab === 'benefits' ? '当前额度快照 · 时间显示为北京时间' : '日期及时间均为北京时间 · 起止日期包含当天' }}</small></div>
        <el-form v-if="tab !== 'benefits'" :inline="true" size="small" @submit.native.prevent="applyFilters">
          <el-form-item label="日期"><el-date-picker v-model="dateRange" type="daterange" value-format="yyyy-MM-dd" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" /></el-form-item>
          <el-form-item label="供应商"><el-select v-model="filters.provider" clearable filterable placeholder="全部供应商"><el-option v-for="item in providerOptions" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="服务"><el-select v-model="filters.serviceType" clearable placeholder="全部服务"><el-option v-for="item in serviceOptions" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="模型"><el-select v-model="filters.modelId" clearable filterable placeholder="全部模型"><el-option v-for="item in options.models || []" :key="item.id" :label="item.name || item.id" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="智能体"><el-select v-model="filters.agentId" clearable filterable placeholder="全部智能体（含历史）"><el-option v-for="item in options.agents || []" :key="item.id" :label="item.name || item.id" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="设备"><el-select v-model="filters.deviceId" clearable filterable placeholder="全部设备（含历史）"><el-option v-for="item in options.devices || []" :key="item.id" :label="item.name || item.id" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="设备型号"><el-input v-model="filters.board" clearable placeholder="设备型号" /></el-form-item>
          <el-form-item label="关键词"><el-input v-model="filters.keyword" clearable placeholder="搜索记录" @keyup.enter.native="applyFilters" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" :disabled="initializing || !!optionsError" :loading="loading" @click="applyFilters">查询</el-button><el-button :disabled="initializing" @click="resetFilters">重置</el-button></el-form-item>
        </el-form>
      </section>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-error" />
      <div v-if="!optionsError" v-loading="loading" class="results">
        <el-tabs v-model="tab" @tab-click="changeTab">
          <el-tab-pane label="工作空间概览" name="overview" />
          <el-tab-pane label="按智能体" name="agents" />
          <el-tab-pane label="按设备" name="devices" />
          <el-tab-pane label="调用明细" name="events" />
          <el-tab-pane label="官方账单" name="bills" />
          <el-tab-pane label="额度与资源包" name="benefits" />
        </el-tabs>
        <BillingBenefits v-if="tab === 'benefits' && workspaceId" :workspace-id="workspaceId" :can-manage="canManage" />
        <template v-else-if="!error && overview">
          <el-alert v-for="notice in coverageNotices" :key="notice.key" :title="notice.title" :description="notice.description" :type="notice.type" show-icon :closable="false" class="coverage-alert" />
          <div class="summary-grid">
            <div class="summary-card"><span>模型调用</span><strong>{{ number(overview.callCount) }}</strong><small>同一轮可能包含多次调用</small></div>
            <div class="summary-card"><span>会话数</span><strong>{{ number(overview.sessionCount) }}</strong><small>按当前筛选范围统计</small></div>
            <div class="summary-card cost-card"><span>可归属官方应付金额</span><BillingCosts :costs="overview.costs || []" :status="overview.costStatus" /><small>分别展示各币种；仅汇总可归属账单</small></div>
            <div class="summary-card"><span>最近账单同步</span><strong class="sync-time">{{ time(overview.lastSyncedAt) }}</strong><small>{{ label(overview.costStatus) }}</small></div>
          </div>
          <p class="billing-note">仅展示已采集的调用用量；供应商未返回的用量不补零。费用以供应商账单为准，尚未出账或无法细分到当前筛选范围的金额显示“—”。调用用量不用于推算或分摊实际费用。</p>
          <template v-if="tab === 'overview'">
            <section class="data-panel"><h2>模型用量</h2><BillingUsage :usage="overview.usage || []" /><div class="status-list"><el-tag v-for="(count, status) in overview.statusCounts || {}" :key="status" size="small" type="info">{{ label(status) }} {{ number(count) }}</el-tag></div></section>
            <section class="data-panel"><h2>每日调用次数</h2><el-table :data="overview.trend || []" empty-text="当前日期范围暂无调用记录"><el-table-column prop="date" label="日期" min-width="180" /><el-table-column label="调用次数" min-width="150"><template slot-scope="scope">{{ number(scope.row.callCount) }}</template></el-table-column></el-table></section>
          </template>
          <section v-else class="data-panel record-panel">
            <div class="table-heading"><h2>{{ tableTitle }}</h2><el-checkbox v-if="tab === 'bills' && canManage" v-model="unattributed" @change="changeUnattributed">平台未归属账单</el-checkbox></div>
            <p v-if="tab === 'bills' && unattributed" class="billing-note">当前明细为平台未归属账单，只有管理员可见；上方概览仍属于当前工作空间。</p>
            <p v-if="tab === 'events'" class="billing-note">按一次供应商调用记录用量。缺少供应商逐调用费用证据时，不展示逐调用金额。</p>
            <BillingRecords :kind="tab" :rows="rows" @drill="drill" />
            <el-pagination :current-page="page" :page-size="limit" :page-sizes="[20, 50, 100]" :total="total" layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changeLimit" />
          </section>
        </template>
        <el-empty v-else-if="!loading && !initializing && !error" description="尚未加载计费数据" />
      </div>
      <BillingAdminDialog :visible="adminVisible" :options="options" :workspace-id="workspaceId" @close="adminVisible = false" @updated="refreshAfterAdmin" />
    </main>
  </div>
</template>
<script>
import Api from '@/apis/api';
import HeaderBar from '@/components/HeaderBar.vue';
import BillingUsage from '@/components/billing/BillingUsage.vue';
import BillingCosts from '@/components/billing/BillingCosts.vue';
import BillingRecords from '@/components/billing/BillingRecords.vue';
import BillingAdminDialog from '@/components/billing/BillingAdminDialog.vue';
import BillingBenefits from '@/components/billing/BillingBenefits.vue';
import { BILLING_TABS, billingFiltersFromQuery, billingQuery, defaultBillingFilters, validateBillingDates, billingLabel, billingTime, decimalText, optionItems, billingCoverageNotices } from '@/utils/billing';

export default {
  components: { HeaderBar, BillingUsage, BillingCosts, BillingRecords, BillingAdminDialog, BillingBenefits },
  data() { return { filters: defaultBillingFilters(), appliedFilters: defaultBillingFilters(), options: {}, workspaceId: '', tab: 'overview', page: 1, limit: 20, overview: null, rows: [], total: 0, loading: false, initializing: false, error: '', optionsError: '', generation: 0, optionsGeneration: 0, adminVisible: false, unattributed: false }; },
  computed: {
    canManage() { return this.options.canManage === true; },
    coverageNotices() { return billingCoverageNotices(this.overview || {}, this.canManage); },
    dateRange: { get() { return [this.filters.startDate, this.filters.endDate]; }, set(value) { this.filters.startDate = value && value[0] || ''; this.filters.endDate = value && value[1] || ''; } },
    providerOptions() { return optionItems(this.options.providers || []); },
    serviceOptions() { return optionItems(this.options.serviceTypes || []); },
    workspaceName() { return this.options.workspaceName || this.workspaceId || '加载中'; },
    tableTitle() { return ({ agents: '智能体用量与消费', devices: '设备用量与消费', events: '供应商调用记录', bills: '供应商官方账单' })[this.tab]; }
  },
  watch: {
    '$route.query': { handler() { this.readRoute(); if (!this.initializing && !this.optionsError && this.workspaceId) this.loadData(); }, deep: true }
  },
  created() { this.readRoute(); this.initialize(); },
  beforeDestroy() { this.generation++; this.optionsGeneration++; },
  methods: {
    label: billingLabel, time: billingTime, number: decimalText,
    readRoute() {
      const query = this.$route.query || {};
      this.filters = billingFiltersFromQuery(query); this.appliedFilters = { ...this.filters };
      this.tab = BILLING_TABS.includes(query.tab) ? query.tab : 'overview';
      this.page = Math.max(1, Math.floor(Number(query.page) || 1));
      this.limit = [20, 50, 100].includes(Number(query.limit)) ? Number(query.limit) : 20;
      this.unattributed = this.canManage && query.unattributed === 'true';
    },
    async initialize() {
      const request = ++this.optionsGeneration; const workspace = localStorage.getItem('currentWorkspaceId') || '';
      this.generation++; this.initializing = true; this.optionsError = ''; this.options = {}; this.overview = null; this.rows = []; this.total = 0;
      try {
        const options = await Api.billing.options();
        const selectedWorkspace = localStorage.getItem('currentWorkspaceId') || '';
        if (request !== this.optionsGeneration || (workspace && workspace !== selectedWorkspace)) return;
        if (!options || !options.workspaceId || (selectedWorkspace && options.workspaceId !== selectedWorkspace)) throw new Error('工作空间上下文已变化，请重新加载');
        this.workspaceId = options.workspaceId;
        if (!workspace) localStorage.setItem('currentWorkspaceId', options.workspaceId);
        this.options = options; this.readRoute(); this.initializing = false;
        await this.loadData();
      } catch (error) { if (request === this.optionsGeneration) { this.optionsError = error.message; this.options = {}; this.overview = null; this.rows = []; } }
      finally { if (request === this.optionsGeneration) this.initializing = false; }
    },
    current(request, workspace) { return request === this.generation && workspace === this.workspaceId && workspace === localStorage.getItem('currentWorkspaceId'); },
    async refreshAfterAdmin() {
      this.loadData();
      const request = ++this.optionsGeneration; const workspace = this.workspaceId;
      try {
        const options = await Api.billing.options();
        if (request !== this.optionsGeneration || workspace !== localStorage.getItem('currentWorkspaceId') || options.workspaceId !== workspace) return;
        this.options = options;
        if (!options.canManage) this.adminVisible = false;
      } catch (error) {
        if (request === this.optionsGeneration && workspace === localStorage.getItem('currentWorkspaceId')) this.error = `账单操作已完成，但筛选选项刷新失败：${error.message}`;
      }
    },
    async loadData() {
      const request = ++this.generation; const workspace = this.workspaceId;
      if (this.tab === 'benefits') { this.error = ''; this.overview = null; this.rows = []; this.total = 0; this.loading = false; return; }
      this.error = validateBillingDates(this.appliedFilters); this.overview = null; this.rows = []; this.total = 0;
      if (this.error || !workspace || workspace !== localStorage.getItem('currentWorkspaceId')) { this.loading = false; return; }
      this.loading = true;
      const query = billingQuery(this.appliedFilters, this.page, this.limit);
      const tab = this.tab;
      try {
        const listRequest = tab === 'overview' ? Promise.resolve(null) : tab === 'agents' || tab === 'devices'
          ? Api.billing.groups({ ...query, groupBy: tab === 'agents' ? 'agent' : 'device' })
          : tab === 'bills' ? Api.billing.bills({ ...query, ...(this.canManage && this.unattributed ? { unattributed: true } : {}) }) : Api.billing.events(query);
        const [overview, records] = await Promise.all([Api.billing.overview(query), listRequest]);
        if (!this.current(request, workspace)) return;
        if (!overview || overview.workspaceId !== workspace) throw new Error('返回数据的工作空间不匹配，请重新加载');
        this.overview = overview;
        if (records) { this.rows = records.list || []; this.total = records.total || 0; }
      } catch (error) { if (this.current(request, workspace)) { this.error = error.message; this.overview = null; this.rows = []; this.total = 0; } }
      finally { if (this.current(request, workspace)) this.loading = false; }
    },
    navigate(filters, tab = this.tab, page = 1, limit = this.limit) {
      const message = tab === 'benefits' ? '' : validateBillingDates(filters);
      if (message) { this.error = message; return; }
      const query = { ...billingQuery(filters, page, limit), tab };
      if (this.canManage && this.unattributed && tab === 'bills') query.unattributed = 'true';
      Object.keys(query).forEach(key => { query[key] = String(query[key]); });
      const stable = value => JSON.stringify(Object.keys(value).sort().map(key => [key, value[key]]));
      if (stable(query) === stable(this.$route.query || {})) { this.appliedFilters = { ...filters }; this.loadData(); }
      else this.$router.replace({ path: '/model-billing', query }).catch(error => { if (error.name !== 'NavigationDuplicated') this.error = '筛选条件未能更新，请重试'; });
    },
    applyFilters() { this.navigate(this.filters); },
    resetFilters() { this.unattributed = false; this.filters = defaultBillingFilters(); this.navigate(this.filters); },
    changeTab() { this.navigate(this.appliedFilters); },
    changePage(value) { this.navigate(this.appliedFilters, this.tab, value); },
    changeLimit(value) { this.navigate(this.appliedFilters, this.tab, 1, value); },
    changeUnattributed() { this.navigate(this.appliedFilters, 'bills'); },
    drill(row) {
      if (!row.id) return;
      const filters = { ...this.appliedFilters };
      if (this.tab === 'agents') filters.agentId = row.id;
      if (this.tab === 'devices') filters.deviceId = row.id;
      this.navigate(filters, 'events');
    }
  }
};
</script>
<style scoped>
.coverage-alert { margin-bottom: 12px; }
.billing-page { min-height: 100vh; background: #f7f8fc; color: #30384c; text-align: left; }.billing-page main { max-width: 1600px; margin: auto; padding: 34px 36px 64px; }.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 24px; }h1 { font-size: 25px; margin: 0 0 10px; }.page-heading p, .workspace-line small { color: #8690a4; margin: 0; font-size: 13px; }.filter-panel, .data-panel, .summary-card { background: #fff; border: 1px solid #e8ebf3; border-radius: 12px; }.filter-panel { padding: 20px 24px 2px; }.workspace-line { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px; margin-bottom: 20px; font-size: 13px; }.filter-panel .el-select, .filter-panel .el-input { width: 190px; }.filter-panel ::v-deep .el-form-item { margin-bottom: 16px; margin-right: 18px; }.filter-panel ::v-deep .el-date-editor--daterange { width: 270px; }.results { margin-top: 24px; min-height: 240px; }.summary-grid { display: grid; grid-template-columns: 1fr 1fr 2fr 1.5fr; gap: 16px; margin-top: 12px; }.summary-card { display: flex; flex-direction: column; gap: 12px; padding: 21px 24px; }.summary-card>span { color: #738097; font-size: 13px; }.summary-card>strong { font-size: 28px; font-weight: 500; }.summary-card>small { color: #9098a8; font-size: 11px; }.summary-card .sync-time { font-size: 15px; line-height: 1.7; }.billing-note { color: #8290a5; font-size: 12px; line-height: 1.8; margin: 16px 0; }.data-panel { padding: 24px; margin-top: 20px; }h2 { font-size: 16px; margin: 0 0 20px; font-weight: 500; }.status-list { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }.page-error { margin-top: 20px; }.table-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }.table-heading h2 { margin-bottom: 0; }.el-pagination { margin-top: 24px; text-align: right; overflow-x: auto; }.billing-page ::v-deep .el-tabs__nav-wrap::after { height: 1px; background: #e5e9f1; }@media(max-width: 1100px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }@media(max-width: 650px) { .billing-page main { padding: 24px 16px 48px; }.page-heading { align-items: flex-start; flex-direction: column; }.filter-panel { padding: 16px 16px 0; }.summary-grid { grid-template-columns: 1fr; }.data-panel { padding: 16px; }.workspace-line { flex-direction: column; } }
</style>
