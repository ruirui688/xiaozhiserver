<template>
  <el-dialog title="数据接入与归属管理" :visible="visible && canManage" width="min(1100px, 94vw)" :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy" @close="$emit('close')">
    <div v-if="canManage" class="billing-admin">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-alert v-if="result" :title="result" type="success" :closable="false" show-icon />
      <el-tabs v-model="tab">
        <el-tab-pane label="数据来源与采集" name="sync">
          <BillingAdminHealth v-if="visible && canManage" />
          <h3>火山引擎官方账单</h3>
          <p class="hint">同步供应商提供的账单金额；未匹配归属的项目保留在平台账单中。</p>
          <p class="hint">当前同步账号：{{ options.volcengine && options.volcengine.accountRef || '尚未配置' }}。账号费用可直接查看，工作空间归属不是查看账号账单的前提。</p>
          <div class="inline-controls">
            <el-date-picker v-model="billPeriod" type="month" value-format="yyyy-MM" placeholder="选择账期" :clearable="false" />
            <el-button type="primary" :loading="busy" :disabled="options.volcengineSyncEnabled !== true || busy || !billPeriod" @click="sync">同步账单</el-button>
          </div>
          <p v-if="!options.volcengineSyncEnabled" class="hint">尚未开通火山引擎账单同步，可先导入官方账单。</p>
          <details ref="importPanel" class="admin-disclosure" :open="importExpanded" @toggle="importExpanded = $event.target.open"><summary>导入官方消费凭证</summary>
          <p class="hint">只导入供应商确认的金额。用量报表、自行算价或按比例分摊的金额不能作为实际消费导入。请按模板整理供应商账单原始字段，保留账单标识与币种。人工导入会单独标记，系统无法自动核验文件是否来自供应商。</p>
          <p class="hint">当前仅支持本项目字段模板，不支持供应商原生导出自动识别。请保留真实官方金额、币种、完整消费期间、账单标识和官方凭证引用；只有 Token 用量的文件不能用于导入费用，也不能根据 Token 自行估算金额。</p>
          <el-form label-width="110px" class="import-form">
            <el-form-item label="供应商"><el-select v-model="importProvider" :disabled="busy"><el-option label="火山引擎" value="VOLCENGINE" /><el-option label="DeepSeek" value="DEEPSEEK" /></el-select><el-button type="text" :disabled="busy" @click="downloadTemplate">下载字段模板</el-button></el-form-item>
            <el-form-item label="账号标签"><el-input v-model="accountRef" maxlength="128" placeholder="与归属映射一致的非敏感账号标签" :disabled="busy" /></el-form-item>
            <el-form-item label="导入后查看"><el-date-picker v-model="importPeriod" type="month" value-format="yyyy-MM" :clearable="false" :disabled="busy" /><small class="hint">导入后返回此账期查看；实际账期以 CSV 内的官方凭证为准，不改写账单。</small></el-form-item>
            <el-form-item label="CSV 文件"><input ref="file" type="file" accept=".csv,text/csv" :disabled="busy" @change="selectFile" /><small class="hint">UTF-8 编码，最大 2 MB；不上传密钥或凭据。</small></el-form-item>
            <el-form-item><el-checkbox v-model="confirmOfficial" :disabled="busy">确认金额来自供应商官方账单</el-checkbox></el-form-item>
            <el-form-item><el-button type="primary" :loading="busy" :disabled="busy || !file || !accountRef.trim() || !confirmOfficial" @click="importBill">导入账单</el-button></el-form-item>
          </el-form>
          </details>
        </el-tab-pane>
        <el-tab-pane label="高级：工作空间归属" name="bindings">
          <div class="section-heading"><p class="hint">按供应商项目、资源或独占账号，将指定有效期内的账单归属到当前工作空间。只在证据足够时选择智能体或设备。</p><el-button :disabled="busy" @click="loadBindings">刷新</el-button></div>
          <el-table :data="bindings" v-loading="loading" empty-text="当前工作空间暂无归属映射">
            <el-table-column label="供应商 / 账号" min-width="130"><template slot-scope="scope">{{ label(scope.row.provider) }}<small class="hint">{{ scope.row.accountRef }}</small></template></el-table-column>
            <el-table-column label="供应商项目 / 资源" min-width="150"><template slot-scope="scope">{{ scopeName(scope.row.scopeType) }}<small class="hint">{{ scope.row.scopeValue }}</small></template></el-table-column>
            <el-table-column label="归属" min-width="160"><template slot-scope="scope">{{ resourceName(scope.row) }}</template></el-table-column>
            <el-table-column label="有效期（北京时间）" min-width="190"><template slot-scope="scope">{{ time(scope.row.validFrom) }}<small class="hint">至 {{ scope.row.validTo ? time(scope.row.validTo) : '长期有效' }}</small></template></el-table-column>
            <el-table-column label="操作" width="80"><template slot-scope="scope"><el-button type="text" :disabled="busy" @click="removeBinding(scope.row)">撤销</el-button></template></el-table-column>
          </el-table>
          <el-pagination :current-page="bindingPage" :page-size="20" :total="bindingTotal" :disabled="busy" layout="total, prev, pager, next" @current-change="changeBindingPage" />
          <el-divider>新增归属映射</el-divider>
          <el-form label-width="120px" class="binding-form">
            <el-form-item label="供应商"><el-select v-model="form.provider"><el-option label="火山引擎" value="VOLCENGINE" /><el-option label="DeepSeek" value="DEEPSEEK" /></el-select></el-form-item>
            <el-form-item label="账号标签"><el-input v-model="form.accountRef" maxlength="128" placeholder="供应商账号的非敏感标签" /></el-form-item>
            <el-form-item label="归属依据"><el-select v-model="form.scopeType"><el-option label="供应商项目" value="PROJECT" /><el-option label="供应商资源" value="RESOURCE" /><el-option label="独占账号" value="ACCOUNT" /></el-select></el-form-item>
            <el-form-item label="项目 / 资源标识"><el-input v-model="form.scopeValue" maxlength="256" placeholder="与官方账单字段一致的稳定标识" /></el-form-item>
            <el-form-item label="智能体（可选）"><el-select v-model="form.agentId" clearable filterable placeholder="不指定则归属工作空间"><el-option v-for="item in options.agents || []" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="设备（可选）"><el-select v-model="form.deviceId" clearable filterable placeholder="仅限该资源由此设备独占使用"><el-option v-for="item in options.devices || []" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="有效期开始"><el-date-picker v-model="form.validFrom" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" placeholder="北京时间" /></el-form-item>
            <el-form-item label="有效期结束"><el-date-picker v-model="form.validTo" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" placeholder="留空为长期有效" /></el-form-item>
            <el-form-item label="依据说明" class="full"><el-input v-model="form.note" type="textarea" :rows="2" maxlength="500" placeholder="说明该项目、资源或账号由此归属主体独占使用的依据" /></el-form-item>
          </el-form>
          <p class="hint">映射不会按用量比例拆分账单。已用于账单归属的映射不能直接删除；变更应使用不重叠的有效期。</p>
          <p class="hint">新映射保存后，请重新同步或导入对应账期，让官方账单重新匹配归属。独占使用依据不足时保留未归属。</p>
          <el-button type="primary" :loading="busy" :disabled="busy" @click="saveBinding">保存映射</el-button>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>
</template>
<script>
import Api from '@/apis/api';
import BillingAdminHealth from './BillingAdminHealth.vue';
import { billingLabel, billingTime, chinaDate, decimalText } from '@/utils/billing';

function newBinding() {
  return { provider: 'VOLCENGINE', accountRef: '', scopeType: 'PROJECT', scopeValue: '', agentId: '', deviceId: '', validFrom: `${chinaDate().slice(0, 7)}-01 00:00:00`, validTo: '', note: '' };
}

export default {
  components: { BillingAdminHealth },
  props: { visible: Boolean, options: { type: Object, default: () => ({}) }, workspaceId: String, importContext: { type: Object, default: null } },
  data() { return { tab: 'sync', billPeriod: chinaDate().slice(0, 7), busy: false, loading: false, error: '', result: '', bindings: [], bindingPage: 1, bindingTotal: 0, generation: 0, form: newBinding(), importProvider: 'VOLCENGINE', accountRef: '', importPeriod: chinaDate().slice(0, 7), importExpanded: false, file: null, confirmOfficial: false }; },
  computed: { canManage() { return this.options.canManage === true; } },
  watch: {
    visible(value) { this.generation++; this.busy = false; this.loading = false; this.error = ''; this.result = ''; this.resetImportFile(); if (value && this.canManage) { this.prepareImport(); this.loadBindings(); } },
    importContext() { if (this.visible && !this.busy) this.prepareImport(); },
    importProvider() { this.resetImportFile(); },
    accountRef() { this.resetImportFile(); },
    workspaceId() { this.generation++; this.bindings = []; this.bindingTotal = 0; this.form = newBinding(); this.resetImportFile(); this.$emit('close'); }
  },
  beforeDestroy() { this.generation++; },
  methods: {
    resourceName(row) { const list = row.deviceId ? this.options.devices || [] : this.options.agents || []; const item = list.find(item => item.id === (row.deviceId || row.agentId)); return item && item.name || (row.deviceId ? '历史设备' : row.agentId ? '历史智能体' : this.options.workspaceName || '整个工作空间'); },
    label: billingLabel, time: billingTime,
    resetImportFile() { this.file = null; this.confirmOfficial = false; if (this.$refs && this.$refs.file) this.$refs.file.value = ''; },
    prepareImport() {
      this.resetImportFile();
      const context = this.importContext;
      this.importExpanded = !!context;
      if (!context || !['VOLCENGINE', 'DEEPSEEK'].includes(context.provider) || typeof context.accountRef !== 'string' || !context.accountRef.trim()) return;
      this.tab = 'sync'; this.importProvider = context.provider; this.accountRef = context.accountRef;
      this.importPeriod = /^\d{4}-(0[1-9]|1[0-2])$/.test(context.billPeriod || '') ? context.billPeriod : chinaDate().slice(0, 7);
      this.$nextTick(() => { if (this.visible && this.$refs.importPanel) this.$refs.importPanel.scrollIntoView({ block: 'nearest' }); });
    },
    scopeName(value) { return ({ PROJECT: '供应商项目', RESOURCE: '供应商资源', ACCOUNT: '独占账号' })[value] || value; },
    current(generation) { return generation === this.generation && this.canManage && this.workspaceId === localStorage.getItem('currentWorkspaceId'); },
    async loadBindings() {
      if (!this.canManage || this.busy) return;
      const generation = ++this.generation; this.loading = true; this.error = '';
      try { const data = await Api.billing.bindings({ page: this.bindingPage, limit: 20 }); if (this.current(generation)) { this.bindings = data.list || []; this.bindingTotal = data.total || 0; } }
      catch (error) { if (this.current(generation)) { this.bindings = []; this.bindingTotal = 0; this.error = error.message; } }
      finally { if (this.current(generation)) this.loading = false; }
    },
    changeBindingPage(value) { if (this.busy) return; this.bindingPage = value; this.loadBindings(); },
    updateCurrent(generation, context) { return this.current(generation) && (!context || (context.provider === this.importProvider && context.accountRef === this.accountRef.trim() && context.billPeriod === this.importPeriod)); },
    async perform(action, success, context = null) {
      if (!this.canManage || this.busy || this.workspaceId !== localStorage.getItem('currentWorkspaceId')) return;
      const generation = this.generation; this.busy = true; this.error = ''; this.result = '';
      try { const data = await action(); if (this.updateCurrent(generation, context)) { this.result = typeof success === 'function' ? success(data) : success; this.$emit('updated', context ? { ...context, message: this.result } : null); } return data; }
      catch (error) { if (this.updateCurrent(generation, context)) this.error = error.message; }
      finally { if (generation === this.generation) this.busy = false; }
    },
    report(data) { return `完成：处理记录 ${decimalText(data.importedCount)} 条（含新增及覆盖修订）`; },
    sync() {
      if (this.options.volcengineSyncEnabled !== true || !/^\d{4}-(0[1-9]|1[0-2])$/.test(this.billPeriod || '')) return;
      const accountRef = this.options.volcengine && this.options.volcengine.accountRef;
      return this.perform(() => Api.billing.sync({ billPeriod: this.billPeriod, ...(accountRef ? { provider: 'VOLCENGINE', accountRef } : {}) }), this.report);
    },
    selectFile(event) {
      const file = event.target.files && event.target.files[0]; this.error = ''; this.file = null; this.confirmOfficial = false;
      if (!file) return;
      if (!/\.csv$/i.test(file.name) || file.size > 2 * 1024 * 1024 || !file.size) { this.error = '请选择不超过 2 MB 的非空 CSV 文件'; event.target.value = ''; return; }
      this.file = file;
    },
    importBill() {
      if (!this.file || !this.accountRef.trim() || !this.confirmOfficial || !/^\d{4}-(0[1-9]|1[0-2])$/.test(this.importPeriod || '')) return;
      const context = { type: 'import', provider: this.importProvider, accountRef: this.accountRef.trim(), billPeriod: this.importPeriod };
      return this.perform(() => Api.billing.importBill(this.file, context.provider, context.accountRef), this.report, context);
    },
    async downloadTemplate() {
      if (!this.canManage || this.busy) return;
      const provider = this.importProvider;
      const generation = this.generation;
      const data = await this.perform(() => Api.billing.importTemplate(provider), '字段模板已下载');
      if (!data || !this.visible || !this.current(generation)) return;
      const url = URL.createObjectURL(data); const link = document.createElement('a'); link.href = url; link.download = `${provider.toLowerCase()}-official-bill.csv`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
    async saveBinding() {
      if (!this.form.accountRef.trim() || !this.form.scopeValue.trim() || !this.form.validFrom) { this.error = '请填写账号标签、项目或资源标识及有效期开始时间'; return; }
      const instant = value => value ? new Date(`${value.replace(' ', 'T')}+08:00`).toISOString() : null;
      let validFrom, validTo;
      try { validFrom = instant(this.form.validFrom); validTo = instant(this.form.validTo); } catch (_) { this.error = '有效期日期无效'; return; }
      if (validTo && validTo <= validFrom) { this.error = '有效期结束必须晚于开始'; return; }
      const payload = { ...this.form, accountRef: this.form.accountRef.trim(), scopeValue: this.form.scopeValue.trim(), workspaceId: this.workspaceId, validFrom, validTo, agentId: this.form.agentId || null, deviceId: this.form.deviceId || null };
      await this.perform(() => Api.billing.createBinding(payload), '归属映射已保存');
      if (!this.error && !this.busy) { this.form = newBinding(); this.loadBindings(); }
    },
    async removeBinding(row) {
      if (!this.canManage || this.busy) return;
      const workspaceId = this.workspaceId;
      try { await this.$confirm('撤销这条归属映射？已用于账单归属的映射会由服务端拒绝撤销。', '撤销映射', { type: 'warning' }); } catch (_) { return; }
      if (workspaceId !== this.workspaceId) return;
      await this.perform(() => Api.billing.deleteBinding(row.id), '归属映射已撤销');
      if (!this.error && !this.busy) this.loadBindings();
    }
  }
};
</script>
<style scoped>
.billing-admin { text-align: left; }.billing-admin> .el-alert { margin-bottom: 16px; }.hint { color: #8790a3; font-size: 12px; line-height: 1.8; }small.hint { display: block; overflow-wrap: anywhere; }h3 { color: #3c465d; margin: 20px 0 8px; }.inline-controls { display: flex; gap: 12px; flex-wrap: wrap; }.import-form { max-width: 640px; }.import-form .el-button--text { margin-left: 12px; }.binding-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }.binding-form .el-select, .binding-form .el-date-editor { width: 100%; }.binding-form .full { grid-column: 1 / -1; }.section-heading { display: flex; gap: 20px; align-items: center; }.el-pagination { margin-top: 16px; text-align: right; }@media(max-width: 760px) { .binding-form { grid-template-columns: 1fr; } }
</style>
