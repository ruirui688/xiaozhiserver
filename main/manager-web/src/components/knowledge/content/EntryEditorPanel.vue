<template>
  <section class="editor-panel">
    <div v-if="draft || entry" class="editor-body">
      <header class="editor-heading"><div><h2>{{ draft ? (draft.id ? '编辑知识' : '新增知识') : '知识详情' }}</h2>
        <p v-if="entry && !draft">{{ entry.enabled === 0 ? '已停用，不参与召回' : '已启用，参与召回' }}<span v-if="entry.editVersion != null"> · 版本 {{ entry.editVersion }}</span></p></div>
        <div v-if="!draft && editable" class="entry-actions"><el-button size="small" :disabled="saving" @click="$emit('toggle', entry)">{{ entry.enabled === 0 ? '启用' : '停用' }}</el-button><el-button type="primary" size="small" :disabled="saving" @click="$emit('edit', entry)">编辑</el-button></div>
      </header>
      <div class="editor-scroll">
      <template v-if="draft">
        <el-radio-group :value="draft.mode" size="small" :disabled="saving" @input="update('mode', $event)"><el-radio-button label="text">正文</el-radio-button><el-radio-button label="qa">问答</el-radio-button></el-radio-group>
        <p class="format-note">历史 Excel / CSV 切片保存为正文，不会自动还原成字段表单。修改不会改写上传原件。</p>
        <template v-if="draft.mode === 'qa'"><div class="field-label">问题</div><el-input :value="draft.question" @input="update('question', $event)" type="textarea" :rows="3" maxlength="4000" show-word-limit :disabled="saving" /><div class="field-label">答案</div><el-input :value="draft.answer" @input="update('answer', $event)" type="textarea" :rows="10" maxlength="16000" show-word-limit :disabled="saving" /></template>
        <template v-else><div class="field-label">知识正文</div><el-input :value="draft.content" @input="update('content', $event)" type="textarea" :rows="16" maxlength="20000" show-word-limit :disabled="saving" /></template>
        <div class="recall-setting"><el-checkbox :value="draft.active" :disabled="saving" @change="update('active', $event)">启用此条知识，参与召回</el-checkbox></div>
        <p class="save-note">保存失败会保留当前草稿。网络异常时，请先核对结果，避免重复新增。</p>
      </template>
      <template v-else><template v-if="entry.question || entry.answer"><div class="field-label">问题</div><pre>{{ entry.question }}</pre><div class="field-label">答案</div><pre>{{ entry.answer }}</pre></template><template v-else><div class="field-label">知识正文</div><pre>{{ entry.content }}</pre></template><details class="technical-id"><summary>条目标识</summary>{{ entry.chunkId || entry.id }}</details></template>
      </div>
      <footer v-if="draft" class="editor-footer"><el-button type="primary" size="small" :loading="saving" :disabled="!editable" @click="$emit('save')">保存修改</el-button><el-button size="small" :disabled="saving" @click="$emit('cancel')">取消</el-button><el-button size="small" type="text" :disabled="saving || loading" @click="$emit('refresh')">刷新列表，保留草稿</el-button></footer>
    </div>
    <el-empty v-else description="从左侧选择知识，或新增条目" :image-size="100" />
  </section>
</template>
<script>
export default {
  props: { entry: Object, draft: Object, editable: Boolean, saving: Boolean, loading: Boolean },
  methods: { update(key, value) { this.$emit('draft-change', { ...this.draft, [key]: value }); } }
};
</script>
<style scoped>
.editor-panel { min-width:0; min-height:0; overflow:hidden; text-align:left; background:#fff; }.editor-body { padding:24px 28px; max-width:1000px; margin:0; height:100%; box-sizing:border-box; display:flex; flex-direction:column; }.editor-scroll { flex:1; min-height:0; overflow:auto; padding-right:4px; }.editor-heading,.editor-footer { flex-shrink:0; }
.editor-heading { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; margin-bottom:20px; }.editor-heading h2 { margin:0; font-size:18px; color:#26324a; }.editor-heading p { margin:8px 0 0; font-size:12px; color:#7b8494; }.entry-actions { display:flex; flex-wrap:wrap; gap:6px; }.entry-actions .el-button { margin:0; }
.field-label { display:block; margin:18px 0 8px; font-size:13px; font-weight:600; color:#445069; }.format-note,.save-note { font-size:12px; color:#7b8494; line-height:1.7; margin:12px 0; }.recall-setting { margin:18px 0 8px; }.editor-footer { display:flex; flex-wrap:wrap; gap:8px; border-top:1px solid #edf0f5; padding-top:16px; margin-top:18px; }.editor-footer .el-button { margin:0; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; font:inherit; font-size:14px; line-height:1.9; margin:0; color:#354159; }.technical-id { margin-top:24px; color:#8992a2; font-size:12px; overflow-wrap:anywhere; }.technical-id summary { cursor:pointer; }
@media(max-width:900px) { .editor-body { padding:18px; }.editor-heading { flex-direction:column; } }
@media(max-width:540px) { .editor-panel { height:600px; } }
</style>
