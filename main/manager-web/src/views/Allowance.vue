<template>
  <div class="allowance-page"><HeaderBar /><main class="allowance-main">
    <header class="allowance-heading"><div><span class="allowance-eyebrow">SERVICE ALLOWANCE</span><h1>额度与用量</h1><p>清楚了解谁承担服务额度，以及额度用在了哪里。</p></div><el-button icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-if="loading" class="allowance-loading">正在读取服务额度…</div>
    <template v-else-if="summary">
      <section class="allowance-responsibility"><i class="el-icon-user" /><div><strong>{{ payerLabel }}</strong><p>在谁的工作空间协作，就使用谁的凭据和额度。服务额度由平台发放。</p></div><el-tag :type="summary.serviceStatus === 'READY' ? 'success' : 'warning'">{{ summary.serviceStatus === 'READY' ? '模型服务已就绪' : '模型服务待就绪' }}</el-tag></section>
      <div v-if="balance" class="allowance-metrics"><article class="allowance-primary"><span>可用服务额度</span><strong>{{ amount(balance.available) }}<small>元</small></strong><p>额度由平台发放，不支持自行充值</p></article><article><span>预占中</span><strong>{{ amount(balance.reserved) }}<small>元</small></strong><p>用于正在进行的服务调用</p></article><article><span>账户累计已用</span><strong>{{ amount(balance.spent) }}<small>元</small></strong><p>包括本人负责的工作空间调用</p></article></div>
      <section v-else class="allowance-card"><h2>工作空间服务状态</h2><p>工作空间费用由负责人承担。负责人的个人账户余额仅本人可见，你仍可查看获授权的工作空间用量。</p></section>
      <section v-if="limits" class="allowance-card"><div class="allowance-section-heading"><div><h2>工作空间使用上限</h2><p>限制本工作空间在负责人账户内的消费，不另行发放额度。</p></div><el-button v-if="limits.canManage" size="small" @click="editLimits">设置上限</el-button></div><dl class="allowance-limits"><div><dt>总使用上限</dt><dd>{{ limits.totalLimit == null ? '未单设上限' : amount(limits.totalLimit) + ' 元' }}</dd></div><div><dt>今日使用 / 日上限</dt><dd>{{ amount(limits.dailySpent) }} / {{ limits.dailyLimit == null ? '未单设上限' : amount(limits.dailyLimit) + ' 元' }}</dd></div><div><dt>累计使用 / 预占</dt><dd>{{ amount(limits.totalSpent) }} / {{ amount(limits.totalReserved) }} 元</dd></div></dl><p>负责人账户可用额度不足时，工作空间的新付费调用暂停，不会改扣成员个人额度。</p></section>
      <section v-if="trial && trial.enabled && trial.granted" class="allowance-card"><h2>注册试用额度</h2><p>注册时已按平台政策「{{ trial.grantedPolicy }}」自动发放一次试用额度，同一账号不会重复发放。额度用完后可在「额度申请」中提交申请，由平台运营审核发放。</p></section>
      <el-tabs v-model="tab" class="allowance-tabs"><el-tab-pane label="当前空间用量" name="usage"><BillingWorkspacePanel mode="usage" :show-supplier-costs="false" route-path="/allowance" /></el-tab-pane><el-tab-pane label="我的额度流水" name="ledger"><section class="allowance-card"><p>仅显示本人的额度账户流水。平台扣减按服务价格记账，与供应商实际采购成本分别核算。</p><el-table :data="ledger" v-loading="ledgerLoading" empty-text="暂无额度流水"><el-table-column prop="createdAt" label="时间" min-width="160" /><el-table-column prop="type" label="类型" width="120"><template slot-scope="s">{{ ledgerLabel(s.row.type) }}</template></el-table-column><el-table-column prop="amount" label="变动（元）" min-width="120" /><el-table-column prop="balanceAfter" label="变动后余额（元）" min-width="160" /><el-table-column prop="reason" label="说明" min-width="200" /></el-table><el-pagination :current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="changePage" /></section></el-tab-pane><el-tab-pane label="额度申请" name="request"><section class="allowance-card"><div class="allowance-section-heading"><div><h2>额度申请</h2><p>平台不提供充值。可用额度不足时提交申请，平台运营审核后发放，处理结果会显示在下方记录中。</p></div><el-button type="primary" size="small" :disabled="hasPendingRequest" @click="openRequest">{{ hasPendingRequest ? '已有待审核申请' : '申请额度' }}</el-button></div><el-table :data="requests" v-loading="requestLoading" empty-text="暂无额度申请记录"><el-table-column prop="createdAt" label="提交时间" min-width="170" /><el-table-column label="期望金额（元）" min-width="120"><template slot-scope="s">{{ s.row.requestedAmount == null ? '未指定' : amount(s.row.requestedAmount) }}</template></el-table-column><el-table-column label="状态" width="110"><template slot-scope="s"><el-tag :type="requestTag(s.row.status)" size="small">{{ requestStatus(s.row.status) }}</el-tag></template></el-table-column><el-table-column label="实际发放（元）" min-width="120"><template slot-scope="s">{{ s.row.grantedAmount == null ? '—' : amount(s.row.grantedAmount) }}</template></el-table-column><el-table-column prop="reason" label="申请说明" min-width="200" /><el-table-column prop="decisionReason" label="处理说明" min-width="200" /></el-table></section></el-tab-pane></el-tabs>
    </template>
    <el-dialog title="申请额度" :visible.sync="requestDialog" width="min(480px,92vw)"><p>申请为本人额度账户发放，不会给其他工作空间负责人充值；实际金额由平台运营决定。</p><el-form label-width="110px"><el-form-item label="期望金额"><el-input v-model="requestForm.amount" placeholder="元，可留空" /></el-form-item><el-form-item label="申请说明"><el-input v-model="requestForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明用途，便于运营审核" /></el-form-item></el-form><span slot="footer"><el-button @click="requestDialog = false">取消</el-button><el-button type="primary" :loading="requestSaving" @click="submitRequest">提交申请</el-button></span></el-dialog>
    <el-dialog title="设置工作空间使用上限" :visible.sync="limitDialog" width="min(480px,92vw)"><p>留空表示不单设该项上限；0 表示暂停新的工作空间付费调用。</p><el-form label-width="110px"><el-form-item label="工作空间总上限"><el-input v-model="limitForm.totalLimit" placeholder="元，留空不单设上限" /></el-form-item><el-form-item label="每日上限"><el-input v-model="limitForm.dailyLimit" placeholder="元，留空不单设上限" /></el-form-item></el-form><span slot="footer"><el-button @click="limitDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveLimits">保存</el-button></span></el-dialog>
  </main></div>
</template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import BillingWorkspacePanel from '@/components/billing/BillingWorkspacePanel.vue';
import { userPlatform, platformRequest } from '@/apis/platform';
import { allowanceAmount, allowancePayerLabel, visibleAllowance, newOperationId } from '@/utils/allowance';
export default {
  components: { HeaderBar, BillingWorkspacePanel },
  data() { return { summary: null, limits: null, loading: false, error: '', tab: 'usage', ledger: [], ledgerLoading: false, page: 1, total: 0, generation: 0, limitDialog: false, limitForm: { totalLimit: '', dailyLimit: '' }, saving: false, trial: null, requests: [], requestLoading: false, requestDialog: false, requestForm: { requestId: '', amount: '', reason: '' }, requestSaving: false, requestGeneration: 0 }; },
  computed: { balance() { return visibleAllowance(this.summary); }, payerLabel() { return allowancePayerLabel(this.summary); }, hasPendingRequest() { return this.requests.some(item => item.status === 'PENDING'); } },
  created() { this.load(); this.loadTrialAndRequests(); }, beforeDestroy() { this.generation++; this.requestGeneration++; },
  watch: { tab(value) { if (value === 'ledger') this.loadLedger(); if (value === 'request') this.loadRequests(); } },
  methods: {
    amount: allowanceAmount,
    ledgerLabel(type) { return ({ GRANT: '平台发放', SETTLE: '服务扣减', RESERVE: '调用预占', RELEASE: '预占释放', REVERSAL: '冲正', FREEZE: '账户冻结' })[type] || type; },
    requestStatus(status) { return ({ PENDING: '待审核', APPROVED: '已发放', REJECTED: '未通过' })[status] || status; },
    requestTag(status) { return ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'info' })[status] || 'info'; },
    async resolveWorkspace() { let id = localStorage.getItem('currentWorkspaceId'); if (id) return id; const list = await platformRequest('GET', '/workspace/list'); id = list?.[0]?.id; if (!id) throw new Error('没有可用工作空间'); localStorage.setItem('currentWorkspaceId', id); return id; },
    async load() {
      const generation = ++this.generation; this.loading = true; this.error = ''; this.summary = null; this.limits = null;
      try { const workspaceId = await this.resolveWorkspace(); const summary = await userPlatform.summary(workspaceId); if (generation !== this.generation || workspaceId !== localStorage.getItem('currentWorkspaceId')) return; if (summary?.workspaceId !== workspaceId) throw new Error('工作空间已变化，请刷新'); this.summary = summary; { const limits = await userPlatform.workspaceLimits(workspaceId); if (generation === this.generation && limits.workspaceId === workspaceId) this.limits = limits; } }
      catch (error) { if (generation === this.generation) this.error = error.message; }
      finally { if (generation === this.generation) this.loading = false; }
    },
    async loadTrialAndRequests() { await Promise.all([this.loadTrial(), this.loadRequests()]); },
    async loadTrial() { try { this.trial = await userPlatform.trial(); } catch (_) { this.trial = null; } },
    async loadRequests() { const generation = ++this.requestGeneration; this.requestLoading = true; try { const result = await userPlatform.allowanceRequests({ page: 1, limit: 50 }); if (generation === this.requestGeneration) this.requests = result.list || []; } catch (error) { if (generation === this.requestGeneration) this.$message.error(error.message); } finally { if (generation === this.requestGeneration) this.requestLoading = false; } },
    openRequest() { this.requestForm = { requestId: newOperationId(), amount: '', reason: '' }; this.requestDialog = true; },
    async submitRequest() {
      if (this.requestSaving) return;
      if (!this.requestForm.reason.trim()) return this.$message.error('请填写申请说明');
      const amount = this.requestForm.amount.trim();
      if (amount && !/^\d+(\.\d{1,6})?$/.test(amount)) return this.$message.error('期望金额请填写非负金额，最多 6 位小数');
      this.requestSaving = true;
      try {
        await userPlatform.requestAllowance({ requestId: this.requestForm.requestId, amount: amount || null, reason: this.requestForm.reason.trim(), workspaceId: this.summary?.canViewBalance ? this.summary.workspaceId : null });
        this.requestDialog = false; this.$message.success('已提交申请，请等待平台运营处理'); await this.loadRequests();
      } catch (error) { this.$message.error(error.message); } finally { this.requestSaving = false; }
    },
    async loadLedger() { this.ledgerLoading = true; this.ledger = []; try { const result = await userPlatform.ledger({ page: this.page, limit: 20 }); this.ledger = result.list || []; this.total = result.total || 0; } catch (error) { this.$message.error(error.message); } finally { this.ledgerLoading = false; } },
    changePage(page) { this.page = page; this.loadLedger(); },
    editLimits() { this.limitForm = { totalLimit: this.limits.totalLimit ?? '', dailyLimit: this.limits.dailyLimit ?? '' }; this.limitDialog = true; },
    async saveLimits() { if (this.saving || !this.limits?.canManage) return; const fields = Object.values(this.limitForm); if (fields.some(value => value !== '' && !/^\d+(\.\d{1,6})?$/.test(String(value)))) return this.$message.error('请填写非负金额，最多 6 位小数'); this.saving = true; try { await userPlatform.saveWorkspaceLimits({ workspaceId: this.summary.workspaceId, expectedVersion: this.limits.responsibilityVersion, totalLimit: this.limitForm.totalLimit === '' ? null : this.limitForm.totalLimit, dailyLimit: this.limitForm.dailyLimit === '' ? null : this.limitForm.dailyLimit }); this.limitDialog = false; await this.load(); } catch (error) { this.$message.error(error.message); } finally { this.saving = false; } },
  },
};
</script>
<style scoped>
.allowance-page{min-height:100vh;background:#f5f7fb;text-align:left}.allowance-main{max-width:1280px;margin:auto;padding:35px 26px 70px}.allowance-heading,.allowance-section-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.allowance-heading h1{font-size:29px;font-weight:600;color:#273c53;margin:10px 0}.allowance-heading p,.allowance-card p,.allowance-section-heading p{font-size:12px;line-height:1.8;color:#8a97a7}.allowance-eyebrow{font-size:10px;letter-spacing:2px;color:#7e99b7}.allowance-responsibility{display:flex;align-items:center;gap:18px;border:1px solid #dce7ef;background:#edf4f9;border-radius:12px;padding:20px;margin:25px 0}.allowance-responsibility>i{font-size:25px;color:#59879a}.allowance-responsibility>div{flex:1}.allowance-responsibility strong{font-size:14px;color:#426878}.allowance-responsibility p{font-size:12px;color:#8198a6;line-height:1.8;margin:7px 0 0}.allowance-metrics{display:grid;grid-template-columns:1.4fr 1fr 1fr;gap:18px}.allowance-metrics article{background:white;border:1px solid #e4e9f1;border-radius:13px;padding:26px}.allowance-metrics span{display:block;font-size:12px;color:#8c98aa}.allowance-metrics strong{display:block;font-size:36px;font-weight:500;color:#384e68;margin:20px 0;overflow-wrap:anywhere}.allowance-metrics small{font-size:12px;margin-left:9px}.allowance-metrics p{font-size:11px;color:#94a1b1;line-height:1.7}.allowance-metrics .allowance-primary{background:#263f56;border-color:#263f56}.allowance-primary span,.allowance-primary p{color:#a6bbcb}.allowance-primary strong{color:#fff}.allowance-card{background:white;border:1px solid #e4e9f1;border-radius:12px;padding:24px;margin:20px 0}.allowance-card h2{font-size:17px;color:#426078;margin:0 0 10px}.allowance-limits{display:flex;flex-wrap:wrap;gap:38px;padding:12px 0}.allowance-limits dt{font-size:11px;color:#98a5b4;margin-bottom:12px}.allowance-limits dd{margin:0;font-size:16px;color:#4f6b81}.allowance-tabs{margin-top:28px}.allowance-card .el-pagination{text-align:right;margin-top:20px}.allowance-loading{padding:50px;color:#8c9aaa}@media(max-width:760px){.allowance-metrics{grid-template-columns:1fr}.allowance-main{padding:24px 14px}.allowance-responsibility{flex-wrap:wrap}.allowance-section-heading{align-items:flex-start}.allowance-responsibility>div{min-width:190px}}
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
