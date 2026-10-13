<template>
  <section class="ops-panel">
    <div class="ops-toolbar"><div><h2>服务定价</h2><p class="ops-muted">按实际用量扣减平台额度，与供应商官方账单分别核算。</p></div><div class="ops-actions"><el-button v-if="canWrite" type="primary" size="small" @click="open">发布价格</el-button><el-button size="small" :loading="loading" @click="load">刷新</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table v-else :data="rows" v-loading="loading" empty-text="暂无服务定价，请选择模型发布价格">
      <el-table-column label="模型" min-width="180"><template slot-scope="s">{{ modelName(s.row.modelId) }}</template></el-table-column>
      <el-table-column prop="version" label="价格版本" min-width="130" />
      <el-table-column label="单位扣减（元）" min-width="210"><template slot-scope="s"><div v-for="(value,key) in s.row.metricRates" :key="key">{{ metric(key) }}：{{ value }} / {{ unit(key) }}</div></template></el-table-column>
      <el-table-column label="单次调用上限" min-width="190"><template slot-scope="s"><div v-for="(value,key) in s.row.maxUsage" :key="key">{{ metric(key) }}：{{ value }} {{ unit(key) }}</div></template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 20" :current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="changePage" />
    <el-dialog title="发布平台服务价格" :visible.sync="dialog" width="min(580px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-alert v-if="modelError" :title="modelError" type="error" :closable="false" /><el-button v-if="modelError" size="small" @click="loadModels">重新加载模型</el-button>
      <el-form label-position="top" :disabled="saving">
        <el-form-item label="服务模型" required><el-select v-model="form.modelId" filterable :loading="modelsLoading" style="width:100%" placeholder="选择模型" @change="chooseModel"><el-option v-for="item in models" :key="item.id" :value="item.id" :label="item.name + ' · ' + capability(item.serviceType)" /></el-select></el-form-item>
        <el-form-item label="新价格版本" required><el-input v-model="form.version" maxlength="64" placeholder="例如 2026-10-v1" /></el-form-item>
        <p v-if="fields.length" class="ops-muted">按下列基础单位填写，保存后仅对后续新调用生效。</p>
        <div v-for="field in fields" :key="field.key" class="rate-input-row"><el-form-item :label="field.label + '价格（元 / ' + field.unit + '）'" required><el-input v-model="form.prices[field.key]" inputmode="decimal" placeholder="填写单位价格" /></el-form-item><el-form-item :label="'单次上限（' + field.unit + '）'" required><el-input v-model="form.maximums[field.key]" inputmode="numeric" placeholder="正整数" /></el-form-item></div>
      </el-form>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" /><span slot="footer"><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!canWrite || modelsLoading || !!modelError || !fields.length" @click="save">发布新版本</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import { operations } from '@/apis/platform';
import { hasPermission } from '@/security/session';
import { paginationTotal } from './service-display';
import { capabilityLabel } from './channels/channel-display';
import { metricDefinitions, fieldsForService, parseMetricMap, ratePayload } from './channels/rate-fields';
export default {
  data() { return { rows: [], models: [], loading: false, error: '', modelsLoading: false, modelError: '', dialog: false, form: { modelId: '', version: '', prices: {}, maximums: {} }, saving: false, saveError: '', page: 1, total: 0, generation: 0 }; },
  computed: { canWrite() { return hasPermission(this.$store.state.userInfo, 'rates.write'); }, fields() { return fieldsForService(this.models.find(model => model.id === this.form.modelId)?.serviceType); } },
  created() { this.load(); this.loadModels(); }, beforeDestroy() { this.generation++; },
  methods: {
    capability: capabilityLabel,
    metric(key) { return metricDefinitions[key]?.label || key; }, unit(key) { return metricDefinitions[key]?.unit || '原计量单位'; },
    modelName(id) { return this.models.find(model => model.id === id)?.name || id; },
    async loadModels() { this.modelsLoading = true; this.modelError = ''; try { this.models = await operations.channelModels(); } catch (error) { this.modelError = error.message; } finally { this.modelsLoading = false; } },
    async load() { const generation = ++this.generation; this.loading = true; this.error = ''; try { const result = await operations.rates({ page: this.page, limit: 20 }); if (generation !== this.generation) return; this.rows = (result.list || []).map(row => ({ ...row, metricRates: parseMetricMap(row.metricRates || row.metricRatesJson), maxUsage: parseMetricMap(row.maxUsage || row.maxUsageJson) })); this.total = paginationTotal(result.total); } catch (error) { if (generation === this.generation) this.error = error.message; } finally { if (generation === this.generation) this.loading = false; } },
    changePage(page) { this.page = page; return this.load(); },
    open() { if (!this.canWrite) return; this.saveError = ''; this.form = { modelId: '', version: '', prices: {}, maximums: {} }; this.dialog = true; if (this.modelError) this.loadModels(); },
    chooseModel() { this.form.prices = Object.fromEntries(this.fields.map(field => [field.key, ''])); this.form.maximums = Object.fromEntries(this.fields.map(field => [field.key, ''])); },
    async save() { if (this.saving || !this.canWrite || this.modelsLoading || this.modelError) return; this.saveError = ''; let payload; try { payload = ratePayload(this.form, this.fields); } catch (error) { this.saveError = error.message; return; } this.saving = true; try { await operations.addRate(payload); this.dialog = false; await this.load(); } catch (error) { this.saveError = error.message; } finally { this.saving = false; } },
  },
};
</script>
<style scoped>
.rate-input-row{display:grid;grid-template-columns:1fr 1fr;gap:14px}.ops-panel>.el-pagination{text-align:right;margin-top:14px}@media(max-width:460px){.rate-input-row{grid-template-columns:1fr;gap:0}}
</style>
