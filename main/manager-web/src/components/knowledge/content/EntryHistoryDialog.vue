<template>
  <el-dialog title="修改历史" :visible="visible" append-to-body width="80%" :close-on-click-modal="false" :before-close="close" custom-class="knowledge-history-dialog">
    <div class="history-content" v-loading="loading"><el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-select :value="revisionId" placeholder="选择历史版本" :disabled="saving" @input="$emit('revision', $event)"><el-option v-for="item in revisions" :key="item.id" :value="item.id" :label="`版本 ${item.version} · ${item.createdAt || ''}`" /></el-select>
      <div v-if="selected && entry" class="comparison"><section><h4>当前内容 · {{ entry.enabled === 0 ? '已停用' : '已启用' }}</h4><pre>{{ display(entry) }}</pre></section><section><h4>恢复为 · {{ selected.enabled === 0 ? '已停用' : '已启用' }}</h4><pre>{{ display(selected) }}</pre></section></div>
      <el-empty v-if="!loading && !revisions.length" description="暂无修改历史" :image-size="60" />
    </div>
    <span slot="footer"><el-button size="small" :disabled="saving" @click="close">关闭</el-button><el-button type="primary" size="small" :disabled="!selected || !editable" :loading="saving" @click="$emit('restore')">恢复此版本</el-button></span>
  </el-dialog>
</template>
<script>
export default {
  props: { visible: Boolean, loading: Boolean, saving: Boolean, editable: Boolean, error: String, entry: Object, revisions: { type:Array, default:() => [] }, revisionId: [String, Number] },
  computed: { selected() { return this.revisions.find(item => item.id === this.revisionId); } },
  methods: { close() { if (!this.saving) this.$emit('close'); }, display(entry) { return entry.question || entry.answer ? `问题：${entry.question || ''}\n答案：${entry.answer || ''}` : entry.content; } }
};
</script>
<style scoped>
.history-content { text-align:left; }.el-select { width:100%; margin-bottom:16px; }.comparison { display:grid; grid-template-columns:1fr 1fr; gap:16px; max-height:55vh; overflow:auto; }.comparison section { min-width:0; background:#f6f8fb; padding:16px; }.comparison h4 { margin:0 0 12px; font-size:13px; }pre { white-space:pre-wrap; overflow-wrap:anywhere; font:inherit; line-height:1.8; }@media(max-width:700px){ .comparison { grid-template-columns:1fr; } }
</style>
