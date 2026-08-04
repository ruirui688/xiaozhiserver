<template>
  <section class="template-switcher" aria-labelledby="role-template-title">
    <div class="template-copy">
      <span class="template-icon"><i class="el-icon-s-operation" /></span>
      <div>
        <div class="template-title-row">
          <h3 id="role-template-title">角色模板</h3>
          <span class="template-count">{{ templates.length }}</span>
        </div>
        <p>一次切换模型、音色、热词、知识库、声纹和飞书配置</p>
      </div>
    </div>

    <div v-if="templates.length" class="template-actions">
      <el-select
        v-model="selectedTemplateId"
        filterable
        class="template-select"
        placeholder="选择要应用的角色模板"
        aria-label="选择角色模板"
      >
        <el-option
          v-for="(template, index) in templates"
          :key="template.id || `template-${index}`"
          :value="template.id"
          :label="template.templateName || template.agentName"
        >
          <div class="template-option">
            <span class="template-option__avatar">{{ templateInitial(template) }}</span>
            <span class="template-option__name">{{ template.templateName || template.agentName }}</span>
            <span
              class="template-scope"
              :class="template.scope === 'WORKSPACE' ? 'is-workspace' : 'is-system'"
            >
              {{ template.scope === 'WORKSPACE' ? '工作空间' : '系统' }}
            </span>
          </div>
        </el-option>
      </el-select>
      <el-button
        type="primary"
        icon="el-icon-refresh"
        :loading="loading"
        :disabled="!selectedTemplate || !workspaceCanWrite"
        class="template-apply"
        @click="applySelected"
      >
        应用模板
      </el-button>
      <el-button
        v-if="selectedTemplate && selectedTemplate.deletable"
        type="text"
        icon="el-icon-delete"
        class="template-delete"
        aria-label="删除选中的角色模板"
        @click="$emit('delete', selectedTemplate)"
      >
        删除
      </el-button>
      <span v-if="!workspaceCanWrite" class="template-readonly">当前工作空间只读</span>
    </div>

    <div v-else class="template-empty">
      暂无模板，可使用右上角“另存为角色模板”创建
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
  data() {
    return {
      selectedTemplateId: "",
    };
  },
  computed: {
    selectedTemplate() {
      return this.templates.find((template) => template.id === this.selectedTemplateId) || null;
    },
  },
  watch: {
    templates: {
      immediate: true,
      handler(templates) {
        if (!templates.some((template) => template.id === this.selectedTemplateId)) {
          this.selectedTemplateId = templates.length === 1 ? templates[0].id : "";
        }
      },
    },
  },
  methods: {
    templateInitial(template) {
      const name = String(template.templateName || template.agentName || "角").trim();
      return name.charAt(0) || "角";
    },
    applySelected() {
      if (this.loading || !this.workspaceCanWrite || !this.selectedTemplate) return;
      this.$emit("select", this.selectedTemplate);
    },
  },
};
</script>

<style lang="scss" scoped>
.template-switcher {
  display: flex;
  min-height: 68px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 11px 14px;
  border: 1px solid #e4e9f4;
  border-radius: 11px;
  background: #f8faff;
  box-sizing: border-box;
}

.template-copy,
.template-title-row,
.template-actions,
.template-option {
  display: flex;
  align-items: center;
}

.template-copy {
  min-width: 260px;
  gap: 10px;
}

.template-title-row {
  gap: 7px;
}

.template-title-row h3 {
  margin: 0;
  color: #27324b;
  font-size: 14px;
  line-height: 20px;
}

.template-copy p {
  margin: 2px 0 0;
  color: #7b849d;
  font-size: 11px;
  line-height: 16px;
}

.template-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: 7px;
  background: #5778ff;
  color: #fff;
  font-size: 13px;
}

.template-count {
  min-width: 20px;
  padding: 1px 5px;
  border-radius: 10px;
  background: #e7ecff;
  color: #5778ff;
  font-size: 10px;
  text-align: center;
}

.template-actions {
  flex: 1;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}

.template-select {
  width: min(360px, 42vw);
}

.template-apply {
  flex-shrink: 0;
}

.template-option {
  gap: 8px;
}

.template-option__avatar {
  display: inline-flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: #edf1ff;
  color: #5778ff;
  font-size: 12px;
  font-weight: 700;
}

.template-option__name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
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

.template-delete {
  margin: 0;
  color: #9aa2b3;
}

.template-delete:hover {
  background: #fff0f0;
  color: #f56c6c;
}

.template-empty {
  color: #929bad;
  font-size: 12px;
  text-align: right;
}

.template-readonly {
  flex-shrink: 0;
  color: #9aa3b8;
  font-size: 11px;
}

@media (max-width: 1100px) {
  .template-switcher {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .template-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .template-select {
    flex: 1;
    width: auto;
  }
}
</style>
