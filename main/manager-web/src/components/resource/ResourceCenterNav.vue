<template>
  <nav class="resource-nav" aria-label="资源中心导航">
    <div class="resource-nav__intro">
      <span class="resource-nav__eyebrow">RESOURCE CENTER</span>
      <strong>资源中心</strong>
      <span>统一维护可复用的知识、人物和插件能力</span>
    </div>
    <div class="resource-nav__tabs">
      <button
        v-for="item in items"
        :key="item.path"
        type="button"
        :class="['resource-tab', { active: isActive(item) }]"
        @click="go(item.path)"
      >
        <i :class="item.icon" />
        <span>{{ item.label }}</span>
        <small>{{ item.hint }}</small>
      </button>
    </div>
  </nav>
</template>

<script>
export default {
  name: "ResourceCenterNav",
  data() {
    return {
      items: [
        {
          label: "知识库管理",
          hint: "资料与文档",
          path: "/knowledge-base-management",
          related: ["/knowledge-file-upload"],
          icon: "el-icon-reading"
        },
        {
          label: "用户信息管理",
          hint: "人物与人脸",
          path: "/person-profile-management",
          related: [],
          icon: "el-icon-user"
        },
        {
          label: "插件管理",
          hint: "工具与玩法",
          path: "/function-plugin-management",
          related: [],
          icon: "el-icon-connection"
        }
      ]
    };
  },
  methods: {
    isActive(item) {
      return this.$route.path === item.path || item.related.includes(this.$route.path);
    },
    go(path) {
      if (this.$route.path !== path) {
        this.$router.push(path);
      }
    }
  }
};
</script>

<style lang="scss" scoped>
.resource-nav {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 14px 24px;
  background: rgba(255, 255, 255, 0.86);
  border-bottom: 1px solid #e8edf8;
  box-shadow: 0 6px 24px rgba(55, 76, 126, 0.06);
}

.resource-nav__intro {
  min-width: 250px;
  display: grid;
  gap: 2px;
  color: #79829a;
  font-size: 12px;
}

.resource-nav__intro strong {
  color: #20263a;
  font-size: 20px;
}

.resource-nav__eyebrow {
  color: #d38f13;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1.5px;
}

.resource-nav__tabs {
  display: flex;
  align-items: stretch;
  gap: 10px;
  min-width: 0;
}

.resource-tab {
  display: grid;
  grid-template-columns: 28px auto;
  grid-template-rows: auto auto;
  column-gap: 8px;
  min-width: 150px;
  padding: 9px 14px;
  border: 1px solid transparent;
  border-radius: 13px;
  background: transparent;
  color: #667085;
  text-align: left;
  cursor: pointer;
  transition: 0.2s ease;
}

.resource-tab i {
  grid-row: 1 / 3;
  align-self: center;
  color: #8d96aa;
  font-size: 21px;
}

.resource-tab span {
  color: #30374b;
  font-size: 14px;
  font-weight: 600;
}

.resource-tab small {
  color: #98a0b3;
  font-size: 11px;
}

.resource-tab:hover {
  background: #f7f9fd;
}

.resource-tab.active {
  border-color: #f2c463;
  background: linear-gradient(135deg, #fff8e6, #fff);
  box-shadow: 0 5px 16px rgba(213, 153, 37, 0.12);
}

.resource-tab.active i,
.resource-tab.active span {
  color: #b87800;
}

@media (max-width: 1120px) {
  .resource-nav__intro {
    display: none;
  }

  .resource-nav {
    overflow-x: auto;
  }
}
</style>
