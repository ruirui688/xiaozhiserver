<template>
  <el-header class="header">
    <OpsHeader v-if="isOps" />
    <div v-else class="header-container">
      <!-- 左侧元素 -->
      <div class="header-left" @click="handleRouter('home')">
        <img loading="lazy" alt="" src="@/assets/xiaozhi-logo.png" class="logo-img" />
        <img loading="lazy" alt="" :src="xiaozhiAiIcon" class="brand-img" />
      </div>

      <!-- 中间导航菜单 -->
      <div class="header-center">
        <div class="equipment-management" :class="{
          'active-tab':
            $route.path === '/home' ||
            $route.path === '/role-config',
        }" @click="handleRouter('home')">
          <img loading="lazy" alt="" src="@/assets/header/robot.png" :style="{
            filter:
              $route.path === '/home' ||
                $route.path === '/role-config'
                ? 'brightness(0) invert(1)'
                : 'None',
          }" />
          <span class="nav-text">{{ $t("header.smartManagement") }}</span>
        </div>
        <div class="equipment-management" :class="{ 'active-tab': ['/device-center', '/device-management'].includes($route.path) }"
          @click="handleRouter('deviceCenter')">
          <i class="el-icon-monitor"></i>
          <span class="nav-text">设备管理</span>
        </div>
        <div v-if="featureStatus.voiceClone" class="equipment-management" :class="{ 'active-tab': $route.path === '/voice-clone-management' }" @click="handleRouter('voiceCloneManagement')"><i class="el-icon-microphone" /><span class="nav-text">音色克隆</span></div>
        <div class="equipment-management"
          :class="{ 'active-tab': resourceCenterActive }"
          @click="handleRouter('resourceCenter')">
          <img loading="lazy" alt="" src="@/assets/header/knowledge_base.png" :style="{
            filter:
              resourceCenterActive ? 'brightness(0) invert(1)' : 'None',
          }" />
          <span class="nav-text">资源中心</span>
        </div>
        <div v-if="featureStatus.addressBook" class="equipment-management"
          :class="{ 'active-tab': $route.path === '/address-book-management' }"
          @click="handleRouter('addressBookManagement')">
          <img loading="lazy" alt="" src="@/assets/header/address_book.png" :style="{
            filter:
              $route.path === '/address-book-management' ? 'brightness(0) invert(1)' : 'None',
          }" />
          <span class="nav-text">{{ $t("header.addressBook") }}</span>
        </div>
        <div class="equipment-management"
          :class="{ 'active-tab': $route.path === '/organization-management' }"
          @click="handleRouter('organizationManagement')">
          <i class="el-icon-s-custom"></i>
          <span class="nav-text">团队</span>
        </div>
        <div class="equipment-management" :class="{ 'active-tab': $route.path === '/allowance' }"
          @click="handleRouter('modelBilling')">
          <i class="el-icon-data-analysis"></i>
          <span class="nav-text">额度与用量</span>
        </div>
      </div>

      <!-- 右侧元素 -->
      <div class="header-right">
        <div class="search-container" v-if="$route.path === '/home'">
          <div class="search-wrapper">
            <el-input v-model="search" :placeholder="$t('header.searchPlaceholder')" class="custom-search-input"
              @keyup.enter.native="handleSearch" @focus="showSearchHistory" @blur="hideSearchHistory" clearable
              ref="searchInput">
              <i slot="suffix" class="el-icon-search search-icon" @click="handleSearch"></i>
            </el-input>
            <!-- 搜索历史下拉框 -->
            <div v-if="showHistory && searchHistory.length > 0" class="search-history-dropdown">
              <div class="search-history-header">
                <span>{{ $t("header.searchHistory") }}</span>
                <el-button type="text" size="small" class="clear-history-btn" @click="clearSearchHistory">
                  {{ $t("header.clearHistory") }}
                </el-button>
              </div>
              <div class="search-history-list">
                <div v-for="(item, index) in searchHistory" :key="index" class="search-history-item"
                  @click.stop="selectSearchHistory(item)">
                  <span class="history-text">{{ item }}</span>
                  <i class="el-icon-close clear-item-icon" @click.stop="removeSearchHistory(index)"></i>
                </div>
              </div>
            </div>
          </div>
        </div>

        <el-select v-model="currentWorkspaceId" class="workspace-select" size="mini" placeholder="工作空间加载中" @change="handleWorkspaceChange">
          <el-option v-for="item in workspaceList" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>

        <img loading="lazy" alt="" src="@/assets/home/avatar.png" class="avatar-img" @click="handleAvatarClick" />
        <span class="el-user-dropdown" @click="handleAvatarClick">
          <span class="username" :title="userInfo.username">{{ userInfo.username || "加载中..." }}</span>
          <i class="el-icon-arrow-down el-icon--right" :class="{ 'rotate-down': userMenuVisible }"></i>
        </span>
        <el-cascader :options="userMenuOptions" trigger="click" :props="cascaderProps"
          style="width: 0px; overflow: hidden" :show-all-levels="false" @change="handleCascaderChange"
          @visible-change="handleUserMenuVisibleChange" ref="userCascader">
          <template slot-scope="{ data }">
            <span>{{ data.label }}</span>
          </template>
        </el-cascader>
      </div>
    </div>

    <!-- 修改密码弹窗 -->
    <ChangePasswordDialog v-if="!isOps" v-model="isChangePasswordDialogVisible" />
  </el-header>
</template>

<script>
import OpsHeader from '@/ops/OpsHeader.vue';
import { isOperationsConsole } from '@/security/session';
import featureManager from "@/utils/featureManager"; // 引入功能管理工具类
import Api from "@/apis/api";
import { mapActions, mapState } from "vuex";
import ChangePasswordDialog from "./ChangePasswordDialog.vue"; // 引入修改密码弹窗组件

export default {
  name: "HeaderBar",
  components: {
    ChangePasswordDialog, OpsHeader,
  },
  props: ["devices"], // 接收父组件设备列表
  data() {
    return {
      isOps: isOperationsConsole(),
      search: "",
      isChangePasswordDialogVisible: false, // 控制修改密码弹窗的显示
      paramDropdownVisible: false,
      voiceCloneDropdownVisible: false,
      userMenuVisible: false, // 添加用户菜单可见状态
      menuVisibleTimer: null, // 菜单显示定时器，防止够快触发
      isSmallScreen: false,
      // 搜索历史相关
      searchHistory: [],
      showHistory: false,
      workspaceList: [],
      currentWorkspaceId: "",
      SEARCH_HISTORY_KEY: "xiaozhi_search_history",
      MAX_HISTORY_COUNT: 3,
      // Cascader 配置
      cascaderProps: {
        expandTrigger: "click",
        value: "value",
        label: "label",
        children: "children",
      },
      // 跳转页面配置
      routerPaths: {
        deviceCenter: "/device-center",
        home: "/home",
        modelConfig: "/model-config",
        modelBilling: "/allowance",
        resourceCenter: "/knowledge-base-management",
        knowledgeBaseManagement: "/knowledge-base-management",
        addressBookManagement: "/address-book-management",
        organizationManagement: "/organization-management",
        voiceCloneManagement: "/voice-clone-management",
        voiceResourceManagement: "/voice-resource-management",
        paramManagement: "/params-management",
        userManagement: "/user-management",
        otaManagement: "/ota-management",
        dictManagement: "/dict-management",
        providerManagement: "/provider-management",
        functionPluginManagement: "/function-plugin-management",
        musicManagement: "/music-management",
        agentTemplate: "/agent-template-management",
        replacementWordManagement: "/replacement-word-management",
        serverSideManagement: "/server-side-management",
        featureManagement: "/feature-management",
      }
    };
  },
  computed: {
    ...mapState({
      featureStatus: (state) => ({
        voiceClone: state.pubConfig.systemWebMenu?.features?.voiceClone?.enabled, // 音色克隆功能状态
        knowledgeBase: state.pubConfig.systemWebMenu?.features?.knowledgeBase?.enabled, // 知识库功能状态
        addressBook: state.pubConfig.systemWebMenu?.features?.addressBook?.enabled, // 通讯录功能状态
      }),
      userInfo: (state) => state.userInfo,
    }),
    xiaozhiAiIcon() {
      return require("@/assets/xiaozhi-ai.png");
    },
    resourceCenterActive() {
      return [
        "/knowledge-base-management",
        "/knowledge-file-upload",
        "/person-profile-management",
        "/asr-hotword-management",
        "/function-plugin-management"
      ].includes(this.$route.path);
    },
    // 用户菜单选项
    userMenuOptions() {
      return [
        {
          label: this.$t("header.changePassword"),
          value: "changePassword",
        },
        {
          label: this.$t("header.logout"),
          value: "logout",
        },
      ];
    },
  },
  async mounted() {
    if (this.isOps) return;
    this.checkScreenSize();
    window.addEventListener("resize", this.checkScreenSize);
    // 从localStorage加载搜索历史
    this.loadSearchHistory();
    // 等待featureManager初始化完成后再加载功能状态
    await this.loadFeatureStatus();
    this.loadWorkspaces();
  },
  //移除事件监听器
  beforeDestroy() {
    window.removeEventListener("resize", this.checkScreenSize);
  },
  methods: {
    handleRouter(type) {
      this.$router.push(this.routerPaths[type]);
    },
    loadWorkspaces() {
      Api.workspace.list(({ data }) => {
        if (data && data.code === 0 && Array.isArray(data.data)) {
          this.workspaceList = data.data;
          const savedWorkspaceId = localStorage.getItem("currentWorkspaceId") || "";
          const targetWorkspaceId = this.currentWorkspaceId || savedWorkspaceId;
          const exists = this.workspaceList.some(item => item.id === targetWorkspaceId);
          if (targetWorkspaceId && exists) {
            this.currentWorkspaceId = targetWorkspaceId;
          } else {
            const current = this.workspaceList[0];
            if (current) {
              this.currentWorkspaceId = current.id;
              localStorage.setItem("currentWorkspaceId", current.id);
            }
          }
          const selected = this.workspaceList.find(item => item.id === this.currentWorkspaceId);
          if (selected) {
            localStorage.setItem("currentWorkspaceCanWrite", selected.canWrite ? "true" : "false");
          }
        }
      });
    },
    handleWorkspaceChange(value) {
      localStorage.setItem("currentWorkspaceId", value);
      const selected = this.workspaceList.find(item => item.id === value);
      if (selected) {
        localStorage.setItem("currentWorkspaceCanWrite", selected.canWrite ? "true" : "false");
      }
      window.location.reload();
    },
    // 加载功能状态
    async loadFeatureStatus() {
      // 等待featureManager初始化完成
      await featureManager.waitForInitialization();
    },
    checkScreenSize() {
      this.isSmallScreen = window.innerWidth <= 1386;
    },
    // 处理搜索
    handleSearch() {
      const searchValue = this.search.trim();

      // 如果搜索内容为空，触发重置事件
      if (!searchValue) {
        this.$emit("search-reset");
        return;
      }

      // 保存搜索历史
      this.saveSearchHistory(searchValue);

      // 触发搜索事件，将搜索关键词传递给父组件
      this.$emit("search", searchValue);

      // 搜索完成后让输入框失去焦点，从而触发blur事件隐藏搜索历史
      if (this.$refs.searchInput) {
        this.$refs.searchInput.blur();
      }
    },

    // 显示搜索历史
    showSearchHistory() {
      this.showHistory = true;
    },

    // 隐藏搜索历史
    hideSearchHistory() {
      // 延迟隐藏，以便点击事件能够执行
      setTimeout(() => {
        this.showHistory = false;
      }, 200);
    },

    // 加载搜索历史
    loadSearchHistory() {
      try {
        const history = localStorage.getItem(this.SEARCH_HISTORY_KEY);
        if (history) {
          this.searchHistory = JSON.parse(history);
        }
      } catch (error) {
        console.error("加载搜索历史失败:", error);
        this.searchHistory = [];
      }
    },

    // 保存搜索历史
    saveSearchHistory(keyword) {
      if (!keyword || this.searchHistory.includes(keyword)) {
        return;
      }

      // 添加到历史记录开头
      this.searchHistory.unshift(keyword);

      // 限制历史记录数量
      if (this.searchHistory.length > this.MAX_HISTORY_COUNT) {
        this.searchHistory = this.searchHistory.slice(0, this.MAX_HISTORY_COUNT);
      }

      // 保存到localStorage
      try {
        localStorage.setItem(this.SEARCH_HISTORY_KEY, JSON.stringify(this.searchHistory));
      } catch (error) {
        console.error("保存搜索历史失败:", error);
      }
    },

    // 选择搜索历史项
    selectSearchHistory(keyword) {
      this.search = keyword;
      this.handleSearch();
    },

    // 移除单个搜索历史项
    removeSearchHistory(index) {
      this.searchHistory.splice(index, 1);
      try {
        localStorage.setItem(this.SEARCH_HISTORY_KEY, JSON.stringify(this.searchHistory));
      } catch (error) {
        console.error("更新搜索历史失败:", error);
      }
    },

    // 清空所有搜索历史
    clearSearchHistory() {
      this.searchHistory = [];
      try {
        localStorage.removeItem(this.SEARCH_HISTORY_KEY);
      } catch (error) {
        console.error("清空搜索历史失败:", error);
      }
    },
    // 显示修改密码弹窗
    showChangePasswordDialog() {
      this.isChangePasswordDialogVisible = true;
      // 添加：显示修改密码弹窗后重置用户菜单可见状态
      this.userMenuVisible = false;
    },
    // 退出登录
    async handleLogout() {
      try {
        // 调用 Vuex 的 logout action
        await this.logout();
        this.$message.success({
          message: this.$t("message.success"),
          showClose: true,
        });
      } catch (error) {
        console.error("退出登录失败:", error);
        this.$message.error({
          message: this.$t("message.error"),
          showClose: true,
        });
      }
    },
    // 监听参数字典下拉菜单的可见状态变化
    handleParamDropdownVisibleChange(visible) {
      this.paramDropdownVisible = visible;
    },

    // 监听音色克隆下拉菜单的可见状态变化
    handleVoiceCloneDropdownVisibleChange(visible) {
      this.voiceCloneDropdownVisible = visible;
    },
    // 在data中添加一个key用于强制重新渲染组件
    // 处理 Cascader 选择变化
    handleCascaderChange(value) {
      if (!value || value.length === 0) {
        return;
      }

      const action = value[value.length - 1];

      switch (action) {
        case "changePassword":
          this.showChangePasswordDialog();
          break;
        case "logout":
          this.handleLogout();
          break;
      }

      // 操作完成后立即清空选择
      setTimeout(() => {
        this.completeResetCascader();
      }, 300);
    },

    // 完全重置级联选择器
    completeResetCascader() {
      if (this.$refs.userCascader) {
        try {
          // 尝试所有可能的方法来清空选择
          // 1. 尝试使用组件提供的clearValue方法
          if (this.$refs.userCascader.clearValue) {
            this.$refs.userCascader.clearValue();
          }

          // 2. 直接清空内部属性
          if (this.$refs.userCascader.$data) {
            this.$refs.userCascader.$data.selectedPaths = [];
            this.$refs.userCascader.$data.displayLabels = [];
            this.$refs.userCascader.$data.inputValue = "";
            this.$refs.userCascader.$data.checkedValue = [];
            this.$refs.userCascader.$data.showAllLevels = false;
          }

          // 3. 操作DOM清除选中状态
          const menuElement = this.$refs.userCascader.$refs.menu;
          if (menuElement && menuElement.$el) {
            const activeItems = menuElement.$el.querySelectorAll(
              ".el-cascader-node.is-active"
            );
            activeItems.forEach((item) => item.classList.remove("is-active"));

            const checkedItems = menuElement.$el.querySelectorAll(
              ".el-cascader-node.is-checked"
            );
            checkedItems.forEach((item) => item.classList.remove("is-checked"));
          }

          console.log("Cascader values cleared");
        } catch (error) {
          console.error("清空选择值失败:", error);
        }
      }
    },

    // 点击头像触发cascader下拉菜单
    handleAvatarClick() {
      if (this.$refs.userCascader) {
        // 切换菜单可见状态
        this.userMenuVisible = !this.userMenuVisible;

        // 菜单收起时清空选择值
        if (!this.userMenuVisible) {
          this.completeResetCascader();
        }

        // 直接设置菜单的显隐状态
        try {
          // 尝试使用toggleDropDownVisible方法
          this.$refs.userCascader.toggleDropDownVisible(this.userMenuVisible);
        } catch (error) {
          // 如果toggle方法失败，尝试直接设置属性
          if (this.$refs.userCascader.$refs.menu) {
            this.$refs.userCascader.$refs.menu.showMenu(this.userMenuVisible);
          } else {
            console.error("Cannot access menu component");
          }
        }
      }
    },

    // 处理用户菜单可见性变化
    handleUserMenuVisibleChange(visible) {
      if (this.menuVisibleTimer) return;
      this.menuVisibleTimer = setTimeout(() => {
        this.userMenuVisible = visible;
        clearTimeout(this.menuVisibleTimer);
        this.menuVisibleTimer = null;
      }, 100);

      // 如果菜单关闭了，也要清空选择值
      if (!visible) {
        this.completeResetCascader();
      }
    },

    // 使用 mapActions 引入 Vuex 的 logout action
    ...mapActions(["logout"]),
  },
};
</script>

<style lang="scss" scoped>
.header {
  background: rgba(251, 252, 255, 0.96);
  border: 1px solid rgba(255, 255, 255, 0.92);
  border-bottom-color: #e9ecf3;
  height: auto !important;
  flex-shrink: 0;
  padding: 0 28px;
  min-width: 900px;
  /* 设置最小宽度防止过度压缩 */
  overflow: visible;
  backdrop-filter: blur(10px);
}

.header-container {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr);
  grid-template-areas: "brand tools" "navigation navigation";
  align-items: center;
  gap: 0 24px;
  padding: 14px 0 0;
  box-sizing: border-box;
}

.header-left {
  grid-area: brand;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 160px;
  cursor: pointer;
}

.logo-img {
  width: 42px;
  height: 42px;
}

.brand-img {
  height: 20px;
}

.header-center {
  grid-area: navigation;
  flex-wrap: wrap;
  padding: 12px 0;
  margin-top: 12px;
  border-top: 1px solid #eef0f5;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  min-width: 0;
  overflow: visible;
}

.header-right {
  grid-area: tools;
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 0 0 auto;
  justify-content: flex-end;
  min-width: 0;
}

.workspace-select {
  width: 210px;
  flex-shrink: 0;
}

.workspace-select::v-deep .el-input__inner {
  height: 38px;
  line-height: 38px;
  border-radius: 6px;
  border-color: #d8dfed;
  color: #4a536f;
  font-size: 14px;
}

.equipment-management {
  height: 42px;
  border-radius: 10px;
  background: transparent;
  display: flex;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  gap: 8px;
  color: #3d4566;
  align-items: center;
  transition: all 0.18s ease;
  cursor: pointer;
  flex-shrink: 0;
  /* 防止导航按钮被压缩 */
  padding: 0 14px;
  position: relative;
  white-space: nowrap;
}

.equipment-management:hover {
  background: #eef1f8;
}

.equipment-management.active-tab {
  background: #586ff0 !important;
  color: #fff !important;
  box-shadow: 0 3px 8px rgba(87, 120, 255, 0.12);
}

.equipment-management img {
  width: 15px;
  height: 13px;
}

.search-container {
  width: 280px;
  flex: 0 0 280px;
}

.search-wrapper {
  position: relative;
}

.search-history-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: white;
  border: 1px solid #e4e6ef;
  border-radius: 4px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  z-index: 1000;
  margin-top: 2px;
}

.search-history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 12px;
  color: #909399;
}

.clear-history-btn {
  color: #909399;
  font-size: 11px;
  padding: 0;
  height: auto;
}

.clear-history-btn:hover {
  color: #606266;
}

.search-history-list {
  max-height: 200px;
  overflow-y: auto;
}

.search-history-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 12px;
  color: #606266;
}

.search-history-item:hover {
  background-color: #f5f7fa;
}

.clear-item-icon {
  font-size: 10px;
  color: #909399;
  visibility: hidden;
}

.more-dropdown {
  padding: 0;
}

.more-dropdown .el-dropdown-link {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 100%;
  padding: 0 18px;
}

.search-history-item:hover .clear-item-icon {
  visibility: visible;
}

.clear-item-icon:hover {
  color: #ff4949;
}

.custom-search-input>>>.el-input__inner {
  height: 40px;
  line-height: 40px;
  border-radius: 6px;
  background-color: #fff;
  border: 1px solid #d8dfed;
  padding-left: 18px;
  padding-right: 36px;
  font-size: 14px;
  color: #4a536f;
  box-shadow: 0 2px 8px rgba(40, 66, 120, 0.06);
  width: 100%;
}

.search-icon {
  cursor: pointer;
  color: #9aa5bd;
  margin-right: 6px;
  font-size: 15px;
  line-height: 40px;
}

.custom-search-input::v-deep .el-input__suffix-inner {
  display: flex;
  align-items: center;
  height: 100%;
}

.avatar-img {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  cursor: pointer;
}

.el-user-dropdown {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  max-width: 150px;
  gap: 6px;
  color: #3d4566;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
}

/* 导航文本样式 - 支持中英文换行 */
.nav-text {
  white-space: nowrap;
  text-align: center;
  line-height: 1.2;
}

.username {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Keep navigation in its own row until there is room for every control. */
@media (min-width: 1800px) {
  .header-container {
    grid-template-columns: 160px minmax(0, 1fr) auto;
    grid-template-areas: "brand navigation tools";
    gap: 20px;
    padding: 14px 0;
  }
  .header-center {
    margin-top: 0;
    padding: 0;
    border-top: 0;
  }
}

@media (max-width: 1100px) {
  .header { padding: 0 20px; }
  .header-container { grid-template-columns: 140px minmax(0, 1fr); gap: 0 16px; }
  .header-left { width: 140px; }
  .header-right { gap: 10px; }
  .search-container { width: 220px; flex-basis: 220px; }
  .workspace-select { width: 180px; }
  .el-user-dropdown { max-width: 110px; }
}

.equipment-management.more-dropdown {
  position: relative;
}

.equipment-management.more-dropdown .el-dropdown-menu {
  position: absolute;
  right: 0;
  min-width: 120px;
  margin-top: 5px;
}

.el-dropdown-menu__item {
  min-width: 60px;
  padding: 8px 20px;
  font-size: 14px;
  color: #606266;
  white-space: nowrap;
}

/* 添加倒三角旋转样式 */
.rotate-down {
  transform: rotate(180deg);
  transition: transform 0.3s ease;
}

.el-icon-arrow-down {
  transition: transform 0.3s ease;
}
</style>
