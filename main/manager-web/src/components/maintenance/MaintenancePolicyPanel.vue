<template>
  <el-dialog
    title="DeepSeek Harness 自主维护"
    :visible.sync="internalVisible"
    width="1200px"
    top="5vh"
    :close-on-click-modal="false"
    @close="$emit('close')">
    <el-alert
      :type="resourceType === 'MEMORY' ? 'warning' : 'info'"
      :closable="false"
      show-icon
      :title="resourceType === 'MEMORY' ? '记忆维护不会联网，所有变更必须人工审批' : '此能力只作用于当前知识库，默认只生成网页来源提案'"
      description="Harness 无数据库、Shell、编辑器或宿主源码权限；Manager API 负责配额、权限、审批、URL 安全校验和最终落库。" />

    <el-tabs v-model="tab" class="maintenance-tabs">
      <el-tab-pane label="策略" name="policy">
        <el-form :model="form" label-width="145px" v-loading="loading">
          <el-form-item label="维护模式">
            <el-select v-model="form.mode" style="width: 320px">
              <el-option label="关闭" value="OFF" />
              <template v-if="resourceType === 'KNOWLEDGE'">
                <el-option label="自主搜索，人工审批" value="DISCOVERY_REVIEW" />
                <el-option label="可信域名自动采纳" value="DISCOVERY_AUTO" />
              </template>
              <el-option v-else label="分析记忆，人工审批" value="REVIEW" />
            </el-select>
          </el-form-item>
          <el-form-item label="维护模型">
            <el-select v-model="form.modelConfigId" filterable style="width: 420px" placeholder="选择模型管理中已测试通过的 DeepSeek 模型">
              <el-option v-for="model in modelOptions" :key="model.id" :label="model.label" :value="model.id" />
            </el-select>
            <div class="tip">直接复用模型管理中的端点、上游模型名和托管凭据，不需要重复填写 API Key。</div>
          </el-form-item>
          <el-form-item v-if="resourceType === 'KNOWLEDGE'" label="关注主题">
            <el-input v-model="topicsText" type="textarea" :rows="3" maxlength="2400" placeholder="每行一个，例如：金铲铲之战 版本公告" />
          </el-form-item>
          <el-form-item v-if="resourceType === 'KNOWLEDGE'" label="初始维护提示词">
            <el-input v-model="form.customPrompt" type="textarea" :rows="6" maxlength="4000" show-word-limit
              placeholder="定义长期质量标准，例如：只采用当前赛季、带明确版本号和发布日期的官方公告；排除旧版本、赛事投票、营销活动和重复页面。" />
            <div class="tip">这是可编辑的业务指令。系统固定的 URL 安全、审批、配额和结构化输出规则不会被覆盖。</div>
          </el-form-item>
          <el-form-item v-if="resourceType === 'KNOWLEDGE'" label="可信域名白名单">
            <el-input v-model="domainsText" type="textarea" :rows="3" maxlength="5000" placeholder="每行一个域名，不要填写协议或路径；自动采纳模式必填" />
            <div class="tip">仅自动采纳白名单域名；所有候选 URL 仍会经过公网地址和 SSRF 校验。</div>
          </el-form-item>
          <el-form-item label="运行间隔">
            <el-input-number v-model="form.scheduleMinutes" :min="60" :max="10080" :step="60" /> 分钟
          </el-form-item>
          <el-form-item label="单次最多提案">
            <el-input-number v-model="form.maxProposals" :min="1" :max="20" />
          </el-form-item>
          <el-form-item label="每月最多运行">
            <el-input-number v-model="form.maxMonthlyRuns" :min="1" :max="1000" />
          </el-form-item>
          <el-form-item label="启用当前资源">
            <el-switch v-model="form.enabled" :disabled="form.mode === 'OFF'" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存策略</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="维护对话" name="conversation">
        <MaintenanceConversationPanel ref="conversation" :active="internalVisible && tab === 'conversation'"
          :resource-type="resourceType" :resource-id="resourceId" @changed="$emit('changed')" />
      </el-tab-pane>
    </el-tabs>
    <span slot="footer"><el-button @click="internalVisible = false">关闭</el-button></span>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api'
import MaintenanceConversationPanel from './MaintenanceConversationPanel.vue'

export default {
  name: 'MaintenancePolicyPanel',
  components: { MaintenanceConversationPanel },
  props: {
    visible: { type: Boolean, default: false },
    resourceType: { type: String, required: true },
    resourceId: { type: String, required: true }
  },
  data() {
    return {
      internalVisible: false,
      tab: 'policy',
      loading: false,
      saving: false,
      topicsText: '',
      domainsText: '',
      form: { mode: 'OFF', scheduleMinutes: 360, maxProposals: 8, maxMonthlyRuns: 60, modelConfigId: '', customPrompt: '', enabled: false },
      modelOptions: []
    }
  },
  watch: {
    visible(value) {
      this.internalVisible = value
      if (value) this.loadAll()
    }
  },
  methods: {
    payload(res) { return (res && res.data && res.data.data) || (res && res.data) || {} },
    fail(error, fallback) {
      const message = (error && error.data && error.data.msg) ||
        (error && error.response && error.response.data && error.response.data.msg) ||
        (error && error.msg)
      this.$message.error(message || fallback)
    },
    list(json) {
      try { return Array.isArray(json) ? json : JSON.parse(json || '[]') } catch (_) { return [] }
    },
    lines(text) { return text.split(/\r?\n/).map(v => v.trim()).filter(Boolean) },
    loadAll() {
      this.loading = true
      this.loadModelOptions()
      Api.maintenance.policy(this.resourceType, this.resourceId, res => {
        const policy = this.payload(res)
        this.form = {
          mode: policy.mode || 'OFF',
          scheduleMinutes: policy.scheduleMinutes || 360,
          maxProposals: policy.maxProposals || 8,
          maxMonthlyRuns: policy.maxMonthlyRuns || 60,
          modelConfigId: policy.modelConfigId || '',
          customPrompt: policy.customPrompt || '',
          enabled: policy.enabled === 1 || policy.enabled === true
        }
        this.topicsText = this.list(policy.topicsJson).join('\n')
        this.domainsText = this.list(policy.trustedDomainsJson).join('\n')
        this.tab = policy.enabled === 1 || policy.enabled === true ? 'conversation' : 'policy'
        this.loading = false
      }, error => {
        this.loading = false
        this.fail(error, '加载自主维护策略失败')
      })
    },
    loadModelOptions() {
      Api.model.getLlmModelCodeList('', ({ data }) => {
        const rows = data && data.code === 0 && Array.isArray(data.data) ? data.data : []
        this.modelOptions = rows.filter(item => {
          const identity = [item.modelName, item.modelCode, item.adapterCode, item.vendorName,
            item.productName, item.upstreamModelId, item.type].filter(Boolean).join(' ')
          return /deepseek/i.test(identity)
        }).map(item => ({
          id: item.id,
          label: `${item.modelName || item.upstreamModelId || item.id}${item.vendorName ? ` · ${item.vendorName}` : ''}`
        }))
      })
    },
    save() {
      this.saving = true
      Api.maintenance.savePolicy(this.resourceType, this.resourceId, {
        ...this.form,
        topics: this.resourceType === 'KNOWLEDGE' ? this.lines(this.topicsText) : [],
        trustedDomains: this.resourceType === 'KNOWLEDGE' ? this.lines(this.domainsText) : []
      }, res => {
        this.saving = false
        this.$message.success('自主维护策略已保存')
        this.loadAll()
      }, error => { this.saving = false; this.fail(error, '保存自主维护策略失败') })
    }
  }
}
</script>

<style scoped>
.maintenance-tabs { margin-top: 18px; }
.tip { color: #909399; font-size: 12px; line-height: 20px; }
</style>
