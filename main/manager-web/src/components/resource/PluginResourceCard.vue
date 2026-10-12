<template>
  <article :class="['plugin-resource-card', `plugin-resource-card--${layout}`]">
    <div class="plugin-main">
      <div class="card-topline">
        <span class="category-label">{{ presentation.category.label }}</span>
        <span :class="['state-label', { 'state-label--error': configurable && configState === 'error' }]">
          {{ statusText }}
        </span>
      </div>
      <button type="button" class="plugin-title" @click="$emit('detail', plugin)">
        {{ presentation.title }} <i class="el-icon-arrow-right" />
      </button>
      <p class="description">{{ presentation.summary }}</p>
      <p v-if="presentation.preparation" class="preparation"><span>使用前</span>{{ presentation.preparation }}</p>
    </div>

    <div class="plugin-usage">
      <div v-if="presentation.usageExample" class="usage-example">
        <span>可以这样说</span>
        <p>“{{ presentation.usageExample }}”</p>
      </div>
      <div class="card-actions">
        <el-button v-if="showSetup" size="small" type="primary" @click="$emit('setup', plugin)">
          {{ presentation.setupLabel }}
        </el-button>
        <el-button size="small" @click="$emit('detail', plugin)">使用说明</el-button>
      </div>
      <div v-if="configurable && presentation.section !== 'retired'" class="service-actions">
        <el-button size="mini" type="text" @click="$emit('configure', plugin)">配置服务密钥</el-button>
        <el-button size="mini" type="text" :loading="testing" :disabled="configState !== 'saved'"
          @click="$emit('test', plugin)">测试连接</el-button>
      </div>
    </div>
  </article>
</template>

<script>
import { pluginPresentation } from '@/utils/pluginPresentation';

export default {
  name: 'PluginResourceCard',
  props: {
    plugin: { type: Object, required: true },
    layout: { type: String, default: 'grid' },
    configurable: { type: Boolean, default: false },
    configState: { type: String, default: 'missing' },
    testing: { type: Boolean, default: false }
  },
  computed: {
    presentation() { return pluginPresentation(this.plugin); },
    showSetup() {
      return this.presentation.section !== 'system' && this.presentation.section !== 'retired'
        && this.presentation.setupTarget && this.presentation.setupTarget !== 'none';
    },
    statusText() {
      if (this.presentation.section === 'retired') return '已停用';
      if (this.configurable) {
        return {
          loading: '正在读取服务配置', error: '服务配置读取失败',
          saved: '服务密钥已保存', missing: this.plugin.providerCode === 'get_weather' ? '未配置和风服务' : '需要配置服务密钥'
        }[this.configState] || '服务配置状态未知';
      }
      return { plugins: '按需添加', activities: '添加后使用', integrations: '专页设置', system: '无需在此添加' }[this.presentation.section];
    }
  }
};
</script>

<style lang="scss" scoped>
.plugin-resource-card {
  display: flex;
  min-width: 0;
  padding: 20px;
  gap: 18px;
  flex-direction: column;
  border: 1px solid #e6eaf2;
  border-radius: 12px;
  background: #fff;
}
.plugin-resource-card:hover { border-color: #b6cbef; }
.plugin-main, .plugin-usage { min-width: 0; }
.card-topline { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; }
.category-label { font-size: 12px; color: #496b9f; }
.state-label { color: #7d8797; font-size: 12px; }
.state-label--error { color: #b96a22; }
.plugin-title {
  display: block;
  padding: 0;
  margin-top: 10px;
  border: 0;
  background: none;
  color: #23344c;
  font: inherit;
  font-size: 17px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}
.plugin-title i { color: #8d9bb0; font-size: 12px; }
.plugin-title:focus-visible { outline: 2px solid #6296ef; outline-offset: 4px; }
.description { margin: 9px 0; color: #526279; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.preparation { margin: 0; color: #79869a; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.preparation span { color: #58687f; margin-right: 7px; }
.plugin-usage { display: flex; flex-direction: column; gap: 12px; margin-top: auto; }
.usage-example { padding: 10px 12px; border-radius: 8px; background: #f6f8fb; }
.usage-example > span { color: #8390a4; font-size: 11px; }
.usage-example p { margin: 4px 0 0; color: #50647f; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.card-actions, .service-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.card-actions .el-button + .el-button, .service-actions .el-button + .el-button { margin-left: 0; }
.service-actions { gap: 16px; }
.service-actions .el-button { padding: 2px 0; }
.plugin-resource-card--list { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(240px, 0.8fr); align-items: center; }
.plugin-resource-card--list .plugin-usage { margin-top: 0; }
@media (max-width: 1180px) {
  .plugin-resource-card--list { grid-template-columns: minmax(0, 1fr); }
  .plugin-resource-card--list .plugin-usage { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; }
  .plugin-resource-card--list .service-actions { grid-column: 1 / -1; }
}
@media (max-width: 680px) {
  .plugin-resource-card--list .plugin-usage { display: flex; }
}
</style>
