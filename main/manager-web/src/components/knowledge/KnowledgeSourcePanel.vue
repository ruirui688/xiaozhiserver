<template>
  <el-dialog
    title="动态数据源"
    :visible.sync="internalVisible"
    width="1050px"
    :close-on-click-modal="false"
    @close="$emit('close')">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="这是当前知识库的可选能力"
      description="只有这里明确添加并启用的数据源才会定时同步；能力仅作用于当前本地知识库，其他知识库和人工上传文档不会受影响。请只添加可信网页，新版本完成切片后才会替换旧版本。" />

    <div class="source-actions">
      <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增网页数据源</el-button>
      <el-button icon="el-icon-refresh" @click="loadSources()">刷新状态</el-button>
    </div>

    <el-table :data="sources" v-loading="loading" empty-text="当前知识库尚未配置动态数据源">
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column prop="sourceUri" label="来源地址" min-width="280" show-overflow-tooltip />
      <el-table-column label="刷新频率" width="110" align="center">
        <template slot-scope="scope">{{ formatInterval(scope.row.refreshIntervalMinutes) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130" align="center">
        <template slot-scope="scope">
          <el-tag :type="statusType(scope.row.syncStatus)" size="small">
            {{ statusText(scope.row.syncStatus) }}
          </el-tag>
          <div v-if="scope.row.currentRevision" class="revision">版本 {{ scope.row.currentRevision }}</div>
        </template>
      </el-table-column>
      <el-table-column label="最近成功" width="155" align="center">
        <template slot-scope="scope">{{ formatDate(scope.row.lastSuccessAt) || '尚未同步' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" align="center">
        <template slot-scope="scope">
          <el-button type="text" :disabled="scope.row.syncStatus === 'SYNCING'" @click="syncNow(scope.row)">立即同步</el-button>
          <el-button type="text" @click="showRuns(scope.row)">记录</el-button>
          <el-button type="text" :disabled="scope.row.syncStatus === 'SYNCING'" @click="openEdit(scope.row)">编辑</el-button>
          <el-button type="text" class="danger-link" :disabled="scope.row.syncStatus === 'SYNCING'" @click="remove(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :title="editingId ? '编辑动态数据源' : '新增动态数据源'"
      :visible.sync="editorVisible"
      width="620px"
      append-to-body
      :close-on-click-modal="false">
      <el-form ref="sourceForm" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="名称" prop="name">
          <el-input v-model.trim="form.name" maxlength="100" placeholder="例如：游戏版本公告" />
        </el-form-item>
        <el-form-item label="来源类型">
          <el-select v-model="form.sourceType" disabled style="width: 100%">
            <el-option label="单网页" value="WEB_PAGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="网页地址" prop="sourceUri">
          <el-input v-model.trim="form.sourceUri" maxlength="2048" placeholder="https://example.com/patch-notes" />
          <div class="form-tip">仅支持公网 HTTP/HTTPS；禁止在 URL 中放 API Key 或 Token。</div>
        </el-form-item>
        <el-form-item label="刷新频率" prop="refreshIntervalMinutes">
          <el-input-number v-model="form.refreshIntervalMinutes" :min="5" :max="10080" :step="5" />
          <span class="form-unit">分钟</span>
        </el-form-item>
        <el-form-item label="自动同步">
          <el-switch v-model="form.enabled" />
          <span class="form-tip inline">关闭后仍可手动立即同步。</span>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </span>
    </el-dialog>

    <el-dialog title="最近同步记录" :visible.sync="runsVisible" width="900px" append-to-body>
      <el-table :data="runs" v-loading="runsLoading" empty-text="暂无同步记录">
        <el-table-column prop="startedAt" label="开始时间" width="165">
          <template slot-scope="scope">{{ formatDate(scope.row.startedAt) }}</template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发" width="90" align="center">
          <template slot-scope="scope">{{ scope.row.triggerType === 'MANUAL' ? '手动' : '定时' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" align="center">
          <template slot-scope="scope"><el-tag :type="runType(scope.row.status)" size="small">{{ runText(scope.row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="revision" label="版本" width="80" align="center" />
        <el-table-column prop="bytesFetched" label="抓取字节" width="110" align="right" />
        <el-table-column prop="error" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <span slot="footer"><el-button @click="internalVisible = false">关闭</el-button></span>
  </el-dialog>
</template>

<script>
import KnowledgeBaseAPI from '@/apis/module/knowledgeBase';

export default {
  name: 'KnowledgeSourcePanel',
  props: {
    visible: { type: Boolean, default: false },
    datasetId: { type: String, required: true }
  },
  data() {
    const validateUrl = (rule, value, callback) => {
      if (!/^https?:\/\//i.test(value || '')) callback(new Error('请输入 HTTP/HTTPS 网页地址'));
      else callback();
    };
    return {
      internalVisible: false,
      loading: false,
      sources: [],
      editorVisible: false,
      editingId: null,
      saving: false,
      form: this.emptyForm(),
      rules: {
        name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
        sourceUri: [{ required: true, validator: validateUrl, trigger: 'blur' }],
        refreshIntervalMinutes: [{ required: true, message: '请输入刷新频率', trigger: 'change' }]
      },
      runsVisible: false,
      runsLoading: false,
      runs: [],
      statusTimer: null
    };
  },
  watch: {
    visible(value) {
      this.internalVisible = value;
      if (value) this.loadSources();
    },
    internalVisible(value) {
      if (!value) {
        this.stopStatusPolling();
        this.$emit('close');
      }
    }
  },
  beforeDestroy() {
    this.stopStatusPolling();
  },
  methods: {
    emptyForm() {
      return { name: '', sourceType: 'WEB_PAGE', sourceUri: '', refreshIntervalMinutes: 30, enabled: true };
    },
    loadSources(silent = false) {
      if (!silent) this.loading = true;
      KnowledgeBaseAPI.getKnowledgeSources(this.datasetId, ({ data }) => {
        this.loading = false;
        if (data && data.code === 0) {
          this.sources = data.data || [];
          this.updateStatusPolling();
        }
        else this.$message.error((data && data.msg) || '获取动态数据源失败');
      }, (error) => {
        this.loading = false;
        this.$message.error(this.errorMessage(error, '获取动态数据源失败'));
      });
    },
    updateStatusPolling() {
      this.stopStatusPolling();
      if (this.internalVisible && this.sources.some(source => source.syncStatus === 'SYNCING')) {
        this.statusTimer = window.setTimeout(() => this.loadSources(true), 3000);
      }
    },
    stopStatusPolling() {
      if (this.statusTimer) window.clearTimeout(this.statusTimer);
      this.statusTimer = null;
    },
    openCreate() {
      this.editingId = null;
      this.form = this.emptyForm();
      this.editorVisible = true;
    },
    openEdit(row) {
      this.editingId = row.id;
      this.form = {
        name: row.name,
        sourceType: row.sourceType,
        sourceUri: row.sourceUri,
        refreshIntervalMinutes: row.refreshIntervalMinutes,
        enabled: row.enabled
      };
      this.editorVisible = true;
    },
    save() {
      this.$refs.sourceForm.validate(valid => {
        if (!valid) return;
        this.saving = true;
        const done = ({ data }) => {
          this.saving = false;
          if (data && data.code === 0) {
            this.editorVisible = false;
            this.$message.success('动态数据源已保存');
            this.loadSources();
          } else this.$message.error((data && data.msg) || '保存动态数据源失败');
        };
        const failed = error => {
          this.saving = false;
          this.$message.error(this.errorMessage(error, '保存动态数据源失败'));
        };
        if (this.editingId) KnowledgeBaseAPI.updateKnowledgeSource(this.datasetId, this.editingId, this.form, done, failed);
        else KnowledgeBaseAPI.createKnowledgeSource(this.datasetId, this.form, done, failed);
      });
    },
    syncNow(row) {
      KnowledgeBaseAPI.syncKnowledgeSource(this.datasetId, row.id, ({ data }) => {
        if (data && data.code === 0) {
          this.$message.success('同步任务已启动');
          this.loadSources();
        } else this.$message.error((data && data.msg) || '启动同步失败');
      }, error => this.$message.error(this.errorMessage(error, '启动同步失败')));
    },
    remove(row) {
      this.$confirm('删除数据源会同时删除它生成的全部知识版本，人工上传文档不受影响。确定继续吗？', '删除动态数据源', { type: 'warning' })
        .then(() => KnowledgeBaseAPI.deleteKnowledgeSource(this.datasetId, row.id, ({ data }) => {
          if (data && data.code === 0) {
            this.$message.success('动态数据源已删除');
            this.loadSources();
            this.$emit('documents-changed');
          } else this.$message.error((data && data.msg) || '删除动态数据源失败');
        }, error => this.$message.error(this.errorMessage(error, '删除动态数据源失败'))))
        .catch(() => {});
    },
    showRuns(row) {
      this.runsVisible = true;
      this.runsLoading = true;
      KnowledgeBaseAPI.getKnowledgeSourceRuns(this.datasetId, row.id, ({ data }) => {
        this.runsLoading = false;
        if (data && data.code === 0) this.runs = data.data || [];
        else this.$message.error((data && data.msg) || '获取同步记录失败');
      }, error => {
        this.runsLoading = false;
        this.$message.error(this.errorMessage(error, '获取同步记录失败'));
      });
    },
    statusText(status) {
      return ({ PENDING: '待同步', SYNCING: '同步中', INDEXED: '已更新', UNCHANGED: '无变化', ERROR: '失败', PAUSED: '已暂停' })[status] || status;
    },
    statusType(status) {
      return ({ SYNCING: 'primary', INDEXED: 'success', UNCHANGED: 'info', ERROR: 'danger', PAUSED: 'warning' })[status] || 'info';
    },
    runText(status) {
      return ({ RUNNING: '抓取中', INDEXING: '切片中', INDEXED: '已发布', UNCHANGED: '无变化', FAILED: '失败' })[status] || status;
    },
    runType(status) {
      return ({ RUNNING: 'primary', INDEXING: 'primary', INDEXED: 'success', UNCHANGED: 'info', FAILED: 'danger' })[status] || 'info';
    },
    formatInterval(minutes) {
      if (minutes >= 1440 && minutes % 1440 === 0) return `${minutes / 1440} 天`;
      if (minutes >= 60 && minutes % 60 === 0) return `${minutes / 60} 小时`;
      return `${minutes} 分钟`;
    },
    formatDate(value) {
      if (!value) return '';
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false });
    },
    errorMessage(error, fallback) {
      return (error && error.data && (error.data.msg || error.data.message)) || (error && error.msg) || fallback;
    }
  }
};
</script>

<style scoped>
.source-actions { margin: 18px 0 12px; }
.revision { margin-top: 4px; color: #909399; font-size: 12px; }
.danger-link { color: #f56c6c; }
.form-tip { margin-top: 6px; color: #909399; font-size: 12px; line-height: 18px; }
.form-tip.inline { margin-left: 10px; }
.form-unit { margin-left: 8px; color: #606266; }
</style>
