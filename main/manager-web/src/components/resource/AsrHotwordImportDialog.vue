<template>
  <el-dialog title="从火山同步已有词库" :visible="true" width="760px" :close-on-click-modal="false" :close-on-press-escape="!importing" :show-close="!importing" @close="$emit('close')">
    <p>读取已配置的火山词库，选择后导入当前工作空间。</p>
    <div class="remote-toolbar">
      <el-button icon="el-icon-refresh" :loading="loading" :disabled="!!importing" @click="loadRemote">读取火山词库</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-for="(failure, index) in sourceErrors" :key="index" :title="`${failure.sourceName}：${failure.message}`" type="warning" :closable="false" show-icon />
    <el-table :data="rows" v-loading="loading" :empty-text="error || sourceErrors.length ? '部分来源读取失败，请重试' : '暂无可导入的火山词库'">
      <el-table-column prop="name" label="火山词库名称" min-width="140" />
      <el-table-column v-if="multipleSources" prop="sourceName" label="火山来源" min-width="120" />
      <el-table-column prop="remoteId" label="词库 ID" min-width="170" />
      <el-table-column prop="wordCount" label="词条数" width="85" />
      <el-table-column label="操作" width="130"><template slot-scope="scope">
        <el-tag v-if="scope.row.imported" type="success" size="small">已同步</el-tag>
        <el-button v-else type="text" :disabled="!canWrite || loading || !!importing" :loading="importing === scope.row.remoteId" @click="importRow(scope.row)">导入工作空间</el-button>
      </template></el-table-column>
    </el-table>
    <p class="hint">这里只读取和导入，不会重新注册词库或替换智能体的热词配置。火山未返回权重的词条按默认权重 4 编辑，导入不修改远端词库，发布新版本后才生效。已导入词库不会覆盖本地草稿；后续编辑、发布和应用请在热词库列表操作。</p>
    <span slot="footer"><el-button :disabled="!!importing" @click="$emit('close')">完成</el-button></span>
  </el-dialog>
</template>
<script>
import api from '@/apis/module/asrHotword';
export default {
  props: { canWrite: Boolean, workspaceId: String },
  data() { return { rows: [], sourceErrors: [], loading: false, importing: '', error: '', generation: 0 }; },
  computed: { multipleSources() { return new Set([...this.rows, ...this.sourceErrors].map(item => item.sourceId)).size > 1; } },
  created() { this.loadRemote(); },
  beforeDestroy() { this.generation++; },
  methods: {
    current(generation) { return generation === this.generation && this.workspaceId === localStorage.getItem('currentWorkspaceId'); },
    async loadRemote() {
      if (this.importing) return;
      const generation = ++this.generation;
      this.error = ''; this.loading = true;
      if (!this.current(generation)) return;
      try {
        const result = await api.remote();
        if (this.current(generation)) { this.rows = result.tables || []; this.sourceErrors = result.errors || []; }
      } catch (error) { if (this.current(generation)) this.error = error.message; }
      finally { if (this.current(generation)) this.loading = false; }
    },
    async importRow(row) {
      const generation = this.generation;
      if (!this.canWrite || this.importing || this.loading || row.imported || !row.sourceId || !this.current(generation)) return;
      this.importing = row.remoteId; this.error = '';
      try {
        await api.importRemote({ sourceId: row.sourceId, remoteId: row.remoteId });
        if (!this.current(generation)) return;
        this.rows = this.rows.map(item => item.remoteId === row.remoteId && item.sourceId === row.sourceId ? { ...item, imported: true } : item);
        this.$message.success('已导入当前工作空间，原有智能体配置保持不变');
        this.$emit('imported');
      } catch (error) { if (this.current(generation)) this.error = error.message; }
      finally { this.importing = ''; }
    }
  }
};
</script>
<style scoped>
p{line-height:1.7;color:#606b80}.remote-toolbar{display:flex;gap:12px;margin:18px 0}.remote-toolbar .el-select{flex:1}.hint{font-size:12px}@media(max-width:640px){.remote-toolbar{flex-wrap:wrap}}
</style>
