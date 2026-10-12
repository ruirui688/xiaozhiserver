<template>
  <section class="plugin-rule-overrides">
    <p class="rule-help">玩法规则默认继承目录。切换为角色自定义后，仅当前角色使用该内容；已有保存值按自定义保留。</p>
    <div v-for="field in ruleFields" :key="field.key" class="rule-field">
      <div class="rule-heading">
        <strong>{{ field.label }}</strong>
        <el-radio-group :value="usesOverride(field.key) ? 'override' : 'inherit'" size="mini"
          @input="changeSource(field.key, $event)">
          <el-radio-button label="inherit">继承目录</el-radio-button>
          <el-radio-button label="override">角色自定义</el-radio-button>
        </el-radio-group>
      </div>
      <el-input v-if="usesOverride(field.key)" type="textarea" :rows="field.key === 'instructions' ? 6 : 3"
        :value="valueFor(field.key)" @input="changeValue(field.key, $event)" />
      <pre v-else class="rule-preview">{{ valueFor(field.key) || '目录未设置此项' }}</pre>
      <span class="rule-source">来源：{{ usesOverride(field.key) ? '当前角色（含历史保存值）' : '插件目录，后续目录修改随配置刷新生效' }}</span>
    </div>
  </section>
</template>

<script>
import { pluginFields, pluginRuleValue, ruleUsesOverride } from '@/utils/pluginCatalog';

export default {
  name: 'PluginRuleOverrides',
  props: { plugin: { type: Object, required: true } },
  computed: {
    ruleFields() {
      const fields = pluginFields(this.plugin);
      const inheritable = (this.plugin.catalogMetadata || {}).inheritablePromptFields || [];
      return [
        { key: 'instructions', label: '玩法规则' },
        { key: 'opening_message', label: '开场要求' },
      ].filter(field => inheritable.includes(field.key) || fields.some(item => item.key === field.key)
        || ruleUsesOverride(this.plugin, field.key));
    },
  },
  methods: {
    usesOverride(key) { return ruleUsesOverride(this.plugin, key); },
    valueFor(key) { return pluginRuleValue(this.plugin, key); },
    changeSource(key, source) {
      const params = { ...(this.plugin.params || {}) };
      if (source === 'inherit') delete params[key];
      else params[key] = this.valueFor(key) || '';
      this.$emit('change', params);
    },
    changeValue(key, value) {
      this.$emit('change', { ...(this.plugin.params || {}), [key]: value });
    },
  },
};
</script>

<style scoped>
.plugin-rule-overrides { margin: 16px 0; padding: 14px; background: #f6f8fc; border-radius: 8px; }
.rule-help, .rule-source { color: #687487; font-size: 12px; line-height: 1.6; }
.rule-field + .rule-field { margin-top: 18px; }
.rule-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.rule-preview { max-height: 240px; margin: 8px 0; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.6; }
</style>
