<template>
  <el-dialog
    custom-class="agent-template-preview-dialog"
    :visible="visible"
    :close-on-click-modal="false"
    width="92%"
    append-to-body
    @close="$emit('update:visible', false)"
  >
    <template slot="title">
      <div class="preview-title">
        <span class="preview-title__back"><i class="el-icon-back" /></span>
        <div>
          <strong>模板预览</strong>
          <span v-if="template">{{ template.templateName }}</span>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="preview-shell">
      <template v-if="preview && template">
        <aside class="preview-identity">
          <span :class="['scope-pill', `scope-${template.scope || 'WORKSPACE'}`]">
            {{ scopeLabel(template.scope) }}
          </span>
          <div class="template-avatar">{{ avatarText }}</div>
          <h2>{{ template.templateName }}</h2>
          <p>{{ template.agentName || '未设置角色名称' }}</p>
          <dl>
            <div><dt>版本</dt><dd>v{{ template.version }}</dd></div>
            <div><dt>交互语种</dt><dd>{{ preview.language || '跟随智能体默认值' }}</dd></div>
            <div><dt>上下文源</dt><dd>{{ preview.contextSourceCount || 0 }} 个</dd></div>
          </dl>
        </aside>

        <main class="preview-content">
          <section>
            <div class="section-heading">
              <div><span>01</span><h3>角色设定</h3></div>
              <small>创建前只读预览</small>
            </div>
            <div class="prompt-preview">{{ preview.systemPrompt || '该模板未设置角色提示词。' }}</div>
          </section>

          <section>
            <div class="section-heading"><div><span>02</span><h3>模型与能力</h3></div></div>
            <div class="model-grid">
              <div v-for="(name, type) in preview.modelNames" :key="type" class="model-item">
                <small>{{ modelLabel(type) }}</small><strong>{{ name }}</strong>
              </div>
              <div v-if="!hasModels" class="empty-inline">模型配置将使用工作空间默认值</div>
            </div>
            <div class="chip-group">
              <span v-for="item in preview.capabilityNames" :key="`cap-${item}`">{{ item }}</span>
              <span v-for="item in preview.skillNames" :key="`skill-${item}`">语义：{{ item }}</span>
              <span v-for="item in preview.tagNames" :key="`tag-${item}`"># {{ item }}</span>
              <em v-if="!hasCapabilities">未配置额外能力</em>
            </div>
          </section>
        </main>

        <aside class="preview-action">
          <div class="copy-plan">
            <span class="copy-plan__icon"><i class="el-icon-document-checked" /></span>
            <h3>应用边界</h3>
            <p>确认后才会创建智能体，预览本身不会调用模型或产生模型费用。</p>
            <ul>
              <li><i class="el-icon-check" />复制人设、模型、技能和可访问知识库</li>
              <li><i class="el-icon-check" />复制标签和安全的上下文源配置</li>
              <li class="excluded"><i class="el-icon-close" />不绑定 TTS 模型、音色或实时音色</li>
              <li class="excluded"><i class="el-icon-close" />不复制设备绑定和敏感请求头</li>
            </ul>
          </div>
          <div v-if="preview.skippedItems && preview.skippedItems.length" class="preview-warning">
            <i class="el-icon-warning-outline" />
            <span>{{ preview.skippedItems.join('；') }}</span>
          </div>
          <el-button type="primary" :disabled="!canWrite || quotaFull" @click="$emit('create', template)">
            使用此模板
          </el-button>
          <small v-if="quotaFull">当前工作空间智能体数量已达上限</small>
          <small v-else-if="!canWrite">当前工作空间为只读</small>
          <small v-else>创建后可在角色配置中单独试听并选择音色</small>
        </aside>
      </template>
    </div>
  </el-dialog>
</template>

<script>
export default {
  name: 'AgentTemplatePreviewDialog',
  props: {
    visible: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
    preview: { type: Object, default: null },
    canWrite: { type: Boolean, default: true },
    quotaFull: { type: Boolean, default: false }
  },
  computed: {
    template() {
      return this.preview && this.preview.template;
    },
    avatarText() {
      const name = (this.template && (this.template.agentName || this.template.templateName)) || '智';
      return name.trim().slice(0, 1);
    },
    hasModels() {
      return Boolean(this.preview && this.preview.modelNames && Object.keys(this.preview.modelNames).length);
    },
    hasCapabilities() {
      if (!this.preview) return false;
      return ['capabilityNames', 'skillNames', 'tagNames']
        .some(key => Array.isArray(this.preview[key]) && this.preview[key].length);
    }
  },
  methods: {
    scopeLabel(scope) {
      return { OFFICIAL: '官方模板', PUBLIC: '模板中心', WORKSPACE: '工作空间' }[scope] || '官方模板';
    },
    modelLabel(type) {
      return { ASR: '语音识别', LLM: '语言模型', Memory: '长期记忆', Intent: '意图识别' }[type] || type;
    }
  }
}
</script>

<style scoped>
.preview-shell {
  min-height: 560px;
  display: grid;
  grid-template-columns: minmax(220px, 0.72fr) minmax(420px, 1.75fr) minmax(260px, 0.85fr);
  gap: 18px;
  padding: 4px 4px 12px;
  color: #303852;
}

.preview-title { display: flex; align-items: center; gap: 14px; }
.preview-title__back { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid #dce3f3; border-radius: 10px; }
.preview-title div { display: flex; align-items: baseline; gap: 12px; }
.preview-title strong { font-size: 22px; }
.preview-title span { color: #8a93a9; }

.preview-identity,
.preview-content,
.preview-action { border: 1px solid #e5eaf4; border-radius: 18px; background: #fff; }
.preview-identity { padding: 28px; text-align: center; }
.scope-pill { display: inline-flex; align-self: flex-start; padding: 5px 10px; border-radius: 8px; font-size: 12px; }
.scope-OFFICIAL { color: #a86500; background: #fff0ca; }
.scope-PUBLIC { color: #3867d8; background: #e7efff; }
.scope-WORKSPACE { color: #587087; background: #edf2f7; }
.template-avatar { display: grid; place-items: center; width: 104px; height: 104px; margin: 42px auto 22px; border-radius: 32px; color: #fff; font-size: 40px; font-weight: 700; background: linear-gradient(145deg, #5478ff, #8d6cf3); box-shadow: 0 18px 36px rgba(83, 103, 206, .26); }
.preview-identity h2 { margin: 0 0 8px; font-size: 23px; }
.preview-identity > p { margin: 0; color: #7e879e; }
.preview-identity dl { margin: 44px 0 0; text-align: left; }
.preview-identity dl div { display: flex; justify-content: space-between; gap: 16px; padding: 14px 0; border-top: 1px solid #eef1f7; }
.preview-identity dt { color: #929aaf; }
.preview-identity dd { margin: 0; text-align: right; }

.preview-content { padding: 8px 28px 28px; overflow: auto; }
.preview-content section { padding-top: 24px; }
.preview-content section + section { margin-top: 24px; border-top: 1px solid #edf0f6; }
.section-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.section-heading div { display: flex; align-items: center; gap: 10px; }
.section-heading span { color: #5577ed; font-weight: 700; }
.section-heading h3 { margin: 0; font-size: 18px; }
.section-heading small { color: #9ca4b7; }
.prompt-preview { min-height: 210px; max-height: 320px; overflow: auto; padding: 22px; border-radius: 14px; color: #4e5872; line-height: 1.85; white-space: pre-wrap; background: #f7f9fd; }
.model-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.model-item { display: flex; flex-direction: column; gap: 7px; padding: 15px; border: 1px solid #e6ebf6; border-radius: 12px; }
.model-item small { color: #8d96ab; }
.model-item strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-inline { grid-column: 1 / -1; padding: 20px; color: #98a0b2; text-align: center; background: #f8f9fc; border-radius: 12px; }
.chip-group { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
.chip-group span { padding: 7px 10px; color: #526685; background: #eef3fb; border-radius: 8px; font-size: 12px; }
.chip-group em { color: #9aa2b5; font-style: normal; }

.preview-action { display: flex; flex-direction: column; padding: 28px; background: linear-gradient(180deg, #f8faff, #fff); }
.copy-plan__icon { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 14px; color: #5274eb; font-size: 22px; background: #e9efff; }
.copy-plan h3 { margin: 20px 0 10px; font-size: 20px; }
.copy-plan p { color: #747e97; line-height: 1.7; }
.copy-plan ul { margin: 24px 0 0; padding: 0; list-style: none; }
.copy-plan li { display: flex; gap: 9px; margin: 14px 0; color: #59647e; line-height: 1.5; }
.copy-plan li i { margin-top: 3px; color: #25aa70; }
.copy-plan li.excluded i { color: #ef7878; }
.preview-warning { display: flex; gap: 8px; margin-top: auto; padding: 12px; color: #a56b15; font-size: 12px; line-height: 1.5; background: #fff8e8; border-radius: 10px; }
.preview-action > .el-button { width: 100%; margin-top: 14px; }
.preview-action > small { margin-top: 10px; color: #8e97aa; text-align: center; line-height: 1.5; }

@media (max-width: 1100px) {
  .preview-shell { grid-template-columns: 1fr; }
  .preview-identity dl { margin-top: 24px; }
  .preview-action { min-height: 420px; }
}
</style>

<style>
.agent-template-preview-dialog { max-width: 1520px; border-radius: 22px; }
.agent-template-preview-dialog .el-dialog__body { padding: 4px 22px 22px; }
</style>
