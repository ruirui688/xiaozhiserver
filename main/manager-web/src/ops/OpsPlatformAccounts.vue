<template>
  <section class="ops-panel">
    <div class="ops-toolbar"><div><h2>平台任务</h2><p class="ops-muted">管理模型测试、评测和官方知识库任务的独立预算，模型调用使用平台通道。</p></div><div class="ops-actions"><el-button v-if="can('pool.adjust')" size="small" @click="open('create')">新建任务账户</el-button><el-button size="small" @click="load">刷新</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" v-loading="loading" empty-text="尚未配置任务账户">
      <el-table-column prop="id" label="账户名称" min-width="150" />
      <el-table-column label="模型范围" min-width="180"><template slot-scope="s">{{ values(s.row.modelIdsJson).map(modelName).join('、') }}</template></el-table-column>
      <el-table-column label="允许任务" min-width="180"><template slot-scope="s">{{ values(s.row.purposesJson).map(purposeLabel).join('、') }}</template></el-table-column>
      <el-table-column v-if="can('allowance.read') || can('pool.read')" label="可用额度（元）" min-width="130"><template slot-scope="s"><span :title="s.row.available">{{ amount(s.row.available) }}</span></template></el-table-column>
      <el-table-column v-if="can('allowance.read') || can('pool.read')" label="已使用（元）" min-width="120"><template slot-scope="s"><span :title="s.row.spent">{{ amount(s.row.spent) }}</span></template></el-table-column>
      <el-table-column v-if="can('allowance.grant')" label="操作" width="110"><template slot-scope="s"><el-button v-if="can('allowance.grant')" type="text" @click="open('grant',s.row)">发放预算</el-button></template></el-table-column>
    </el-table>
    <el-dialog :title="title" :visible.sync="dialog" width="min(600px,94vw)" :close-on-click-modal="false">
      <el-form label-width="105px">
        <template v-if="action === 'create'"><el-form-item label="账户名称"><el-input v-model="form.id" maxlength="100" placeholder="例如 official-knowledge" /></el-form-item><el-form-item label="模型范围"><el-select v-model="form.modelIds" multiple filterable :loading="modelsLoading" placeholder="选择允许使用的模型" style="width:100%"><el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" /></el-select><el-alert v-if="modelError" :title="modelError" type="error" :closable="false" /><el-button v-if="modelError" size="small" @click="loadModels">重新加载模型</el-button></el-form-item><el-form-item label="任务范围"><el-checkbox-group v-model="form.purposes"><el-checkbox v-for="item in purposes" :key="item.value" :label="item.value">{{ item.label }}</el-checkbox></el-checkbox-group></el-form-item></template>
        <template v-else><el-form-item label="任务账户">{{ form.id }}</el-form-item><template v-if="action === 'grant'"><el-form-item label="发放金额"><el-input v-model="form.amount" placeholder="元" /></el-form-item><el-form-item label="发放原因"><el-input v-model="form.reason" type="textarea" /></el-form-item></template></template>
      </el-form><el-alert v-if="saveError" :title="saveError" type="error" :closable="false" /><span slot="footer"><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确认</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import { operations } from '@/apis/platform';
import { hasPermission } from '@/security/session';
import { allowanceDisplayAmount, newOperationId } from '@/utils/allowance';
const purposes = [{value:'MODEL_TEST',label:'模型测试'},{value:'BENCHMARK',label:'模型评测'},{value:'MODEL_ASSISTANT',label:'配置助手'},{value:'OFFICIAL_KNOWLEDGE',label:'官方知识库'}];
export default {
  data() { return { purposes,rows:[],loading:false,error:'',dialog:false,saving:false,saveError:'',action:'create',form:{},models:[],modelsLoading:false,modelError:'' }; },
  computed: { title() { return {create:'新建平台任务账户',grant:'发放任务预算'}[this.action]; } },
  created() { this.load(); if (this.can('credentials.read') || this.can('rates.read') || this.can('pool.adjust')) this.loadModels(); },
  methods: {
    amount: allowanceDisplayAmount,
    modelName(id) { return this.models.find(model => model.id === id)?.name || id; },
    async loadModels() { this.modelsLoading = true; this.modelError = ''; try { this.models = await operations.channelModels(); } catch(error) { this.modelError = error.message; } finally { this.modelsLoading = false; } },
    can(permission) { return hasPermission(this.$store.state.userInfo,permission); },
    values(value) { try { const result=typeof value==='string'?JSON.parse(value):value; return Array.isArray(result)?result:[]; } catch (_) { return []; } },
    purposeLabel(value) { return purposes.find(item=>item.value===value)?.label || value; },
    async load() { this.loading=true; this.error=''; try { this.rows=await operations.services(); } catch (error) { this.error=error.message; } finally { this.loading=false; } },
    open(action,row={}) { if (!this.can(action === 'create' ? 'pool.adjust' : 'allowance.grant') || !['create','grant'].includes(action)) return; this.action=action; this.form={requestId:newOperationId(),id:row.id||'',accountId:row.accountId,modelIds:[],purposes:[],amount:'',reason:'',provider:'DEEPSEEK'}; this.saveError=''; this.dialog=true; },
    async save() {
      if (this.saving || !this.can(this.action === 'create' ? 'pool.adjust' : 'allowance.grant') || !['create','grant'].includes(this.action)) return;
      if (this.action === 'create' && (this.modelsLoading || this.modelError)) { this.saveError = this.modelError || '正在读取模型，请稍后'; return; }
      const models=[...new Set(this.form.modelIds || [])];
      if (this.action==='create' && (!this.form.id.trim() || !models.length || !this.form.purposes.length)) { this.saveError='请填写账户名称、至少一个模型和任务类型'; return; }
      if (this.action==='grant' && (!/^\d+(\.\d{1,6})?$/.test(this.form.amount) || Number(this.form.amount)<=0 || !this.form.reason.trim())) { this.saveError='请填写正数金额及发放原因'; return; }
      this.saving=true; this.saveError='';
      try { if (this.action==='create') await operations.addService({id:this.form.id.trim(),modelIds:models,purposes:this.form.purposes}); else if (this.action==='grant') await operations.grantService({requestId:this.form.requestId,accountId:this.form.accountId,amount:this.form.amount,reason:this.form.reason});  this.dialog=false; this.$message.success('任务账户已更新'); await this.load(); } catch (error) { this.saveError=error.message; } finally { this.saving=false; }
    },
  },
};
</script>
