<template>
  <article class="plugin-resource-card" tabindex="0" @click="$emit('detail', plugin)">
    <div class="card-topline">
      <div class="plugin-type">
        <span class="type-badge">{{ interaction ? "互动" : "工具" }}</span>
        <span>{{ category }}</span>
      </div>
      <span class="config-badge">{{ configText }}</span>
    </div>

    <div class="plugin-heading">
      <div :class="['plugin-icon', `plugin-icon--${categoryKey}`]">
        <i :class="iconClass" />
      </div>
      <div>
        <h3>{{ plugin.name }}</h3>
        <code>{{ plugin.providerCode }}</code>
      </div>
    </div>

    <p class="description">{{ description }}</p>
    <div class="example">
      <span>用户可以说</span>
      <strong>“{{ example }}”</strong>
    </div>

    <div class="card-actions" @click.stop>
      <template v-if="interaction && canManage">
        <el-button size="mini" type="primary" plain @click="$emit('edit', plugin)">编辑玩法</el-button>
        <el-dropdown trigger="click" @command="$emit('command', plugin, $event)">
          <el-button size="mini">更多 <i class="el-icon-arrow-down" /></el-button>
          <el-dropdown-menu slot="dropdown">
            <el-dropdown-item command="duplicate">复制玩法</el-dropdown-item>
            <el-dropdown-item command="delete" class="danger-action">删除</el-dropdown-item>
          </el-dropdown-menu>
        </el-dropdown>
      </template>
      <el-button v-else size="mini" type="text" @click="$emit('detail', plugin)">
        查看能力说明 <i class="el-icon-arrow-right" />
      </el-button>
    </div>
  </article>
</template>

<script>
export default {
  name: "PluginResourceCard",
  props: {
    plugin: { type: Object, required: true },
    interaction: { type: Boolean, default: false },
    canManage: { type: Boolean, default: false },
    category: { type: String, default: "工具能力" },
    categoryKey: { type: String, default: "tool" },
    description: { type: String, default: "" },
    example: { type: String, default: "" },
    configText: { type: String, default: "角色中启用" }
  },
  computed: {
    iconClass() {
      const icons = {
        life: "el-icon-cloudy",
        info: "el-icon-search",
        media: "el-icon-headset",
        device: "el-icon-cpu",
        chat: "el-icon-chat-dot-round",
        tool: "el-icon-connection"
      };
      return icons[this.categoryKey] || icons.tool;
    }
  }
};
</script>

<style lang="scss" scoped>
.plugin-resource-card {
  display: flex;
  min-height: 270px;
  padding: 20px;
  border: 1px solid #e6eaf2;
  border-radius: 17px;
  background: #fff;
  box-shadow: 0 8px 22px rgba(48, 63, 96, 0.06);
  box-sizing: border-box;
  cursor: pointer;
  flex-direction: column;
  outline: none;
  transition: 0.2s ease;
}

.plugin-resource-card:hover,
.plugin-resource-card:focus {
  transform: translateY(-2px);
  border-color: #efbd4f;
  box-shadow: 0 14px 34px rgba(181, 126, 22, 0.14);
}

.card-topline,
.plugin-type,
.plugin-heading,
.card-actions {
  display: flex;
  align-items: center;
}

.card-topline {
  justify-content: space-between;
  color: #8a92a4;
  font-size: 12px;
}

.plugin-type {
  gap: 7px;
}

.type-badge {
  padding: 3px 8px;
  border-radius: 99px;
  background: #f8b72f;
  color: #fff;
  font-weight: 700;
}

.config-badge {
  padding: 3px 8px;
  border: 1px solid #e2e6ee;
  border-radius: 99px;
  color: #7b8395;
}

.plugin-heading {
  gap: 12px;
  margin-top: 18px;
}

.plugin-icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 13px;
  background: #fff2cf;
  color: #c98500;
  font-size: 22px;
}

.plugin-icon--info { background: #eaf2ff; color: #427bd7; }
.plugin-icon--media { background: #f2eaff; color: #8b57d4; }
.plugin-icon--device { background: #e8f7f1; color: #2e9d72; }
.plugin-icon--chat { background: #fff0f5; color: #d05b87; }

.plugin-heading h3 {
  margin: 0 0 4px;
  color: #242a3a;
  font-size: 20px;
}

.plugin-heading code {
  color: #9aa1b1;
  font-size: 11px;
}

.description {
  min-height: 44px;
  margin: 16px 0 12px;
  color: #697187;
  font-size: 13px;
  line-height: 22px;
}

.example {
  display: grid;
  gap: 4px;
  padding: 11px 12px;
  border-radius: 10px;
  background: #f7f8fb;
}

.example span {
  color: #9aa1b0;
  font-size: 11px;
}

.example strong {
  overflow: hidden;
  color: #525b70;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-actions {
  justify-content: flex-end;
  gap: 8px;
  margin-top: auto;
  padding-top: 16px;
}

.danger-action {
  color: #f56c6c;
}
</style>
