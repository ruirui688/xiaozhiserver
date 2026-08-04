<template>
  <section class="template-switcher" aria-labelledby="role-template-title">
    <div class="template-heading">
      <div>
        <div class="template-title-row">
          <span class="template-icon"><i class="el-icon-s-operation" /></span>
          <h3 id="role-template-title">一键切换角色模板</h3>
          <span class="template-count">{{ templates.length }} 个</span>
        </div>
        <p>选择模板后，模型、音色、热词、知识库、声纹和飞书配置将一起切换。</p>
      </div>
      <div class="template-tip">
        <i class="el-icon-warning-outline" />
        <span>点击模板后会先进行确认</span>
      </div>
    </div>

    <div v-if="templates.length" class="template-list">
      <div
        v-for="(template, index) in templates"
        :key="template.id || `template-${index}`"
        class="template-card"
        :class="{
          'template-loading': loading,
          'template-disabled': !workspaceCanWrite,
        }"
        role="button"
        :tabindex="loading ? -1 : 0"
        :aria-disabled="loading || !workspaceCanWrite ? 'true' : 'false'"
        @click="selectTemplate(template)"
        @keyup.enter="selectTemplate(template)"
      >
        <div class="template-avatar">
          {{ templateInitial(template) }}
        </div>
        <div class="template-info">
          <div class="template-name-row">
            <strong>{{ template.templateName || template.agentName }}</strong>
            <span
              class="template-scope"
              :class="template.scope === 'WORKSPACE' ? 'is-workspace' : 'is-system'"
            >
              {{ template.scope === 'WORKSPACE' ? '工作空间' : '系统预设' }}
            </span>
          </div>
          <span class="template-description">整套角色配置 · 一键应用</span>
        </div>
        <span class="template-use">
          {{ loading ? '切换中' : '使用' }}
          <i :class="loading ? 'el-icon-loading' : 'el-icon-arrow-right'" />
        </span>
        <button
          v-if="template.deletable"
          type="button"
          class="template-delete"
          title="删除此模板"
          aria-label="删除此模板"
          @click.stop="$emit('delete', template)"
        >
          <i class="el-icon-delete" />
        </button>
      </div>
    </div>

    <div v-else class="template-empty">
      <i class="el-icon-folder-opened" />
      <div>
        <strong>还没有可用模板</strong>
        <span>完成当前角色配置后，可在页面右上角保存为模板。</span>
      </div>
    </div>
  </section>
</template>

<script>
export default {
  name: "RoleTemplateSwitcher",
  props: {
    templates: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    workspaceCanWrite: { type: Boolean, default: true },
  },
  methods: {
    templateInitial(template) {
      const name = String(template.templateName || template.agentName || "角").trim();
      return name.charAt(0) || "角";
    },
    selectTemplate(template) {
      if (this.loading) return;
      this.$emit("select", template);
    },
  },
};
</script>

<style lang="scss" scoped>
.template-switcher {
  padding: 20px;
  border: 1px solid #dfe6ff;
  border-radius: 14px;
  background: linear-gradient(135deg, #f8faff 0%, #f4f6ff 100%);
}

.template-heading,
.template-title-row,
.template-tip,
.template-card,
.template-name-row,
.template-empty {
  display: flex;
  align-items: center;
}

.template-heading {
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.template-title-row {
  gap: 9px;
}

.template-title-row h3 {
  margin: 0;
  color: #27324b;
  font-size: 16px;
  line-height: 24px;
}

.template-heading p {
  margin: 5px 0 0 33px;
  color: #7b849d;
  font-size: 12px;
  line-height: 18px;
}

.template-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: #5778ff;
  color: #fff;
  font-size: 13px;
}

.template-count {
  padding: 2px 7px;
  border-radius: 10px;
  background: #e7ecff;
  color: #5778ff;
  font-size: 11px;
}

.template-tip {
  flex-shrink: 0;
  gap: 5px;
  color: #8b94aa;
  font-size: 12px;
}

.template-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 10px;
}

.template-card {
  position: relative;
  min-width: 0;
  min-height: 64px;
  padding: 10px 42px 10px 11px;
  border: 1px solid #e0e5f5;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(74, 96, 170, 0.04);
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.template-card:hover,
.template-card:focus {
  border-color: #9db0ff;
  box-shadow: 0 6px 16px rgba(87, 120, 255, 0.12);
  outline: none;
  transform: translateY(-1px);
}

.template-card.template-disabled {
  opacity: 0.72;
  cursor: not-allowed;
}

.template-avatar {
  display: flex;
  flex: 0 0 38px;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  margin-right: 10px;
  border-radius: 10px;
  background: #edf1ff;
  color: #5778ff;
  font-size: 16px;
  font-weight: 700;
}

.template-info {
  min-width: 0;
  flex: 1;
}

.template-name-row {
  min-width: 0;
  gap: 7px;
}

.template-name-row strong {
  overflow: hidden;
  color: #30384f;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-scope {
  flex-shrink: 0;
  padding: 1px 5px;
  border-radius: 4px;
  background: #f1f3f8;
  color: #828ba2;
  font-size: 10px;
}

.template-scope.is-workspace {
  background: #eaf7f2;
  color: #3f9b78;
}

.template-description {
  display: block;
  margin-top: 4px;
  color: #9aa2b5;
  font-size: 11px;
}

.template-use {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 2px;
  margin-left: 10px;
  color: #5778ff;
  font-size: 12px;
}

.template-delete {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #b2b8c8;
  cursor: pointer;
}

.template-delete:hover {
  background: #fff0f0;
  color: #f56c6c;
}

.template-empty {
  justify-content: center;
  gap: 12px;
  min-height: 74px;
  border: 1px dashed #ccd5f3;
  border-radius: 10px;
  color: #9aa3b8;
}

.template-empty > i {
  font-size: 25px;
}

.template-empty strong,
.template-empty span {
  display: block;
}

.template-empty strong {
  margin-bottom: 3px;
  color: #69738c;
  font-size: 13px;
}

.template-empty span {
  font-size: 11px;
}

@media (max-width: 1100px) {
  .template-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .template-tip {
    margin-left: 33px;
  }
}
</style>
