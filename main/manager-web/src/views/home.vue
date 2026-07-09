<template>
  <div class="welcome">
    <!-- 公共头部 -->
    <HeaderBar :devices="devices" @search="handleSearch" @search-reset="handleSearchReset" />
    <el-main class="home-main">
      <div>
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
            <div v-if="canWrite" class="add-device-btn">
              <div class="left-add" @click="showAddDialog">
                {{ $t('home.addAgent') }}
              </div>
              <div style="width: 23px;height: 13px;background: #5778ff;margin-left: -10px;" />
              <div class="right-add">
                <i class="el-icon-right" @click="showAddDialog" style="font-size: 20px;color: #fff;" />
              </div>
            </div>
          </div>
        </div>
        <div class="device-list-container">
          <template v-if="!isLoading">
            <DeviceItem v-for="(item, index) in devices" :key="index" :device="item" :feature-status="featureStatus" 
              :can-write="canWrite"
              @configure="goToRoleConfig" @deviceManage="handleDeviceManage" @delete="handleDeleteAgent" 
              @chat-history="handleShowChatHistory" />
          </template>
        </div>
      </div>
      <AddWisdomBodyDialog :visible.sync="addDeviceDialogVisible" @confirm="handleWisdomBodyAdded" />
    </el-main>
    <el-footer>
      <version-footer />
    </el-footer>
    <chat-history-dialog :visible.sync="showChatHistory" :agent-id="currentAgentId" :agent-name="currentAgentName" />
  </div>

</template>

<script>
import Api from '@/apis/api';
import AddWisdomBodyDialog from '@/components/AddWisdomBodyDialog.vue';
import ChatHistoryDialog from '@/components/ChatHistoryDialog.vue';
import DeviceItem from '@/components/DeviceItem.vue';
import HeaderBar from '@/components/HeaderBar.vue';
import VersionFooter from '@/components/VersionFooter.vue';
import featureManager from '@/utils/featureManager';

export default {
  name: 'HomePage',
  components: { DeviceItem, AddWisdomBodyDialog, HeaderBar, VersionFooter, ChatHistoryDialog },
  data() {
    return {
      addDeviceDialogVisible: false,
      devices: [],
      originalDevices: [],
      isSearching: false,
      searchRegex: null,
      isLoading: true,
      showChatHistory: false,
      currentAgentId: '',
      currentAgentName: '',
      canWrite: localStorage.getItem('currentWorkspaceCanWrite') !== 'false',
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
    this.fetchAgentList();
    await this.loadFeatureStatus();
  },

  methods: {
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
    
    showAddDialog() {
      if (!this.canWrite) {
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
      this.addDeviceDialogVisible = false;
    },
    handleDeviceManage() {
      this.$router.push('/device-management');
    },
    handleSearch(keyword) {
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
    fetchAgentList() {
      this.isLoading = true;
      Api.agent.getAgentList(({ data }) => {
        if (data?.data) {
          this.originalDevices = data.data.map(item => ({
            ...item,
            agentId: item.id
          }));

          this.handleSearchReset();
        }
        this.isLoading = false;
      }, (error) => {
        console.error('Failed to fetch agent list:', error);
        this.isLoading = false;
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
}

.add-device-btn {
  display: flex;
  align-items: center;
  margin-left: 75px;
  margin-top: 15px;
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
