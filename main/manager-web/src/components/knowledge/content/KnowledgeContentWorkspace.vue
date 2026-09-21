<template>
  <section class="knowledge-workspace">
    <el-alert v-if="reason" :title="reason" type="info" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div class="workspace-grid">
      <entry-list-panel :entries="entries" :keywords="keywords" :total="total" :page="page" :selected-id="selectedId"
        :busy="saving || !!draft" :saving="saving" :loading="loading" :editable="canEdit"
        @keyword="keywords = $event" @search="search" @page="changePage" @select="selectEntry" @add="editEntry()" />
      <entry-editor-panel :entry="selectedEntry" :draft="draft" :editable="canEdit" :saving="saving" :loading="loading"
        @draft-change="draft = $event" @edit="editEntry" @toggle="toggleEntry"
        @save="saveDraft" @cancel="discardDraft" @refresh="fetchEntries" />
    </div>
  </section>
</template>
<script>
import API from '@/apis/module/knowledgeBase';
import EntryListPanel from './EntryListPanel.vue';
import EntryEditorPanel from './EntryEditorPanel.vue';

export default {
  components: { EntryListPanel, EntryEditorPanel },
  props: {
    visible: { type: Boolean, default: true }, readonly: Boolean,
    datasetId: { type: String, default: '' }, documentId: { type: String, default: '' }, documentName: { type: String, default: '' }
  },
  data() {
    return { selectedId: null, entries: [], total: 0, page: 1, keywords: '', loading: false, saving: false,
      editable: false, reason: '', error: '', draft: null, requestId: 0, contextEpoch: 0 };
  },
  computed: {
    selectedEntry() { return this.entries.find(entry => (entry.id || entry.chunkId) === this.selectedId) || null; },
    canEdit() { return this.editable && !this.readonly; }
  },
  watch: {
    visible: { immediate: true, handler(value) { if (value) this.resetContext(); else this.invalidate(); } },
    datasetId() { if (this.visible) this.resetContext(); },
    documentId() { if (this.visible) this.resetContext(); }
  },
  mounted() { window.addEventListener('beforeunload', this.handleBeforeUnload); },
  beforeDestroy() { window.removeEventListener('beforeunload', this.handleBeforeUnload); this.invalidate(); },
  methods: {
    handleBeforeUnload(event) { if (this.draft || this.saving) { event.preventDefault(); event.returnValue = ''; } },
    async confirmLeave() {
      if (this.saving) { this.$message.warning('正在保存，请稍候再离开'); return false; }
      if (!this.draft) return true;
      const epoch = this.contextEpoch;
      try { await this.$confirm('存在未保存的修改，离开将丢失当前草稿。', '确认离开'); return epoch === this.contextEpoch; }
      catch (_) { return false; }
    },
    async selectEntry(entry) {
      if (this.saving) return;
      const epoch = this.contextEpoch;
      if (this.draft) { await this.discardDraft(); if (this.draft || epoch !== this.contextEpoch) return; }
      this.selectedId = entry.id || entry.chunkId; this.error = '';
    },
    invalidate() { this.requestId++; this.contextEpoch++; },
    resetContext() {
      this.invalidate(); this.selectedId = null; this.entries = []; this.total = 0; this.page = 1; this.keywords = '';
      this.draft = null; this.saving = false; this.editable = false; this.reason = ''; this.error = '';
      this.fetchEntries();
    },
    request(method, ...args) {
      return new Promise((resolve, reject) => API[method](...args, response => {
        const result = response && response.data;
        if (result && result.code === 0) resolve(result.data);
        else reject(new Error((result && result.msg) || '操作失败'));
      }, failure => reject(new Error((failure && failure.data && failure.data.msg) || '网络异常，请刷新核对结果；草稿已保留'))));
    },
    async fetchEntries() {
      if (!this.datasetId || !this.documentId) return;
      const id = ++this.requestId; this.loading = true; this.error = '';
      try {
        const data = await this.request('listEntries', this.datasetId, this.documentId, { page: this.page, page_size: 10, keywords: this.keywords });
        if (id !== this.requestId) return;
        this.entries = data.list || [];
        if (!this.entries.some(entry => (entry.id || entry.chunkId) === this.selectedId)) this.selectedId = this.entries.length ? (this.entries[0].id || this.entries[0].chunkId) : null;
        this.total = data.total || 0; this.editable = data.editable === true;
        this.reason = this.readonly ? '当前账号没有编辑权限，仅可查看内容。' : (data.reason || '');
        if (data.indexStatus === 'pending') this.reason += ' 内容已保存，下次检索时更新索引；更新失败时使用数据库检索。';
      } catch (err) { if (id === this.requestId) { this.error = err.message; this.editable = false; this.entries = []; this.total = 0; } }
      finally { if (id === this.requestId) this.loading = false; }
    },
    search() { if (this.draft || this.saving) return; this.page = 1; this.fetchEntries(); },
    changePage(page) { if (this.draft || this.saving) return; this.page = page; this.fetchEntries(); },
    editEntry(entry = {}) {
      if (!this.canEdit || this.saving) return;
      this.error = ''; this.selectedId = entry.id || entry.chunkId || null;
      this.draft = { id: entry.id, expectedVersion: entry.editVersion, mode: entry.question || entry.answer ? 'qa' : 'text',
        content: entry.content || '', question: entry.question || '', answer: entry.answer || '', active: entry.enabled !== 0 };
    },
    async discardDraft() {
      if (this.saving) return;
      const epoch = this.contextEpoch;
      try { await this.$confirm('放弃尚未保存的修改？', '确认'); if (epoch === this.contextEpoch) { this.draft = null; this.error = ''; } } catch (_) { /* Keep draft. */ }
    },
    async closeDialog() {
      if (this.saving) return;
      const epoch = this.contextEpoch;
      if (this.draft) { await this.discardDraft(); if (this.draft) return; }
      if (epoch !== this.contextEpoch) return;
      this.invalidate(); this.$emit('close');
    },
    payload(entry) {
      return { content: entry.content || '', question: entry.question || '', answer: entry.answer || '', enabled: entry.enabled === 0 ? 0 : 1, expectedVersion: entry.editVersion };
    },
    async mutate(method, args, onSuccess) {
      if (!this.canEdit || this.saving) return;
      const epoch = this.contextEpoch; this.saving = true; this.error = '';
      try {
        const result = await this.request(method, this.datasetId, this.documentId, ...args);
        if (epoch !== this.contextEpoch) return;
        onSuccess(); this.$emit('changed'); this.$message.success(result && result.indexStatus === 'pending' ? '内容已保存，下次检索时更新索引；更新失败时使用数据库检索' : '已保存'); await this.fetchEntries();
      } catch (err) { if (epoch === this.contextEpoch) this.error = err.message; }
      finally { if (epoch === this.contextEpoch) this.saving = false; }
    },
    saveDraft() {
      const draft = this.draft; if (!draft || this.saving) return;
      const qa = draft.mode === 'qa';
      if ((qa && (!draft.question.trim() || !draft.answer.trim())) || (!qa && !draft.content.trim())) {
        this.error = qa ? '问题和答案均不能为空' : '正文不能为空'; return;
      }
      if ((qa && (draft.question.length > 4000 || draft.answer.length > 16000 || draft.question.length + draft.answer.length > 20000)) || (!qa && draft.content.length > 20000)) {
        this.error = '内容超过长度限制：正文 20000 字，问题 4000 字，答案 16000 字'; return;
      }
      const payload = { content: qa ? '' : draft.content.trim(), question: qa ? draft.question.trim() : '',
        answer: qa ? draft.answer.trim() : '', enabled: draft.active ? 1 : 0, expectedVersion: draft.expectedVersion };
      return this.mutate(draft.id ? 'updateEntry' : 'createEntry', draft.id ? [draft.id, payload] : [payload], () => { this.draft = null; });
    },
    async toggleEntry(entry) {
      if (this.saving || !this.canEdit) return;
      const epoch = this.contextEpoch;
      try { await this.$confirm(entry.enabled === 0 ? '恢复此条目的召回？' : '停用后机器人将不再召回此条目。', '确认'); }
      catch (_) { return; }
      if (epoch !== this.contextEpoch) return;
      return this.mutate('updateEntry', [entry.id, { ...this.payload(entry), enabled: entry.enabled === 0 ? 1 : 0 }], () => {});
    }
  }
};
</script>
<style scoped>
.knowledge-workspace { text-align:left; background:#fff; border:1px solid #e5e9f0; border-radius:10px; overflow:hidden; }
.knowledge-workspace > .el-alert { border-radius:0; }
.workspace-grid { display:grid; grid-template-columns:310px minmax(0,1fr); grid-template-rows:minmax(0,1fr); height:calc(100vh - 190px); min-height:480px; }
@media(min-width:1600px) { .workspace-grid { grid-template-columns:350px minmax(0,1fr); } }
@media(max-width:700px) { .workspace-grid { grid-template-columns:220px minmax(0,1fr); } }
@media(max-width:540px) { .workspace-grid { display:block; height:auto; }.workspace-grid > :first-child { max-height:330px; border-right:0; border-bottom:1px solid #e5e9f0; } }
</style>
