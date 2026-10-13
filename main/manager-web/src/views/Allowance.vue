<template>
  <div class="allowance-page">
    <HeaderBar />
    <main class="allowance-main">
      <header class="allowance-heading">
        <h1>额度与用量</h1>
        <div class="allowance-actions">
          <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="refresh">刷新</el-button>
        </div>
      </header>
      <div v-if="error" class="allowance-error" role="alert"><span>{{ error }}</span><el-button type="text" @click="load">重试</el-button></div>
      <div v-if="loading" class="allowance-loading">正在读取服务额度…</div>
      <template v-else-if="summary">
        <section class="allowance-summary" aria-label="账户额度摘要">
          <div class="allowance-context">
            <span class="allowance-payer">{{ payerLabel }}</span>
            <el-tag class="allowance-service-status" size="mini" :type="serviceState.type">{{ serviceState.label }}</el-tag>
            <el-button class="allowance-limit-action" type="text" size="small" icon="el-icon-setting" @click="editLimits">空间上限</el-button>
          </div>
          <div v-if="balance" class="allowance-totals">
            <div class="allowance-available"><span>账户可用额度</span><strong><el-tooltip :content="exactAmount(balance.available)" :disabled="amount(balance.available) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :class="{ 'allowance-money-range': amount(balance.available).includes('小于') }" :disabled="amount(balance.available) === '—'" aria-label="查看账户可用额度的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(balance.available) }}<small>元</small></button></el-tooltip></strong></div>
            <div><span>账户预占</span><strong><el-tooltip :content="exactAmount(balance.reserved)" :disabled="amount(balance.reserved) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :class="{ 'allowance-money-range': amount(balance.reserved).includes('小于') }" :disabled="amount(balance.reserved) === '—'" aria-label="查看账户预占的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(balance.reserved) }}<small>元</small></button></el-tooltip></strong></div>
            <div><span>账户累计已用</span><strong><el-tooltip :content="exactAmount(balance.spent)" :disabled="amount(balance.spent) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :class="{ 'allowance-money-range': amount(balance.spent).includes('小于') }" :disabled="amount(balance.spent) === '—'" aria-label="查看账户累计已用的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(balance.spent) }}<small>元</small></button></el-tooltip></strong></div>
          </div>
          <p class="allowance-summary-note">{{ balance ? '统计本人账户下的全部工作空间 · 额度由平台统一发放' : '使用负责人的服务额度；账户余额仅本人可见。' }}</p>
          <p v-if="serviceState.description" class="allowance-service-note">{{ serviceState.description }}</p>
        </section>
        <el-tabs v-model="tab" class="allowance-tabs">
          <el-tab-pane label="用量明细" name="usage" lazy>
            <UserUsagePanel v-if="tab === 'usage'" :key="summary.workspaceId + ':' + usageRevision" :workspace-id="summary.workspaceId" />
          </el-tab-pane>
          <el-tab-pane label="我的额度流水" name="ledger" lazy>
            <section v-if="tab === 'ledger'" class="allowance-panel">
              <p class="allowance-panel-note">本人账户的发放与扣减记录，与供应商采购费用分别核算。</p>
              <p v-if="trial && trial.enabled && trial.granted" class="allowance-trial"><i class="el-icon-present" />已按「{{ trial.grantedPolicy }}」发放注册试用额度，每个账号仅一次。</p>
              <div v-if="ledgerError" class="allowance-error" role="alert"><span>{{ ledgerError }}</span><el-button type="text" @click="loadLedger">重试</el-button></div>
              <template v-else>
                <el-table :data="ledger" v-loading="ledgerLoading" size="small" empty-text="暂无额度流水">
                  <el-table-column prop="createdAt" label="时间" min-width="160" />
                  <el-table-column prop="type" label="类型" width="110"><template slot-scope="s">{{ ledgerLabel(s.row.type) }}</template></el-table-column>
                  <el-table-column prop="amount" label="变动（元）" min-width="120"><template slot-scope="s"><el-tooltip :content="exactAmount(s.row.amount)" :disabled="amount(s.row.amount) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :disabled="amount(s.row.amount) === '—'" aria-label="查看流水变动的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(s.row.amount) }}</button></el-tooltip></template></el-table-column>
                  <el-table-column prop="balanceAfter" label="变动后余额（元）" min-width="150"><template slot-scope="s"><el-tooltip :content="exactAmount(s.row.balanceAfter)" :disabled="amount(s.row.balanceAfter) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :disabled="amount(s.row.balanceAfter) === '—'" aria-label="查看变动后余额的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(s.row.balanceAfter) }}</button></el-tooltip></template></el-table-column>
                  <el-table-column prop="reason" label="说明" min-width="180" />
                </el-table>
                <el-pagination :current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="changePage" />
              </template>
            </section>
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-dialog title="工作空间使用上限" :visible.sync="limitDialog" width="min(480px,92vw)">
        <div v-if="limitLoading" class="allowance-dialog-note" role="status">正在读取工作空间上限…</div>
        <div v-else-if="limitError" class="allowance-error" role="alert"><span>{{ limitError }}</span><el-button type="text" @click="loadLimits">重试</el-button></div>
        <template v-else-if="limits">
          <p class="allowance-dialog-note">仅限制当前工作空间的使用，不另行发放额度。负责人账户额度不足时，也会暂停新的付费调用。</p>
          <dl class="allowance-limits">
            <dt>总使用上限</dt><dd><span v-if="limits.totalLimit == null">未单设上限</span><template v-else><el-tooltip :content="exactAmount(limits.totalLimit)" :disabled="amount(limits.totalLimit) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :disabled="amount(limits.totalLimit) === '—'" aria-label="查看总使用上限的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(limits.totalLimit) }}<small>元</small></button></el-tooltip></template></dd>
            <dt>今日使用 / 日上限</dt><dd><el-tooltip :content="exactAmount(limits.dailySpent)" :disabled="amount(limits.dailySpent) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :disabled="amount(limits.dailySpent) === '—'" aria-label="查看今日使用的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(limits.dailySpent) }}<small>元</small></button></el-tooltip> / <span v-if="limits.dailyLimit == null">未单设上限</span><template v-else><el-tooltip :content="exactAmount(limits.dailyLimit)" :disabled="amount(limits.dailyLimit) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :disabled="amount(limits.dailyLimit) === '—'" aria-label="查看每日上限的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(limits.dailyLimit) }}<small>元</small></button></el-tooltip></template></dd>
            <dt>累计使用 / 预占</dt><dd><el-tooltip :content="exactAmount(limits.totalSpent)" :disabled="amount(limits.totalSpent) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :disabled="amount(limits.totalSpent) === '—'" aria-label="查看累计使用的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(limits.totalSpent) }}<small>元</small></button></el-tooltip> / <el-tooltip :content="exactAmount(limits.totalReserved)" :disabled="amount(limits.totalReserved) === '—'" popper-class="allowance-amount-tooltip" placement="top"><button type="button" class="allowance-money" :disabled="amount(limits.totalReserved) === '—'" aria-label="查看空间预占的精确金额" @click="$event.currentTarget.focus()" @keydown.esc.stop="$event.currentTarget.blur()">{{ amount(limits.totalReserved) }}<small>元</small></button></el-tooltip></dd>
          </dl>
          <el-form v-if="limits.canManage === true" label-width="110px" size="small">
            <el-form-item label="工作空间总上限"><el-input v-model="limitForm.totalLimit" placeholder="元，留空不单设上限" /></el-form-item>
            <el-form-item label="每日上限"><el-input v-model="limitForm.dailyLimit" placeholder="元，留空不单设上限" /></el-form-item>
            <p class="allowance-dialog-note">留空不单设上限；填 0 暂停新的工作空间付费调用。</p>
          </el-form>
        </template>
        <span slot="footer"><el-button size="small" @click="limitDialog = false">关闭</el-button><el-button v-if="limits && limits.canManage === true && !limitLoading && !limitError" type="primary" size="small" :loading="saving" @click="saveLimits">保存上限</el-button></span>
      </el-dialog>
    </main>
  </div>
</template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import UserUsagePanel from '@/components/allowance/UserUsagePanel.vue';
import { userPlatform, platformRequest } from '@/apis/platform';
import { allowanceAmount, allowanceDisplayAmount, allowancePayerLabel, visibleAllowance, allowanceServiceState } from '@/utils/allowance';
import { userUsagePage } from '@/utils/userUsage';
export default {
  components: { HeaderBar, UserUsagePanel },
  data() {
    return {
      summary: null, loading: false, error: '', tab: 'usage', generation: 0, usageRevision: 0,
      ledger: [], ledgerLoading: false, ledgerError: '', page: 1, total: 0, ledgerGeneration: 0,
      limits: null, limitDialog: false, limitLoading: false, limitError: '', limitForm: { totalLimit: '', dailyLimit: '' }, saving: false,
      trial: null
    };
  },
  computed: {
    balance() { return visibleAllowance(this.summary); },
    payerLabel() { return allowancePayerLabel(this.summary); },
    serviceState() { return allowanceServiceState(this.summary); }
  },
  created() { this.load(); },
  beforeDestroy() { this.generation++; this.ledgerGeneration++; },
  watch: {
    tab(value) {
      if (value === 'ledger') { this.loadLedger(); this.loadTrial(); }
    }
  },
  methods: {
    amount: allowanceDisplayAmount,
    exactAmount(value) { const exact = allowanceAmount(value); return exact === '—' ? '金额未提供' : `精确金额：${exact} 元`; },
    ledgerLabel(type) { return ({ GRANT: '平台发放', SETTLE: '服务扣减', RESERVE: '调用预占', RELEASE: '预占释放', REVERSAL: '冲正', FREEZE: '账户冻结' })[type] || type; },
    async resolveWorkspace() {
      let id = localStorage.getItem('currentWorkspaceId');
      if (id) return id;
      const list = await platformRequest('GET', '/workspace/list'); id = list?.[0]?.id;
      if (!id) throw new Error('没有可用工作空间');
      localStorage.setItem('currentWorkspaceId', id); return id;
    },
    async load() {
      const generation = ++this.generation;
      this.loading = true; this.error = ''; this.summary = null; this.limits = null; this.limitLoading = false;
      try {
        const workspaceId = await this.resolveWorkspace();
        const summary = await userPlatform.summary(workspaceId);
        if (generation !== this.generation || workspaceId !== localStorage.getItem('currentWorkspaceId')) return;
        if (summary?.workspaceId !== workspaceId) throw new Error('工作空间已变化，请刷新');
        this.summary = summary;
      } catch (error) { if (generation === this.generation) this.error = error.message; }
      finally { if (generation === this.generation) this.loading = false; }
    },
    refresh() {
      this.usageRevision++;
      this.load();
      if (this.tab === 'ledger') { this.loadLedger(); this.loadTrial(); }
    },
    async loadTrial() { try { this.trial = await userPlatform.trial(); } catch (_) { this.trial = null; } },
    async loadLedger() {
      const generation = ++this.ledgerGeneration;
      this.ledgerLoading = true; this.ledgerError = ''; this.ledger = []; this.total = 0;
      try {
        const result = await userPlatform.ledger({ page: this.page, limit: 20 });
        if (generation === this.ledgerGeneration) { const records = userUsagePage(result); this.ledger = records.list; this.total = records.total; }
      } catch (error) { if (generation === this.ledgerGeneration) this.ledgerError = error.message; }
      finally { if (generation === this.ledgerGeneration) this.ledgerLoading = false; }
    },
    changePage(page) { this.page = page; this.loadLedger(); },
    editLimits() { this.limitDialog = true; return this.loadLimits(); },
    async loadLimits() {
      if (this.limitLoading || !this.summary) return;
      const generation = this.generation; const workspaceId = this.summary.workspaceId;
      this.limitLoading = true; this.limitError = ''; this.limits = null;
      try {
        const limits = await userPlatform.workspaceLimits(workspaceId);
        if (generation !== this.generation) return;
        if (workspaceId !== localStorage.getItem('currentWorkspaceId') || limits.workspaceId !== workspaceId) throw new Error('工作空间已变化，请重新读取');
        this.limits = limits; this.limitForm = { totalLimit: limits.totalLimit ?? '', dailyLimit: limits.dailyLimit ?? '' };
      } catch (error) { if (generation === this.generation) this.limitError = error.message; }
      finally { if (generation === this.generation) this.limitLoading = false; }
    },
    async saveLimits() {
      if (this.saving || this.limits?.canManage !== true || this.limitLoading || this.limitError) return;
      if (this.summary?.workspaceId !== localStorage.getItem('currentWorkspaceId') || this.limits.workspaceId !== this.summary?.workspaceId) return this.$message.error('工作空间已变化，请重新读取');
      if (Object.values(this.limitForm).some(value => value !== '' && !/^\d+(\.\d{1,6})?$/.test(String(value)))) return this.$message.error('请填写非负金额，最多 6 位小数');
      this.saving = true;
      try {
        await userPlatform.saveWorkspaceLimits({ workspaceId: this.summary.workspaceId, expectedVersion: this.limits.responsibilityVersion, totalLimit: this.limitForm.totalLimit === '' ? null : this.limitForm.totalLimit, dailyLimit: this.limitForm.dailyLimit === '' ? null : this.limitForm.dailyLimit });
        this.limitDialog = false; await this.load();
      } catch (error) { this.$message.error(error.message); }
      finally { this.saving = false; }
    }
  }
};
</script>
<style scoped>
.allowance-service-note{font-size:12px;line-height:1.7;color:#718096;margin:6px 0 0}
.allowance-money{font:inherit;color:inherit;line-height:inherit;background:none;border:0;padding:0;text-align:inherit;overflow-wrap:anywhere;cursor:pointer}.allowance-money small{font-size:11px;margin-left:4px}.allowance-money:disabled{cursor:default}.allowance-money:focus-visible{outline:2px solid #486ce7;outline-offset:3px;border-radius:3px}.allowance-totals .allowance-money-range{font-size:12px;line-height:1.5}
.allowance-page{min-height:100vh;background:#f5f7fb;text-align:left;color:#34465d}.allowance-main{max-width:1360px;margin:auto;padding:22px 26px 36px}.allowance-heading,.allowance-actions{display:flex;align-items:center;justify-content:space-between;gap:10px}.allowance-heading{margin-bottom:16px}.allowance-heading h1{font-size:23px;font-weight:600;margin:0;white-space:nowrap}.allowance-actions .el-button+.el-button{margin-left:0}.allowance-summary{background:#fff;border:1px solid #e3e9f1;border-radius:12px;padding:12px 20px}.allowance-context{display:flex;align-items:center;flex-wrap:wrap;gap:8px;color:#718096;font-size:12px}.allowance-payer{min-width:0}.allowance-limit-action{margin-left:auto;padding:3px 0}.allowance-totals{display:grid;grid-template-columns:1.3fr 1fr 1fr;gap:20px;margin-top:12px}.allowance-totals>div{min-width:0}.allowance-totals span{display:block;font-size:12px;color:#8390a2}.allowance-totals strong{display:block;margin-top:5px;font-size:23px;line-height:1.3;font-weight:500;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.allowance-totals small{font-size:11px;font-weight:400;margin-left:5px;color:#8290a2}.allowance-available strong{color:#486ce7}.allowance-summary-note{margin:9px 0 0;color:#8995a6;font-size:11px;line-height:1.6}.allowance-tabs{margin-top:14px}.allowance-tabs ::v-deep .el-tabs__header{margin-bottom:12px}.allowance-tabs ::v-deep .el-tabs__item{font-size:13px}.allowance-panel{background:white;border:1px solid #e3e9f1;border-radius:12px;padding:16px}.allowance-panel-note,.allowance-trial{font-size:12px;line-height:1.7;color:#8390a2;margin:0 0 12px}.allowance-trial{color:#678366}.allowance-trial i{margin-right:5px}.allowance-panel .el-pagination{text-align:right;overflow-x:auto;margin-top:14px}.allowance-error{display:flex;align-items:center;justify-content:space-between;gap:12px;background:#fff5f5;border:1px solid #f2dcdc;color:#aa5656;padding:10px 14px;border-radius:8px;font-size:13px;line-height:1.6;margin-bottom:12px}.allowance-error .el-button{flex-shrink:0;padding:4px}.allowance-loading{padding:30px 0;color:#8c9aaa;font-size:13px}.allowance-dialog-note{color:#8190a3;font-size:12px;line-height:1.8;margin:0 0 18px}.allowance-limits{display:grid;grid-template-columns:135px minmax(0,1fr);gap:12px;font-size:13px;margin:0 0 24px}.allowance-limits dt{color:#8190a3}.allowance-limits dd{margin:0;overflow-wrap:anywhere}
@media(max-width:600px){.allowance-main{padding:16px 12px 24px}.allowance-heading{gap:8px;margin-bottom:12px}.allowance-heading h1{font-size:20px}.allowance-actions{gap:6px}.allowance-actions .el-button{padding:9px}.allowance-summary{padding:12px}.allowance-context{gap:6px}.allowance-payer{flex-basis:100%}.allowance-totals{gap:10px;margin-top:10px}.allowance-totals span{font-size:10px}.allowance-totals strong{font-size:18px}.allowance-totals small{font-size:10px;margin-left:3px}.allowance-summary-note{font-size:10px}.allowance-panel{padding:12px}.allowance-tabs ::v-deep .el-tabs__item{padding:0 14px;font-size:12px}.allowance-limits{grid-template-columns:1fr;gap:5px}.allowance-limits dd{margin-bottom:8px}}
/* The embedded usage panel does not provide the full billing page's header layout. */
@media (max-width: 899px) {
  .allowance-page ::v-deep .el-header.header { min-width: 0; width: 100%; padding: 0 16px; }
  .allowance-page ::v-deep .header-container { min-width: 0; max-width: 100%; }
  .allowance-page ::v-deep .header-center {
    max-width: 100%;
    flex-wrap: nowrap;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: thin;
    padding: 10px 0 12px;
  }
  .allowance-page ::v-deep .equipment-management { height: 38px; padding: 0 12px; font-size: 12px; }
}
@media (max-width: 600px) {
  .allowance-page ::v-deep .header-container {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas: "brand" "tools" "navigation";
    gap: 12px 0;
  }
  .allowance-page ::v-deep .header-left { width: auto; }
  .allowance-page ::v-deep .logo-img { width: 32px; height: 32px; }
  .allowance-page ::v-deep .brand-img { height: 18px; }
  .allowance-page ::v-deep .header-right { justify-content: flex-start; flex-wrap: wrap; gap: 8px; width: 100%; }
  .allowance-page ::v-deep .workspace-select { width: auto; min-width: 120px; max-width: 100%; flex: 1 1 132px; }
  .allowance-page ::v-deep .el-user-dropdown { max-width: 82px; font-size: 13px; }
  .allowance-page ::v-deep .header-center { margin-top: 0; }
}
</style>

<style>
.allowance-amount-tooltip.el-tooltip__popper{max-width:min(320px,calc(100vw - 24px));box-sizing:border-box;overflow-wrap:anywhere}
</style>
