<template><div class="ops-page"><HeaderBar /><main class="ops-main"><div class="ops-eyebrow">YOUR WORKSPACE</div><h1>今天，从这里开始</h1><p class="ops-lead">{{ profile.username }}，你的工作台按已授予的岗位权限展示。</p><el-alert v-if="$route.query.denied" title="该功能不在当前岗位授权范围内" type="warning" :closable="false" /><div class="ops-entry-grid"><router-link v-for="item in entries" :key="item.path" :to="item.path" class="ops-entry"><i :class="item.icon" /><h2>{{ item.label }}</h2><span>进入工作区 <i class="el-icon-arrow-right" /></span></router-link></div><el-empty v-if="!entries.length" description="尚无有效岗位授权，请联系权限治理人员" /><OpsRecoveryCode /><section class="ops-note"><i class="el-icon-lock" /><div><strong>岗位各有边界</strong><p>运营工作台只处理服务、额度与已授权的公共资源。用户聊天、音频、记忆及私有知识库不在此工作台开放。</p></div></section></main></div></template>
<script>
import OpsRecoveryCode from './OpsRecoveryCode.vue';
import HeaderBar from '@/components/HeaderBar.vue';
import { availableOperations } from './navigation';
export default { components: { HeaderBar, OpsRecoveryCode }, computed: { profile() { return this.$store.state.userInfo || {}; }, entries() { return availableOperations(this.profile); } } };
</script>
