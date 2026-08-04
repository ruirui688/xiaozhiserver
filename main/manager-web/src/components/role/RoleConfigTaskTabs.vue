<template>
  <nav class="role-task-tabs" aria-label="角色配置任务导航">
    <button
      v-for="panel in panels"
      :key="panel.key"
      type="button"
      :class="{ active: value === panel.key }"
      @click="$emit('input', panel.key)"
    >
      <i :class="panel.icon" />{{ panel.label }}
    </button>
  </nav>
</template>

<script>
import { ROLE_CONFIG_PANELS } from "@/utils/roleConfigPresentation";

export default {
  name: "RoleConfigTaskTabs",
  props: {
    value: { type: String, default: "basic" },
  },
  data() {
    return {
      panels: ROLE_CONFIG_PANELS,
    };
  },
};
</script>

<style lang="scss" scoped>
.role-task-tabs {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 0;
  overflow-x: auto;
}

.role-task-tabs button {
  display: inline-flex;
  min-width: 104px;
  height: 32px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 0 13px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #5f6881;
  cursor: pointer;
  font-size: 12px;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.role-task-tabs button:hover,
.role-task-tabs button:focus,
.role-task-tabs button.active {
  background: #edf1ff;
  color: #5778ff;
  outline: none;
}

.role-task-tabs button.active {
  font-weight: 600;
  box-shadow: inset 0 0 0 1px #dce4ff;
}

@media (max-width: 1250px) {
  .role-task-tabs button {
    flex-shrink: 0;
  }
}
</style>
