<template>
  <div class="content-page">
    <HeaderBar />
    <main>
      <header class="page-heading"><div class="heading-copy"><el-button type="text" icon="el-icon-arrow-left" @click="returnToDocuments(false)">返回文档</el-button><div><h1>管理知识内容</h1><p>{{ documentName || '知识文档' }}<span v-if="knowledgeBaseName"> · {{ knowledgeBaseName }}</span></p></div></div>
        <el-button size="small" @click="returnToDocuments(true)">召回测试</el-button></header>
      <knowledge-content-workspace v-if="datasetId && documentId" ref="workspace" :dataset-id="datasetId" :document-id="documentId" :document-name="documentName" :readonly="readOnly" />
      <el-alert v-else title="缺少知识库或文档标识，请从文档列表进入。" type="error" :closable="false" />
    </main>
  </div>
</template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import { isNavigationFailure } from 'vue-router';
import KnowledgeContentWorkspace from '@/components/knowledge/content/KnowledgeContentWorkspace.vue';
export default {
  components: { HeaderBar, KnowledgeContentWorkspace },
  computed: {
    datasetId() { return this.queryString('datasetId'); },
    documentId() { return this.queryString('documentId'); },
    documentName() { return this.queryString('documentName'); },
    knowledgeBaseName() { return this.queryString('knowledgeBaseName'); },
    readOnly() { return this.$route.query.readOnly === '1'; }
  },
  beforeRouteLeave(to, from, next) { this.checkLeave(next); },
  beforeRouteUpdate(to, from, next) { this.checkLeave(next); },
  methods: {
    queryString(key) { const value = this.$route.query[key]; return typeof value === 'string' ? value : ''; },
    async checkLeave(next) {
      const workspace = this.$refs.workspace;
      const allowed = !workspace || await workspace.confirmLeave();
      next(allowed ? undefined : false);
    },
    returnToDocuments(retrieval) {
      return this.$router.push({ name: 'KnowledgeFileUpload', query: { datasetId: this.datasetId, knowledgeBaseName: this.knowledgeBaseName,
        readOnly: this.readOnly ? '1' : '0', ...(retrieval ? { retrieval: '1' } : {}) } }).catch(error => {
        if (!isNavigationFailure(error)) this.$message.error('页面跳转失败，请重试');
      });
    }
  }
};
</script>
<style scoped>
.content-page { min-height:100vh; background:#f4f6fb; text-align:left; }main { padding:18px 28px 24px; }.page-heading { display:flex; justify-content:space-between; align-items:center; gap:20px; margin-bottom:16px; }.heading-copy { display:flex; align-items:center; gap:24px; min-width:0; }.heading-copy > div { min-width:0; }h1 { margin:0; color:#25334a; font-size:20px; font-weight:600; }.heading-copy p { margin:6px 0 0; color:#7b8494; font-size:12px; overflow-wrap:anywhere; }@media(max-width:700px){main{padding:14px;}.heading-copy{gap:12px;}h1{font-size:17px;}}
</style>
