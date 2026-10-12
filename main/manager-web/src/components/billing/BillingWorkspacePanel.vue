<template>
  <section class="billing-surface workspace-panel" :class="{ 'is-compact': compact }">
    <div class="billing-panel filter-box">
      <el-form :inline="true" size="small" @submit.native.prevent="applyFilters">
        <el-form-item :label="mode === 'costs' ? '消费期间' : '调用日期'"><el-date-picker v-model="dateRange" type="daterange" popper-class="billing-workspace-date-picker" value-format="yyyy-MM-dd" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" /></el-form-item>
        <el-form-item v-if="mode === 'usage'" label="服务"><el-select v-model="filters.serviceType" clearable placeholder="全部服务"><el-option v-for="item in serviceOptions" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item v-else label="供应商"><el-select v-model="filters.provider" clearable placeholder="全部供应商"><el-option v-for="item in providerOptions" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" :disabled="initializing || !!optionsError" @click="applyFilters">查询</el-button><el-button @click="resetFilters">重置</el-button><el-button v-if="mode === 'usage'" type="text" @click="advanced = !advanced">{{ advanced ? '收起筛选' : '更多筛选' }} <i :class="advanced ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" /></el-button></el-form-item>
        <div v-if="advanced && mode === 'usage'" class="advanced-filters">
          <el-form-item label="供应商"><el-select v-model="filters.provider" clearable><el-option v-for="item in providerOptions" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="智能体"><el-select v-model="filters.agentId" clearable filterable placeholder="全部智能体（含历史）"><el-option v-for="item in options.agents || []" :key="item.id" :label="item.name || '历史智能体'" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="设备"><el-select v-model="filters.deviceId" clearable filterable placeholder="全部设备（含历史）"><el-option v-for="item in options.devices || []" :key="item.id" :label="item.name || '历史设备'" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="模型"><el-select v-model="filters.modelId" clearable filterable placeholder="全部模型"><el-option v-for="item in options.models || []" :key="item.id" :label="item.name || '历史模型'" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="型号"><el-input v-model="filters.board" clearable placeholder="设备型号" /></el-form-item><el-form-item label="搜索"><el-input v-model="filters.keyword" clearable placeholder="智能体 / 设备名称" /></el-form-item>
        </div>
      </el-form>
      <p v-if="mode === 'costs'" class="billing-muted">仅统计消费区间完整落在所选日期内、且已明确归属本空间的账单；跨期费用不按天拆分。</p>
    </div>
    <div v-if="optionsError || error" class="billing-inline-error"><span>{{ optionsError || error }}</span><el-button type="text" @click="initialize">重试</el-button></div>
    <BillingCollectionHealth v-if="mode === 'usage' && workspaceId" :workspace-id="workspaceId" :can-manage="canManage" :compact="compact" :notices="compact ? coverageNotices : []" :usage-unknown-count="compact ? Number(overview && overview.usageUnknownCount || 0) : 0" />
    <div v-if="loading || initializing" class="billing-skeleton"><i /><i /></div>
    <template v-else-if="overview && !error && !optionsError">
      <template v-if="mode === 'costs'">
        <div class="billing-metrics"><article class="billing-metric"><span class="billing-kicker">{{ workspaceName }} · 已归属官方费用</span><div class="workspace-cost"><BillingCosts :costs="overview.costs || []" :status="overview.costStatus" /></div><p>仅计入有归属证据的官方金额，各币种分别列示。</p></article></div>
        <div v-if="!(overview.costs || []).length" class="billing-notice"><i class="el-icon-info" /><div>当前空间尚无可核实的官方费用<p>这不代表未使用或免费。共享账号账单需要有依据的归属，不能按用量比例拆分。</p><el-button v-if="canManage" type="text" @click="$emit('manage')">查看数据与归属</el-button></div></div>
        <section class="billing-panel"><div class="billing-panel-heading"><h2>本空间官方账单</h2><span class="billing-muted">主单与明细不重复计算</span></div><BillingBillList :query="{ startDate: appliedFilters.startDate, endDate: appliedFilters.endDate, provider: appliedFilters.provider }" /></section>
      </template>
      <template v-else>
        <template v-if="!compact"><el-alert v-for="notice in coverageNotices" :key="notice.key" :title="notice.title" :description="notice.description" :type="notice.type" show-icon :closable="false" class="coverage-alert" /></template>
        <div v-if="compact" class="compact-overview">
          <div class="compact-counts"><span><strong>{{ number(overview.callCount) }}</strong> 次记录</span><span>{{ number(overview.sessionCount) }} 个会话</span><span v-if="Number(overview.failedCount) > 0" class="failed">失败 {{ number(overview.failedCount) }}</span><span v-if="Number(overview.cancelledCount) > 0">取消 {{ number(overview.cancelledCount) }}</span><span v-if="unknownCallCount > 0">结果未知 {{ number(unknownCallCount) }}</span><el-tooltip content="调用尝试可能包含失败或未执行的请求；用量仅包含已采集记录。" placement="top"><i class="el-icon-info" tabindex="0" aria-label="统计范围说明" /></el-tooltip></div>
          <BillingCompactUsage :usage="overview.usage || []" :max-items="3" />
        </div>
        <div v-else class="billing-metrics"><article class="billing-metric"><span class="billing-kicker">已记录调用</span><div class="amount">{{ number(overview.callCount) }} <small>次</small></div><p>已记录的调用尝试，可能包含失败或未执行的请求。</p></article><article class="billing-metric"><span class="billing-kicker">已记录会话</span><div class="amount">{{ number(overview.sessionCount) }} <small>个</small></div><p>统计当前筛选范围，不能代表采集前的历史使用。</p></article><article class="billing-metric usage-metric"><span class="billing-kicker">供应商用量与本地观测</span><BillingUsage :usage="overview.usage || []" /></article></div>
        <div v-if="!Number(overview.callCount)" class="billing-notice"><i class="el-icon-info" /><div>尚未收到当前范围的用量记录<p v-if="!compact">请结合采集起点与覆盖情况查看。已采集 0 条不等于没有使用，供应商费用仍以官方账单为准。</p><p v-else>暂无记录不代表没有发生使用。</p></div></div>
        <section class="billing-panel records-panel"><div class="billing-panel-heading"><div class="billing-segments"><button v-for="item in [{ id: 'agents', name: '按智能体' }, { id: 'devices', name: '按设备' }, { id: 'events', name: '调用记录' }]" :key="item.id" :class="{ active: tab === item.id }" :aria-pressed="tab === item.id" @click="tab = item.id; changeTab()">{{ item.name }}</button></div><span v-if="!compact" class="billing-muted">不同视图为同一批调用，不相加</span></div><BillingRecords :kind="tab" :rows="rows" :compact="compact" :show-supplier-costs="showSupplierCosts" @drill="drill" /><el-pagination :current-page="page" :page-size="limit" :page-sizes="pageSizes" :pager-count="compact ? 5 : 7" :small="compact" :total="total" layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changeLimit" /></section>
      </template>
    </template>
  </section>
</template>
<script>
import Api from '@/apis/api';
import BillingCollectionHealth from './BillingCollectionHealth.vue';
import BillingCompactUsage from './BillingCompactUsage.vue';
import BillingBillList from './BillingBillList.vue';
import { billingRoute, billingNavigationQuery } from '@/utils/billingNavigation';
import BillingUsage from '@/components/billing/BillingUsage.vue';
import BillingCosts from '@/components/billing/BillingCosts.vue';
import BillingRecords from '@/components/billing/BillingRecords.vue';

import { billingFiltersFromQuery, billingQuery, defaultBillingFilters, validateBillingDates, billingLabel, billingTime, decimalText, optionItems, billingCoverageNotices } from '@/utils/billing';

export default {
  components: { BillingUsage, BillingCompactUsage, BillingCosts, BillingRecords, BillingCollectionHealth, BillingBillList },
  props: { compact: { type: Boolean, default: false }, mode: { type: String, default: 'usage' }, showSupplierCosts: { type: Boolean, default: true }, routePath: { type: String, default: '/model-billing' } },
  data() { return { advanced: false, filters: defaultBillingFilters(), appliedFilters: defaultBillingFilters(), options: {}, workspaceId: '', tab: 'overview', page: 1, limit: this.compact ? 10 : 20, overview: null, rows: [], total: 0, loading: false, initializing: false, error: '', optionsError: '', generation: 0, optionsGeneration: 0 }; },
  computed: {
    canManage() { return this.options.canManage === true; },
    pageSizes() { return this.compact ? [10, 20] : [20, 50, 100]; },
    unknownCallCount() { const value = this.overview || {}; return Math.max(0, Number(value.callCount || 0) - Number(value.successCount || 0) - Number(value.failedCount || 0) - Number(value.cancelledCount || 0)); },
    coverageNotices() { return billingCoverageNotices(this.overview || {}, this.canManage, this.showSupplierCosts); },
    dateRange: { get() { return [this.filters.startDate, this.filters.endDate]; }, set(value) { this.filters.startDate = value && value[0] || ''; this.filters.endDate = value && value[1] || ''; } },
    providerOptions() { return optionItems(this.options.providers || []); },
    serviceOptions() { return optionItems(this.options.serviceTypes || []); },
    workspaceName() { return this.options.workspaceName || '当前工作空间'; },
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
      const state = billingRoute(query, this.canManage);
      this.tab = this.mode === 'costs' ? 'bills' : state.group;
      this.advanced = ['agentId','deviceId','modelId','board','keyword','provider'].some(key => !!this.filters[key]);
      this.page = Math.max(1, Math.floor(Number(query.page) || 1));
      const limits = this.compact ? [10, 20] : [20, 50, 100];
      this.limit = limits.includes(Number(query.limit)) ? Number(query.limit) : this.compact ? 10 : 20;
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
    async loadData() {
      const request = ++this.generation; const workspace = this.workspaceId;
      this.error = validateBillingDates(this.appliedFilters); this.overview = null; this.rows = []; this.total = 0;
      if (this.error || !workspace || workspace !== localStorage.getItem('currentWorkspaceId')) { this.loading = false; return; }
      this.loading = true;
      const query = this.mode === 'costs' ? { startDate: this.appliedFilters.startDate, endDate: this.appliedFilters.endDate, provider: this.appliedFilters.provider, page: this.page, limit: this.limit } : billingQuery(this.appliedFilters, this.page, this.limit);
      const tab = this.tab;
      try {
        const listRequest = tab === 'overview' || this.mode === 'costs' ? Promise.resolve(null) : tab === 'agents' || tab === 'devices'
          ? Api.billing.groups({ ...query, groupBy: tab === 'agents' ? 'agent' : 'device' })
          : tab === 'bills' ? Api.billing.bills(query) : Api.billing.events(query);
        const [overview, records] = await Promise.all([Api.billing.overview(query), listRequest]);
        if (!this.current(request, workspace)) return;
        if (!overview || overview.workspaceId !== workspace) throw new Error('返回数据的工作空间不匹配，请重新加载');
        this.overview = overview;
        if (records) { this.rows = records.list || []; this.total = records.total || 0; }
      } catch (error) { if (this.current(request, workspace)) { this.error = error.message; this.overview = null; this.rows = []; this.total = 0; } }
      finally { if (this.current(request, workspace)) this.loading = false; }
    },
    navigate(filters, tab = this.tab, page = 1, limit = this.limit) {
      const message = validateBillingDates(filters);
      if (message) { this.error = message; return; }
      const query = billingNavigationQuery(this.$route.query, { ...billingQuery(filters, page, limit), ...Object.fromEntries(Object.entries(filters).filter(([, value]) => !value)), view: this.mode === 'costs' ? 'costs' : 'usage', scope: 'workspace', group: ['agents','devices','events'].includes(tab) ? tab : 'agents' }, this.canManage);
      Object.keys(query).forEach(key => { query[key] = String(query[key]); });
      const stable = value => JSON.stringify(Object.keys(value).sort().map(key => [key, value[key]]));
      if (stable(query) === stable(this.$route.query || {})) { this.appliedFilters = { ...filters }; this.loadData(); }
      else this.$router.replace({ path: this.routePath, query }).catch(error => { if (error.name !== 'NavigationDuplicated') this.error = '筛选条件未能更新，请重试'; });
    },
    applyFilters() { this.navigate(this.filters); },
    resetFilters() { this.filters = defaultBillingFilters(); this.navigate(this.filters); },
    changeTab() { this.navigate(this.appliedFilters); },
    changePage(value) { this.navigate(this.appliedFilters, this.tab, value); },
    changeLimit(value) { this.navigate(this.appliedFilters, this.tab, 1, value); },
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
<style src="@/assets/styles/billing.css"></style>
<style scoped>
.filter-box{margin-top:0;margin-bottom:18px;padding-bottom:10px}.filter-box .el-form-item{margin-bottom:12px;margin-right:14px}.filter-box .el-select,.filter-box .el-input{width:180px}.filter-box .el-date-editor--daterange{width:280px;max-width:100%}.advanced-filters{padding-top:12px;border-top:1px solid #edf0f5;margin-top:4px}.workspace-cost{margin:20px 0}.usage-metric .usage-list{margin-top:18px;max-height:240px;overflow:auto}.coverage-alert{margin-bottom:14px}@media(max-width:550px){.filter-box .el-form-item{display:block;margin-right:0}.filter-box ::v-deep .el-form-item__content{max-width:100%}.filter-box .el-select,.filter-box .el-input{max-width:100%}.filter-box .el-date-editor--daterange{width:260px}}
.is-compact .filter-box{border:0;border-radius:0;padding:0;margin:0;background:transparent}.is-compact .filter-box .el-form{display:flex;align-items:flex-start;flex-wrap:wrap;gap:8px 12px}.is-compact .filter-box .el-form-item{margin:0}.is-compact .filter-box .el-select{width:140px}.is-compact .advanced-filters{width:100%;display:flex;flex-wrap:wrap;gap:8px 12px;padding-top:10px}.is-compact .compact-overview{display:grid;gap:7px;padding:8px 0 12px}.compact-counts{display:flex;flex-wrap:wrap;gap:6px 14px;align-items:center;font-size:12px;color:#778399}.compact-counts strong{font-size:18px;color:#24344c;font-weight:600}.compact-counts .failed{color:#b34949}.is-compact .records-panel{padding:0;border:0;border-radius:0;margin:0}.is-compact .billing-panel-heading{margin-bottom:8px}.is-compact .billing-segments button{padding:6px 11px}.is-compact .billing-notice{padding:9px 12px;margin:8px 0}.is-compact .billing-skeleton{margin:10px 0}.is-compact .billing-skeleton i{height:80px}.is-compact .el-pagination{margin-top:10px}
@media(max-width:550px){.is-compact .filter-box .el-form{gap:6px 10px}.is-compact .filter-box .el-form-item{display:inline-block}.is-compact .filter-box ::v-deep .el-form-item__label{padding-right:7px}.is-compact .filter-box .el-form>.el-form-item:first-child{width:100%}.is-compact .filter-box ::v-deep .el-form>.el-form-item:first-child>.el-form-item__content{width:calc(100% - 64px)}.is-compact .filter-box .el-date-editor--daterange{width:100%;min-width:0}.is-compact .filter-box .el-select{width:132px}.is-compact .compact-counts{gap:5px 10px}.is-compact .advanced-filters .el-form-item{max-width:100%}}
</style>
