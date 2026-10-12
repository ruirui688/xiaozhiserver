<template><div class="ops-page"><HeaderBar /><main class="ops-main"><div class="ops-toolbar"><div><h1>操作审计</h1><p class="ops-lead">身份治理与平台操作的记录，不包含用户聊天或秘密字段。</p></div><el-button @click="load">刷新</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><section class="ops-panel"><el-table :data="rows" v-loading="loading"><el-table-column prop="createdAt" label="时间" min-width="170" /><el-table-column prop="actorId" label="操作人" min-width="180" /><el-table-column prop="action" label="操作" min-width="150" /><el-table-column prop="targetId" label="对象" min-width="180" /><el-table-column prop="outcome" label="结果" /></el-table><p class="ops-muted">展示最近 500 条记录。</p></section></main></div></template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import { platformRequest } from '@/apis/platform';
export default {components:{HeaderBar},data(){return{rows:[],loading:false,error:''};},created(){this.load();},methods:{async load(){this.loading=true;this.error='';try{this.rows=await platformRequest('GET','/ops/iam/audit');}catch(error){this.error=error.message;}finally{this.loading=false;}}}};
</script>
