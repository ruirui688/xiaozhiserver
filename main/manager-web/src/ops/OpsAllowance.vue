<template>
  <section class="allowance-workspace">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="pool" class="ops-metrics">
      <article class="ops-metric"><small>额度总池 · 元</small><strong :title="String(pool.total)">{{ money(pool.total) }}</strong></article>
      <article class="ops-metric"><small>已发放 · 元</small><strong :title="String(pool.allocated)">{{ money(pool.allocated) }}</strong></article>
      <article class="ops-metric"><small>可发放 · 元</small><strong :title="String(pool.available)">{{ money(pool.available) }}</strong></article>
    </div>
    <div class="ops-toolbar allowance-toolbar">
      <div v-if="can('allowance.read')" class="ops-actions allowance-search">
        <el-input v-model="keyword" size="small" clearable maxlength="100" placeholder="搜索用户名或完整用户 ID" aria-label="搜索用户" @keyup.enter.native="search" @clear="search" />
        <el-button size="small" :loading="loading" @click="search">搜索</el-button>
      </div>
      <div class="ops-actions">
        <el-button v-if="can('allowance.grant')" size="small" type="primary" @click="open('grant')">发放额度</el-button>
        <el-button v-if="can('pool.adjust')" size="small" @click="open('pool')">调整总池</el-button>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>
    <section v-if="can('allowance.read')" class="ops-panel ops-table">
      <el-table :data="accounts" v-loading="loading" :empty-text="searchedKeyword ? '未找到匹配用户，请更换用户名或完整 ID' : '暂无用户额度账户'">
        <el-table-column label="用户" min-width="300">
          <template slot-scope="s"><div class="allowance-user" :title="userLabel(s.row)"><strong>{{ userName(s.row) }}</strong><span v-if="s.row.username" class="allowance-user-id">{{ userId(s.row.userId) }}</span></div></template>
        </el-table-column>
        <el-table-column label="剩余额度（元）" min-width="150" align="right"><template slot-scope="s"><span class="allowance-money" :title="String(s.row.remaining)">{{ money(s.row.remaining) }}</span></template></el-table-column>
        <el-table-column label="累计消耗（元）" min-width="150" align="right"><template slot-scope="s"><span class="allowance-money" :title="String(s.row.spent)">{{ money(s.row.spent) }}</span></template></el-table-column>
        <el-table-column label="状态" width="90"><template slot-scope="s"><el-tag size="small" :type="s.row.frozen ? 'warning' : 'success'">{{ s.row.frozen ? '已冻结' : '正常' }}</el-tag></template></el-table-column>
        <el-table-column v-if="can('allowance.grant') || can('allowance.freeze')" label="操作" width="155">
          <template slot-scope="s">
            <el-button v-if="can('allowance.grant')" type="text" @click="open('grant', s.row)">发放额度</el-button>
            <el-dropdown v-if="can('allowance.freeze')" trigger="click" @command="open('freeze', s.row)">
              <el-button type="text" class="allowance-more">更多<i class="el-icon-arrow-down el-icon--right" /></el-button>
              <el-dropdown-menu slot="dropdown"><el-dropdown-item command="freeze">{{ s.row.frozen ? '解冻账户' : '冻结账户' }}</el-dropdown-item></el-dropdown-menu>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination :current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" @current-change="changePage" />
    </section>
    <el-collapse v-if="requests.length && (can('allowance.read') || can('allowance.review'))" class="allowance-legacy">
      <el-collapse-item :title="'历史额度申请（' + requests.length + '）'" name="legacy">
        <el-table :data="requests" v-loading="requestsLoading">
          <el-table-column prop="createdAt" label="提交时间" min-width="170" />
          <el-table-column prop="userId" label="用户 ID" min-width="190" />
          <el-table-column label="期望金额（元）" min-width="120"><template slot-scope="s">{{ s.row.requestedAmount == null ? '未指定' : money(s.row.requestedAmount) }}</template></el-table-column>
          <el-table-column prop="reason" label="申请说明" min-width="220" />
          <el-table-column v-if="can('allowance.grant') || can('allowance.review')" label="操作" width="150"><template slot-scope="s"><el-button v-if="can('allowance.grant')" type="text" @click="approve(s.row)">批准发放</el-button><el-button v-if="can('allowance.review')" type="text" @click="reject(s.row)">不通过</el-button></template></el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>
    <p v-if="!loading && !error && !pool && !can('allowance.read') && !requests.length" class="ops-empty">暂无待处理事项。</p>
    <el-dialog :title="dialogTitle" :visible.sync="dialog" width="min(520px,92vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form class="ops-form" label-width="90px" :disabled="saving" @submit.native.prevent="save">
        <el-form-item v-if="action !== 'pool'" label="用户">
          <el-select v-model="form.userId" class="allowance-user-select" filterable remote :remote-method="searchUsers" :loading="usersLoading" :disabled="targetLocked" placeholder="搜索用户名或完整用户 ID" no-data-text="未找到匹配用户">
            <el-option v-for="row in userOptions" :key="userId(row.userId)" :value="userId(row.userId)" :label="userLabel(row)" />
          </el-select>
          <el-button v-if="hasMoreUsers" type="text" :loading="usersLoading" @click="loadMoreUsers">加载更多用户</el-button>
          <el-alert v-if="userError" :title="userError" type="error" :closable="false" />
          <el-button v-if="userError && !targetLocked" type="text" @click="searchUsers(userSearch)">重新加载用户</el-button>
        </el-form-item>
        <el-form-item v-if="action !== 'freeze'" :label="action === 'pool' ? '调整金额' : '发放金额'">
          <el-input v-model="form.amount" placeholder="金额（元）" />
          <p class="ops-muted">{{ action === 'pool' ? '负数仅可收回尚未发放的额度。' : '金额不能超过可发放额度和岗位上限。' }}</p>
        </el-form-item>
        <el-form-item label="操作原因"><el-input v-model="form.reason" type="textarea" maxlength="500" :rows="3" /></el-form-item>
        <p v-if="action === 'freeze'" class="ops-muted">{{ form.frozen ? '冻结后停止新的付费调用，已开始的调用继续结算。' : '解冻后可恢复调用，仍需有可用额度和已启用的模型通道。' }}</p>
      </el-form>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      <span slot="footer"><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button :type="action === 'freeze' && form.frozen ? 'danger' : 'primary'" :loading="saving" @click="save">确认{{ dialogTitle }}</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import { operations } from '@/apis/platform';
import { hasPermission } from '@/security/session';
import { allowanceDisplayAmount, newOperationId } from '@/utils/allowance';
import { exactUserId, accountName, accountLabel, mergeAccountOptions, paginationTotal } from './service-display';
const actionPermissions = { pool: 'pool.adjust', grant: 'allowance.grant', freeze: 'allowance.freeze' };
export default {
  data() {
    return { pool: null, accounts: [], loading: false, error: '', page: 1, total: 0, keyword: '', searchedKeyword: '', dialog: false, action: 'grant', form: {}, saving: false, saveError: '', generation: 0, requests: [], requestsLoading: false, requestGeneration: 0, targetLocked: false, userOptions: [], userSearch: '', userPage: 1, userTotal: 0, usersLoading: false, userError: '', userGeneration: 0 };
  },
  computed: {
    dialogTitle() { return this.action === 'pool' ? '调整总池' : this.action === 'freeze' ? (this.form.frozen ? '冻结账户' : '解冻账户') : '发放额度'; },
    hasMoreUsers() { return !this.targetLocked && this.userPage * 20 < this.userTotal; },
  },
  created() { this.load(); },
  beforeDestroy() { this.generation++; this.requestGeneration++; this.userGeneration++; },
  methods: {
    money: allowanceDisplayAmount, userId: exactUserId, userName: accountName, userLabel: accountLabel,
    can(permission) { return hasPermission(this.$store.state.userInfo, permission); },
    async loadRequests() {
      const generation = ++this.requestGeneration; this.requestsLoading = true;
      try { const result = await operations.allowanceRequests({ page: 1, limit: 50 }); if (generation === this.requestGeneration) this.requests = result.list || []; }
      catch (error) { if (generation === this.requestGeneration) this.error = error.message; }
      finally { if (generation === this.requestGeneration) this.requestsLoading = false; }
    },
    async approve(row) {
      if (!this.can('allowance.grant')) return;
      let amount;
      try { const result = await this.$prompt(`用户期望 ${row.requestedAmount == null ? '未指定' : row.requestedAmount + ' 元'}，请填写实际发放金额。`, '批准额度申请', { inputPlaceholder: '实际发放金额（元）', inputValidator: value => /^\d+(\.\d{1,6})?$/.test(String(value)) && Number(value) > 0 || '请填写大于零的金额' }); amount = String(result.value).trim(); } catch (_) { return; }
      let reason;
      try { const result = await this.$prompt('请填写发放原因，将记入额度流水。', '发放原因', { inputPlaceholder: '例如：按试用政策发放', inputValidator: value => !!String(value).trim() || '请填写原因' }); reason = String(result.value).trim(); } catch (_) { return; }
      if (!this.can('allowance.grant')) return;
      try { await operations.approveAllowance(row.id, { requestId: `allowance-approval-${row.id}`, amount, reason }); this.$message.success('已发放额度'); await this.load(); } catch (error) { this.$message.error(error.message); }
    },
    async reject(row) {
      if (!this.can('allowance.review')) return;
      let reason;
      try { const result = await this.$prompt('不通过原因会显示给用户。', '不通过额度申请', { inputPlaceholder: '例如：不符合当前发放政策', inputValidator: value => !!String(value).trim() || '请填写原因' }); reason = String(result.value).trim(); } catch (_) { return; }
      if (!this.can('allowance.review')) return;
      try { await operations.rejectAllowance(row.id, reason); this.$message.success('已处理'); await this.loadRequests(); } catch (error) { this.$message.error(error.message); }
    },
    async load() {
      const generation = ++this.generation; this.loading = true; this.error = '';
      const requests = this.can('allowance.read') || this.can('allowance.review') ? this.loadRequests() : Promise.resolve();
      try {
        const [pool, accounts] = await Promise.all([this.can('pool.read') ? operations.pool() : null, this.can('allowance.read') ? operations.accounts({ page: this.page, limit: 20, keyword: this.searchedKeyword }) : null]);
        if (generation !== this.generation) return;
        this.pool = pool; this.accounts = accounts?.list || []; this.total = paginationTotal(accounts?.total);
      } catch (error) { if (generation === this.generation) this.error = error.message; }
      finally { await requests; if (generation === this.generation) this.loading = false; }
    },
    search() { this.searchedKeyword = this.keyword.trim(); this.page = 1; return this.load(); },
    changePage(page) { this.page = page; return this.load(); },
    open(action, row) {
      if (this.saving || !actionPermissions[action] || !this.can(actionPermissions[action])) return;
      this.userGeneration++; this.usersLoading = false; this.userError = ''; this.userSearch = ''; this.userPage = 1; this.userTotal = 0;
      this.targetLocked = Boolean(row); this.userOptions = row ? mergeAccountOptions([row]) : [];
      this.action = action; this.form = { requestId: newOperationId(), userId: exactUserId(row?.userId), amount: '', reason: '', frozen: !row?.frozen };
      this.saveError = row && !this.form.userId ? '无法确认用户身份，请刷新后重试' : ''; this.dialog = true;
      if (action === 'grant' && !row) this.searchUsers('');
    },
    searchUsers(keyword) { return this.loadUsers(String(keyword || '').trim(), false); },
    loadMoreUsers() { if (this.hasMoreUsers && !this.usersLoading) return this.loadUsers(this.userSearch, true); },
    async loadUsers(keyword, append) {
      if (this.targetLocked || !this.can('allowance.read')) return;
      const generation = ++this.userGeneration; const page = append ? this.userPage + 1 : 1;
      this.userSearch = keyword; this.usersLoading = true; this.userError = '';
      try {
        const result = await operations.accounts({ page, limit: 20, keyword });
        if (generation !== this.userGeneration) return;
        const selected = this.userOptions.filter(row => exactUserId(row.userId) === this.form.userId);
        this.userOptions = mergeAccountOptions([...(append ? this.userOptions : selected), ...(result.list || [])]);
        this.userPage = page; this.userTotal = paginationTotal(result.total);
      } catch (error) { if (generation === this.userGeneration) this.userError = error.message; }
      finally { if (generation === this.userGeneration) this.usersLoading = false; }
    },
    async save() {
      if (this.saving || !this.can(actionPermissions[this.action])) return;
      const selected = this.userOptions.some(row => exactUserId(row.userId) === this.form.userId);
      if (!this.form.reason.trim() || (this.action !== 'pool' && (!exactUserId(this.form.userId) || !selected))) { this.saveError = '请选择有效用户并填写操作原因'; return; }
      if (this.action !== 'freeze' && (!/^-?\d+(\.\d{1,6})?$/.test(this.form.amount) || Number(this.form.amount) === 0 || (this.action === 'grant' && Number(this.form.amount) < 0))) { this.saveError = '请填写有效的非零金额，发放金额必须大于零'; return; }
      this.saving = true; this.saveError = '';
      try {
        const { requestId, userId, amount, reason, frozen } = this.form;
        if (this.action === 'pool') await operations.adjustPool({ requestId, amount, reason });
        else if (this.action === 'freeze') await operations.freeze({ requestId, userId, frozen, reason });
        else await operations.grant({ requestId, userId, amount, reason });
        this.dialog = false; this.$message.success('操作已记录'); await this.load();
      } catch (error) { this.saveError = error.message; }
      finally { this.saving = false; }
    },
  },
};
</script>
<style scoped>
.allowance-search { flex: 1; max-width: 420px; flex-wrap: nowrap; }
.allowance-search .el-input { flex: 1; min-width: 0; }
.allowance-user { display: flex; align-items: baseline; gap: 10px; white-space: nowrap; }
.allowance-user strong { flex-shrink: 0; font-weight: 500; }
.allowance-user-id { color: #7b879b; font-size: 12px; }
.allowance-money { font-variant-numeric: tabular-nums; white-space: nowrap; }
.allowance-more { margin-left: 12px; }
.allowance-user-select { width: 100%; }
.allowance-legacy { margin-top: 16px; }
@media (max-width: 680px) { .allowance-search { max-width: none; width: 100%; } }
</style>
