<template>
  <section class="template-switcher" aria-label="角色模板切换">
    <div class="template-label">
      <i class="el-icon-s-operation" />
      <span>模板</span>
      <span class="template-count">{{ templates.length }}</span>
    </div>

    <div v-if="templates.length" class="template-actions">
      <el-select
        v-model="selectedTemplateId"
        filterable
        size="small"
        class="template-select"
        placeholder="选择角色模板"
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
        size="small"
        icon="el-icon-refresh"
        :loading="loading"
        :disabled="!selectedTemplate || !workspaceCanWrite"
        class="template-apply"
        @click="applySelected"
      >
        应用
      </el-button>
      <el-button
        v-if="selectedTemplate && selectedTemplate.deletable"
        type="text"
        icon="el-icon-delete"
        class="template-delete"
        aria-label="删除选中的角色模板"
        @click="$emit('delete', selectedTemplate)"
      />
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
  flex: 0 1 500px;
  min-width: 390px;
  height: 34px;
  align-items: center;
  justify-content: flex-end;
  gap: 9px;
  margin-left: auto;
  padding-left: 12px;
  border-left: 1px solid #e8ebf5;
  box-sizing: border-box;
}

.template-label,
.template-actions,
.template-option {
  display: flex;
  align-items: center;
}

.template-label {
  flex-shrink: 0;
  gap: 5px;
  color: #5f6881;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.template-label i {
  color: #6d89ff;
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
  flex: 0 1 auto;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
}

.template-select {
  width: min(260px, 24vw);
}

.template-apply {
  flex-shrink: 0;
  height: 30px;
  padding: 7px 11px;
  border-radius: 7px;
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
  width: 28px;
  height: 28px;
  margin: 0;
  padding: 0;
  color: #9aa2b3;
}

.template-delete:hover {
  background: #fff0f0;
  color: #f56c6c;
}

.template-empty {
  overflow: hidden;
  color: #929bad;
  font-size: 11px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-readonly {
  flex-shrink: 0;
  color: #9aa3b8;
  font-size: 11px;
}

@media (max-width: 1100px) {
  .template-switcher {
    flex: 1 1 100%;
    width: 100%;
    min-width: 0;
    height: 39px;
    justify-content: flex-start;
    margin-left: 0;
    padding: 5px 0 0;
    border-top: 1px solid #edf0f6;
    border-left: 0;
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
