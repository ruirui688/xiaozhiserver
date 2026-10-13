<template>
  <section class="channel-page">
    <div class="ops-toolbar"><div><h2>供应商通道</h2><p class="ops-muted">统一提供模型服务，用户注册后无需分配供应商密钥。</p></div><el-button v-if="canWrite" size="small" type="primary" icon="el-icon-plus" @click="open()">新增通道</el-button></div>
    <div class="channel-filters"><el-select v-model="provider" clearable size="small" placeholder="全部供应商" @change="search"><el-option v-for="item in providers" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-select v-model="serviceType" clearable size="small" placeholder="全部能力" @change="search"><el-option label="大模型" value="LLM" /><el-option label="语音识别" value="ASR" /><el-option label="语音合成" value="TTS" /></el-select><el-input v-model="keyword" clearable size="small" maxlength="100" placeholder="搜索通道、模型或账号" @clear="search" @keyup.enter.native="search" /><el-button size="small" :loading="loading" @click="search">查询</el-button><el-button size="small" icon="el-icon-refresh" :loading="loading" aria-label="刷新通道" @click="load" /></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="channel-table"><el-table v-if="!error" :data="rows" v-loading="loading" size="small" row-key="id" :max-height="360" :empty-text="keyword || provider || serviceType ? '没有匹配的通道' : '暂无通道，请添加供应商通道'">
      <el-table-column label="通道" min-width="170"><template slot-scope="s"><strong>{{ s.row.name }}</strong><small>{{ providerName(s.row.provider) }}</small></template></el-table-column>
      <el-table-column label="服务模型" min-width="200" show-overflow-tooltip><template slot-scope="s">{{ s.row.modelName || s.row.modelId }}<small>{{ capability(s.row.serviceType) }}</small></template></el-table-column>
      <el-table-column label="状态" width="130"><template slot-scope="s"><el-tag size="small" :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '已启用' : '已停用' }}</el-tag><small>{{ s.row.modelPublished === false ? '模型未发布' : s.row.rateConfigured === false ? '待配置价格' : s.row.credentialConfigured ? '凭据已配置' : '凭据未配置' }}</small></template></el-table-column>
      <el-table-column label="最近调用" min-width="170"><template slot-scope="s">{{ execution(s.row.lastStatus) }}<small>{{ s.row.lastCalledAt || '尚无执行记录' }}</small></template></el-table-column>
      <el-table-column label="操作" width="95"><template slot-scope="s"><el-button type="text" @click="open(s.row)">{{ canWrite ? '配置' : '详情' }}</el-button></template></el-table-column>
    </el-table></div>
    <el-pagination v-if="total > limit" class="channel-pagination" :current-page="page" :page-size="limit" :total="total" :pager-count="5" layout="total, prev, pager, next" @current-change="changePage" />
    <ChannelEditor v-if="editing && canWrite" :row="selected" @close="editing=false" @saved="load" />
    <el-drawer v-if="detail" title="通道详情" :visible="true" size="min(480px,96vw)" @close="detail=null"><dl class="channel-detail"><dt>通道</dt><dd>{{ detail.name }}</dd><dt>供应商账号</dt><dd>{{ detail.accountRef || '—' }}</dd><dt>地域 / 项目</dt><dd>{{ detail.region || '—' }} / {{ detail.projectRef || '—' }}</dd><dt>平台通道并发上限</dt><dd>{{ detail.maxConcurrency || '—' }}</dd><dt>配置版本</dt><dd>{{ detail.version }}</dd></dl></el-drawer>
  </section>
</template>
<script>
import { operations } from '@/apis/platform';
import { hasPermission } from '@/security/session';
import { paginationTotal } from './service-display';
import { channelProviders, providerLabel, capabilityLabel, executionLabel } from './channels/channel-display';
import ChannelEditor from './channels/ChannelEditor.vue';
export default {
  components: { ChannelEditor },
  data() { return { providers: channelProviders, rows: [], total: 0, page: 1, limit: 20, provider: '', serviceType: '', keyword: '', searchedKeyword: '', loading: false, error: '', generation: 0, editing: false, selected: null, detail: null }; },
  computed: { canWrite() { return hasPermission(this.$store.state.userInfo, 'credentials.write'); } },
  created() { this.load(); }, beforeDestroy() { this.generation++; },
  methods: {
    providerName: providerLabel, capability: capabilityLabel, execution: executionLabel,
    async load() { const generation = ++this.generation; this.loading = true; this.error = ''; try { const result = await operations.channels({ page: this.page, limit: this.limit, provider: this.provider, serviceType: this.serviceType, keyword: this.searchedKeyword }); if (generation !== this.generation) return; this.rows = result.list || []; this.total = paginationTotal(result.total); } catch (error) { if (generation === this.generation) { this.error = error.message; this.rows = []; this.total = 0; } } finally { if (generation === this.generation) this.loading = false; } },
    search() { this.page = 1; this.searchedKeyword = this.keyword.trim(); return this.load(); },
    changePage(page) { this.page = page; return this.load(); },
    open(row = null) { if (!this.canWrite) { if (row) this.detail = row; return; } this.selected = row; this.editing = true; },
  },
};
</script>
<style scoped>
.channel-page h2{margin:0;font-size:16px}.channel-page .ops-muted{margin:5px 0 0}.channel-filters{display:flex;align-items:center;gap:8px;margin:18px 0 14px}.channel-filters .el-select{width:155px}.channel-filters .el-input{max-width:260px}.channel-table{border:1px solid #e5eaf0;border-radius:8px;overflow:hidden;background:white}.channel-table small{display:block;color:#83909b;font-size:12px;margin-top:3px}.channel-table strong{font-weight:500}.channel-pagination{text-align:right;margin-top:16px}.channel-detail{padding:0 24px;overflow-wrap:anywhere}.channel-detail dt{color:#84919b;font-size:12px;margin-top:20px}.channel-detail dd{margin:6px 0;color:#273e4b}@media(max-width:700px){.channel-filters{flex-wrap:wrap}.channel-filters .el-select{width:calc(50% - 4px)}.channel-filters .el-input{max-width:none;width:calc(100% - 136px)}.channel-table{overflow:auto}.channel-table>.el-table{min-width:760px}}
</style>
