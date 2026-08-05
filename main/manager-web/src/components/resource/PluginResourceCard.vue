<template>
  <article
    :class="['plugin-resource-card', `plugin-resource-card--${layout}`]"
    tabindex="0"
    @click="$emit('detail', plugin)"
    @keyup.enter="$emit('detail', plugin)"
  >
    <div class="card-topline">
      <div class="plugin-type">
        <span :class="['type-badge', { 'type-badge--interaction': interaction }]">
          {{ interaction ? "互动" : "工具" }}
        </span>
        <span>{{ category }}</span>
      </div>
      <span :class="['config-badge', { 'config-badge--ready': configurable && configured }]">
        {{ configurable ? (configured ? "密钥已配置" : "待配置密钥") : configText }}
      </span>
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
      <template v-if="configurable">
        <el-button size="mini" type="primary" plain @click="$emit('configure', plugin)">
          配置
        </el-button>
        <el-button
          size="mini"
          :loading="testing"
          :disabled="!configured"
          @click="$emit('test', plugin)"
        >测试</el-button>
      </template>
      <el-button size="mini" type="text" @click="$emit('detail', plugin)">
        查看说明
      </el-button>
      <template v-if="interaction && canManage">
        <el-button size="mini" type="primary" plain @click="$emit('edit', plugin)">编辑</el-button>
        <el-dropdown trigger="click" @command="$emit('command', plugin, $event)">
          <el-button size="mini">更多 <i class="el-icon-arrow-down" /></el-button>
          <el-dropdown-menu slot="dropdown">
            <el-dropdown-item command="duplicate">复制玩法</el-dropdown-item>
            <el-dropdown-item command="delete" class="danger-action">删除</el-dropdown-item>
          </el-dropdown-menu>
        </el-dropdown>
      </template>
    </div>
  </article>
</template>

<script>
export default {
  name: "PluginResourceCard",
  props: {
    plugin: { type: Object, required: true },
    layout: { type: String, default: "grid" },
    interaction: { type: Boolean, default: false },
    canManage: { type: Boolean, default: false },
    category: { type: String, default: "工具能力" },
    categoryKey: { type: String, default: "tool" },
    description: { type: String, default: "" },
    example: { type: String, default: "" },
    configText: { type: String, default: "角色中启用" },
    configurable: { type: Boolean, default: false },
    configured: { type: Boolean, default: false },
    testing: { type: Boolean, default: false }
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
  min-height: 236px;
  padding: 17px;
  border: 1px solid #e6eaf2;
  border-radius: 13px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(48, 63, 96, 0.04);
  box-sizing: border-box;
  cursor: pointer;
  flex-direction: column;
  outline: none;
  transition: 0.2s ease;
}

.plugin-resource-card:hover,
.plugin-resource-card:focus {
  transform: translateY(-1px);
  border-color: #9dbcf5;
  box-shadow: 0 10px 24px rgba(65, 105, 180, 0.1);
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
  background: #4f86e8;
  color: #fff;
  font-weight: 700;
}

.type-badge--interaction {
  background: #9a6be8;
}

.config-badge {
  padding: 3px 8px;
  border: 1px solid #e2e6ee;
  border-radius: 99px;
  color: #7b8395;
}

.config-badge--ready {
  border-color: #b9e4d1;
  background: #effaf5;
  color: #27835f;
}

.plugin-heading {
  gap: 12px;
  margin-top: 15px;
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
  font-size: 17px;
}

.plugin-heading code {
  color: #9aa1b1;
  font-size: 11px;
}

.description {
  display: -webkit-box;
  min-height: 42px;
  margin: 14px 0 11px;
  overflow: hidden;
  color: #697187;
  font-size: 13px;
  line-height: 21px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
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
  padding-top: 13px;
}

.plugin-resource-card--list {
  display: grid;
  grid-template-columns: 128px minmax(210px, 0.8fr) minmax(240px, 1.2fr) minmax(210px, 0.9fr) auto;
  align-items: center;
  gap: 16px;
  min-height: 104px;
  padding: 14px 16px;
}

.plugin-resource-card--list .card-topline {
  align-items: flex-start;
  flex-direction: column;
  gap: 7px;
}

.plugin-resource-card--list .plugin-heading {
  margin-top: 0;
}

.plugin-resource-card--list .plugin-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  font-size: 19px;
}

.plugin-resource-card--list .description {
  min-height: auto;
  margin: 0;
}

.plugin-resource-card--list .example {
  min-width: 0;
}

.plugin-resource-card--list .card-actions {
  margin: 0;
  padding: 0;
  white-space: nowrap;
}

@media (max-width: 1450px) {
  .plugin-resource-card--list {
    grid-template-columns: 120px minmax(190px, 0.8fr) minmax(220px, 1.2fr) auto;
  }

  .plugin-resource-card--list .example {
    display: none;
  }
}

.danger-action {
  color: #f56c6c;
}
</style>
