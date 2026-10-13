<template>
  <el-drawer :title="row ? '配置供应商通道' : '新增供应商通道'" :visible="true" size="min(520px,96vw)" :wrapper-closable="!saving" :close-on-press-escape="!saving" :before-close="close">
    <div class="channel-editor">
      <el-alert v-if="modelError" :title="modelError" type="error" :closable="false" /><el-button v-if="modelError" size="small" @click="loadModels">重新加载模型</el-button>
      <el-form label-position="top" :disabled="saving" @submit.native.prevent="save">
        <el-form-item label="通道名称" required><el-input v-model="form.name" maxlength="100" placeholder="例如 DeepSeek 主通道" /></el-form-item>
        <el-form-item label="服务模型" required><el-select v-model="form.modelId" filterable :loading="modelsLoading" :disabled="!!row" placeholder="选择模型" style="width:100%"><el-option v-for="item in models" :key="item.id" :value="item.id" :label="modelLabel(item)" /></el-select><small v-if="selectedModel">{{ provider(selectedModel.provider) }} · {{ capability(selectedModel.serviceType) }}{{ selectedModel.published === false ? ' · 尚未发布' : '' }}</small></el-form-item>
        <el-form-item label="供应商账号" required><el-input v-model="form.accountRef" maxlength="200" placeholder="供应商账号或账单中的账号标识" /></el-form-item>
        <el-form-item :label="row && row.credentialConfigured ? '更新 API Key' : 'API Key'" :required="!row || !row.credentialConfigured"><el-input v-model="form.apiKey" type="password" autocomplete="new-password" :show-password="false" :placeholder="row && row.credentialConfigured ? '留空保留当前密钥' : '粘贴供应商 API Key'" maxlength="4096" /><small>仅保存到服务器，页面不回显密钥。</small></el-form-item>
        <el-form-item label="启用通道"><el-switch v-model="form.enabled" /><small class="channel-inline-note">启用后可用于模型调用</small></el-form-item>
        <el-collapse v-model="advanced"><el-collapse-item title="高级设置" name="settings"><p class="ops-muted">地域与项目用于费用核对；实际调用地址由模型配置决定。</p><el-form-item label="地域"><el-input v-model="form.region" maxlength="100" placeholder="按供应商配置填写（可选）" /></el-form-item><el-form-item label="项目"><el-input v-model="form.projectRef" maxlength="200" placeholder="项目标识（可选）" /></el-form-item><el-form-item label="平台通道并发上限"><el-input-number v-model="form.maxConcurrency" :min="1" :max="200" :precision="0" /><p class="ops-muted">仅限制本平台此通道的同时调用数，不等同于供应商账号总配额。</p></el-form-item></el-collapse-item></el-collapse>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div class="channel-editor-actions"><el-button :disabled="saving" @click="close">取消</el-button><el-button type="primary" :loading="saving" :disabled="!canWrite || modelsLoading || !!modelError" @click="save">保存通道</el-button></div>
    </div>
  </el-drawer>
</template>
<script>
import { operations } from '@/apis/platform';
import { hasPermission } from '@/security/session';
import { newOperationId } from '@/utils/allowance';
import { channelPayload, providerLabel, capabilityLabel } from './channel-display';
export default {
  props: { row: { type: Object, default: null } },
  data() { const row = this.row || {}; return { form: { name: row.name || '', modelId: row.modelId || '', accountRef: row.accountRef || '', region: row.region || '', projectRef: row.projectRef || '', enabled: row.enabled === true, apiKey: '', maxConcurrency: Number(row.maxConcurrency) || 20 }, models: [], modelsLoading: false, modelError: '', error: '', saving: false, advanced: [], requestId: newOperationId(), generation: 0 }; },
  computed: { canWrite() { return hasPermission(this.$store.state.userInfo, 'credentials.write'); }, selectedModel() { return this.models.find(model => model.id === this.form.modelId); } },
  watch: { form: { deep: true, handler() { if (!this.saving) this.requestId = newOperationId(); } } },
  created() { this.loadModels(); },
  beforeDestroy() { this.generation++; this.form.apiKey = ''; },
  methods: {
    provider: providerLabel, capability: capabilityLabel,
    modelLabel(item) { return `${item.name} · ${capabilityLabel(item.serviceType)}`; },
    async loadModels() { const generation = ++this.generation; this.modelsLoading = true; this.modelError = ''; try { const models = await operations.channelModels(); if (generation !== this.generation) return; this.models = Array.isArray(models) ? models : []; if (this.row && !this.models.some(model => model.id === this.row.modelId)) this.models.unshift({ id: this.row.modelId, name: this.row.modelName || this.row.modelId, provider: this.row.provider, serviceType: this.row.serviceType }); } catch (error) { if (generation === this.generation) this.modelError = error.message; } finally { if (generation === this.generation) this.modelsLoading = false; } },
    close(done) { if (this.saving) return; this.form.apiKey = ''; this.$emit('close'); if (typeof done === 'function') done(); },
    async save() {
      if (this.saving || !this.canWrite || this.modelsLoading || this.modelError) return;
      this.error = ''; let payload;
      try { payload = channelPayload(this.form, this.row, this.requestId); } catch (error) { this.error = error.message; return; }
      this.saving = true;
      try {
        if (this.row?.enabled && !payload.enabled) { try { await this.$confirm('停用后此通道不再接受新调用，确定停用？', '停用通道', { type: 'warning' }); } catch (_) { return; } }
        await operations.saveChannel(payload); this.form.apiKey = ''; this.$emit('saved'); this.$emit('close');
      } catch (error) { this.error = error.message; } finally { this.saving = false; }
    },
  },
};
</script>
<style scoped>
.channel-editor{padding:0 24px 24px;text-align:left}.channel-editor small{color:#7e8b98;line-height:1.6}.channel-editor .el-form-item{margin-bottom:18px}.channel-inline-note{margin-left:12px}.channel-editor-actions{display:flex;justify-content:flex-end;gap:8px;padding-top:24px}.channel-editor .el-alert{margin:14px 0}
</style>
