<template><div class="ops-page"><HeaderBar /><main class="ops-main"><div class="ops-toolbar"><div><span class="ops-eyebrow">SUPPLIER COSTS</span><h1>供应商实际成本</h1><p class="ops-lead">官方账单、账号余额和资源权益分别核对。平台服务额度在「额度与凭据」管理。</p></div><el-button v-if="can('supplier.bill.sync') || can('supplier.bill.import')" @click="dialog=true">同步与导入</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-tabs :value="tab" @input="changeTab"><el-tab-pane label="实际费用" name="costs" /><el-tab-pane label="余额与资源包" name="benefits" /></el-tabs><BillingAccountOverview v-if="tab === 'costs'" :key="revision" :accounts="accounts" :show-workspace-usage="false" :can-import="can('supplier.bill.import')" @manage="openImport" @benefits="openBenefits" /><BillingBenefits v-else :key="`${account ? account.accountRef : 'all'}-${revision}`" workspace-id="" scope="account" :account="account" :can-manage="true" standalone-account :can-sync="can('supplier.bill.sync')" /><el-dialog title="官方成本数据接入" :visible.sync="dialog" width="min(570px,94vw)" :close-on-click-modal="false"><p class="ops-muted">只记录官方凭证金额；资源包保持查询，不执行购买、续费或切换。</p><el-alert v-if="actionError" :title="actionError" type="error" :closable="false" /><section v-if="can('supplier.bill.sync')"><h3>同步火山引擎账单</h3><div class="ops-actions"><el-date-picker v-model="period" type="month" value-format="yyyy-MM" :clearable="false" /><el-button :loading="busy" :disabled="options.volcengineSyncEnabled !== true" @click="sync">同步账单</el-button></div><p v-if="!options.volcengineSyncEnabled" class="ops-muted">同步尚未配置，已有快照仍可查看。</p></section><section v-if="can('supplier.bill.import')"><h3>导入官方费用凭证</h3><p class="ops-muted">当前接收本项目 CSV 字段模板；保留官方账单标识、金额、币种及来源。Token 数或余额变化不能代替消费金额。</p><el-form label-width="90px"><el-form-item label="供应商"><el-select v-model="form.provider"><el-option label="DeepSeek" value="DEEPSEEK" /><el-option label="火山引擎" value="VOLCENGINE" /></el-select></el-form-item><el-form-item label="账号标签"><el-input v-model="form.accountRef" /></el-form-item><el-form-item label="CSV 文件"><input ref="file" type="file" accept=".csv,text/csv" @change="selectFile" /></el-form-item><el-form-item><el-checkbox v-model="confirmed">金额来自供应商官方消费凭证</el-checkbox></el-form-item></el-form><div class="ops-actions"><el-button :disabled="busy" @click="template">下载字段模板</el-button><el-button type="primary" :loading="busy" :disabled="!confirmed || !file || !form.accountRef.trim()" @click="importBill">导入凭证</el-button></div></section></el-dialog></main></div></template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import BillingAccountOverview from '@/components/billing/BillingAccountOverview.vue';
import BillingBenefits from '@/components/billing/BillingBenefits.vue';
import Api from '@/apis/api';
import { hasPermission } from '@/security/session';
import { chinaDate } from '@/utils/billing';
import '@/assets/styles/billing.css';
export default {
  components: { HeaderBar, BillingAccountOverview, BillingBenefits },
  data() { return { accounts: [], options: {}, error: '', account: null, dialog: false, form: { provider: 'DEEPSEEK', accountRef: '' }, file: null, confirmed: false, busy: false, actionError: '', period: chinaDate().slice(0,7), revision: 0 }; },
  computed: { tab() { return this.$route.query.view === 'benefits' ? 'benefits' : 'costs'; } },
  created() { this.load(); },
  watch: { 'form.provider'() { this.resetFile(); }, 'form.accountRef'() { this.resetFile(); } },
  methods: {
    can(permission) { return hasPermission(this.$store.state.userInfo, permission); },
    async load() { this.error = ''; try { const [accounts,options] = await Promise.all([Api.billing.accounts(),Api.billing.options()]); this.accounts = accounts.list || []; this.options = options; } catch (error) { this.error = error.message; } },
    changeTab(view) { if (view !== this.tab) this.$router.replace({ path: '/model-billing', query: { ...this.$route.query, view } }); },
    openBenefits(account) { this.account = account; this.changeTab('benefits'); },
    openImport(context) { if (!this.can('supplier.bill.import') && !this.can('supplier.bill.sync')) return; if (context) { this.form.provider = context.provider; this.form.accountRef = context.accountRef; } this.dialog = true; },
    resetFile() { this.file = null; this.confirmed = false; if (this.$refs.file) this.$refs.file.value = ''; },
    selectFile(event) { const file = event.target.files?.[0]; this.file = null; this.confirmed = false; if (file && (!/\.csv$/i.test(file.name) || !file.size || file.size > 2 * 1024 * 1024)) { this.actionError = '请选择不超过 2 MB 的非空 CSV 文件'; return; } this.file = file; },
    async perform(action) { if (this.busy) return; this.busy = true; this.actionError = ''; try { const result = await action(); this.revision++; await this.load(); this.$message.success('已完成数据接入'); return result; } catch (error) { this.actionError = error.message; } finally { this.busy = false; } },
    sync() { if (this.can('supplier.bill.sync')) return this.perform(() => Api.billing.sync({ billPeriod: this.period })); },
    importBill() { if (!this.confirmed || !this.file || !this.can('supplier.bill.import')) return; return this.perform(async () => { const result = await Api.billing.importBill(this.file,this.form.provider,this.form.accountRef.trim()); this.resetFile(); return result; }); },
    async template() { if (this.busy) return; this.busy = true; this.actionError = ''; try { const blob = await Api.billing.importTemplate(this.form.provider); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `${this.form.provider.toLowerCase()}-official-bill.csv`; link.click(); setTimeout(() => URL.revokeObjectURL(url),1000); } catch (error) { this.actionError = error.message; } finally { this.busy = false; } },
  },
};
</script>
