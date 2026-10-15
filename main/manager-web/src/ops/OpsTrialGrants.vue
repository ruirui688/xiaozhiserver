<template>
  <section v-if="can('allowance.read')" class="ops-panel trial-grants">
    <div class="ops-toolbar"><h3>注册赠额</h3><el-button size="small" :loading="loading" @click="refresh">刷新赠额状态</el-button></div>
    <p v-if="policy" class="ops-muted">政策 {{ policy.policy }} · {{ policy.enabled ? '已启用' : '已暂停' }} · 每人 {{ policy.amount }} 元 · 已发 {{ policy.issuedUsers }} 人 · 人数上限 {{ String(policy.maxUsers) === '0' ? '未设限' : policy.maxUsers }}</p>
    <p class="ops-muted">赠额消耗现有总池，不会自动充值。补发只安排所选用户重试，是否到账以执行结果和流水为准；已领取者不会重复赠额。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <div class="ops-toolbar"><div class="ops-actions"><el-select v-model="status" size="small" aria-label="赠额状态" @change="resetPage"><el-option v-for="item in statuses" :key="item.value" :value="item.value" :label="item.label" /></el-select><el-select v-model="limit" size="small" aria-label="每页赠额记录" @change="resetPage"><el-option v-for="size in [20,50,100]" :key="size" :value="size" :label="size + ' 条/页'" /></el-select></div><el-button v-if="can('allowance.grant')" size="small" :disabled="!selected.length || loading || saving || !policy || !policy.enabled" @click="openTargets(selected)">重试所选 {{ selected.length }} 位用户</el-button></div>
    <el-table :key="tableGeneration" :data="rows" v-loading="loading" row-key="userId" @selection-change="selectRows">
      <el-table-column v-if="can('allowance.grant')" type="selection" :selectable="selectable" width="45" />
      <el-table-column prop="userId" label="用户 ID" min-width="185" /><el-table-column prop="policy" label="政策" min-width="100" /><el-table-column prop="amount" label="赠额（元）" min-width="100" />
      <el-table-column label="状态" min-width="100"><template slot-scope="s">{{ statusLabel(s.row.status) }}</template></el-table-column>
      <el-table-column label="待发 / 复核原因" min-width="220"><template slot-scope="s">{{ reasonLabel(s.row.reasonCode) }}</template></el-table-column>
      <el-table-column prop="attempts" label="尝试次数" width="90" /><el-table-column prop="nextAttemptAt" label="下次尝试" min-width="170" />
      <el-table-column v-if="can('allowance.grant')" label="操作" width="100"><template slot-scope="s"><el-button type="text" :disabled="!selectable(s.row) || !policy || !policy.enabled || saving" @click="openTargets([s.row])">重试补发</el-button></template></el-table-column>
    </el-table>
    <div class="ops-toolbar"><span class="ops-muted">第 {{ cursors.length }} 页 · 换页清空选择，单次最多 100 位用户</span><div><el-button size="small" :disabled="loading || cursors.length === 1" @click="previousPage">上一页</el-button><el-button size="small" :disabled="loading || rows.length < limit" @click="nextPage">下一页</el-button></div></div>
    <el-dialog title="确认重试注册赠额" :visible.sync="dialog" width="min(560px,92vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @closed="invalidateDialog">
      <p>仅处理以下 {{ targets.length }} 位用户。历史用户尚无任务时会创建任务；已领取者不会重复发放，不会调整总池。</p>
      <div class="trial-targets"><div v-for="target in targets" :key="target">{{ target }}</div></div>
      <el-input v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="saving" placeholder="填写补发原因（必填，将记入审计）" />
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      <span slot="footer"><el-button :disabled="saving" @click="cancel">取消</el-button><el-button type="primary" :loading="saving" :disabled="saving" @click="retry">确认安排重试</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import { operations } from '@/apis/platform';
import { hasPermission } from '@/security/session';
import { exactUserId } from './service-display';
const statuses = [{ value: 'REVIEW', label: '待复核' }, { value: 'PENDING', label: '待发放' }, { value: 'PROCESSING', label: '处理中' }, { value: 'DISABLED', label: '政策暂停' }, { value: 'GRANTED', label: '已发放' }];
const reasons = { ISSUANCE_POOL_EXHAUSTED: '总池可发额度不足', OPERATOR_DAILY_GRANT_LIMIT: '操作员当日发放上限', GRANT_LIMIT_EXCEEDED: '超过单次发放上限', ACCOUNT_FROZEN: '用户额度账户已冻结', USER_UNAVAILABLE: '用户不可用', TRIAL_CAP_REACHED: '赠额人数达到上限', POLICY_INVALID: '赠额政策配置无效', POLICY_DISABLED: '赠额政策已暂停', IDEMPOTENCY_CONFLICT: '幂等记录冲突，需复核', RETRY_EXHAUSTED: '重试次数耗尽' };
export default {
  data() { return { statuses, policy: null, status: 'REVIEW', limit: 20, rows: [], selected: [], cursors: ['0'], loading: false, error: '', notice: '', generation: 0, tableGeneration: 0, dialog: false, targets: [], reason: '', saveError: '', saving: false, dialogGeneration: 0 }; },
  created() { this.refresh(); },
  beforeDestroy() { this.generation++; this.dialogGeneration++; },
  methods: {
    can(permission) { return hasPermission(this.$store.state.userInfo, permission); },
    statusLabel(value) { return statuses.find(item => item.value === value)?.label || value; },
    reasonLabel(value) { return value ? (reasons[value] || value) : '—'; },
    selectable(row) { return Boolean(exactUserId(row.userId)) && ['PENDING', 'REVIEW', 'DISABLED'].includes(row.status); },
    selectRows(rows) { this.selected = rows.filter(this.selectable).slice(0, 100); },
    async refresh() {
      if (!this.can('allowance.read')) return;
      const generation = ++this.generation; this.loading = true; this.error = ''; this.selected = []; this.tableGeneration++;
      const query = { status: this.status, afterUserId: this.cursors[this.cursors.length - 1], limit: this.limit };
      try {
        const [policy, rows] = await Promise.all([operations.trial(), operations.trialRequests(query)]);
        if (generation !== this.generation || !this.can('allowance.read')) return;
        if (!Array.isArray(rows) || rows.some(row => !exactUserId(row.userId))) throw new Error('赠额记录格式异常，请刷新重试');
        this.policy = policy; this.rows = rows.map(row => ({ ...row, userId: exactUserId(row.userId) }));
      } catch (error) { if (generation === this.generation) { this.error = error.message; this.rows = []; } }
      finally { if (generation === this.generation) this.loading = false; }
    },
    resetPage() { this.cursors = ['0']; return this.refresh(); },
    nextPage() { if (!this.loading && this.rows.length === this.limit) { this.cursors.push(exactUserId(this.rows[this.rows.length - 1].userId)); return this.refresh(); } },
    previousPage() { if (!this.loading && this.cursors.length > 1) { this.cursors.pop(); return this.refresh(); } },
    openTargets(rows) {
      if (this.saving || !this.can('allowance.read') || !this.can('allowance.grant')) return;
      const ids = rows.map(row => exactUserId(row.userId));
      if (!ids.length || ids.length > 100 || ids.some(id => !id)) { this.error = '请选择 1 至 100 位有效用户'; return; }
      this.dialogGeneration++; this.targets = [...new Set(ids)]; this.reason = ''; this.saveError = ''; this.dialog = true;
    },
    invalidateDialog() { this.dialogGeneration++; },
    cancel() { if (!this.saving) { this.dialog = false; this.invalidateDialog(); } },
    async retry() {
      if (!this.dialog || this.saving || !this.can('allowance.read') || !this.can('allowance.grant')) return;
      if (!this.reason.trim() || this.reason.trim().length > 500 || !this.targets.length || this.targets.length > 100 || this.targets.some(id => !exactUserId(id))) { this.saveError = '请选择有效用户并填写 1 至 500 字的原因'; return; }
      const generation = this.dialogGeneration, userIds = this.targets.slice(), reason = this.reason.trim(); this.saving = true; this.saveError = '';
      try {
        await operations.retryTrial({ userIds, reason });
        if (generation !== this.dialogGeneration) return;
        this.saving = false; this.dialog = false; this.notice = `已提交 ${userIds.length} 位用户的补发检查；未领取者将安排重试，已领取者不会重复发放。这不代表已到账，请刷新查看状态和流水。`;
        this.$emit('submitted'); await this.refresh();
      } catch (error) { if (generation === this.dialogGeneration) this.saveError = error.message; }
      finally { if (generation === this.dialogGeneration) this.saving = false; }
    },
  },
};
</script>
<style scoped>
.trial-grants { margin-top: 20px; }
.trial-targets { max-height: 160px; overflow: auto; margin: 12px 0; font-variant-numeric: tabular-nums; }
.trial-grants .el-alert { margin: 10px 0; }
</style>
