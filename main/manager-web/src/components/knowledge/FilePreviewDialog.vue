<template>
  <el-dialog :title="`管理内容 - ${documentName}`" :visible="visible" width="90%"
    class="knowledge-content-dialog" :before-close="closeDialog" :close-on-click-modal="false">
    <el-alert v-if="reason" :title="reason" type="info" :closable="false" />
    <div class="toolbar">
      <el-input v-model="keywords" placeholder="搜索正文、问题或答案" maxlength="200" clearable :disabled="loading || !!draft || saving" @keyup.enter.native="search" />
      <el-button :disabled="loading || !!draft || saving" @click="search">搜索</el-button>
      <el-button v-if="canEdit" type="primary" :disabled="loading || !!draft || saving" @click="editEntry()">新增条目</el-button>
      <el-button :disabled="!!draft || saving" @click="$emit('retrieval-test')">召回测试</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section v-if="draft" class="editor">
      <h3>{{ draft.id ? '编辑条目' : '新增条目' }}</h3>
      <el-radio-group v-model="draft.mode" :disabled="saving">
        <el-radio-button label="text">正文</el-radio-button><el-radio-button label="qa">问答</el-radio-button>
      </el-radio-group>
      <template v-if="draft.mode === 'qa'">
        <label>问题</label><el-input v-model="draft.question" type="textarea" maxlength="4000" show-word-limit :rows="3" :disabled="saving" />
        <label>答案</label><el-input v-model="draft.answer" type="textarea" maxlength="16000" show-word-limit :rows="6" :disabled="saving" />
      </template>
      <template v-else><label>正文</label><el-input v-model="draft.content" type="textarea" maxlength="20000" show-word-limit :rows="10" :disabled="saving" /></template>
      <el-checkbox v-model="draft.active" :disabled="saving">参与召回</el-checkbox>
      <p class="hint">修改保存在知识条目中，不会改写原始上传文件。失败时草稿保留；网络异常请先刷新核对，避免重复新增。</p>
      <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="saveDraft">保存</el-button>
      <el-button :disabled="saving" @click="discardDraft">取消编辑</el-button>
      <el-button :disabled="saving || loading" @click="fetchEntries">刷新列表（保留草稿）</el-button>
    </section>
    <div v-loading="loading" class="entries">
      <el-empty v-if="!loading && !entries.length" description="暂无匹配条目" />
      <article v-for="entry in entries" :key="entry.id || entry.chunkId" class="entry">
        <header><strong>条目 {{ entry.chunkId || entry.id }}</strong>
          <el-tag size="mini" :type="entry.enabled === 0 ? 'info' : 'success'">{{ entry.enabled === 0 ? '已停用' : '参与召回' }}</el-tag>
          <span v-if="entry.editVersion != null">版本 {{ entry.editVersion }}</span>
        </header>
        <template v-if="entry.question || entry.answer"><h4>问题</h4><pre>{{ entry.question }}</pre><h4>答案</h4><pre>{{ entry.answer }}</pre></template>
        <pre v-else>{{ entry.content }}</pre>
        <div v-if="canEdit" class="actions">
          <el-button size="small" :disabled="!!draft || saving" @click="editEntry(entry)">编辑</el-button>
          <el-button size="small" :disabled="!!draft || saving" @click="toggleEntry(entry)">{{ entry.enabled === 0 ? '恢复召回' : '停用' }}</el-button>
          <el-button size="small" :disabled="!!draft || saving" @click="showHistory(entry)">修改历史</el-button>
        </div>
      </article>
    </div>
    <el-pagination :current-page="page" :page-size="10" :total="total" layout="prev, pager, next, total"
      :disabled="loading || !!draft || saving" @current-change="changePage" />
    <el-dialog title="修改历史与恢复预览" :visible.sync="historyVisible" append-to-body width="80%"
      :close-on-click-modal="false" :before-close="closeHistory">
      <el-alert v-if="historyError" type="error" :title="historyError" :closable="false" />
      <div v-loading="historyLoading">
        <el-select v-model="revisionId" placeholder="选择历史版本" :disabled="saving" class="revision-select">
          <el-option v-for="item in revisions" :key="item.id" :value="item.id" :label="`版本 ${item.version} · ${item.createdAt || ''}`" />
        </el-select>
        <div v-if="selectedRevision" class="comparison">
          <section><h4>当前内容 · {{ historyEntry.enabled === 0 ? '已停用' : '参与召回' }}</h4><pre>{{ displayContent(historyEntry) }}</pre></section>
          <section><h4>恢复为 · {{ selectedRevision.enabled === 0 ? '已停用' : '参与召回' }}</h4><pre>{{ displayContent(selectedRevision) }}</pre></section>
        </div>
        <p v-if="!historyLoading && !revisions.length">暂无修改历史</p>
      </div>
      <span slot="footer"><el-button :disabled="saving" @click="closeHistory">关闭</el-button>
        <el-button type="primary" :disabled="!selectedRevision || !canEdit" :loading="saving" @click="restoreRevision">恢复此版本</el-button></span>
    </el-dialog>
  </el-dialog>
</template>

<script>
import API from '@/apis/module/knowledgeBase';

export default {
  props: {
    visible: Boolean, readonly: Boolean,
    datasetId: { type: String, default: '' }, documentId: { type: String, default: '' }, documentName: { type: String, default: '' }
  },
  data() {
    return { entries: [], total: 0, page: 1, keywords: '', loading: false, saving: false,
      editable: false, reason: '', error: '', draft: null, requestId: 0, contextEpoch: 0,
      historyVisible: false, historyLoading: false, historyError: '', historyEntry: null, revisions: [], revisionId: null, historyRequestId: 0 };
  },
  computed: {
    canEdit() { return this.editable && !this.readonly; },
    selectedRevision() { return this.revisions.find(item => item.id === this.revisionId); }
  },
  watch: {
    visible: { immediate: true, handler(value) { if (value) this.resetContext(); else this.invalidate(); } },
    datasetId() { if (this.visible) this.resetContext(); },
    documentId() { if (this.visible) this.resetContext(); }
  },
  beforeDestroy() { this.invalidate(); },
  methods: {
    invalidate() { this.requestId++; this.contextEpoch++; this.historyRequestId++; this.historyVisible = false; },
    resetContext() {
      this.invalidate(); this.entries = []; this.total = 0; this.page = 1; this.keywords = '';
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
        this.entries = data.list || []; this.total = data.total || 0; this.editable = data.editable === true;
        this.reason = this.readonly ? '当前账号没有编辑权限，仅可查看内容。' : (data.reason || '');
        if (data.indexStatus === 'pending') this.reason += ' 内容已保存，下次检索时更新索引；更新失败时使用数据库检索。';
      } catch (err) { if (id === this.requestId) { this.error = err.message; this.editable = false; this.entries = []; this.total = 0; } }
      finally { if (id === this.requestId) this.loading = false; }
    },
    search() { if (this.draft || this.saving) return; this.page = 1; this.fetchEntries(); },
    changePage(page) { if (this.draft || this.saving) return; this.page = page; this.fetchEntries(); },
    editEntry(entry = {}) {
      if (!this.canEdit || this.saving) return;
      this.error = '';
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
      const epoch = this.contextEpoch; this.saving = true; this.error = ''; this.historyError = '';
      try {
        const result = await this.request(method, this.datasetId, this.documentId, ...args);
        if (epoch !== this.contextEpoch) return;
        onSuccess(); this.$emit('changed'); this.$message.success(result && result.indexStatus === 'pending' ? '内容已保存，下次检索时更新索引；更新失败时使用数据库检索' : '已保存'); await this.fetchEntries();
      } catch (err) { if (epoch === this.contextEpoch) { this.error = err.message; this.historyError = err.message; } }
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
    },
    async showHistory(entry) {
      const id = ++this.historyRequestId; this.historyEntry = entry; this.historyVisible = true;
      this.revisions = []; this.revisionId = null; this.historyLoading = true; this.historyError = '';
      try {
        const data = await this.request('entryRevisions', this.datasetId, this.documentId, entry.id);
        if (id !== this.historyRequestId) return;
        this.revisions = data || []; this.revisionId = this.revisions.length ? this.revisions[0].id : null;
      } catch (err) { if (id === this.historyRequestId) this.historyError = err.message; }
      finally { if (id === this.historyRequestId) this.historyLoading = false; }
    },
    closeHistory() { if (!this.saving) { this.historyVisible = false; this.historyRequestId++; } },
    displayContent(entry) { return entry.question || entry.answer ? `问题：${entry.question || ''}\n答案：${entry.answer || ''}` : entry.content; },
    async restoreRevision() {
      if (!this.selectedRevision || this.saving) return;
      const epoch = this.contextEpoch;
      const entryId = this.historyEntry.id;
      const payload = { revisionId: this.revisionId, expectedVersion: this.historyEntry.editVersion };
      try { await this.$confirm('将创建一个新版本，恢复为所选历史内容及启停状态。是否继续？', '确认恢复'); }
      catch (_) { return; }
      if (epoch !== this.contextEpoch) return;
      return this.mutate('restoreEntry', [entryId, payload], () => { this.historyVisible = false; });
    }
  }
};
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; margin: 16px 0; flex-wrap: wrap; }
.toolbar .el-input { flex: 1; min-width: 180px; }
.entries { max-height: 50vh; overflow: auto; }
.entry, .editor { padding: 16px; margin: 12px 0; border: 1px solid #dcdfe6; border-radius: 8px; background: #fafbfc; }
header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; text-align: left; }
label { display: block; margin: 12px 0 6px; }
.hint { color: #606266; }
.editor .el-checkbox { display: block; margin-top: 12px; }
.comparison { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; max-height: 50vh; overflow: auto; }
.comparison section { min-width: 0; padding: 12px; background: #f5f7fa; }
.revision-select { width: 100%; margin: 12px 0; }
@media (max-width: 700px) { .comparison { grid-template-columns: 1fr; } }
</style>
