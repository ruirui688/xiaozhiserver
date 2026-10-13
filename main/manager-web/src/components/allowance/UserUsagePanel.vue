<template>
  <section class="user-usage-panel">
    <el-form class="user-usage-filters" :inline="true" size="small" @submit.native.prevent="applyFilters">
      <el-form-item label="调用日期"><el-date-picker v-model="dateRange" type="daterange" value-format="yyyy-MM-dd" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" /></el-form-item>
      <el-form-item label="服务"><el-select v-model="filters.serviceType" clearable placeholder="全部服务"><el-option v-for="item in serviceOptions" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" :loading="loading" :disabled="initializing || !!optionsError" @click="applyFilters">查询</el-button><el-button @click="resetFilters">重置</el-button><el-button type="text" @click="advanced = !advanced">{{ advanced ? '收起筛选' : '更多筛选' }}</el-button></el-form-item>
      <div v-if="advanced" class="user-usage-advanced">
        <el-form-item label="智能体"><el-select v-model="filters.agentId" clearable filterable placeholder="全部智能体"><el-option v-for="item in options.agents || []" :key="item.id" :label="item.name || '历史智能体'" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="设备"><el-select v-model="filters.deviceId" clearable filterable placeholder="全部设备"><el-option v-for="item in options.devices || []" :key="item.id" :label="item.name || '历史设备'" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="模型"><el-select v-model="filters.modelId" clearable filterable placeholder="全部模型"><el-option v-for="item in options.models || []" :key="item.id" :label="item.name || '历史模型'" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="型号"><el-input v-model="filters.board" clearable placeholder="设备型号" /></el-form-item><el-form-item label="搜索"><el-input v-model="filters.keyword" clearable placeholder="智能体 / 设备名称" /></el-form-item>
      </div>
    </el-form>
    <div v-if="error || optionsError" class="user-usage-error" role="alert"><span>{{ optionsError || error }}</span><el-button type="text" @click="initialize">重试</el-button></div>
    <div class="user-usage-tabs" aria-label="用量分类"><button v-for="item in groups" :key="item.id" :class="{ active: tab === item.id }" :aria-pressed="tab === item.id" @click="changeTab(item.id)">{{ item.label }}</button></div>
    <p v-if="loading || initializing" class="user-usage-loading" role="status">正在读取使用记录…</p>
    <template v-else-if="overview && !error && !optionsError">
      <p v-if="empty" class="user-usage-empty">暂无使用记录</p>
      <template v-else>
        <div class="user-usage-counts"><span><strong>{{ number(overview.callCount) }}</strong> 条记录</span><span>{{ number(overview.sessionCount) }} 个会话</span><span v-if="positive(overview.failedCount)" class="call-failed">{{ number(overview.failedCount) }} 条失败</span><span v-if="positive(overview.cancelledCount)">{{ number(overview.cancelledCount) }} 条取消</span></div>
        <p v-if="positive(overview.usageUnknownCount)" class="user-usage-incomplete">{{ number(overview.usageUnknownCount) }} 条记录的用量尚未完整，已确认的用量照常展示。</p>
        <UserUsageRecords :kind="tab" :rows="rows" @drill="drill" />
        <el-pagination :current-page="page" :page-size="limit" :page-sizes="[10,20]" :pager-count="5" :small="true" :total="total" layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changeLimit" />
      </template>
    </template>
  </section>
</template>
<script>
import UserUsageRecords from './UserUsageRecords.vue';
import { userPlatform } from '@/apis/platform';
import { decimalText, optionItems, validateBillingDates } from '@/utils/billing';
import { USER_USAGE_GROUPS, defaultUserUsageFilters, userUsageFilters, userUsageQuery, userUsageRoute, userUsageCount, userUsagePage } from '@/utils/userUsage';

export default {
  components: { UserUsageRecords }, props: { workspaceId: { type: String, required: true } },
  data() { return { filters: defaultUserUsageFilters(), appliedFilters: defaultUserUsageFilters(), advanced: false, options: {}, overview: null, rows: [], total: 0, tab: 'agents', page: 1, limit: 10, loading: false, initializing: false, error: '', optionsError: '', generation: 0, optionsGeneration: 0, groups: [{ id: 'agents', label: '按智能体' }, { id: 'devices', label: '按设备' }, { id: 'events', label: '调用记录' }] }; },
  computed: {
    serviceOptions() { return optionItems(this.options.serviceTypes || []); },
    empty() { return userUsageCount(this.overview?.callCount) === 0 && this.rows.length === 0; },
    dateRange: { get() { return [this.filters.startDate, this.filters.endDate]; }, set(value) { this.filters.startDate = value?.[0] || ''; this.filters.endDate = value?.[1] || ''; } }
  },
  created() { this.readRoute(); this.initialize(); },
  beforeDestroy() { this.generation++; this.optionsGeneration++; },
  watch: {
    workspaceId() { this.initialize(); },
    '$route.query': { deep: true, handler() { this.readRoute(); if (!this.initializing && !this.optionsError) this.loadData(); } }
  },
  methods: {
    number: decimalText,
    positive(value) { return (userUsageCount(value) || 0) > 0; },
    readRoute() {
      const query = this.$route.query || {};
      this.filters = userUsageFilters(query); this.appliedFilters = { ...this.filters };
      this.tab = USER_USAGE_GROUPS.includes(query.group) ? query.group : USER_USAGE_GROUPS.includes(query.tab) ? query.tab : 'agents';
      this.page = Math.max(1, userUsageCount(query.page) || 1); this.limit = [10,20].includes(Number(query.limit)) ? Number(query.limit) : 10;
      this.advanced = ['agentId','deviceId','modelId','board','keyword'].some(key => !!this.filters[key]);
    },
    isWorkspaceCurrent(workspace) { return workspace === this.workspaceId && workspace === localStorage.getItem('currentWorkspaceId'); },
    current(request, workspace) { return request === this.generation && this.isWorkspaceCurrent(workspace); },
    async initialize() {
      const request = ++this.optionsGeneration; const workspace = this.workspaceId;
      this.generation++; this.initializing = true; this.loading = false; this.error = ''; this.optionsError = ''; this.options = {}; this.overview = null; this.rows = []; this.total = 0;
      try {
        const options = await userPlatform.usageOptions({ workspaceId: workspace });
        if (request !== this.optionsGeneration || !this.isWorkspaceCurrent(workspace)) return;
        if (options?.workspaceId !== workspace) throw new Error('工作空间已变化，请重新读取');
        this.options = options; this.readRoute(); this.initializing = false; await this.loadData();
      } catch (error) { if (request === this.optionsGeneration && this.isWorkspaceCurrent(workspace)) this.optionsError = error.message || '筛选条件读取失败，请重试'; }
      finally { if (request === this.optionsGeneration) this.initializing = false; }
    },
    async loadData() {
      const request = ++this.generation; const workspace = this.workspaceId;
      this.error = validateBillingDates(this.appliedFilters); this.overview = null; this.rows = []; this.total = 0;
      if (this.error || !this.isWorkspaceCurrent(workspace)) { this.loading = false; return; }
      this.loading = true;
      const query = { ...userUsageQuery(this.appliedFilters, this.page, this.limit), workspaceId: workspace };
      try {
        const recordsRequest = this.tab === 'events' ? userPlatform.usageEvents(query) : userPlatform.usageGroups({ ...query, groupBy: this.tab === 'devices' ? 'device' : 'agent' });
        const [overview, records] = await Promise.all([userPlatform.usageOverview(query), recordsRequest]);
        if (!this.current(request, workspace)) return;
        if (overview?.workspaceId !== workspace || (records?.workspaceId && records.workspaceId !== workspace)) throw new Error('返回记录的工作空间不匹配，请重新读取');
        if (userUsageCount(overview.callCount) === null) throw new Error('使用记录暂时无法读取，请重试');
        const result = userUsagePage(records); this.overview = overview; this.rows = result.list; this.total = result.total;
      } catch (error) { if (this.current(request, workspace)) { this.error = error.message || '使用记录读取失败，请重试'; this.overview = null; this.rows = []; this.total = 0; } }
      finally { if (request === this.generation) this.loading = false; }
    },
    navigate(filters, tab = this.tab, page = 1, limit = this.limit) {
      const message = validateBillingDates(filters); if (message) { this.error = message; return; }
      const query = userUsageRoute(filters, tab, page, limit);
      const stable = value => JSON.stringify(Object.keys(value).sort().map(key => [key,value[key]]));
      if (stable(query) === stable(this.$route.query || {})) { this.appliedFilters = { ...filters }; this.loadData(); }
      else this.$router.replace({ path: '/allowance', query }).catch(error => { if (error.name !== 'NavigationDuplicated') this.error = '筛选条件更新失败，请重试'; });
    },
    applyFilters() { this.navigate(this.filters); },
    resetFilters() { this.filters = defaultUserUsageFilters(); this.navigate(this.filters); },
    changeTab(tab) { if (USER_USAGE_GROUPS.includes(tab)) this.navigate(this.appliedFilters, tab); },
    changePage(page) { this.navigate(this.appliedFilters, this.tab, page); },
    changeLimit(limit) { this.navigate(this.appliedFilters, this.tab, 1, limit); },
    drill(row) { if (!row.id) return; this.navigate({ ...this.appliedFilters, [this.tab === 'devices' ? 'deviceId' : 'agentId']: row.id }, 'events'); }
  }
};
</script>
<style scoped>
.user-usage-panel{min-width:0}.user-usage-filters{display:flex;flex-wrap:wrap;align-items:flex-start;gap:8px 12px}.user-usage-filters .el-form-item{margin:0}.user-usage-filters .el-select,.user-usage-filters .el-input{width:145px}.user-usage-filters .el-date-editor--daterange{width:280px;max-width:100%}.user-usage-advanced{display:flex;flex-wrap:wrap;gap:8px 12px;width:100%;padding-top:10px}.user-usage-tabs{display:flex;gap:5px;margin:14px 0 10px}.user-usage-tabs button{border:1px solid transparent;border-radius:6px;padding:6px 11px;background:transparent;color:#718096;cursor:pointer;font:inherit;font-size:12px}.user-usage-tabs button.active{color:#365fbd;background:#edf2fb;border-color:#dce5f4}.user-usage-counts{display:flex;flex-wrap:wrap;gap:8px 16px;font-size:12px;color:#7b899c;margin:8px 0}.user-usage-counts strong{font-size:17px;color:#34465d}.call-failed{color:#ad6262}.user-usage-empty,.user-usage-loading{padding:32px 15px;text-align:center;background:#fff;border:1px solid #e7ecf3;border-radius:10px;color:#8491a2;font-size:13px}.user-usage-error{display:flex;align-items:center;justify-content:space-between;gap:12px;background:#fff5f5;border:1px solid #f2dcdc;color:#aa5656;padding:10px 14px;border-radius:8px;font-size:13px;margin:12px 0}.user-usage-error .el-button{padding:3px;flex-shrink:0}.user-usage-incomplete{font-size:12px;color:#98763e;margin:8px 0 12px;line-height:1.7}.user-usage-panel>.el-pagination{margin-top:12px;overflow-x:auto;text-align:right}
@media(max-width:600px){.user-usage-filters{gap:8px}.user-usage-filters>.el-form-item:first-child{width:100%}.user-usage-filters ::v-deep .el-form-item__label{padding-right:7px}.user-usage-filters ::v-deep .el-form-item:first-child .el-form-item__content{max-width:calc(100% - 63px)}.user-usage-filters .el-date-editor--daterange{width:100%;min-width:0}.user-usage-filters .el-select,.user-usage-filters .el-input{width:132px;max-width:100%}.user-usage-advanced .el-form-item{max-width:100%}}
</style>
