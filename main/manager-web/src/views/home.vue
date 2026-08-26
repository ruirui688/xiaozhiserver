<template>
  <div class="welcome">
    <!-- 公共头部 -->
    <HeaderBar :devices="devices" @search="handleSearch" @search-reset="handleSearchReset" />
    <el-main class="home-main">
      <nav class="agent-center-nav" aria-label="智能体中心">
        <button :class="{ active: activeSection === 'agents' }" type="button" @click="activeSection = 'agents'">
          {{ $t('home.myAgents') }}
        </button>
        <span />
        <button :class="{ active: activeSection === 'templates' }" type="button" @click="openTemplateCenter">
          {{ $t('home.agentTemplates') }}
        </button>
      </nav>
      <div v-if="activeSection === 'agents'">
        <!-- 首页内容 -->
        <div class="add-device">
          <div class="add-device-bg">
            <div class="hellow-text" style="margin-top: 30px;">
              {{ $t('home.greeting') }}
            </div>
            <div class="hellow-text">
              {{ $t('home.wish') }}
            </div>
            <div class="hi-hint">
              let's have a wonderful day!
            </div>
            <div v-if="workspaceQuota" class="workspace-quota">
              <span>{{ $t('home.workspaceAgentQuota', { count: workspaceQuota.agentCount, limit: workspaceQuota.agentLimit }) }}</span>
              <span>{{ $t('home.agentStorageQuota', { limit: formatBytes(workspaceQuota.agentStorageLimitBytes) }) }}</span>
            </div>
            <div v-if="canWrite" :class="['add-device-btn', { 'quota-full': isAgentLimitReached }]" @click="showAddDialog">
              <div class="left-add">
                {{ $t('home.addAgent') }}
              </div>
              <div style="width: 23px;height: 13px;background: #5778ff;margin-left: -10px;" />
              <div class="right-add">
                <i class="el-icon-right" style="font-size: 20px;color: #fff;" />
              </div>
            </div>
          </div>
        </div>
        <div class="device-list-container">
          <template v-if="!isLoading">
            <DeviceItem v-for="item in devices" :key="item.agentId || item.id" :device="item" :feature-status="featureStatus"
              :can-write="canWrite"
              @configure="goToRoleConfig" @deviceManage="handleDeviceManage" @delete="handleDeleteAgent" 
              @chat-history="handleShowChatHistory" />
          </template>
        </div>
      </div>
      <AgentTemplateCenter v-else :templates="agentTemplates" :loading="templateLoading"
        :can-write="canWrite" :quota-full="isAgentLimitReached" @preview="handlePreviewTemplate" />
      <AgentTemplatePreviewDialog :visible.sync="templatePreviewVisible" :preview="templatePreview"
        :loading="templatePreviewLoading" :can-write="canWrite" :quota-full="isAgentLimitReached"
        @create="handleCreateFromTemplate" />
      <AddWisdomBodyDialog :visible.sync="addDeviceDialogVisible" @confirm="handleWisdomBodyAdded" />
    </el-main>
    <el-footer>
      <version-footer />
    </el-footer>
    <chat-history-dialog :visible.sync="showChatHistory" :agent-id="currentAgentId" :agent-name="currentAgentName"
      @history-changed="handleChatHistoryChanged" />
  </div>

</template>

<script>
import Api from '@/apis/api';
import AddWisdomBodyDialog from '@/components/AddWisdomBodyDialog.vue';
import AgentTemplateCenter from '@/components/home/AgentTemplateCenter.vue';
import AgentTemplatePreviewDialog from '@/components/home/AgentTemplatePreviewDialog.vue';
import ChatHistoryDialog from '@/components/ChatHistoryDialog.vue';
import DeviceItem from '@/components/DeviceItem.vue';
import HeaderBar from '@/components/HeaderBar.vue';
import VersionFooter from '@/components/VersionFooter.vue';
import featureManager from '@/utils/featureManager';

export default {
  name: 'HomePage',
  components: { DeviceItem, AddWisdomBodyDialog, AgentTemplateCenter, AgentTemplatePreviewDialog, HeaderBar, VersionFooter, ChatHistoryDialog },
  data() {
    return {
      addDeviceDialogVisible: false,
      devices: [],
      originalDevices: [],
      agentListRequestId: 0,
      isSearching: false,
      searchRegex: null,
      isLoading: true,
      showChatHistory: false,
      currentAgentId: '',
      currentAgentName: '',
      activeSection: 'agents',
      agentTemplates: [],
      templateLoading: false,
      templatePreviewVisible: false,
      templatePreviewLoading: false,
      templatePreview: null,
      canWrite: localStorage.getItem('currentWorkspaceCanWrite') !== 'false',
      workspaceQuota: null,
      // 功能状态
      featureStatus: {
        voiceprintRecognition: false,
        voiceClone: false,
        knowledgeBase: false
      }
    }
  },

  async mounted() {
    this.loadWorkspacePermission();
    this.loadWorkspaceQuota();
    this.fetchAgentList();
    this.fetchAgentTemplates();
    await this.loadFeatureStatus();
  },

  computed: {
    isAgentLimitReached() {
      return Boolean(this.workspaceQuota && this.workspaceQuota.agentCount >= this.workspaceQuota.agentLimit);
    }
  },

  methods: {
    openTemplateCenter() {
      this.activeSection = 'templates';
      if (!this.agentTemplates.length) {
        this.fetchAgentTemplates();
      }
    },
    fetchAgentTemplates() {
      this.templateLoading = true;
      Api.agent.getAccessibleAgentTemplates(({ data }) => {
        this.agentTemplates = data && data.code === 0 && Array.isArray(data.data) ? data.data : [];
        this.templateLoading = false;
      }, (error) => {
        console.error('获取智能体模板失败:', error);
        this.templateLoading = false;
        this.$message.error(this.$t('home.templateLoadFailed'));
      });
    },
    handlePreviewTemplate(template) {
      this.templatePreview = { template };
      this.templatePreviewVisible = true;
      this.templatePreviewLoading = true;
      Api.agent.getAgentTemplatePreview(template.id, ({ data }) => {
        if (data && data.code === 0 && data.data) {
          this.templatePreview = data.data;
          this.templatePreviewLoading = false;
          return;
        }
        this.templatePreviewLoading = false;
        this.$message.error((data && data.msg) || '模板预览加载失败');
      }, (error) => {
        this.templatePreviewLoading = false;
        this.$message.error((error && error.msg) || '模板预览加载失败');
      });
    },
    handleCreateFromTemplate(template) {
      if (!this.canWrite) {
        return;
      }
      if (this.isAgentLimitReached) {
        this.$message.warning(this.$t('home.workspaceAgentLimitReached', {
          limit: this.workspaceQuota.agentLimit
        }));
        return;
      }
      this.$prompt(this.$t('home.templateAgentNamePrompt'), this.$t('home.createFromTemplate'), {
        confirmButtonText: this.$t('button.ok'),
        cancelButtonText: this.$t('button.cancel'),
        inputValue: template.agentName || template.templateName,
        inputPattern: /^.{1,64}$/,
        inputErrorMessage: this.$t('home.templateAgentNameInvalid')
      }).then(({ value }) => {
        Api.agent.createAgentFromTemplate(template.id, value.trim(), template.version, ({ data }) => {
          if (data && data.code === 0) {
            const skipped = data.data && data.data.skippedItems ? data.data.skippedItems : [];
            this.$message.success(this.$t('home.templateCreateSuccess'));
            if (skipped.length) {
              this.$alert(skipped.join('\n'), this.$t('home.templateCreateWarnings'), { type: 'warning' });
            }
            this.activeSection = 'agents';
            this.templatePreviewVisible = false;
            this.fetchAgentList();
            this.loadWorkspaceQuota();
            return;
          }
          this.$message.error((data && data.msg) || this.$t('home.templateCreateFailed'));
        }, (error) => {
          this.$message.error((error && error.msg) || this.$t('home.templateCreateFailed'));
        });
      }).catch(() => {});
    },
    // 加载功能状态
    async loadFeatureStatus() {
      await featureManager.waitForInitialization();
      const config = featureManager.getConfig();
      this.featureStatus = {
        voiceprintRecognition: config.voiceprintRecognition,
        voiceClone: config.voiceClone,
        knowledgeBase: config.knowledgeBase
      };
    },
    loadWorkspacePermission() {
      Api.workspace.current(({ data }) => {
        if (data && data.code === 0 && data.data) {
          this.canWrite = Boolean(data.data.canWrite);
          localStorage.setItem('currentWorkspaceCanWrite', this.canWrite ? 'true' : 'false');
        }
      });
    },
    loadWorkspaceQuota() {
      Api.workspace.quota(({ data }) => {
        if (data && data.code === 0 && data.data) {
          this.workspaceQuota = data.data;
        }
      });
    },
    formatBytes(bytes) {
      const value = Number(bytes) || 0;
      if (value < 1024) return `${value} B`;
      if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
      if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
      return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    },
    
    showAddDialog() {
      if (!this.canWrite) {
        return;
      }
      if (this.isAgentLimitReached) {
        this.$message.warning(this.$t('home.workspaceAgentLimitReached', {
          limit: this.workspaceQuota.agentLimit
        }));
        return;
      }
      this.addDeviceDialogVisible = true
    },
    goToRoleConfig() {
      // 点击配置角色后跳转到角色配置页
      this.$router.push('/role-config')
    },
    handleWisdomBodyAdded(res) {
      this.fetchAgentList();
      this.loadWorkspaceQuota();
      this.addDeviceDialogVisible = false;
    },
    handleDeviceManage() {
      this.$router.push('/device-management');
    },
    handleSearch(keyword) {
      this.activeSection = 'agents';
      this.isSearching = true;
      this.isLoading = true;
      // 检测MAC地址格式：包含4个冒号
      const isMac = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/.test(keyword)
      const searchType = isMac ? 'mac' : 'name';
      Api.agent.searchAgent(keyword, searchType, ({ data }) => {
        if (data?.data) {
          this.devices = data.data.map(item => ({
            ...item,
            agentId: item.id
          }));
        }
        this.isLoading = false;
      }, (error) => {
        console.error('搜索智能体失败:', error);
        this.isLoading = false;
        this.$message.error(this.$t('message.searchFailed'));
      });
    },
    handleSearchReset() {
      this.isSearching = false;
      // 直接将原始设备列表赋值给显示设备列表，避免重新加载数据
      this.devices = [...this.originalDevices];
    },

    // 搜索更新智能体列表
    handleSearchResult(filteredList) {
      this.devices = filteredList; // 更新设备列表
    },
    // 获取智能体列表
    fetchAgentList({ preserveSearch = false, background = false } = {}) {
      const requestId = ++this.agentListRequestId;
      if (!background) {
        this.isLoading = true;
      }
      Api.agent.getAgentList(({ data }) => {
        if (requestId !== this.agentListRequestId) {
          return;
        }
        if (data?.data) {
          const refreshedDevices = data.data.map(item => ({
            ...item,
            agentId: item.id
          }));
          this.originalDevices = refreshedDevices;

          if (preserveSearch && this.isSearching) {
            const refreshedById = new Map(
              refreshedDevices.map(item => [item.agentId, item])
            );
            this.devices = this.devices
              .map(item => refreshedById.get(item.agentId || item.id))
              .filter(Boolean);
          } else {
            this.handleSearchReset();
          }
        }
        if (!background) {
          this.isLoading = false;
        }
      }, (error) => {
        if (requestId !== this.agentListRequestId) {
          return;
        }
        console.error('Failed to fetch agent list:', error);
        if (!background) {
          this.isLoading = false;
        }
      });
    },
    // 删除智能体
    handleDeleteAgent(agentId) {
      this.$confirm(this.$t('home.confirmDeleteAgent'), '提示', {
        confirmButtonText: this.$t('button.ok'),
        cancelButtonText: this.$t('button.cancel'),
        type: 'warning'
      }).then(() => {
        Api.agent.deleteAgent(agentId, (res) => {
          if (res.data.code === 0) {
            this.$message.success({
              message: this.$t('home.deleteSuccess'),
              showClose: true
            });
            this.fetchAgentList(); // 刷新列表
            this.loadWorkspaceQuota();
          } else {
            this.$message.error({
              message: res.data.msg || this.$t('home.deleteFailed'),
              showClose: true
            });
          }
        });
      }).catch(() => { });
    },
    handleShowChatHistory({ agentId, agentName }) {
      this.currentAgentId = agentId;
      this.currentAgentName = agentName;
      this.showChatHistory = true;
    },
    handleChatHistoryChanged() {
      this.fetchAgentList({ preserveSearch: true, background: true });
    }
  }
}
</script>

<style scoped>
.welcome {
  min-width: 900px;
  min-height: 506px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(145deg, #e6eeff, #eff0ff);
  background-size: cover;
  /* 确保背景图像覆盖整个元素 */
  background-position: center;
  /* 从顶部中心对齐 */
  -webkit-background-size: cover;
  /* 兼容老版本WebKit浏览器 */
  -o-background-size: cover;
  /* 兼容老版本Opera浏览器 */
}

.add-device {
  height: 195px;
  border-radius: 18px;
  position: relative;
  overflow: hidden;
  background: linear-gradient(269.62deg,
      #e0e6fd 0%,
      #cce7ff 49.69%,
      #d3d3fe 100%);
  box-shadow: 0 18px 42px rgba(91, 123, 210, 0.12);
}

.add-device-bg {
  width: 100%;
  height: 100%;
  text-align: left;
  background-image: url("@/assets/home/main-top-bg.png");
  overflow: hidden;
  background-size: cover;
  /* 确保背景图像覆盖整个元素 */
  background-position: center;
  /* 从顶部中心对齐 */
  -webkit-background-size: cover;
  /* 兼容老版本WebKit浏览器 */
  -o-background-size: cover;
  box-sizing: border-box;

  /* 兼容老版本Opera浏览器 */
  .hellow-text {
    margin-left: 75px;
    color: #3d4566;
    font-size: 33px;
    font-weight: 700;
    letter-spacing: 0;
  }

  .hi-hint {
    font-weight: 400;
    font-size: 12px;
    text-align: left;
    color: #818cae;
    margin-left: 75px;
    margin-top: 5px;
  }

  .workspace-quota {
    display: flex;
    gap: 18px;
    margin-left: 75px;
    margin-top: 7px;
    color: #596788;
    font-size: 12px;
  }
}

.add-device-btn {
  display: flex;
  align-items: center;
  margin-left: 75px;
  margin-top: 10px;
  cursor: pointer;

  .left-add {
    padding: 0 14px;
    height: 34px;
    border-radius: 17px;
    background: #5778ff;
    color: #fff;
    font-size: 14px;
    font-weight: 500;
    text-align: center;
    line-height: 34px;
  }

  .right-add {
    width: 34px;
    height: 34px;
    border-radius: 50%;
    background: #5778ff;
    margin-left: -6px;
    display: flex;
    justify-content: center;
    align-items: center;
  }
}

.add-device-btn.quota-full {
  cursor: not-allowed;
  opacity: 0.58;
}

.device-list-container {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
  gap: 30px;
  padding: 30px 0 20px;
}

.home-main {
  padding: 28px;
  display: flex;
  flex-direction: column;
}

.agent-center-nav {
  display: flex;
  align-items: center;
  gap: 22px;
  min-height: 54px;
  margin-bottom: 18px;
  padding: 0 24px;
  border-radius: 17px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 12px 30px rgba(70, 94, 162, 0.08);
}

.agent-center-nav span {
  width: 1px;
  height: 22px;
  background: #d9deec;
}

.agent-center-nav button {
  height: 54px;
  border: 0;
  border-bottom: 3px solid transparent;
  color: #707992;
  background: transparent;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.agent-center-nav button.active {
  color: #3f62dc;
  border-bottom-color: #5778ff;
}

/* 在 DeviceItem.vue 的样式中 */
.device-item {
  margin: 0 !important;
  /* 避免冲突 */
  width: auto !important;
}

.footer {
  font-size: 12px;
  font-weight: 400;
  margin-top: auto;
  padding-top: 30px;
  color: #979db1;
  text-align: center;
  /* 居中显示 */
}

</style>
