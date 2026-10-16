<template>
  <div class="ops-page">
    <HeaderBar />
    <main class="ops-main">
      <h1>{{ sectionTitle }}</h1>
      <el-alert v-if="navigationError" :title="navigationError" type="error" :closable="false" />
      <el-tabs v-if="tabs.length" :value="activeTab" @input="selectTab">
        <el-tab-pane v-for="item in tabs" :key="item.id" :label="item.label" :name="item.id" />
      </el-tabs>
      <OpsAllowance v-if="activeTab === 'allowance'" />
      <OpsCredentials v-else-if="activeTab === 'credentials'" />
      <OpsRates v-else-if="activeTab === 'rates'" />
      <OpsPlatformAccounts v-else-if="activeTab === 'tasks'" />
      <OpsReservationReview v-else-if="activeTab === 'review'" />
      <p v-else class="ops-empty">当前岗位没有可查看的服务项目。</p>
    </main>
  </div>
</template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import { hasPermission } from '@/security/session';
import { serviceTabs } from './service-display';
import OpsAllowance from './OpsAllowance.vue';
import OpsCredentials from './OpsCredentials.vue';
import OpsPlatformAccounts from './OpsPlatformAccounts.vue';
import OpsReservationReview from './OpsReservationReview.vue';
import OpsRates from './OpsRates.vue';
export default {
  components: { HeaderBar, OpsAllowance, OpsCredentials, OpsRates, OpsPlatformAccounts, OpsReservationReview },
  data() { return { navigationError: '' }; },
  computed: {
    sectionTitle() { return hasPermission(this.$store.state.userInfo, 'credentials.read') || hasPermission(this.$store.state.userInfo, 'rates.read') || hasPermission(this.$store.state.userInfo, 'allowance.read') ? '模型与额度' : hasPermission(this.$store.state.userInfo, 'pool.read') ? '额度总池' : '用量复核'; },
    tabs() {
      const profile = this.$store.state.userInfo;
      return serviceTabs.filter(item => item.permissions.some(permission => hasPermission(profile, permission)))
        .map(item => item.id === 'allowance' && !hasPermission(profile, 'allowance.read') ? { ...item, label: '额度总池' } : item);
    },
    activeTab() { return this.tabs.find(item => item.id === this.$route.query.tab)?.id || this.tabs[0]?.id || ''; },
  },
  watch: {
    '$route.query.tab'() { this.normalizeRoute(); },
    tabs() { this.normalizeRoute(); },
  },
  created() { this.normalizeRoute(); },
  methods: {
    normalizeRoute() { if (this.activeTab && this.$route.query.tab !== this.activeTab) this.selectTab(this.activeTab); },
    async selectTab(tab) {
      if (!this.tabs.some(item => item.id === tab) || this.$route.query.tab === tab) return;
      this.navigationError = '';
      try { await this.$router.replace({ path: this.$route.path, query: { ...this.$route.query, tab }, hash: this.$route.hash }); }
      catch (error) { if (error.name !== 'NavigationDuplicated') this.navigationError = '页面切换失败，请重试'; }
    },
  },
};
</script>
