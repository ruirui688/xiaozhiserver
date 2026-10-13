<template>
  <section>
    <div class="billing-toolbar"><span>官方账期</span><el-date-picker v-model="period" type="month" value-format="yyyy-MM" :clearable="false" size="small" @change="changePeriod" /><span class="billing-muted">按账期完整查看，不按调用天数分摊</span><el-button size="small" icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button></div>
    <div v-if="error" class="billing-inline-error">{{ error }}</div>
    <div v-if="loading" class="billing-skeleton"><i /><i /></div>
    <div v-else-if="!accounts.length" class="billing-empty"><i class="el-icon-wallet" /><h2>尚未接入供应商账号</h2><p>配置只读账单来源，或导入供应商官方账单后，即可在这里查看账号费用。内部用量不受账单接入影响。</p><el-button @click="$emit('manage')">查看数据接入</el-button></div>
    <template v-else>
      <div class="billing-metrics">
        <article v-for="item in reports" :key="key(item.account)" class="billing-metric" :class="{ active: focusedKey === key(item.account) }">
          <div class="account-heading"><span class="provider-icon"><i class="el-icon-office-building" /></span><div><h3>{{ label(item.account.provider) }}</h3><p>{{ item.account.label || item.account.accountRef }}</p></div><el-tag v-if="item.data" size="mini" :type="coverageType(item.data)">{{ state(item).coverageLabel }}</el-tag></div>
          <template v-if="item.error"><div class="account-unavailable">暂时无法读取</div><p>{{ item.error }}</p><el-button type="text" @click="load">重新读取</el-button></template>
          <template v-else-if="item.data">
            <div v-if="!item.data.costs || !item.data.costs.length" class="account-unavailable">{{ state(item).emptyText }}</div>
            <div v-for="cost in item.data.costs || []" :key="cost.currency" class="account-money"><span class="billing-muted">官方已付金额</span><div class="amount">{{ money(cost.paidAmount, cost.currency) }}</div><p v-if="cost.paymentCurrencyVerified === false" class="account-warning">支付币种待核实，已付与未付暂不展示。</p><p>官方应付 {{ money(cost.payableAmount, cost.currency) }}<span class="money-separator">·</span>未付 {{ money(cost.unpaidAmount, cost.currency) }}</p></div>
            <p v-if="state(item).sourceTime" class="account-sync"><i class="el-icon-time" /> {{ state(item).sourceTime }}</p>
            <p v-if="state(item).imported" class="account-warning">人工导入的官方金额；凭证来源与账期完整性尚未自动核验。</p>
            <p v-else-if="item.data.sync && item.data.sync.lastAttemptStatus === 'LEGACY_SNAPSHOT'" class="account-warning">已有历史账单，账号同步完整性尚待核实。</p>
            <p v-if="!state(item).importOnly && item.data.sync && item.data.sync.lastAttemptStatus === 'FAILED'" class="account-warning">最近同步失败，当前展示此前成功快照。</p>
            <p v-else-if="item.data.sync && item.data.sync.isStale" class="account-warning">快照较早，建议更新账单。</p>
            <div class="account-bottom"><span>{{ number(item.data.billCount) }} 条主账单</span><el-button v-if="item.data.billCount > 0" type="text" @click="focusAccount(item.account)">查看产品与账单 <i class="el-icon-arrow-right" /></el-button></div>
          </template>
          <p v-if="state(item).importOnly" class="account-source-note">此账号暂不支持自动获取消费账单。实际费用需导入含官方金额的凭证，余额和 Token 用量不能代替消费金额。</p>
          <div v-if="state(item).importOnly || item.account.provider === 'DEEPSEEK'" class="account-actions">
            <el-button v-if="state(item).capabilities.balanceSupported" size="small" @click="$emit('benefits', item.account)">查看余额</el-button>
            <el-button v-if="canImport && state(item).capabilities.statementImportSupported" size="small" @click="openImport(item.account)">导入官方凭证</el-button>
            <el-button v-if="showWorkspaceUsage" size="small" @click="$emit('usage', item.account)">本工作空间用量</el-button>
            <a v-if="item.account.provider === 'DEEPSEEK'" href="https://platform.deepseek.com/usage" target="_blank" rel="noopener noreferrer">官方用量控制台 <i class="el-icon-top-right" /></a>
          </div>
        </article>
      </div>
      <p class="account-footnote">已付金额指该账期账单截至快照的官方已付；不等于本月资金流水。各账号独立展示，未取得的账单不计为零。</p>
      <section v-if="focused && focused.data" class="billing-panel" ref="productPanel">
        <div class="billing-panel-heading"><div><h2>{{ label(focused.account.provider) }} · 产品费用</h2><p>采用官方主账单金额；展开核对明细，金额不重复累加。</p></div><el-button size="small" @click="openBills('')">查看全部账单</el-button></div>
        <div v-if="focused.data.reconciliation && focused.data.reconciliation.status === 'REVIEW_REQUIRED'" class="billing-notice"><i class="el-icon-warning-outline" /><div>部分账单需要核对<p>主单与明细存在数量、金额或币种差异。当前保留官方主单金额，不用明细覆盖。</p></div></div>
        <div v-if="focused.data.coverage && focused.data.coverage.status === 'PARTIAL'" class="billing-notice"><i class="el-icon-warning-outline" /><div>账单覆盖尚待核对<p>当前保留已取得的官方主单金额。账期完整性、快照时效或主明细关联仍需核实，不能将此金额视为完整月结总额。</p><p v-if="focused.data.coverage.orphanDetailCount">{{ number(focused.data.coverage.orphanDetailCount) }} 条明细缺少对应主单，未重复加进主单金额。</p></div></div>
        <div v-if="!(focused.data.products || []).length" class="product-empty">尚无产品账单。余额与资源包可在独立页面查看，不能代替实际费用。</div>
        <div v-for="(product, index) in focused.data.products || []" :key="`${product.product}-${index}`" class="product-row"><div><strong>{{ product.product || '供应商未提供产品名称' }}</strong><p>{{ label(product.serviceType) }} · {{ number(product.billCount) }} 条主账单</p></div><div class="product-amount"><strong v-for="cost in product.costs || []" :key="cost.currency">{{ money(cost.paidAmount, cost.currency) }}</strong><span v-if="!(product.costs || []).length">未提供金额</span><small>官方已付</small></div><el-button type="text" @click="openBills(product.product)">核对账单 <i class="el-icon-arrow-right" /></el-button></div>
      </section>
      <section v-if="billQuery" class="billing-panel" ref="billPanel"><div class="billing-panel-heading"><div><h2>官方账单{{ billQuery.product ? ' · ' + billQuery.product : '' }}</h2><p>{{ label(billQuery.provider) }} · {{ period }} · 仅展示主单</p></div><el-button type="text" @click="billQuery = null">收起账单</el-button></div><BillingBillList :query="billQuery" account-view /></section>
    </template>
  </section>
</template>
<script>
import Api from '@/apis/api';
import BillingBillList from './BillingBillList.vue';
import { billingLabel, billingTime, amountText, decimalText, chinaDate } from '@/utils/billing';
import { accountKey, accountQuery } from '@/utils/billingNavigation';
import { billingAccountState } from '@/utils/billingAccounts';
export default {
  components: { BillingBillList }, props: { accounts: { type: Array, default: () => [] }, showWorkspaceUsage: { type: Boolean, default: true }, canImport: { type: Boolean, default: true } },
  data() { return { period: chinaDate().slice(0, 7), reports: [], focusedKey: '', billQuery: null, loading: false, error: '', generation: 0 }; },
  computed: { focused() { return this.reports.find(item => accountKey(item.account) === this.focusedKey) || this.reports.find(item => item.data); } },
  watch: { accounts: { handler() { this.load(); }, deep: true }, '$route.query.billPeriod'() { this.readPeriod(); this.load(); } },
  created() { this.readPeriod(); this.load(); }, beforeDestroy() { this.generation++; },
  methods: {
    key: accountKey, label: billingLabel, time: billingTime, money: amountText, number: decimalText,
    readPeriod() { const period = this.$route.query.billPeriod; this.period = typeof period === 'string' ? period : chinaDate().slice(0, 7); },
    changePeriod() { this.billQuery = null; this.$router.replace({ path: '/model-billing', query: { ...this.$route.query, view: 'costs', scope: 'account', billPeriod: this.period } }).catch(error => { if (error.name !== 'NavigationDuplicated') this.error = '账期切换失败，请重试'; }); },
    state(item) { return billingAccountState(item.account, item.data); },
    openImport(account) { this.$emit('manage', { provider: account.provider, accountRef: account.accountRef, billPeriod: this.period }); },
    focusAccount(account) { this.focusedKey = accountKey(account); this.billQuery = null; this.$nextTick(() => { this.$refs.productPanel?.scrollIntoView({ block: 'start', behavior: 'smooth' }); }); },
    coverageType(data) { return ['SNAPSHOT', 'EMPTY_CONFIRMED'].includes((data.coverage || {}).status) ? 'info' : 'warning'; },
    async load() {
      const generation = ++this.generation; this.loading = true; this.error = ''; this.reports = []; this.billQuery = null;
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(this.period || '')) { this.error = '请选择有效账期'; this.loading = false; return; }
      const period = this.period;
      const reports = await Promise.all(this.accounts.map(async account => {
        try {
          const data = await Api.billing.accountOverview(accountQuery(account, period));
          if (!data || data.scope !== 'ACCOUNT' || data.provider !== account.provider || data.accountRef !== account.accountRef || data.billPeriod !== period) throw new Error('账单账号或账期不匹配，请重新读取');
          return { account, data: { ...data, coverage: data.coverage || { status: 'NOT_OBTAINED' } } };
        } catch (error) { return { account, error: error.message }; }
      }));
      if (generation !== this.generation) return;
      this.reports = reports; this.loading = false;
      if (!reports.some(item => accountKey(item.account) === this.focusedKey)) this.focusedKey = reports.length ? accountKey(reports[0].account) : '';
    },
    openBills(product) { if (!this.focused) return; this.billQuery = accountQuery(this.focused.account, this.period, product ? { product } : {}); this.$nextTick(() => { if (this.$refs.billPanel) this.$refs.billPanel.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }); }
  }
};
</script>
<style scoped>
.account-source-note{margin-top:15px!important;color:#8996a9;font-size:12px;line-height:1.9}.account-actions{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:14px}.account-actions .el-button+.el-button{margin-left:0}.account-actions a{color:#5776d6;font-size:12px;line-height:2;padding:4px 0}
.account-heading{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.account-heading>div{flex:1;min-width:110px}.provider-icon{width:38px;height:38px;border-radius:10px;background:#f0f4ff;display:grid;place-items:center;color:#5776d6;font-size:19px}.account-heading p{font-size:11px;overflow-wrap:anywhere;margin-top:3px}.account-money{margin-top:22px}.account-money .amount{margin:4px 0 2px}.money-separator{margin:0 10px;color:#c2cada}.account-sync{margin-top:20px!important}.account-sync i{margin-right:4px}.account-bottom{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:13px;padding-top:13px;border-top:1px solid #edf0f5}.account-bottom>span{font-size:11px;color:#8d99aa}.account-warning{color:#a97a30!important;margin-top:6px!important}.account-unavailable{font-size:20px;margin:26px 0;color:#8995a8}.account-footnote{font-size:11px;color:#8f9aab;line-height:1.9;margin:14px 3px 0!important}.product-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(130px,auto) auto;align-items:center;gap:24px;padding:19px 0;border-top:1px solid #edf0f5}.product-row strong{font-size:14px;font-weight:550;overflow-wrap:anywhere}.product-row p,.product-amount small{font-size:12px;color:#8996a9;line-height:1.8;margin-top:4px}.product-amount{text-align:right}.product-amount strong,.product-amount small{display:block}.product-empty{font-size:13px;padding:25px 0;color:#8996a9;line-height:1.9}@media(max-width:550px){.product-row{grid-template-columns:minmax(0,1fr) auto;gap:8px 14px}.product-row>.el-button{grid-column:1/3;justify-self:start}.product-amount{max-width:145px}.product-amount strong{font-size:13px}}
</style>
