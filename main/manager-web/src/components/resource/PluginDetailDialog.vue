<template>
  <el-dialog class="plugin-detail-dialog" :title="presentation.title" :visible="visible"
    width="min(680px, calc(100vw - 32px))" top="6vh"
    :close-on-click-modal="false" @update:visible="$emit('update:visible', $event)" @closed="advancedPanels = []">
    <div v-if="plugin" class="plugin-detail">
      <p class="summary">{{ presentation.summary }}</p>
      <section>
        <h3>使用前需要什么</h3>
        <p>{{ presentation.preparation }}</p>
      </section>
      <section v-if="configurable && !retired" class="service-config">
        <div>
          <h3>我的服务配置</h3>
          <p>{{ configText }}</p>
        </div>
        <div class="service-actions">
          <el-button size="small" @click="$emit('configure', plugin)">配置服务密钥</el-button>
          <el-button size="small" :loading="testing" :disabled="configState !== 'saved'"
            @click="$emit('test', plugin)">测试连接</el-button>
        </div>
      </section>
      <section>
        <h3>{{ retired ? '现在应该在哪里设置' : '怎么设置' }}</h3>
        <ol><li v-for="(step, index) in presentation.setupSteps" :key="index">{{ step }}</li></ol>
      </section>
      <section v-if="presentation.usageExample" class="usage-example">
        <h3>设置好后，可以这样说</h3>
        <p>“{{ presentation.usageExample }}”</p>
      </section>
      <p class="availability-note">{{ presentation.availabilityNote }}</p>

      <el-collapse v-model="advancedPanels" class="technical-details">
        <el-collapse-item title="高级：技术说明与维护" name="technical">
          <div v-if="advancedPanels.includes('technical')">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="功能编码">{{ plugin.providerCode }}</el-descriptions-item>
              <el-descriptions-item label="调用说明">{{ pluginDefault(plugin, 'description') || '未填写' }}</el-descriptions-item>
              <el-descriptions-item label="工具来源">{{ sourceLabel }}</el-descriptions-item>
              <el-descriptions-item label="启用机制">{{ pluginActivation(plugin) }}</el-descriptions-item>
              <el-descriptions-item label="注入阶段">{{ pluginInjection(plugin) }}</el-descriptions-item>
              <el-descriptions-item label="支持路径（声明）">{{ pluginModelPaths(plugin) }}</el-descriptions-item>
              <el-descriptions-item v-if="interaction" label="默认玩法规则">
                <div class="rule-content">{{ pluginDefault(plugin, 'instructions') || '未填写' }}</div>
              </el-descriptions-item>
              <el-descriptions-item v-if="interaction" label="默认开场要求">
                {{ pluginDefault(plugin, 'opening_message') || '未填写' }}
              </el-descriptions-item>
            </el-descriptions>
            <p class="maintenance-note">此处是静态目录定义，不是当前会话的工具清单。修改调用说明不会自动启用功能。</p>
            <div v-if="canManage && !retired" class="maintenance-actions">
              <el-button size="small" @click="$emit('edit-prompt', plugin)">编辑调用说明</el-button>
              <template v-if="interaction">
                <el-button size="small" @click="$emit('edit', plugin)">编辑玩法</el-button>
                <el-button size="small" @click="$emit('command', plugin, 'duplicate')">复制玩法</el-button>
                <el-button size="small" type="danger" plain @click="$emit('command', plugin, 'delete')">删除玩法</el-button>
              </template>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
    <div slot="footer" class="dialog-footer">
      <el-button @click="$emit('update:visible', false)">关闭</el-button>
      <el-button v-if="plugin && presentation.setupTarget" type="primary" @click="$emit('setup', plugin)">
        {{ presentation.setupLabel }}
      </el-button>
    </div>
  </el-dialog>
</template>

<script>
import { pluginPresentation } from '@/utils/pluginPresentation';
import { isInteractionPlugin, isRetiredPlugin, pluginDefault, pluginActivation,
  pluginInjection, pluginModelPaths } from '@/utils/pluginCatalog';

export default {
  name: 'PluginDetailDialog',
  props: {
    visible: { type: Boolean, default: false },
    plugin: { type: Object, default: null },
    canManage: { type: Boolean, default: false },
    configurable: { type: Boolean, default: false },
    configState: { type: String, default: 'missing' },
    testing: { type: Boolean, default: false }
  },
  data() { return { advancedPanels: [] }; },
  computed: {
    presentation() { return pluginPresentation(this.plugin); },
    interaction() { return isInteractionPlugin(this.plugin); },
    retired() { return isRetiredPlugin(this.plugin); },
    sourceLabel() {
      const source = pluginDefault(this.plugin, 'tool_source', 'server_plugin');
      return { routing: '模型路由', server_plugin: '服务端插件', server_mcp: '服务器 MCP',
        device_iot: '设备 IoT', device_mcp: '设备 MCP', mcp_endpoint: 'MCP 接入点' }[source] || source;
    },
    configText() {
      return {
        loading: '正在读取服务配置…', error: '配置状态读取失败，请打开服务配置确认。',
        saved: '服务密钥已保存。这不代表连接测试已通过，请测试后再添加。',
        missing: this.plugin && this.plugin.providerCode === 'get_weather'
          ? '尚未配置和风服务。普通天气可尝试备用来源；天气预警需要配置和风服务并测试连接。'
          : '尚未配置服务密钥，请先配置并测试连接。'
      }[this.configState] || '服务配置状态未知。';
    }
  },
  watch: {
    plugin() { this.advancedPanels = []; }
  },
  methods: { pluginDefault, pluginActivation, pluginInjection, pluginModelPaths }
};
</script>

<style lang="scss" scoped>
.plugin-detail-dialog ::v-deep .el-dialog { display: flex; flex-direction: column; max-height: 88vh; margin-bottom: 0; }
.plugin-detail-dialog ::v-deep .el-dialog__body { min-height: 0; max-height: calc(88vh - 140px); overflow-y: auto; padding: 18px 24px; }
.plugin-detail-dialog ::v-deep .el-dialog__header,
.plugin-detail-dialog ::v-deep .el-dialog__footer { flex-shrink: 0; }
.plugin-detail { color: #53647a; font-size: 14px; line-height: 1.8; }
.summary { margin: 0 0 20px; color: #334b69; font-size: 15px; }
section { margin: 18px 0; }
h3 { margin: 0 0 6px; color: #344a65; font-size: 14px; }
p { margin: 0; overflow-wrap: anywhere; }
ol { padding-left: 22px; margin: 4px 0; }
li + li { margin-top: 5px; }
.usage-example, .service-config { padding: 14px 16px; border-radius: 9px; background: #f5f8fc; }
.service-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.availability-note, .maintenance-note { color: #8590a0; font-size: 12px; }
.technical-details { margin-top: 20px; }
.rule-content { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 240px; overflow: auto; }
.maintenance-note { margin-top: 10px; }
.maintenance-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.maintenance-actions .el-button + .el-button { margin-left: 0; }
</style>
