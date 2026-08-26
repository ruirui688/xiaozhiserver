<template>
  <el-dialog title="维护设置" :visible.sync="internalVisible" width="720px" top="6vh"
    :close-on-click-modal="false" @close="$emit('close')">
    <el-alert :closable="false" show-icon type="info"
      :title="resourceType === 'MEMORY' ? '长期记忆维护不联网，所有变更均需审核' : '设置只控制当前知识库；维护过程与提案都在当前智控台展示'" />
    <el-form class="maintenance-form" :model="form" label-width="120px" v-loading="loading">
      <el-form-item label="维护方式">
        <el-select v-model="form.mode" class="wide">
          <el-option label="关闭" value="OFF" />
          <template v-if="resourceType === 'KNOWLEDGE'">
            <el-option label="联网查找，人工审核" value="DISCOVERY_REVIEW" />
            <el-option label="可信域名自动采纳" value="DISCOVERY_AUTO" />
          </template>
          <el-option v-else label="分析记忆，人工审核" value="REVIEW" />
        </el-select>
      </el-form-item>
      <el-form-item label="DeepSeek 模型">
        <el-select v-model="form.modelConfigId" filterable class="wide" placeholder="复用模型管理中已测试通过的模型">
          <el-option v-for="model in modelOptions" :key="model.id" :label="model.label" :value="model.id" />
        </el-select>
        <div class="tip">API Key 继续由后台托管，不会发送到浏览器。</div>
      </el-form-item>
      <el-form-item v-if="resourceType === 'KNOWLEDGE'" label="关注主题">
        <el-input v-model="topicsText" type="textarea" :rows="3" maxlength="2400" placeholder="每行一个主题" />
      </el-form-item>
      <el-form-item v-if="resourceType === 'KNOWLEDGE'" label="初始提示词">
        <el-input v-model="form.customPrompt" type="textarea" :rows="5" maxlength="4000" show-word-limit
          placeholder="例如：只保留当前赛季、带版本号和发布日期的官方公告。" />
      </el-form-item>
      <el-form-item v-if="resourceType === 'KNOWLEDGE' && form.mode === 'DISCOVERY_AUTO'" label="可信域名">
        <el-input v-model="domainsText" type="textarea" :rows="3" maxlength="5000" placeholder="每行一个域名，不含协议和路径" />
      </el-form-item>
      <el-collapse class="advanced">
        <el-collapse-item title="运行频率与额度" name="limits">
          <el-form-item label="运行间隔"><el-input-number v-model="form.scheduleMinutes" :min="60" :max="10080" :step="60" /> 分钟</el-form-item>
          <el-form-item label="单次提案"><el-input-number v-model="form.maxProposals" :min="1" :max="20" /> 条</el-form-item>
          <el-form-item label="每月运行"><el-input-number v-model="form.maxMonthlyRuns" :min="1" :max="1000" /> 次</el-form-item>
        </el-collapse-item>
      </el-collapse>
      <el-form-item label="启用"><el-switch v-model="form.enabled" :disabled="form.mode === 'OFF'" /></el-form-item>
    </el-form>
    <span slot="footer">
      <el-button @click="internalVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save(false)">保存</el-button>
      <el-button type="success" :loading="saving" @click="save(true)">保存并进入 Harness</el-button>
    </span>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api'

export default {
  name: 'MaintenancePolicyPanel',
  props: {
    visible: { type: Boolean, default: false },
    resourceType: { type: String, required: true },
    resourceId: { type: String, required: true }
  },
  data() {
    return {
      internalVisible: false, loading: false, saving: false, topicsText: '', domainsText: '',
      form: { mode: 'OFF', scheduleMinutes: 360, maxProposals: 8, maxMonthlyRuns: 60, modelConfigId: '', customPrompt: '', enabled: false },
      modelOptions: []
    }
  },
  watch: { visible(value) { this.internalVisible = value; if (value) this.loadAll() } },
  methods: {
    payload(res) { return (res && res.data && res.data.data) || (res && res.data) || {} },
    fail(error, fallback) {
      const message = (error && error.data && error.data.msg) ||
        (error && error.response && error.response.data && error.response.data.msg) || (error && error.msg)
      this.$message.error(message || fallback)
    },
    list(json) { try { return Array.isArray(json) ? json : JSON.parse(json || '[]') } catch (_) { return [] } },
    lines(text) { return text.split(/\r?\n/).map(value => value.trim()).filter(Boolean) },
    loadAll() {
      this.loading = true
      this.loadModelOptions()
      Api.maintenance.policy(this.resourceType, this.resourceId, res => {
        const policy = this.payload(res)
        this.form = {
          mode: policy.mode || 'OFF', scheduleMinutes: policy.scheduleMinutes || 360,
          maxProposals: policy.maxProposals || 8, maxMonthlyRuns: policy.maxMonthlyRuns || 60,
          modelConfigId: policy.modelConfigId || '', customPrompt: policy.customPrompt || '',
          enabled: policy.enabled === 1 || policy.enabled === true
        }
        this.topicsText = this.list(policy.topicsJson).join('\n')
        this.domainsText = this.list(policy.trustedDomainsJson).join('\n')
        this.loading = false
      }, error => { this.loading = false; this.fail(error, '加载维护设置失败') })
    },
    loadModelOptions() {
      Api.model.getLlmModelCodeList('', ({ data }) => {
        const rows = data && data.code === 0 && Array.isArray(data.data) ? data.data : []
        this.modelOptions = rows.filter(item => /deepseek/i.test([
          item.modelName, item.modelCode, item.adapterCode, item.vendorName,
          item.productName, item.upstreamModelId, item.type
        ].filter(Boolean).join(' '))).map(item => ({
          id: item.id,
          label: `${item.modelName || item.upstreamModelId || item.id}${item.vendorName ? ` · ${item.vendorName}` : ''}`
        }))
      })
    },
    save(openAfterSave) {
      this.saving = true
      Api.maintenance.savePolicy(this.resourceType, this.resourceId, {
        ...this.form,
        topics: this.resourceType === 'KNOWLEDGE' ? this.lines(this.topicsText) : [],
        trustedDomains: this.resourceType === 'KNOWLEDGE' ? this.lines(this.domainsText) : []
      }, () => {
        this.saving = false
        this.$message.success('维护设置已保存')
        this.$emit('changed')
        if (openAfterSave) this.$emit('open-workbench')
        this.internalVisible = false
      }, error => { this.saving = false; this.fail(error, '保存维护设置失败') })
    }
  }
}
</script>

<style scoped>
.maintenance-form { margin-top: 22px; padding-right: 16px; }
.wide { width: 100%; }
.tip { color: #909399; font-size: 12px; line-height: 20px; }
.advanced { margin: 0 0 18px 24px; }
</style>
