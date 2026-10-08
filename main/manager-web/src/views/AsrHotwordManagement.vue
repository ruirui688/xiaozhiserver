<template>
  <div class="hotword-page">
    <HeaderBar /><ResourceCenterNav />
    <main>
      <div class="toolbar">
        <div><h1>热词库</h1><p>录入专有名词，发布到火山引擎，再应用到智能体。</p></div>
        <el-button icon="el-icon-refresh" :disabled="busy" :loading="loading" @click="load(true)">刷新本地列表</el-button>
        <el-button v-if="importModels.length" icon="el-icon-download" :disabled="!canWrite || busy || loading" @click="importVisible = true">从火山同步</el-button>
        <el-button type="primary" icon="el-icon-plus" :disabled="!canWrite || busy || loading" @click="edit()">新建词库</el-button>
      </div>
      <el-alert title="保存只生成草稿；发布会创建新版本，原有智能体保持当前版本。发布成功后请显式应用到需要更新的智能体。" type="info" :closable="false" show-icon />
      <el-alert v-if="error" class="notice" :title="error" type="error" :closable="false" show-icon />
      <section class="list" v-loading="loading">
        <el-table :data="rows" :empty-text="importModels.length ? '暂无热词库，可从火山同步已有词库，或新建词库' : '暂无热词库，可新建词库；火山已有词库请联系平台管理员同步'">
          <el-table-column prop="name" label="词库名称" min-width="140" />
          <el-table-column label="识别模型" min-width="150"><template slot-scope="scope">{{ modelName(scope.row.modelId) }}</template></el-table-column>
          <el-table-column label="词条" width="75"><template slot-scope="scope">{{ (scope.row.entries || []).length }}</template></el-table-column>
          <el-table-column label="发布状态" min-width="190"><template slot-scope="scope">
            <el-tag :type="scope.row.status === 'PUBLISHED' ? 'success' : scope.row.status === 'FAILED' ? 'danger' : 'warning'" size="small">{{ statusLabel(scope.row.status) }}</el-tag>
            <small v-if="locked(scope.row)">{{ scope.row.recoverable ? '操作已超时，可重试原操作恢复' : '同步进行中，请稍后刷新；中断超过 5 分钟可重试恢复' }}</small>
            <small v-if="scope.row.lastError" class="failure">{{ scope.row.lastError }}</small>
            <small v-if="scope.row.remoteId && scope.row.status !== 'PUBLISHED'">已引用智能体保持其当前版本</small>
          </template></el-table-column>
          <el-table-column label="已引用智能体" min-width="160"><template slot-scope="scope">{{ (scope.row.agentNames || []).join('、') || '尚未应用' }}<small v-if="(scope.row.agentIds || []).length">包含旧版本引用；更新需再次应用</small></template></el-table-column>
          <el-table-column label="操作" width="310"><template slot-scope="scope">
            <el-button type="text" :disabled="!canWrite || busy || locked(scope.row)" @click="edit(scope.row)">编辑</el-button>
            <el-button type="text" :disabled="!canWrite || busy || actionLocked(scope.row, 'PUBLISHING')" @click="publish(scope.row)">{{ scope.row.status === 'FAILED' || scope.row.status === 'PUBLISHING' ? '重试发布' : '发布' }}</el-button>
            <el-button type="text" :disabled="!canWrite || busy || scope.row.status !== 'PUBLISHED'" @click="openApply(scope.row)">应用</el-button>
            <el-button type="text" :disabled="!canWrite || busy || locked(scope.row) || !(scope.row.agentIds || []).length" @click="unapply(scope.row)">解除应用</el-button>
            <el-button type="text" :disabled="!canWrite || busy || actionLocked(scope.row, 'DELETING') || (scope.row.agentIds || []).length > 0" @click="remove(scope.row)">{{ scope.row.status === 'DELETING' ? '重试删除' : '删除' }}</el-button>
          </template></el-table-column>
        </el-table>
      </section>
    </main>
    <AsrHotwordImportDialog v-if="importVisible" :models="importModels" :can-write="canWrite" :workspace-id="workspaceId" @close="importVisible = false" @imported="load" />
    <el-dialog :title="editingId ? '编辑热词库' : '新建热词库'" :visible.sync="editorVisible" width="620px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
      <el-form label-width="85px" @submit.native.prevent>
        <el-form-item label="词库名称"><el-input v-model="form.name" maxlength="80" show-word-limit :disabled="busy" /></el-form-item>
        <el-form-item label="识别模型"><el-select v-model="form.modelId" placeholder="选择火山语音识别模型" :disabled="!!editingId || busy" style="width:100%">
          <el-option v-for="model in models" :key="model.id" :value="model.id" :label="model.modelName" />
        </el-select><small>使用已配置模型的账号发布，创建后不可更换模型。</small></el-form-item>
        <el-form-item label="热词"><el-input v-model="form.text" type="textarea" :rows="10" :disabled="busy" placeholder="每行一个词，可粘贴多行。例如：&#10;慧苑&#10;星岚|4" />
          <small>可写“词条|权重”，权重 1–10，默认 4。每词通常最多 10 个汉字或 30 个英文字母，混合内容按实际长度校验。最多 5000 条；实时识别模型可能有更低的生效容量。</small>
        </el-form-item>
      </el-form>
      <el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" />
      <span slot="footer"><el-button :disabled="busy" @click="editorVisible = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!canWrite" @click="save">保存草稿</el-button></span>
    </el-dialog>
    <el-dialog title="应用热词库" :visible.sync="applyVisible" width="520px" :close-on-click-modal="false" :show-close="!busy">
      <p>将“{{ applying && applying.name }}”的最新已发布版本设为选中智能体的当前热词库；其原热词选择将被替换。</p>
      <el-select v-model="selectedAgents" multiple filterable placeholder="选择使用同一识别模型的智能体" style="width:100%" :disabled="busy">
        <el-option v-for="agent in compatibleAgents" :key="agent.id" :value="agent.id" :label="agent.agentName" />
      </el-select>
      <p v-if="!compatibleAgents.length">暂无使用该识别模型的智能体，请先调整智能体的语音识别模型。</p>
      <el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" />
      <span slot="footer"><el-button :disabled="busy" @click="applyVisible = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!canWrite || !selectedAgents.length" @click="apply">确认应用</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import ResourceCenterNav from '@/components/resource/ResourceCenterNav.vue';
import AsrHotwordImportDialog from '@/components/resource/AsrHotwordImportDialog.vue';
import api from '@/apis/module/asrHotword';
import { parseHotwordText, formatHotwordEntries, hotwordStatus } from '@/utils/asrHotwords';
export default {
  components: { HeaderBar, ResourceCenterNav, AsrHotwordImportDialog },
  data() { return { rows: [], models: [], agents: [], loading: false, busy: false, error: '', dialogError: '', generation: 0,
    workspaceId: localStorage.getItem('currentWorkspaceId'), canWrite: localStorage.getItem('currentWorkspaceCanWrite') !== 'false',
    editorVisible: false, applyVisible: false, importVisible: false, editingId: '', form: { name: '', modelId: '', text: '' }, applying: null, selectedAgents: [] }; },
  computed: {
    importModels() { return this.models.filter(model => model.canImportRemote === true); },
    compatibleAgents() { return this.agents.filter(agent => this.applying && agent.asrModelId === this.applying.modelId); }
  },
  created() { this.load(); },
  beforeDestroy() { this.generation++; },
  methods: {
    statusLabel: hotwordStatus,
    locked(row) { return ['PUBLISHING', 'DELETING'].includes(row.status); },
    actionLocked(row, state) { return this.locked(row) && !(row.recoverable === true && row.status === state); },
    current(generation) { return generation === this.generation && this.workspaceId === localStorage.getItem('currentWorkspaceId'); },
    modelName(id) { const model = this.models.find(item => item.id === id); return model ? model.modelName : '模型不可用'; },
    async load(notify = false) {
      const generation = ++this.generation; this.loading = true; this.error = '';
      try {
        const [rows, models, agents] = await Promise.all([api.list(), api.models(), api.agents()]);
        if (!this.current(generation)) return;
        this.rows = rows || []; this.models = models || []; this.agents = agents || [];
        if (notify === true) this.$message.success(this.rows.length
          ? `已刷新，共 ${this.rows.length} 个本地词库`
          : '本地暂无词库；火山已有词库需通过“从火山同步”导入');
      } catch (error) { if (this.current(generation)) this.error = error.message; }
      finally { if (this.current(generation)) this.loading = false; }
    },
    edit(row) {
      this.editingId = row ? row.id : ''; this.form = row ? { name: row.name, modelId: row.modelId, text: formatHotwordEntries(row.entries) } : { name: '', modelId: '', text: '' };
      this.dialogError = ''; this.editorVisible = true;
    },
    async save() {
      if (this.busy || !this.canWrite) return;
      let payload;
      try {
        if (!this.form.name.trim() || !this.form.modelId) throw new Error('请填写名称并选择识别模型');
        payload = { name: this.form.name.trim(), modelId: this.form.modelId, entries: parseHotwordText(this.form.text) };
      } catch (error) { this.dialogError = error.message; return; }
      await this.perform(() => this.editingId ? api.update(this.editingId, payload) : api.create(payload), () => { this.editorVisible = false; this.$message.success('草稿已保存，请发布后应用'); }, true);
    },
    async perform(action, onSuccess, dialog = false) {
      if (this.busy || !this.canWrite || !this.current(this.generation)) return;
      const generation = this.generation; this.busy = true; this.dialogError = ''; this.error = '';
      try {
        const result = await action();
        if (!this.current(generation)) return;
        onSuccess(result); await this.load();
      } catch (error) { if (this.current(generation)) { if (dialog) this.dialogError = error.message; else this.error = error.message; } }
      finally { this.busy = false; }
    },
    async publish(row) {
      const generation = this.generation;
      try { await this.$confirm(`将“${row.name}”发布到火山引擎？这会创建新版本，已有智能体保持当前版本；发布后请再次应用。`, '发布热词库', { type: 'warning' }); } catch (_) { return; }
      if (!this.current(generation)) return;
      await this.perform(() => api.publish(row.id), result => {
        if (result.status === 'PUBLISHED') this.$message.success('已发布到火山引擎，请应用到需要更新的智能体');
        else this.$message.error(result.lastError || '发布失败，草稿已保留，可重试');
      });
    },
    openApply(row) { this.applying = row; this.selectedAgents = []; this.dialogError = ''; this.applyVisible = true; },
    async apply() {
      if (!this.applying || !this.selectedAgents.length) return;
      await this.perform(() => api.apply(this.applying.id, this.selectedAgents), () => { this.applyVisible = false; this.$message.success('已应用到选中智能体'); }, true);
    },
    async unapply(row) {
      const generation = this.generation;
      try { await this.$confirm('从当前列出的所有智能体中解除该词库及其历史版本的热词配置？', '解除应用', { type: 'warning' }); } catch (_) { return; }
      if (!this.current(generation)) return;
      await this.perform(() => api.unapply(row.id, row.agentIds), () => this.$message.success('已解除应用'));
    },
    async remove(row) {
      const generation = this.generation;
      try { await this.$confirm(`删除“${row.name}”？本项目发布的火山词表版本会被删除；从火山导入的原始词表会保留。`, '删除热词库', { type: 'warning' }); } catch (_) { return; }
      if (!this.current(generation)) return;
      await this.perform(() => api.remove(row.id), () => this.$message.success('词库已删除'));
    }
  }
};
</script>
<style scoped>
.hotword-page{min-height:100vh;background:#f5f7fc;color:#30384f}main{max-width:1440px;margin:auto;padding:24px}.toolbar{display:flex;align-items:center;gap:12px;margin-bottom:18px}.toolbar>div{margin-right:auto}h1{font-size:24px;margin:0}p,small{color:#7a849b;line-height:1.6}small{display:block}.list{background:white;border:1px solid #e5eaf3;border-radius:10px;padding:18px;margin-top:20px}.notice{margin-top:12px}.failure{color:#d94a4a;overflow-wrap:anywhere}@media(max-width:640px){main{padding:12px}.toolbar{flex-wrap:wrap}.hotword-page /deep/ .el-dialog{width:94%!important}}
</style>
