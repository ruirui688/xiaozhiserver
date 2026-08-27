<template>
  <section class="native-harness-panel">
    <header class="native-toolbar">
      <div>
        <strong>DeepSeek Harness 原生控制台</strong>
        <span>会话与消息由 Harness 原生界面管理；管理台只负责资源授权和提案审批。</span>
      </div>
      <div class="toolbar-actions">
        <el-button size="small" icon="el-icon-time" @click="runsVisible = true">维护记录</el-button>
        <el-button size="small" icon="el-icon-document-checked" @click="proposalsVisible = true">审核提案</el-button>
        <el-button size="small" icon="el-icon-refresh" :loading="launching" @click="launch(true)">重新连接</el-button>
      </div>
    </header>

    <div class="console-host" v-loading="launching" element-loading-text="正在安全启动原生 Harness…">
      <iframe v-if="consoleUrl" :key="consoleKey" class="console-frame" :src="consoleUrl"
        title="DeepSeek Harness 原生控制台" referrerpolicy="no-referrer"
        allow="clipboard-read; clipboard-write" />
      <el-result v-else-if="errorMessage" icon="error" title="原生 Harness 启动失败"
        :sub-title="errorMessage">
        <template slot="extra"><el-button type="primary" @click="launch(true)">重试</el-button></template>
      </el-result>
      <el-result v-else icon="info" title="准备启动原生 Harness"
        sub-title="原生会话保存在 Harness 自己的工作区中，不会被转换成管理台消息。" />
    </div>

    <MaintenanceProposalPanel :visible="proposalsVisible" :resource-type="resourceType"
      :resource-id="resourceId" @close="proposalsVisible = false" @changed="proposalChanged" />
    <MaintenanceRunPanel :visible="runsVisible" :resource-type="resourceType"
      :resource-id="resourceId" @close="runsVisible = false" />
  </section>
</template>

<script>
import Api from '@/apis/api'
import MaintenanceProposalPanel from './MaintenanceProposalPanel.vue'
import MaintenanceRunPanel from './MaintenanceRunPanel.vue'

export default {
  name: 'NativeHarnessPanel',
  components: { MaintenanceProposalPanel, MaintenanceRunPanel },
  props: {
    active: { type: Boolean, default: false },
    resourceType: { type: String, required: true },
    resourceId: { type: String, required: true }
  },
  data() {
    return {
      consoleUrl: '', consoleKey: 0, launching: false, errorMessage: '',
      proposalsVisible: false, runsVisible: false, launchedResource: ''
    }
  },
  computed: {
    resourceKey() { return `${this.resourceType}:${this.resourceId}` }
  },
  watch: {
    active(value) { if (value) this.launch(false) },
    resourceKey() {
      this.consoleUrl = ''
      this.errorMessage = ''
      this.launchedResource = ''
      if (this.active) this.launch(false)
    }
  },
  mounted() { if (this.active) this.launch(false) },
  methods: {
    payload(res) { return (res && res.data && res.data.data) || (res && res.data) || null },
    failureMessage(error) {
      return (error && error.data && error.data.msg) || (error && error.msg) || '无法连接原生 Harness 控制台'
    },
    validLaunchUrl(value) {
      try {
        const url = new URL(value, window.location.href)
        return url.protocol === 'https:' && url.pathname.startsWith('/maintenance-console/')
      } catch (_) {
        return false
      }
    },
    launch(force) {
      if (!this.active || !this.resourceId || this.launching) return
      if (!force && this.consoleUrl && this.launchedResource === this.resourceKey) return
      this.launching = true
      this.errorMessage = ''
      Api.maintenance.launchConsole(this.resourceType, this.resourceId, res => {
        const result = this.payload(res) || {}
        if (!this.validLaunchUrl(result.launchUrl)) {
          this.consoleUrl = ''
          this.errorMessage = '服务端返回的 Harness 地址无效'
        } else {
          this.consoleUrl = result.launchUrl
          this.consoleKey += 1
          this.launchedResource = this.resourceKey
        }
        this.launching = false
      }, error => {
        this.consoleUrl = ''
        this.errorMessage = this.failureMessage(error)
        this.launching = false
      })
    },
    proposalChanged() {
      this.$emit('changed')
    }
  }
}
</script>

<style scoped>
.native-harness-panel { border: 1px solid #dfe5ef; border-radius: 12px; overflow: hidden; background: #fff; }
.native-toolbar { min-height: 52px; padding: 10px 14px; display: flex; justify-content: space-between; align-items: center; gap: 16px; border-bottom: 1px solid #e7ebf2; background: #f8faff; }
.native-toolbar strong,.native-toolbar span { display: block; }.native-toolbar span { margin-top: 4px; color: #8490a3; font-size: 12px; }
.toolbar-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }.toolbar-actions .el-button { margin: 0; }
.console-host { height: calc(88vh - 150px); min-height: 560px; background: #f4f6fa; }
.console-frame { display: block; width: 100%; height: 100%; border: 0; background: #fff; }
@media (max-width: 900px) {
  .native-toolbar { align-items: flex-start; flex-direction: column; }
  .console-host { min-height: 500px; height: 70vh; }
}
</style>
