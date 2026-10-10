<template>
  <div class="policy-panel" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-else-if="loaded && !canWrite" title="当前工作空间权限只允许查看记忆策略。" type="info" :closable="false" show-icon />
    <el-form :model="form" :disabled="!canWrite || saving || !loaded || busy" label-width="125px" size="small">
      <el-form-item label="记忆归属">
        <el-radio-group v-model="form.memoryScope">
          <el-radio-button label="AGENT">按智能体共享</el-radio-button>
          <el-radio-button label="DEVICE">按设备隔离</el-radio-button>
        </el-radio-group>
        <p>按智能体共享：同一智能体的多个设备使用同一份记忆。按设备隔离：只使用来源属于当前设备的记忆。切换范围会改变已有记忆的可见性。</p>
      </el-form-item>
      <el-form-item label="未识别人物">
        <el-select v-model="form.unknownSpeakerPolicy">
          <el-option label="可提取为共享记忆" value="SHARED" />
          <el-option label="不保存未识别人物的信息" value="IGNORE" />
        </el-select>
        <p>多人共用或公共设备建议选择不提取；身份不明时仍可读取当前空间已有的共享记忆。</p>
      </el-form-item>
      <el-form-item label="会话后自我维护">
        <el-select v-model="form.maintenanceMode">
          <el-option label="自动整理并生效" value="AUTO" />
          <el-option label="生成候选，人工确认" value="REVIEW" />
          <el-option label="关闭自动整理" value="OFF" />
        </el-select>
        <p>{{ maintenanceDescription }}</p>
      </el-form-item>
      <el-form-item label="生效记忆容量">
        <el-input-number v-model="form.activeMaxChars" :min="100" :max="20000" :step="100" /> 字
        <p>限制当前智能体的生效事实总量。超过上限时需要归档或整理，不能保证全部事实进入每轮对话。</p>
      </el-form-item>
      <el-form-item label="每轮记忆预算">
        <el-input-number v-model="form.contextMaxChars" :min="200" :max="4000" :step="100" /> 字，
        <el-input-number v-model="form.contextMaxItems" :min="1" :max="30" /> 条
        <p>通过“实际注入预览”检查指定设备和人物在此预算下会使用哪些记忆。</p>
      </el-form-item>
      <el-form-item label="保留天数">
        <el-input-number v-model="form.retentionDays" :min="0" :max="36500" :step="1" /> 天
        <p>0 表示不按天数到期；正数为新建或自动更新的记忆设置期限，不追溯修改旧事实。到期后停止用于对话，审计记录仍保留。</p>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存策略</el-button>
      </el-form-item>
    </el-form>
    <el-button size="small" :disabled="loading || saving" @click="load">重新读取</el-button>
  </div>
</template>

<script>
import Api from '@/apis/api'
import { policyFields, responseData, memoryError } from './memoryPolicy.mjs'

export default {
  name: 'MemoryPolicyPanel',
  props: { agentId: { type: String, required: true }, busy: { type: Boolean, default: false } },
  data() { return { form: policyFields(), canWrite: false, loaded: false, loading: false, saving: false, error: '', requestVersion: 0 } },
  computed: {
    maintenanceDescription() {
      return {
        AUTO: '会话结束后由记忆模型提取、合并和修正事实，通过校验后自动生效。',
        REVIEW: '会话结束后只生成待确认候选，确认后才改变生效记忆。',
        OFF: '停止会话结束后的自动整理；已有记忆仍可读取，也可手工编辑或发起重审。'
      }[this.form.maintenanceMode]
    }
  },
  watch: { agentId: { immediate: true, handler() { this.load() } } },
  beforeDestroy() { this.requestVersion += 1 },
  methods: {
    load(afterSave = false) {
      const version = ++this.requestVersion
      const agentId = this.agentId
      this.loaded = false
      this.canWrite = false
      this.loading = false
      this.error = ''
      this.$emit('loaded', null)
      if (!this.agentId) return
      this.loading = true
      const failed = (error) => {
        if (version !== this.requestVersion || agentId !== this.agentId) return
        this.loading = this.saving = false
        this.error = memoryError(error, '记忆策略读取失败，请重试')
      }
      Api.memory.policy(this.agentId, (res) => {
        if (version !== this.requestVersion || agentId !== this.agentId) return
        const data = responseData(res)
        this.form = policyFields(data)
        this.canWrite = data.canWrite === true
        this.loaded = true
        this.loading = this.saving = false
        this.$emit('loaded', data)
        if (afterSave === true) {
          this.$message.success('记忆策略已保存')
          this.$emit('saved')
        }
      }, failed, failed)
    },
    save() {
      if (!this.canWrite || !this.loaded || this.saving || this.busy) return
      const version = ++this.requestVersion
      const agentId = this.agentId
      this.saving = true
      this.error = ''
      const failed = (error) => {
        if (version !== this.requestVersion || agentId !== this.agentId) return
        this.saving = false
        this.error = memoryError(error, '保存结果未确认，请重新读取策略后重试')
      }
      Api.memory.savePolicy(this.agentId, policyFields(this.form), () => {
        if (version === this.requestVersion && agentId === this.agentId) this.load(true)
      }, failed, failed)
    }
  }
}
</script>

<style scoped>
.policy-panel { padding: 8px 16px 20px; max-width: 940px; }
.el-alert { margin-bottom: 18px; }
p { color: #718096; font-size: 12px; line-height: 1.6; margin: 6px 0 0; }
</style>
