<template>
  <div class="ops-page"><HeaderBar /><main class="ops-main ops-home">
    <div class="ops-toolbar ops-home-heading"><div><h1>工作台</h1><p class="ops-lead">{{ profile.username || '员工' }} · 选择要处理的工作</p></div><OpsRecoveryCode /></div>
    <el-alert v-if="$route.query.denied" title="该功能不在当前岗位授权范围内，请选择下方可用入口" type="warning" :closable="false" />
    <section v-if="tasks.length" class="ops-panel ops-home-tasks"><h2>常用任务</h2>
      <div class="ops-entry-grid"><router-link v-for="item in tasks" :key="item.key" :to="item.to" class="ops-entry"><i :class="item.icon" aria-hidden="true" /><div><h3>{{ item.label }}</h3><p>{{ item.description }}</p></div><i class="el-icon-arrow-right ops-entry-arrow" aria-hidden="true" /></router-link></div>
    </section>
    <el-empty v-else description="暂无可用功能，请联系岗位管理人员" />
  </main></div>
</template>
<script>
import OpsRecoveryCode from './OpsRecoveryCode.vue';
import HeaderBar from '@/components/HeaderBar.vue';
import { availableOperationsTasks } from './navigation';
export default { components: { HeaderBar, OpsRecoveryCode }, computed: { profile() { return this.$store.state.userInfo || {}; }, tasks() { return availableOperationsTasks(this.profile); } } };
</script>
