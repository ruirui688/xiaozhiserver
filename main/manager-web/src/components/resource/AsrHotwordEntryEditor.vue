<template>
  <div class="entry-editor">
    <div class="batch-panel">
      <label for="hotword-batch">批量添加热词</label>
      <el-input id="hotword-batch" v-model="text" type="textarea" :rows="3" :disabled="disabled" placeholder="每行一个热词，直接粘贴即可。例如：&#10;慧苑&#10;象山石浦" />
      <div class="actions">
        <span>新增词默认权重</span><el-input-number v-model="defaultWeight" :min="1" :max="10" :precision="0" size="small" :disabled="disabled" aria-label="新增词默认权重" />
        <el-button size="small" type="primary" :disabled="disabled || !text.trim()" @click="add">添加到列表</el-button>
      </div>
      <small>权重 1–10，默认 4，数值越大越强调该词。重复词自动跳过，已有权重保持不变。</small>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
    <div class="actions">
      <strong>已添加 {{ value.length }} 个热词</strong>
      <el-input v-model="search" size="small" clearable placeholder="搜索已添加的热词" :disabled="disabled" aria-label="搜索热词" />
      <el-button size="small" :disabled="disabled" @click="addRow">单条新增</el-button>
    </div>
    <el-table :data="pageRows" max-height="330" empty-text="粘贴热词后点击添加，或单条新增">
      <el-table-column label="热词" min-width="160"><template slot-scope="scope">
        <el-input :value="scope.row.entry.word" size="small" :disabled="disabled" aria-label="热词" @input="update(scope.row.index, 'word', $event)" />
      </template></el-table-column>
      <el-table-column label="权重" width="150"><template slot-scope="scope">
        <el-input-number :value="scope.row.entry.weight" :min="1" :max="10" :precision="0" size="small" :disabled="disabled" :aria-label="`${scope.row.entry.word || '新热词'}的权重`" @change="update(scope.row.index, 'weight', $event)" />
      </template></el-table-column>
      <el-table-column label="操作" width="65"><template slot-scope="scope">
        <el-button type="text" :disabled="disabled" @click="remove(scope.row.index)">删除</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="filtered.length > 20" small layout="prev, pager, next" :page-size="20" :total="filtered.length" :current-page="page" @current-change="page = $event" />
    <div class="actions">
      <span>统一权重</span><el-input-number v-model="allWeight" :min="1" :max="10" :precision="0" size="small" :disabled="disabled" aria-label="统一权重" />
      <el-button size="small" :disabled="disabled || !value.length" @click="applyWeight">应用到全部 {{ value.length }} 条</el-button>
    </div>
    <small>统一权重会覆盖所有词条的权重。每词最多 30 字节（通常为 10 个汉字或 30 个英文字母），最多 5000 条。</small>
  </div>
</template>
<script>
import { mergeHotwordText, validateHotwordEntries } from '@/utils/asrHotwords';
export default {
  props: { value: { type: Array, default: () => [] }, disabled: Boolean },
  data() { return { text: '', defaultWeight: 4, allWeight: 4, search: '', page: 1, error: '', notice: '' }; },
  computed: {
    filtered() { return this.value.map((entry, index) => ({ entry, index })).filter(row => row.entry.word.includes(this.search.trim())); },
    pageRows() { return this.filtered.slice((this.page - 1) * 20, this.page * 20); }
  },
  watch: { search() { this.page = 1; }, 'value.length'() { this.page = Math.min(this.page, Math.max(1, Math.ceil(this.filtered.length / 20))); } },
  methods: {
    update(index, field, value) { this.$emit('input', this.value.map((entry, i) => i === index ? { ...entry, [field]: value } : entry)); },
    remove(index) { this.$emit('input', this.value.filter((_, i) => i !== index)); },
    addRow() {
      if (this.value.length >= 5000) { this.error = '每个词库最多 5000 个热词'; return; }
      this.search = ''; this.page = Math.ceil((this.value.length + 1) / 20);
      this.$emit('input', [...this.value, { word: '', weight: this.defaultWeight || 4 }]);
    },
    add() {
      this.error = ''; this.notice = '';
      try {
        const result = mergeHotwordText(this.value, this.text, this.defaultWeight);
        this.$emit('input', result.entries); this.text = ''; this.search = ''; this.page = 1;
        this.notice = `已添加 ${result.added} 条，跳过 ${result.skipped} 条重复热词`;
      } catch (error) { this.error = error.message; }
    },
    applyWeight() {
      if (!Number.isInteger(this.allWeight) || this.allWeight < 1 || this.allWeight > 10) { this.error = '请选择 1 至 10 的统一权重'; return; }
      this.$emit('input', this.value.map(entry => ({ ...entry, weight: this.allWeight })));
      this.notice = `已将全部 ${this.value.length} 条热词权重设为 ${this.allWeight}`;
    },
    getEntries() {
      const entries = this.text.trim() ? mergeHotwordText(this.value, this.text, this.defaultWeight).entries : this.value;
      return validateHotwordEntries(entries);
    }
  }
};
</script>
<style scoped>
.entry-editor{text-align:left}.batch-panel{background:#f6f8fc;padding:14px;border-radius:8px}.batch-panel label{display:block;margin-bottom:8px}.actions{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin:12px 0}.actions>.el-input{flex:1;min-width:140px}.entry-editor small{display:block;line-height:1.6;color:#68758b}.entry-editor .el-alert{margin-top:10px}.entry-editor .el-pagination{margin-top:10px}.entry-editor .el-input-number{width:125px}
</style>
