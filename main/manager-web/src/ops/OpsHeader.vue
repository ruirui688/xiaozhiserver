<template>
  <div class="ops-header">
    <router-link to="/home" class="ops-brand"><span class="ops-mark">智</span><span>运营工作台<small>PLATFORM OPERATIONS</small></span></router-link>
    <el-select class="ops-navigation" :value="$route.path" placeholder="工作台" @change="navigate"><el-option label="工作台" value="/home" /><el-option v-for="item in entries" :key="item.path" :label="item.label" :value="item.path" /></el-select>
    <span class="ops-identity"><i class="el-icon-user" /> {{ profile.username || '员工' }}</span>
    <el-button size="small" plain @click="logout">退出</el-button>
  </div>
</template>
<script>
import { availableOperations } from './navigation';
import { operations } from '@/apis/platform';
export default {
  computed: { profile() { return this.$store.state.userInfo || {}; }, entries() { return availableOperations(this.profile); } },
  methods: {
    navigate(path) { if (path !== this.$route.path) this.$router.push(path); },
    async logout() { try { await operations.logout(); } catch (_) { this.$message.warning('服务端注销未确认，本机会话已清除'); } finally { this.$store.commit('clearAuth'); this.$router.replace('/login'); } },
  },
};
</script>
