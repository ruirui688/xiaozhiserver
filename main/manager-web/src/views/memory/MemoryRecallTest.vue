<template>
  <div class="preview-panel">
    <p class="hint">根据已保存策略预览指定设备、人物的记忆上下文。不会调用对话模型；在线设备实际使用还受刷新状态和当前身份影响。</p>
    <el-form inline size="small">
      <el-form-item label="设备">
        <el-select v-model="deviceId" placeholder="选择设备" filterable :disabled="!policy">
          <el-option v-for="device in devices" :key="device.deviceId" :value="device.deviceId" :label="device.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="当前人物">
        <el-select v-model="speakerId" :disabled="!policy">
          <el-option value="" label="未识别人物（仅共享记忆）" />
          <el-option v-for="speaker in speakers" :key="speaker.speakerId" :value="speaker.speakerId" :label="speaker.label" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" :disabled="!policy || !deviceId" :loading="running" @click="runTest">预览实际注入</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="result">
      <div class="budget-row">
        <el-tag>字符 {{ result.usage && result.usage.chars || 0 }} / {{ result.budgets && result.budgets.maxChars }}</el-tag>
        <el-tag>条目 {{ result.usage && result.usage.items || 0 }} / {{ result.budgets && result.budgets.maxItems }}</el-tag>
        <el-tag :type="ready ? 'success' : 'warning'">{{ ready ? '快照可用' : '快照不可用' }}</el-tag>
      </div>
      <div class="budget-meters">
        <div><span>字符预算占用</span><el-progress :percentage="budgetPercent('chars', 'maxChars')" :stroke-width="8" /></div>
        <div><span>条目预算占用</span><el-progress :percentage="budgetPercent('items', 'maxItems')" :stroke-width="8" /></div>
      </div>
      <el-alert v-if="truncated" title="部分事实因候选数量或上下文预算限制未进入本次预览。" type="warning" :closable="false" show-icon />
      <p class="hint">已按设备范围、人物归属、有效状态、遗忘规则及上下文预算过滤。其他人物的私有记忆不会在此展示。</p>
      <ol v-if="selectedFacts.length" class="selected-facts" aria-label="按实际注入顺序排列的记忆">
        <li v-for="(text, index) in selectedFacts" :key="index"><span class="fact-order">{{ index + 1 }}</span><span>{{ text }}</span></li>
      </ol>
      <p v-else class="context">当前条件下没有可注入记忆。</p>
      <el-collapse v-if="result.selected_context"><el-collapse-item title="查看完整注入正文" name="context"><pre class="context">{{ result.selected_context }}</pre></el-collapse-item></el-collapse>
    </template>
    <p v-else-if="!running && !error" class="hint">选择设备和人物后查看当前服务端生成的上下文。修改条件、策略或事实后需要重新预览。</p>
  </div>
</template>

<script>
import Api from '@/apis/api'
import { responseData, memoryError } from './memoryPolicy.mjs'
import { boundedPercent } from './memoryVisualState.mjs'

export default {
  name: 'MemoryRecallTest',
  props: {
    agentId: { type: String, required: true },
    policy: { type: Object, default: null },
    revision: { type: Number, default: 0 }
  },
  data() { return { deviceId: '', speakerId: '', result: null, error: '', running: false, requestVersion: 0 } },
  computed: {
    devices() { return this.policy && this.policy.devices || [] },
    speakers() { return this.policy && this.policy.speakers || [] },
    ready() { return this.result && this.result.snapshot && this.result.snapshot.status === 'ready' },
    selectedFacts() { return String(this.result && this.result.selected_context || '').split('\n').filter(line => line.trim()).map(line => line.replace(/^- /, '')) },
    truncated() { return Boolean(this.result && ((this.result.snapshot || {}).truncated || (this.result.excluded || {}).truncated)) }
  },
  watch: {
    agentId() { this.deviceId = ''; this.speakerId = ''; this.invalidate() },
    deviceId() { this.invalidate() },
    speakerId() { this.invalidate() },
    revision() { this.invalidate() },
    policy() {
      if (!this.devices.some(item => item.deviceId === this.deviceId)) this.deviceId = ''
      if (!this.speakers.some(item => item.speakerId === this.speakerId)) this.speakerId = ''
      this.invalidate()
    }
  },
  beforeDestroy() { this.invalidate() },
  methods: {
    budgetPercent(used, max) { return Math.round(boundedPercent((this.result.usage || {})[used], (this.result.budgets || {})[max])) },
    invalidate() { this.requestVersion += 1; this.result = null; this.error = ''; this.running = false },
    runTest() {
      if (!this.policy || !this.deviceId || this.running) return
      const version = ++this.requestVersion
      const identity = [this.agentId, this.deviceId, this.speakerId, this.revision]
      const current = () => version === this.requestVersion && identity.every((value, index) => value === [this.agentId, this.deviceId, this.speakerId, this.revision][index])
      this.result = null
      this.error = ''
      this.running = true
      const failed = (error) => {
        if (!current()) return
        this.running = false
        this.error = memoryError(error, '读取实际注入预览失败，请重试')
      }
      Api.memory.preview(this.agentId, { deviceId: this.deviceId, speakerId: this.speakerId }, (res) => {
        if (!current()) return
        this.running = false
        this.result = responseData(res)
      }, failed, failed)
    }
  }
}
</script>

<style scoped>
.preview-panel { padding: 8px 16px 20px; }
.hint { color: #718096; line-height: 1.7; font-size: 13px; }
.budget-row { display: flex; gap: 10px; flex-wrap: wrap; margin: 14px 0; }
.context { white-space: pre-wrap; overflow-wrap: anywhere; background: #f7f8fc; padding: 18px; border-radius: 6px; line-height: 1.7; max-height: 420px; overflow: auto; }
.budget-meters { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin: 18px 0; }
.budget-meters span { display: block; font-size: 12px; color: #747e91; margin-bottom: 8px; }
.selected-facts { list-style: none; padding: 0; margin: 16px 0; }
.selected-facts li { display: flex; gap: 12px; padding: 12px 4px; border-bottom: 1px solid #e8ecf3; font-size: 14px; line-height: 1.65; color: #313d54; overflow-wrap: anywhere; }
.fact-order { flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; background: #edf2fc; color: #4669bc; border-radius: 6px; font-size: 12px; }
@media (max-width: 700px) { .budget-meters { grid-template-columns: 1fr; gap: 14px; } }
</style>
