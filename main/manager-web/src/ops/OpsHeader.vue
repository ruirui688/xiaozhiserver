<template>
  <div class="ops-header">
    <router-link to="/home" class="ops-brand"><span class="ops-mark">智</span><span>{{ persona.title }}</span></router-link>
    <nav class="ops-navigation" aria-label="运营功能导航">
      <router-link to="/home" class="ops-nav-link" :class="{ 'is-active': $route.path === '/home' }" :aria-current="$route.path === '/home' ? 'page' : null">工作台</router-link>
      <router-link v-for="item in entries" :key="item.path" :to="item.path" class="ops-nav-link" :class="{ 'is-active': $route.path === item.path }" :aria-current="$route.path === item.path ? 'page' : null">{{ item.label }}</router-link>
    </nav>
    <div class="ops-account"><span class="ops-identity" :title="profile.username"><i class="el-icon-user" /> {{ profile.username || '员工' }}</span><el-button size="mini" plain :loading="loggingOut" @click="logout">退出</el-button></div>
  </div>
</template>
<script>
import { availableOperations, operationsPersona } from './navigation';
import { operations } from '@/apis/platform';
export default {
  data() { return { loggingOut: false }; },
  computed: { profile() { return this.$store.state.userInfo || {}; }, persona() { return operationsPersona(this.profile); }, entries() { return availableOperations(this.profile); } },
  methods: {
    async logout() { if (this.loggingOut) return; this.loggingOut = true; try { await operations.logout(); } catch (_) { this.$message.warning('服务端注销未确认，本机会话已清除'); } finally { this.$store.commit('clearAuth'); this.$router.replace('/login'); this.loggingOut = false; } },
  },
};
</script>
