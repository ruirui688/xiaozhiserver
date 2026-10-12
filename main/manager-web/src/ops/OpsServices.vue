<template><div class="ops-page"><HeaderBar /><main class="ops-main"><span class="ops-eyebrow">MANAGED MODEL SERVICES</span><h1>服务运营</h1><p class="ops-lead">管理平台服务额度、用户独立凭据及计价规则。供应商实际采购费用在「供应商成本」查看。</p><el-tabs v-model="tab"><el-tab-pane v-for="item in tabs" :key="item.id" :label="item.label" :name="item.id" /></el-tabs><OpsAllowance v-if="tab === 'allowance'" /><OpsCredentials v-else-if="tab === 'credentials'" /><OpsRates v-else-if="tab === 'rates'" /><OpsPlatformAccounts v-else-if="tab === 'tasks'" /><OpsReservationReview v-else-if="tab === 'review'" /></main></div></template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import { hasPermission } from '@/security/session';
import OpsAllowance from './OpsAllowance.vue';
import OpsCredentials from './OpsCredentials.vue';
import OpsPlatformAccounts from './OpsPlatformAccounts.vue';
import OpsReservationReview from './OpsReservationReview.vue';
import OpsRates from './OpsRates.vue';
export default {
  components: { HeaderBar, OpsAllowance, OpsCredentials, OpsRates, OpsPlatformAccounts, OpsReservationReview }, data() { return { tab: '' }; },
  computed: { tabs() { const p = this.$store.state.userInfo; return [{ id: 'tasks', label: '平台任务账户', permissions: ['allowance.read','pool.read','credentials.read'] },{ id: 'review', label: '异常用量复核', permissions: ['allowance.review'] },{ id: 'allowance', label: '额度账户', permissions: ['allowance.read', 'pool.read', 'allowance.review'] }, { id: 'credentials', label: '凭据库存', permissions: ['credentials.read'] }, { id: 'rates', label: '服务计价', permissions: ['rates.read'] }].filter(item => item.permissions.some(permission => hasPermission(p, permission))); } },
  created() { this.tab = this.tabs[0]?.id || ''; },
};
</script>
