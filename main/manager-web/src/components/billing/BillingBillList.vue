<template>
  <div class="bill-list">
    <div v-if="error" class="billing-inline-error"><span>{{ error }}</span><el-button type="text" @click="load">重试</el-button></div>
    <el-table v-else :data="rows" v-loading="loading" empty-text="当前范围尚无官方主账单" row-key="id">
      <el-table-column label="产品 / 账单" min-width="190"><template slot-scope="s"><strong>{{ s.row.product || '未提供产品名称' }}</strong><small>{{ s.row.billId }}</small><small>{{ label(s.row.billingMode || (s.row.supplierUsage || {}).BillingMode) }} · {{ label(s.row.serviceType) }}</small></template></el-table-column>
      <el-table-column label="消费区间（北京时间）" min-width="165"><template slot-scope="s">{{ time(s.row.expenseBegin) }}<small>{{ s.row.expenseEnd ? '至 ' + time(s.row.expenseEnd) : '结束时间未提供' }}</small></template></el-table-column>
      <el-table-column label="官方已付" min-width="145"><template slot-scope="s"><strong>{{ money(s.row.paidAmount, s.row.currency) }}</strong><small>应付 {{ money(s.row.payableAmount, s.row.currency) }}</small><small>未付 {{ money(s.row.unpaidAmount, s.row.currency) }}</small></template></el-table-column>
      <el-table-column label="资源抵扣" min-width="150"><template slot-scope="s"><div v-for="item in deductions(s.row)" :key="item.name">{{ item.name }} {{ item.value }}</div><small>数量，不计入金额</small></template></el-table-column>
      <el-table-column label="归属 / 核对" min-width="150"><template slot-scope="s"><span>{{ label(s.row.attributionLevel) }}</span><small v-if="s.row.reconciliation">{{ reconciliation(s.row.reconciliation) }}</small><small v-if="s.row.paymentCurrencyVerified === false">支付币种关系待核实</small></template></el-table-column>
      <el-table-column label="凭证明细" width="105" fixed="right"><template slot-scope="s"><el-button type="text" @click="openDetails(s.row)">展开核对</el-button></template></el-table-column>
    </el-table>
    <el-pagination :current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="changePage" />
    <el-dialog title="官方账单明细" :visible.sync="detailVisible" width="min(1180px, 96vw)" append-to-body custom-class="billing-bill-dialog">
      <p class="detail-note">{{ selected && selected.product }} · 此处为主单的核对明细，不是额外支出，不能与主单金额相加。</p>
      <div v-if="detailError" class="detail-error">{{ detailError }}<el-button type="text" @click="loadDetails">重试</el-button></div>
      <BillingRecords v-else v-loading="detailLoading" kind="bills" :rows="details" />
      <el-pagination :current-page="detailPage" :page-size="20" :total="detailTotal" layout="total, prev, pager, next" @current-change="changeDetailPage" />
    </el-dialog>
  </div>
</template>
<script>
import Api from '@/apis/api';
import BillingRecords from './BillingRecords.vue';
import { billingLabel, billingTime, amountText, supplierUsageRows } from '@/utils/billing';
export default {
  components: { BillingRecords }, props: { query: { type: Object, required: true }, accountView: Boolean },
  data() { return { rows: [], page: 1, total: 0, loading: false, error: '', generation: 0, detailGeneration: 0, detailVisible: false, selected: null, details: [], detailPage: 1, detailTotal: 0, detailLoading: false, detailError: '' }; },
  watch: { query: { handler() { this.page = 1; this.detailGeneration++; this.detailVisible = false; this.load(); }, deep: true } },
  created() { this.load(); }, beforeDestroy() { this.generation++; this.detailGeneration++; },
  methods: {
    label: billingLabel, time: billingTime, money: amountText,
    deductions(row) { return supplierUsageRows(row.supplierUsage || {}).filter(item => item.name.startsWith('抵扣')); },
    reconciliation(value) { const status = typeof value === 'string' ? value : value.status; return ({ MATCHED: '主明细已核对', REVIEW_REQUIRED: '待核对', NOT_OBTAINED: '尚无明细证据', NO_DETAILS: '尚无对应明细', CURRENCY_MISMATCH: '币种不一致', COUNT_UNVERIFIED: '明细数量待核对', AMOUNT_UNAVAILABLE: '缺少完整金额', AMOUNT_MISMATCH: '主明细金额不一致', ORPHAN_DETAIL: '缺少对应主单' })[status] || '待核对'; },
    request(query) { return this.accountView ? Api.billing.accountBills(query) : Api.billing.bills(query); },
    async load() {
      const generation = ++this.generation; this.loading = true; this.error = ''; this.rows = []; this.total = 0;
      try { const data = await this.request({ ...this.query, level: 'BILL', page: this.page, limit: 20 }); if (generation === this.generation) { this.rows = data.list || []; this.total = data.total || 0; } }
      catch (error) { if (generation === this.generation) this.error = error.message; }
      finally { if (generation === this.generation) this.loading = false; }
    },
    changePage(value) { this.page = value; this.load(); },
    openDetails(row) { this.selected = row; this.detailPage = 1; this.detailVisible = true; this.loadDetails(); },
    changeDetailPage(value) { this.detailPage = value; this.loadDetails(); },
    async loadDetails() {
      const generation = ++this.detailGeneration; this.detailLoading = true; this.details = []; this.detailTotal = 0; this.detailError = '';
      const query = { ...this.query, billId: this.selected.billId, provider: this.selected.provider || this.query.provider, accountRef: this.selected.accountRef || this.query.accountRef, level: 'DETAIL', page: this.detailPage, limit: 20 }; delete query.product;
      try { const data = await this.request(query); if (generation === this.detailGeneration) { this.details = data.list || []; this.detailTotal = data.total || 0; } }
      catch (error) { if (generation === this.detailGeneration) this.detailError = error.message; }
      finally { if (generation === this.detailGeneration) this.detailLoading = false; }
    }
  }
};
</script>
<style scoped>
.bill-list{min-width:0}.bill-list small{display:block;color:#8d99aa;font-size:11px;line-height:1.8;margin-top:3px;overflow-wrap:anywhere}.bill-list strong{font-weight:550}.detail-note{color:#7a879a;font-size:13px;line-height:1.8;margin:0 0 20px}.detail-error{padding:15px;background:#fff6f3;color:#a86247}.billing-bill-dialog .el-pagination{margin-top:20px;overflow-x:auto}
</style>
