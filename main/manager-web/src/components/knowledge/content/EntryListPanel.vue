<template>
  <aside class="entry-list-panel">
    <div class="search-row"><el-input :value="keywords" placeholder="搜索知识内容" size="small" clearable maxlength="200" :disabled="busy" @input="$emit('keyword', $event)" @keyup.enter.native="$emit('search')" />
      <el-button size="small" :disabled="busy" @click="$emit('search')">搜索</el-button></div>
    <div class="list-heading"><span>{{ total }} 条知识</span><el-button v-if="editable" size="small" type="primary" plain :disabled="busy" @click="$emit('add')">新增条目</el-button></div>
    <div v-loading="loading" class="entry-list">
      <el-empty v-if="!loading && !entries.length" description="暂无匹配条目" :image-size="70" />
      <button v-for="(entry, index) in entries" :key="entry.id || entry.chunkId" class="entry-row" :class="{ selected: selectedId === (entry.id || entry.chunkId) }" :disabled="saving || loading" @click="$emit('select', entry)">
        <span class="entry-title">{{ summary(entry) }}</span>
        <span class="entry-meta"><span>条目 {{ (page - 1) * 10 + index + 1 }} · {{ entry.question || entry.answer ? '问答' : '正文' }}</span>
          <span :class="{ disabled: entry.enabled === 0 }">{{ entry.enabled === 0 ? '已停用' : '启用' }}</span></span>
      </button>
    </div>
    <el-pagination small :current-page="page" :page-size="10" :total="total" layout="prev, pager, next" :pager-count="5" :disabled="busy || loading" @current-change="$emit('page', $event)" />
  </aside>
</template>
<script>
export default {
  props: { entries: { type: Array, default: () => [] }, keywords: String, total: Number, page: Number, selectedId: String, busy: Boolean, saving: Boolean, loading: Boolean, editable: Boolean },
  methods: {
    summary(entry) {
      // Display-only excerpt, never infer structured fields from colon-separated legacy text.
      const lines = String(entry.question || entry.content || entry.answer || '').split(/\r?\n/);
      const line = lines.find(value => value.trim() && !/^\s*[-#*_]+\s*$/.test(value)) || '未命名知识';
      return line.trim().slice(0, 90);
    }
  }
};
</script>
<style scoped>
.entry-list-panel { display:flex; flex-direction:column; min-width:0; min-height:0; height:100%; text-align:left; border-right:1px solid #e5e9f0; }
.search-row { display:flex; gap:8px; padding:16px; }.search-row .el-input { min-width:0; }
.list-heading { display:flex; align-items:center; justify-content:space-between; padding:0 16px 12px; font-size:13px; color:#606779; }
.entry-list { flex:1; overflow:auto; min-height:180px; }.entry-row { display:block; width:100%; padding:14px 16px; border:0; border-bottom:1px solid #edf0f5; background:#fff; text-align:left; cursor:pointer; }
.entry-row:hover { background:#f7f9ff; }.entry-row.selected { background:#eef2ff; box-shadow:inset 3px 0 #5969e8; }.entry-row:disabled { cursor:default; }
.entry-title { display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; color:#26324a; font-weight:500; font-size:14px; line-height:1.6; }
.entry-meta { display:flex; justify-content:space-between; gap:8px; margin-top:8px; font-size:12px; color:#69768b; }.entry-meta .disabled { color:#b27526; }
.el-pagination { padding:12px 8px; border-top:1px solid #edf0f5; text-align:center; }
</style>
