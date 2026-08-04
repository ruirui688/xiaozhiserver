<template>
  <nav class="resource-nav" aria-label="资源中心导航">
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
          label: "知识库",
          path: "/knowledge-base-management",
          related: ["/knowledge-file-upload"],
          icon: "el-icon-reading"
        },
        {
          label: "人物与人脸",
          path: "/person-profile-management",
          related: [],
          icon: "el-icon-user"
        },
        {
          label: "插件",
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
  min-height: 48px;
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid #e8edf8;
  box-shadow: 0 4px 14px rgba(55, 76, 126, 0.04);
}

.resource-nav__tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.resource-tab {
  display: inline-flex;
  min-width: 112px;
  height: 36px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 14px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #667085;
  text-align: left;
  cursor: pointer;
  transition: 0.2s ease;
}

.resource-tab i {
  color: #8d96aa;
  font-size: 16px;
}

.resource-tab span {
  color: #30374b;
  font-size: 13px;
  font-weight: 600;
}

.resource-tab:hover {
  background: #f7f9fd;
}

.resource-tab.active {
  background: #edf3ff;
  box-shadow: inset 0 0 0 1px #dbe6ff;
}

.resource-tab.active i,
.resource-tab.active span {
  color: #3675d8;
}

@media (max-width: 1120px) {
  .resource-nav {
    overflow-x: auto;
  }
}
</style>
