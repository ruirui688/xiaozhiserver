<template>
  <div class="billing-page">
    <HeaderBar />
    <main class="billing-main">
      <header class="billing-heading">
        <div><div class="billing-eyebrow">MODEL INSIGHTS</div><h1>用量与费用</h1><p>费用有据可查，用量清晰可见。</p></div>
        <el-button v-if="canManage" icon="el-icon-setting" @click="adminVisible = true">数据与归属管理</el-button>
      </header>
      <div v-if="initializing" class="billing-skeleton" aria-label="正在加载计费工作台"><i /><i /><i /></div>
      <section v-else-if="optionsError" class="billing-empty"><i class="el-icon-warning-outline" /><h2>计费工作台暂时无法加载</h2><p>{{ optionsError }}</p><el-button @click="initialize">重新加载</el-button></section>
      <template v-else>
        <section class="billing-scope-bar">
          <div class="billing-scope-controls">
            <div v-if="canManage" class="billing-segments" aria-label="查看范围">
              <button :class="{ active: scope === 'account' }" :aria-pressed="scope === 'account'" @click="changeScope('account')"><i class="el-icon-office-building" />供应商账号</button>
              <button :class="{ active: scope === 'workspace' }" :aria-pressed="scope === 'workspace'" @click="changeScope('workspace')"><i class="el-icon-house" />工作空间</button>
            </div>
            <el-select v-if="scope === 'account'" :value="selectedAccountKey" size="small" placeholder="全部供应商账号" :loading="accountsLoading" @change="chooseAccount"><el-option label="全部供应商账号" value="" /><el-option v-for="account in accounts" :key="key(account)" :value="key(account)" :label="account.label || label(account.provider)" /></el-select>
            <span v-else class="billing-workspace-name"><i class="el-icon-folder-opened" />{{ workspaceName }}</span>
          </div>
          <span class="billing-scope-caption">{{ scope === 'account' ? '平台账号范围 · 不受顶部工作空间切换影响' : '仅当前工作空间 · 可在顶部切换空间' }}</span>
        </section>
        <nav class="billing-main-nav" aria-label="计费功能"><button v-for="item in views" :key="item.id" :aria-current="view === item.id ? 'page' : null" :class="{ active: view === item.id }" @click="changeView(item.id)"><i :class="item.icon" /><span>{{ item.label }}</span></button></nav>
        <div v-if="(accountsError || invalidAccount) && scope === 'account'" class="billing-inline-error"><span>{{ accountsError || '所选供应商账号不可用，请重新选择账号。' }}</span><el-button type="text" @click="loadAccounts">重试</el-button></div>
        <template v-else-if="scope === 'workspace' || !accountsLoading">
          <BillingAccountOverview v-if="view === 'costs' && scope === 'account'" :key="`accounts-${revision}`" :accounts="visibleAccounts" @manage="adminVisible = true" />
          <BillingWorkspacePanel v-else-if="view !== 'benefits'" :key="`${view}-${workspaceId}-${revision}`" :mode="view" @manage="adminVisible = true" />
          <BillingBenefits v-else :key="`benefits-${scope}-${selectedAccountKey}-${revision}`" :workspace-id="workspaceId" :can-manage="canManage" :scope="scope" :account="selectedAccount" />
        </template>
        <div v-else class="billing-skeleton" aria-label="正在读取供应商账号"><i /><i /></div>
        <footer class="billing-footer"><i class="el-icon-lock" /> 官方费用、账户资金和服务额度分别展示 · 时间均为北京时间</footer>
      </template>
      <BillingAdminDialog :visible="adminVisible" :options="options" :workspace-id="workspaceId" @close="adminVisible = false" @updated="revision++" />
      <el-dialog title="查看工作空间用量" :visible.sync="workspacePrompt" width="min(460px, 92vw)"><p class="billing-dialog-copy">用量按调用时的工作空间归属记录。查看「{{ workspaceName }}」的智能体和设备用量。</p><span slot="footer"><el-button @click="workspacePrompt = false">取消</el-button><el-button type="primary" @click="enterUsage">查看工作空间用量</el-button></span></el-dialog>
    </main>
  </div>
</template>
<script>
import Api from '@/apis/api';
import HeaderBar from '@/components/HeaderBar.vue';
import BillingAdminDialog from '@/components/billing/BillingAdminDialog.vue';
import BillingAccountOverview from '@/components/billing/BillingAccountOverview.vue';
import BillingWorkspacePanel from '@/components/billing/BillingWorkspacePanel.vue';
import BillingBenefits from '@/components/billing/BillingBenefits.vue';
import { billingLabel } from '@/utils/billing';
import { billingRoute, billingNavigationQuery, accountKey } from '@/utils/billingNavigation';
import '@/assets/styles/billing.css';

export default {
  components: { HeaderBar, BillingAdminDialog, BillingAccountOverview, BillingWorkspacePanel, BillingBenefits },
  data() { return { options: {}, workspaceId: '', initializing: true, optionsError: '', accounts: [], accountsLoading: false, accountsError: '', generation: 0, accountGeneration: 0, adminVisible: false, workspacePrompt: false, revision: 0, views: [{ id: 'costs', label: '费用总览', icon: 'el-icon-pie-chart' }, { id: 'benefits', label: '余额与资源包', icon: 'el-icon-wallet' }, { id: 'usage', label: '用量分析', icon: 'el-icon-data-analysis' }] }; },
  computed: {
    canManage() { return this.options.canManage === true; },
    routeState() { return billingRoute(this.$route.query, this.canManage); },
    view() { return this.routeState.view; }, scope() { return this.routeState.scope; },
    workspaceName() { return this.options.workspaceName || '当前工作空间'; },
    selectedAccount() { const q = this.$route.query; return this.accounts.find(item => item.provider === q.accountProvider && item.accountRef === q.accountRef) || null; },
    selectedAccountKey() { return this.selectedAccount ? accountKey(this.selectedAccount) : ''; },
    invalidAccount() { return !this.accountsLoading && !!this.$route.query.accountRef && !this.selectedAccount; },
    visibleAccounts() { return this.selectedAccount ? [this.selectedAccount] : this.accounts; }
  },
  created() { this.initialize(); }, beforeDestroy() { this.generation++; this.accountGeneration++; },
  methods: {
    label: billingLabel, key: accountKey,
    async initialize() {
      const request = ++this.generation; const workspace = localStorage.getItem('currentWorkspaceId') || '';
      this.initializing = true; this.optionsError = ''; this.options = {}; this.accounts = []; this.adminVisible = false;
      try {
        const options = await Api.billing.options();
        if (request !== this.generation || (workspace && workspace !== localStorage.getItem('currentWorkspaceId'))) return;
        if (!options || !options.workspaceId || (localStorage.getItem('currentWorkspaceId') && options.workspaceId !== localStorage.getItem('currentWorkspaceId'))) throw new Error('工作空间已变化，请重新加载');
        this.options = options; this.workspaceId = options.workspaceId;
        if (!workspace) localStorage.setItem('currentWorkspaceId', options.workspaceId);
        if (options.canManage === true) await this.loadAccounts();
      } catch (error) { if (request === this.generation) this.optionsError = error.message; }
      finally { if (request === this.generation) this.initializing = false; }
    },
    async loadAccounts() {
      if (!this.canManage) return;
      const request = ++this.accountGeneration; this.accountsLoading = true; this.accountsError = '';
      try { const data = await Api.billing.accounts(); if (request === this.accountGeneration && this.canManage) this.accounts = data.list || []; }
      catch (error) { if (request === this.accountGeneration) { this.accounts = []; this.accountsError = error.message; } }
      finally { if (request === this.accountGeneration) this.accountsLoading = false; }
    },
    navigate(patch) { const query = billingNavigationQuery(this.$route.query, patch, this.canManage); this.$router.push({ path: '/model-billing', query }).catch(error => { if (error.name !== 'NavigationDuplicated') this.$message.error('页面切换失败，请重试'); }); },
    changeView(view) { if (view === 'usage' && this.scope === 'account') { this.workspacePrompt = true; return; } this.navigate({ view, scope: this.scope, page: 1 }); },
    changeScope(scope) { this.navigate({ scope, view: scope === 'account' && this.view === 'usage' ? 'costs' : this.view, page: 1 }); },
    enterUsage() { this.workspacePrompt = false; this.navigate({ view: 'usage', scope: 'workspace', page: 1 }); },
    chooseAccount(value) { const account = this.accounts.find(item => accountKey(item) === value); this.navigate({ accountProvider: account && account.provider, accountRef: account && account.accountRef, product: '', page: 1 }); }
  }
};
</script>
