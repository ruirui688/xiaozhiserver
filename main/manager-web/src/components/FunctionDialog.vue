<template>
  <el-drawer :visible.sync="dialogVisible" direction="rtl" size="80%" :wrapperClosable="false" :withHeader="false">
    <!-- 自定义标题区域 -->
    <div class="custom-header">
      <div class="header-left">
        <div>
          <h3 class="bold-title">{{ $t('functionDialog.title') }}</h3>
          <div class="drawer-subtitle">选择进入大模型工具列表的系统能力和互动玩法；飞书等第三方集成请回到角色编辑页单独绑定。</div>
        </div>
      </div>
      <button class="custom-close-btn" @click="closeDialog">×</button>
    </div>

    <div class="function-manager">
      <!-- 左侧：未选功能 -->
      <div class="function-column">
        <div class="column-header">
          <h4 class="column-title">{{ $t('functionDialog.unselectedFunctions') }}</h4>
          <el-button type="text" @click="selectAll" class="select-all-btn">
            {{ $t('functionDialog.selectAll') }}
          </el-button>
        </div>
        <div class="function-list">
          <div v-if="unselected.length">
            <div v-for="func in unselected" :key="func.name" class="function-item">
              <el-checkbox :label="func.name" v-model="selectedNames" @change="(val) => handleCheckboxChange(func, val)"
                @click.native.stop></el-checkbox>
              <div class="func-tag" @click="handleFunctionClick(func)">
                <div class="color-dot"></div>
                <span>{{ func.name }}</span>
                <span v-if="isDeclarativeInteractionPlugin(func)" class="declarative-plugin-tag">玩法</span>
              </div>
            </div>
          </div>
          <div v-else style="display: flex; justify-content: center; align-items: center;">
            <el-empty :description="$t('functionDialog.noMorePlugins')" />
          </div>
        </div>
      </div>

      <!-- 中间：已选功能 -->
      <div class="function-column">
        <div class="column-header">
          <h4 class="column-title">{{ $t('functionDialog.selectedFunctions') }}</h4>
          <el-button type="text" @click="deselectAll" class="select-all-btn">
            {{ $t('functionDialog.selectAll') }}
          </el-button>
        </div>
        <div class="function-list">
          <div v-if="selectedList.length > 0">
            <div v-for="func in selectedList" :key="func.name" class="function-item">
              <el-checkbox :label="func.name" v-model="selectedNames" @change="(val) => handleCheckboxChange(func, val)"
                @click.native.stop></el-checkbox>
              <div class="func-tag" @click="handleFunctionClick(func)">
                <div class="color-dot"></div>
                <span>{{ func.name }}</span>
                <span v-if="isDeclarativeInteractionPlugin(func)" class="declarative-plugin-tag">玩法</span>
              </div>
            </div>
          </div>
          <div v-else style="display: flex; justify-content: center; align-items: center;">
            <el-empty :description="$t('functionDialog.pleaseSelectPlugin')" />
          </div>
        </div>
      </div>

      <!-- 右侧：参数配置 -->
      <div class="params-column">
        <h4 v-if="currentFunction" class="column-title">
          {{ $t('functionDialog.paramConfig') }} - {{ currentFunction.name }}
        </h4>
        <div v-if="currentFunction" class="params-container">
          <div v-if="isSharedConfigPlugin(currentFunction)" class="shared-config-row">
            <div>
              <div class="shared-config-title">{{ $t('functionDialog.userSharedConfig') }}</div>
              <div class="shared-config-status">
                {{ sharedConfigConfigured(currentFunction.providerCode)
                  ? $t('functionDialog.sharedConfigReady')
                  : $t('functionDialog.sharedConfigMissing') }}
              </div>
            </div>
            <el-button
              size="small"
              icon="el-icon-setting"
              @click="openSharedConfig(currentFunction)"
            >{{ $t('functionDialog.configure') }}</el-button>
          </div>
          <el-form :model="currentFunction" class="param-form">
            <div v-if="agentConfigFields(currentFunction).length === 0 && !isSharedConfigPlugin(currentFunction)">
              <el-empty :description="currentFunction.name + $t('functionDialog.noNeedToConfig')" />
            </div>
            <el-form-item v-for="field in agentConfigFields(currentFunction)" :key="field.key" :label="field.label"
              class="param-item" :class="{ 'textarea-field': field.type === 'array' || field.type === 'json' || field.type === 'textarea' }">
              <template #label>
                <span style="font-size: 16px; margin-right: 6px;">{{ field.label }}</span>
                <el-tooltip effect="dark" :content="fieldRemark(field)" placement="top">
                  <img src="@/assets/home/info.png" alt="" class="info-icon">
                </el-tooltip>
              </template>
              <!-- ARRAY -->
              <el-input v-if="field.type === 'array'" type="textarea" v-model="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />

              <!-- TEXTAREA -->
              <el-input v-else-if="field.type === 'textarea'" type="textarea" :rows="5"
                v-model="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />

              <!-- JSON -->
              <el-input v-else-if="field.type === 'json'" type="textarea" :rows="6" placeholder="请输入合法的 JSON"
                v-model="textCache[field.key]" @blur="flushJson(field)" />

              <!-- number -->
              <el-input-number v-else-if="field.type === 'number'" :value="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />

              <!-- boolean -->
              <el-switch v-else-if="field.type === 'boolean' || field.type === 'bool'"
                :value="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />

              <!-- string or fallback -->
              <el-input v-else v-model="currentFunction.params[field.key]"
                @change="val => handleParamChange(currentFunction, field.key, val)" />
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="empty-tip">{{ $t('functionDialog.pleaseSelectFunctionForParam') }}</div>
      </div>
    </div>

    <!-- MCP区域 -->
    <div class="mcp-access-point" v-if="featureStatus.mcpAccessPoint">
      <div class="mcp-container">
        <!-- 左侧区域 -->
        <div class="mcp-left">
          <div class="mcp-header">
            <h3 class="bold-title">{{ $t('functionDialog.mcpAccessPoint') }}</h3>
          </div>
          <div class="url-header">
            <div class="address-desc">
              <span>{{ $t('functionDialog.mcpAddressDesc') }}</span>
              <a href="https://github.com/xinnan-tech/xiaozhi-esp32-server/blob/main/docs/mcp-endpoint-enable.md"
                target="_blank" class="doc-link">{{ $t('functionDialog.howToDeployMcp') }}</a> &nbsp;&nbsp;|&nbsp;&nbsp;
              <a href="https://github.com/xinnan-tech/xiaozhi-esp32-server/blob/main/docs/mcp-endpoint-integration.md"
                target="_blank" class="doc-link">{{ $t('functionDialog.howToIntegrateMcp') }}</a> &nbsp;
            </div>
          </div>
          <el-input v-model="mcpUrl" readonly class="url-input">
            <template #suffix>
              <el-button @click="copyUrl" class="inner-copy-btn" icon="el-icon-document-copy">
                {{ $t('functionDialog.copy') }}
              </el-button>
            </template>
          </el-input>

          <div class="no-code-tools-panel">
            <div class="no-code-tools-header">
              <h4>本地 HTTP 工具</h4>
              <el-button size="mini" type="primary" icon="el-icon-plus" @click="openMcpToolEditor()">新增工具</el-button>
            </div>
            <div v-if="noCodeTools.length > 0" class="no-code-tools-list">
              <div v-for="(tool, index) in noCodeTools" :key="tool.name || index" class="no-code-tool-row">
                <el-switch v-model="tool.enabled" size="mini" @change="markNoCodeToolsDirty" />
                <div class="no-code-tool-main">
                  <div class="no-code-tool-name">{{ tool.name || '未命名工具' }}</div>
                  <div class="no-code-tool-desc">{{ tool.description || tool.url || '未填写描述' }}</div>
                </div>
                <el-button size="mini" icon="el-icon-edit" @click="openMcpToolEditor(index)"></el-button>
                <el-button size="mini" icon="el-icon-delete" type="danger" plain @click="removeMcpTool(index)"></el-button>
              </div>
            </div>
            <div v-else class="no-code-tools-empty">还没有本地 HTTP 工具</div>
            <div class="no-code-tools-actions">
              <el-button size="mini" :loading="savingNoCodeTools" @click="saveNoCodeTools">保存 HTTP 工具</el-button>
              <el-button size="mini" @click="loadNoCodeTools">重新加载</el-button>
            </div>
          </div>
        </div>

        <!-- 右侧区域 -->
        <div class="mcp-right">
          <div class="mcp-header">
            <h3 class="bold-title">{{ $t('functionDialog.accessPointStatus') }}</h3>
          </div>
          <div class="status-container">
            <span class="status-indicator" :class="mcpStatus"></span>
            <span class="status-text">{{
              mcpStatus === 'connected' ? $t('functionDialog.connected') :
                mcpStatus === 'loading' ? $t('functionDialog.loading') : $t('functionDialog.disconnected')
            }}</span>
            <button class="refresh-btn" @click="refreshStatus">
              <span class="refresh-icon">↻</span>
              <span>{{ $t('functionDialog.refresh') }}</span>
            </button>
          </div>
          <div class="mcp-tools-list">
            <div v-if="mcpTools.length > 0" class="tools-grid">
              <el-button v-for="tool in mcpTools" :key="tool" size="small" class="tool-btn" plain>
                {{ tool }}
              </el-button>
            </div>
            <div v-else class="no-tools">
              <span>{{ $t('functionDialog.noAvailableTools') }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-dialog
      title="HTTP MCP 工具"
      :visible.sync="mcpToolEditorVisible"
      width="720px"
      append-to-body
      class="mcp-tool-editor-dialog">
      <el-form :model="editingMcpTool" label-width="92px" size="small">
        <el-form-item label="工具名称">
          <el-input v-model="editingMcpTool.name" placeholder="例如 query_order，仅用英文、数字、下划线" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editingMcpTool.description" type="textarea" :rows="2" placeholder="告诉大模型什么时候应该调用这个工具" />
        </el-form-item>
        <el-form-item label="请求方式">
          <el-select v-model="editingMcpTool.method" class="method-select">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
            <el-option label="PUT" value="PUT" />
            <el-option label="PATCH" value="PATCH" />
            <el-option label="DELETE" value="DELETE" />
          </el-select>
        </el-form-item>
        <el-form-item label="URL">
          <el-input v-model="editingMcpTool.url" :placeholder="'https://example.com/api/{{id}}'" />
        </el-form-item>
        <el-form-item label="Headers">
          <el-input v-model="editingHeadersText" type="textarea" :rows="4" placeholder='{"Authorization":"Bearer xxx"}' />
        </el-form-item>
        <el-form-item label="查询参数">
          <el-input v-model="editingQueryText" type="textarea" :rows="3" :placeholder="'{\"q\":\"{{keyword}}\"}'" />
        </el-form-item>
        <el-form-item label="Body 模板">
          <el-input v-model="editingMcpTool.bodyTemplate" type="textarea" :rows="5" :placeholder="'{\"orderId\":\"{{order_id}}\"}'" />
        </el-form-item>
        <el-form-item label="超时秒数">
          <el-input-number v-model="editingMcpTool.timeoutSeconds" :min="1" :max="60" />
        </el-form-item>
        <el-form-item label="工具参数">
          <div class="mcp-param-list">
            <div v-for="(param, index) in editingMcpTool.parameters" :key="index" class="mcp-param-row">
              <el-input v-model="param.name" placeholder="参数名" />
              <el-select v-model="param.type" placeholder="类型">
                <el-option label="string" value="string" />
                <el-option label="number" value="number" />
                <el-option label="integer" value="integer" />
                <el-option label="boolean" value="boolean" />
                <el-option label="object" value="object" />
                <el-option label="array" value="array" />
              </el-select>
              <el-input v-model="param.description" placeholder="描述" />
              <el-checkbox v-model="param.required">必填</el-checkbox>
              <el-button icon="el-icon-delete" size="mini" @click="removeMcpParam(index)"></el-button>
            </div>
          </div>
          <el-button size="mini" icon="el-icon-plus" @click="addMcpParam">新增参数</el-button>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="mcpToolEditorVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmMcpToolEditor">确定</el-button>
      </span>
    </el-dialog>

    <user-plugin-config-dialog
      :visible.sync="sharedConfigDialogVisible"
      :provider-code="sharedConfigProviderCode"
      :plugin-name="sharedConfigPluginName"
      @saved="loadSharedConfigStatus"
    />

    <div class="drawer-footer">
      <el-button @click="closeDialog">{{ $t('functionDialog.cancel') }}</el-button>
      <el-button type="primary" @click="saveSelection">{{ $t('functionDialog.saveConfig') }}</el-button>
    </div>
  </el-drawer>
</template>

<script>
import Api from '@/apis/api';
import i18n from '@/i18n';
import featureManager from '@/utils/featureManager';
import UserPluginConfigDialog from '@/components/UserPluginConfigDialog.vue';

const SHARED_FIELDS = {
  get_weather: ['api_host', 'api_key', 'default_location'],
  web_search: ['provider', 'api_key'],
};

export default {
  i18n,
  components: { UserPluginConfigDialog },

  props: {
    value: Boolean,
    functions: {
      type: Array,
      default: () => []
    },
    allFunctions: {
      type: Array,
      default: () => []
    },
    agentId: {
      type: String,
      required: true
    }
  },
  data() {
    return {
      textCache: {},
      dialogVisible: this.value,
      selectedNames: [],
      currentFunction: null,
      modifiedFunctions: {},
      tempFunctions: {},
      // 添加一个标志位来跟踪是否已经保存
      hasSaved: false,
      loading: false,

      mcpUrl: "",
      mcpStatus: "disconnected",
      mcpTools: [],
      noCodeTools: [],
      noCodeToolsDirty: false,
      savingNoCodeTools: false,
      sharedConfigDialogVisible: false,
      sharedConfigProviderCode: '',
      sharedConfigPluginName: '',
      sharedConfigStatuses: {},
      mcpToolEditorVisible: false,
      editingMcpToolIndex: -1,
      editingMcpTool: {
        name: '',
        description: '',
        enabled: true,
        method: 'GET',
        url: '',
        headers: {},
        queryParams: {},
        bodyTemplate: '',
        timeoutSeconds: 10,
        parameters: []
      },
      editingHeadersText: "{}",
      editingQueryText: "{}",
      
      // 功能状态
      featureStatus: {
        mcpAccessPoint: false,
        addressBook: false
      }
    }
  },
  computed: {
    selectedList() {
      const list = this.allFunctions.filter(f => this.selectedNames.includes(f.name));
      // 如果通讯录功能未启用，过滤掉设备呼叫设备插件
      if (!this.featureStatus.addressBook) {
        return list.filter(f => f.providerCode !== 'call_device');
      }
      return list;
    },
    unselected() {
      const list = this.allFunctions.filter(f => !this.selectedNames.includes(f.name));
      // 如果通讯录功能未启用，过滤掉设备呼叫设备插件
      if (!this.featureStatus.addressBook) {
        return list.filter(f => f.providerCode !== 'call_device');
      }
      return list;
    }
  },
  watch: {
    currentFunction(newFn) {
      if (!newFn) return;
      // 对每个字段，如果是 array 或 json，就在 textCache 里生成初始字符串
      newFn.fieldsMeta.forEach(f => {
        const v = newFn.params[f.key];
        if (f.type === 'array') {
          this.$set(this.textCache, f.key, Array.isArray(v) ? v.join('\n') : '');
        }
        else if (f.type === 'json') {
          try {
            this.$set(this.textCache, f.key, JSON.stringify(v ?? {}, null, 2));
          } catch {
            this.$set(this.textCache, f.key, '');
          }
        }
      });
    },
    async value(v) {
      this.dialogVisible = v;
      if (v) {
        // 加载功能状态（需要在初始化选中态之前）
        await this.loadFeatureStatus();

        // 对话框打开时，初始化选中态
        this.selectedNames = this.functions.map(f => f.name);

        // 如果通讯录功能未启用，从已选列表中移除设备呼叫设备插件
        if (!this.featureStatus.addressBook) {
          this.selectedNames = this.selectedNames.filter(name => {
            const func = this.allFunctions.find(f => f.name === name);
            return func && func.providerCode !== 'call_device';
          });
        }

        // 把后端传来的 this.functions（带 params）merge 到 allFunctions 上
        this.functions.forEach(saved => {
          const idx = this.allFunctions.findIndex(f => f.name === saved.name);
          if (idx >= 0) {
            // 保留用户之前在 saved.params 上的改动
            this.allFunctions[idx].params = { ...saved.params };
          }
        });
        // 右侧默认指向第一个
        this.currentFunction = this.selectedList[0] || null;

        // 加载MCP数据
        this.loadMcpAddress();
        this.loadMcpTools();
        this.loadNoCodeTools();
        this.loadSharedConfigStatus();
      }
    },
    dialogVisible(newVal) {
      this.$emit('input', newVal);
    }
  },
  methods: {
    /**
     * 加载功能状态
     */
    async loadFeatureStatus() {
      // 确保featureManager已初始化完成
      await featureManager.waitForInitialization();

      const config = featureManager.getConfig();
      this.featureStatus = {
        mcpAccessPoint: config.mcpAccessPoint || false,
        addressBook: config.addressBook || false
      };
    },
    loadSharedConfigStatus() {
      Api.user.getPluginConfigs(({ data }) => {
        if (!data || data.code !== 0) return;
        const statuses = {};
        (data.data || []).forEach(item => {
          statuses[item.providerCode] = Boolean(item.configured && item.secretConfigured);
        });
        this.sharedConfigStatuses = statuses;
      });
    },
    isSharedConfigPlugin(func) {
      return Boolean(func && SHARED_FIELDS[func.providerCode]);
    },
    sharedConfigConfigured(providerCode) {
      return Boolean(this.sharedConfigStatuses[providerCode]);
    },
    openSharedConfig(func) {
      this.sharedConfigProviderCode = func.providerCode;
      this.sharedConfigPluginName = func.name;
      this.sharedConfigDialogVisible = true;
    },
    agentConfigFields(func) {
      const fields = Array.isArray(func && func.fieldsMeta) ? func.fieldsMeta : [];
      const shared = SHARED_FIELDS[func && func.providerCode] || [];
      return fields.filter(field => !shared.includes(field.key));
    },
    sanitizeFunctionParams(func, params) {
      const sanitized = { ...(params || {}) };
      const shared = SHARED_FIELDS[func && func.providerCode] || [];
      shared.forEach(key => delete sanitized[key]);
      return sanitized;
    },
    
    copyUrl() {
      const textarea = document.createElement('textarea');
      textarea.value = this.mcpUrl;
      textarea.style.position = 'fixed';  // 防止页面滚动
      document.body.appendChild(textarea);
      textarea.select();

      try {
        const successful = document.execCommand('copy');
        if (successful) {
          this.$message.success(this.$t('functionDialog.copiedToClipboard'));
        } else {
          this.$message.error(this.$t('functionDialog.copyFailed'));
        }
      } catch (err) {
        this.$message.error('复制失败，请手动复制');
        console.error('复制失败:', err);
      } finally {
        document.body.removeChild(textarea);
      }
    },

    refreshStatus() {
      this.mcpStatus = "loading";
      this.loadMcpTools();
    },

    // 加载MCP接入点地址
    loadMcpAddress() {
      Api.agent.getAgentMcpAccessAddress(this.agentId, (res) => {
        if (res.data.code === 0) {
          this.mcpUrl = res.data.data || "";
        } else {
          this.mcpUrl = res.data.msg;
          console.error('获取MCP地址失败:', res.data.msg);
        }
      });
    },

    // 加载MCP工具列表
    loadMcpTools() {
      Api.agent.getAgentMcpToolsList(this.agentId, (res) => {
        if (res.data.code === 0) {
          this.mcpTools = res.data.data || [];
          // 根据工具列表更新状态
          this.mcpStatus = this.mcpTools.length > 0 ? "connected" : "disconnected";
        } else {
          this.mcpTools = [];
          this.mcpStatus = "disconnected";
          console.error('获取MCP工具列表失败:', res.data.msg);
        }
      });
    },

    createEmptyMcpTool() {
      return {
        name: '',
        description: '',
        enabled: true,
        method: 'GET',
        url: '',
        headers: {},
        queryParams: {},
        bodyTemplate: '',
        timeoutSeconds: 10,
        parameters: []
      };
    },

    loadNoCodeTools() {
      Api.agent.getAgentMcpNoCodeTools(this.agentId, (res) => {
        if (res.data.code === 0) {
          this.noCodeTools = (res.data.data || []).map(tool => ({
            ...this.createEmptyMcpTool(),
            ...tool,
            enabled: tool.enabled !== false,
            headers: tool.headers || {},
            queryParams: tool.queryParams || {},
            parameters: tool.parameters || []
          }));
          this.noCodeToolsDirty = false;
        } else {
          this.$message.error(res.data.msg || '加载 HTTP 工具失败');
        }
      });
    },

    markNoCodeToolsDirty() {
      this.noCodeToolsDirty = true;
    },

    openMcpToolEditor(index = -1) {
      this.editingMcpToolIndex = index;
      const tool = index >= 0 ? this.noCodeTools[index] : this.createEmptyMcpTool();
      this.editingMcpTool = JSON.parse(JSON.stringify({
        ...this.createEmptyMcpTool(),
        ...tool,
        headers: tool.headers || {},
        queryParams: tool.queryParams || {},
        parameters: tool.parameters || []
      }));
      this.editingHeadersText = JSON.stringify(this.editingMcpTool.headers || {}, null, 2);
      this.editingQueryText = JSON.stringify(this.editingMcpTool.queryParams || {}, null, 2);
      this.mcpToolEditorVisible = true;
    },

    confirmMcpToolEditor() {
      const tool = JSON.parse(JSON.stringify(this.editingMcpTool));
      tool.name = (tool.name || '').trim();
      tool.description = (tool.description || '').trim();
      tool.url = (tool.url || '').trim();
      if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(tool.name)) {
        this.$message.error('工具名称必须以英文字母开头，只能包含英文、数字、下划线和中划线');
        return;
      }
      if (!tool.description) {
        this.$message.error('请填写工具描述');
        return;
      }
      if (!tool.url) {
        this.$message.error('请填写 URL');
        return;
      }
      try {
        tool.headers = this.editingHeadersText.trim() ? JSON.parse(this.editingHeadersText) : {};
        tool.queryParams = this.editingQueryText.trim() ? JSON.parse(this.editingQueryText) : {};
      } catch (err) {
        this.$message.error('Headers 或查询参数不是合法 JSON');
        return;
      }
      tool.parameters = (tool.parameters || [])
        .filter(param => param.name && param.name.trim())
        .map(param => ({
          ...param,
          name: param.name.trim(),
          type: param.type || 'string',
          description: param.description || '',
          required: !!param.required
        }));
      if (this.editingMcpToolIndex >= 0) {
        this.$set(this.noCodeTools, this.editingMcpToolIndex, tool);
      } else {
        this.noCodeTools.push(tool);
      }
      this.markNoCodeToolsDirty();
      this.mcpToolEditorVisible = false;
    },

    removeMcpTool(index) {
      this.noCodeTools.splice(index, 1);
      this.markNoCodeToolsDirty();
    },

    addMcpParam() {
      this.editingMcpTool.parameters.push({
        name: '',
        type: 'string',
        description: '',
        required: false
      });
    },

    removeMcpParam(index) {
      this.editingMcpTool.parameters.splice(index, 1);
    },

    saveNoCodeTools() {
      this.savingNoCodeTools = true;
      Api.agent.saveAgentMcpNoCodeTools(this.agentId, this.noCodeTools, (res) => {
        this.savingNoCodeTools = false;
        if (res.data.code === 0) {
          this.noCodeToolsDirty = false;
          this.$message.success('HTTP 工具配置已保存');
          this.refreshStatus();
        } else {
          this.$message.error(res.data.msg || '保存 HTTP 工具失败');
        }
      });
    },

    flushArray(key) {
      const text = this.textCache[key] || '';
      const arr = text
        .split('\n')
        .map(s => s.trim())
        .filter(Boolean);
      this.handleParamChange(this.currentFunction, key, arr);
    },

    flushJson(field) {
      const key = field.key;
      if (!key) {
        return;
      }
      const text = this.textCache[key] || '';
      try {
        const obj = JSON.parse(text);
        this.handleParamChange(this.currentFunction, key, obj);
      } catch {
        this.$message.error(`${this.currentFunction.name}${this.$t('functionDialog.jsonFormatError')}`);
      }
    },
    handleFunctionClick(func) {
      if (this.selectedNames.includes(func.name)) {
        const tempFunc = this.tempFunctions[func.name];
        this.currentFunction = tempFunc ? tempFunc : func;
      }
    },
    handleParamChange(func, key, value) {
      if (!this.tempFunctions[func.name]) {
        this.tempFunctions[func.name] = JSON.parse(JSON.stringify(func));
      }
      this.tempFunctions[func.name].params[key] = value;
    },
    handleCheckboxChange(func, checked) {
      if (checked) {
        if (!this.selectedNames.includes(func.name)) {
          this.selectedNames = [...this.selectedNames, func.name];
        }
      } else {
        this.selectedNames = this.selectedNames.filter(name => name !== func.name);
      }

      if (this.selectedList.length > 0) {
        this.currentFunction = this.selectedList[0];
      } else {
        this.currentFunction = null;
      }
    },

    selectAll() {
      this.selectedNames = [...this.allFunctions.map(f => f.name)];
      if (this.selectedList.length > 0) {
        this.currentFunction = JSON.parse(JSON.stringify(this.selectedList[0]));
      }
    },

    deselectAll() {
      this.selectedNames = [];
      this.currentFunction = null;
    },

    closeDialog() {
      this.tempFunctions = {};
      this.selectedNames = this.functions.map(f => f.name);
      this.currentFunction = null;
      this.dialogVisible = false;
      this.$emit('input', false);
      this.$emit('dialog-closed', false);
    },

    saveSelection() {
      Object.keys(this.tempFunctions).forEach(name => {
        this.modifiedFunctions[name] = JSON.parse(JSON.stringify(this.tempFunctions[name]));
      });
      this.tempFunctions = {};
      this.hasSaved = true;

      let selected = this.selectedList.map(f => {
        const modified = this.modifiedFunctions[f.name];
        return {
          id: f.id,
          name: f.name,
          providerCode: f.providerCode,
          fieldsMeta: f.fieldsMeta,
          params: this.sanitizeFunctionParams(
            f,
            modified ? modified.params : f.params
          )
        }
      });

      // 如果通讯录功能未启用，自动取消已选的设备呼叫设备插件
      if (!this.featureStatus.addressBook) {
        selected = selected.filter(f => f.providerCode !== 'call_device');
      }

      this.$emit('update-functions', selected);
      this.dialogVisible = false;
      // 通知父组件对话框已关闭且已保存
      this.$emit('dialog-closed', true);
    },
    fieldRemark(field) {
      let description = (field && field.label) ? field.label : '';
      if (field.default) {
        description += `（${this.$t('functionDialog.defaultValue')}：${field.default}）`;
      }
      return description;
    },
    isDeclarativeInteractionPlugin(func) {
      const fields = Array.isArray(func && func.fieldsMeta) ? func.fieldsMeta : [];
      const customField = fields.find(field => field.key === 'custom_plugin');
      if (!customField) {
        return false;
      }
      if (typeof customField.default === 'boolean') {
        return customField.default;
      }
      return String(customField.default).toLowerCase() === 'true';
    },
  }
}
</script>

<style lang="scss" scoped>
.function-manager {
  display: grid;
  grid-template-columns: max-content max-content 1fr;
  gap: 12px;
  height: calc(58vh);
}

.custom-header {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid #EBEEF5;

  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .bold-title {
    font-size: 18px;
    font-weight: bold;
    margin: 0;
  }

  .drawer-subtitle {
    margin-top: 6px;
    color: #606266;
    font-size: 13px;
    line-height: 1.5;
  }

  .select-all-btn {
    padding: 0;
    height: auto;
    font-size: 14px;
  }
}

.function-column {
  position: relative;
  display: flex;
  flex-direction: column;
  width: auto;
  height: 100%; 
  padding: 10px;
  border-right: 1px solid #EBEEF5;
  scrollbar-width: none;
  overflow-x: hidden;
  box-sizing: border-box;
}

.mcp-access-point {
  position: relative;
  z-index: 1;
  background: white;
}

.function-column::-webkit-scrollbar {
  display: none;
}

.function-list {
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.function-item {
  padding: 8px 12px;
  margin: 4px 0;
  width: 100%;
  text-align: left;
  cursor: pointer;
  border-radius: 4px;
  transition: background-color 0.2s;
  display: flex;
  align-items: center;
  justify-content: space-between;

  &:hover {
    background-color: #f5f7fa;
  }
}

.params-column {
  min-width: 280px;
  padding: 10px;
  overflow-y: auto;
  scrollbar-width: none;
}

.params-column::-webkit-scrollbar {
  display: none;
}

.column-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.column-title {
  text-align: center;
  width: 100%;
}

.func-tag {
  display: flex;
  align-items: center;
  cursor: pointer;
  flex-grow: 1;
  margin-left: 8px;
  min-width: 0;
}

.func-tag span:first-of-type {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.declarative-plugin-tag {
  flex-shrink: 0;
  margin-left: 8px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #ecf5ff;
  color: #409eff;
  font-size: 12px;
  line-height: 18px;
}

.color-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  background-color: #5778ff;
  margin-right: 8px;
  border-radius: 50%;
}

.param-form {
  .param-item {
    font-size: 16px;

    &.textarea-field {
      ::v-deep .el-form-item__content {
        margin-left: 0 !important;
        display: block;
        width: 100%;
      }

      ::v-deep .el-form-item__label {
        display: block;
        width: 100% !important;
        margin-bottom: 8px;
      }
    }
  }

  .param-input {
    width: 100%;
  }

  ::v-deep .el-form-item {
    display: flex;
    flex-direction: column;
    margin-bottom: 12px;

    .el-form-item__label {
      font-size: 14px !important;
      color: #606266;
      text-align: left;
      padding-right: 10px;
      flex-shrink: 0;
      width: auto !important;
    }

    .el-form-item__content {
      margin-left: 0 !important;
      flex-grow: 1;

      .el-input__inner {
        text-align: left;
        padding-left: 8px;
        width: 100%;
      }
    }
  }
}

.params-container {
  padding: 16px;
  border-radius: 4px;
  min-width: 280px;
}

.shared-config-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  margin-bottom: 14px;
  border-bottom: 1px solid #ebeef5;
}

.shared-config-title {
  color: #303133;
  font-size: 14px;
  font-weight: 600;
}

.shared-config-status {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
}

.empty-tip {
  padding: 20px;
  color: #909399;
  text-align: center;
}


.drawer-footer {
  position: absolute;
  bottom: 0;
  width: 100%;
  border-top: 1px solid #e8e8e8;
  padding: 10px 16px;
  text-align: center;
  background: #fff;
}

.info-icon {
  width: 16px;
  height: 16px;
  margin-right: 1vh;
}

.custom-close-btn {
  position: absolute;
  top: 50%;
  right: 10px;
  transform: translateY(-50%);
  width: 35px;
  height: 35px;
  border-radius: 50%;
  border: 2px solid #cfcfcf;
  background: none;
  font-size: 30px;
  font-weight: lighter;
  color: #cfcfcf;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
  padding: 0;
  outline: none;
  transition: all 0.3s;
}

.custom-close-btn:hover {
  color: #409EFF;
  border-color: #409EFF;
}

::v-deep .el-checkbox__label {
  display: none;
}

.mcp-access-point {
  border-top: 1px solid #EBEEF5;
  padding: 20px 24px;
  text-align: left;
}

.mcp-header {
  .bold-title {
    font-size: 18px;
    font-weight: bold;
    margin: 5px 0 30px 0;
  }
}

.mcp-container {
  display: flex;
  justify-content: space-between;
  gap: 30px;
}

.mcp-left,
.mcp-right {
  flex: 1;
  padding-bottom: 50px;
}

.url-header {
  margin-bottom: 8px;
  color: black;

  h4 {
    margin: 0 0 15px 0;
    font-size: 16px;
    font-weight: normal;
  }

  .address-desc {
    display: flex;
    align-items: center;
    font-size: 14px;
    margin-bottom: 12px;

    .doc-link {
      color: #1677ff;
      text-decoration: none;
      margin-left: 4px;

      &:hover {
        text-decoration: underline;
      }
    }
  }
}

.url-input {
  border-radius: 4px 0 0 4px;
  font-size: 14px;
  height: 36px;
  box-sizing: border-box;

  ::v-deep .el-input__inner {
    background-color: #f5f5f5 !important;
  }

  ::v-deep .el-input__suffix {
    right: 0;
    display: flex;
    align-items: center;
    padding-right: 10px;

    .inner-copy-btn {
      pointer-events: auto;
      border: none;
      background: #1677ff;
      color: white;
      padding: 6px;
      margin-top: 4px;
      margin-left: 4px;
    }
  }
}

.mcp-right {
  h4 {
    margin: 0 0 10px 0;
    font-size: 16px;
    font-weight: normal;
    color: black;
  }
}

.status-container {
  display: flex;
  align-items: center;

  .status-indicator {
    display: inline-block;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    margin-right: 8px;

    &.disconnected {
      background-color: #909399;
      /* 灰色 - 未连接 */
    }

    &.connected {
      background-color: #67C23A;
      /* 绿色 - 已连接 */
    }

    &.loading {
      background-color: #E6A23C;
      /* 橙色 - 加载中 */
      animation: pulse 1.5s infinite;
    }
  }

  .status-text {
    font-size: 14px;
    margin-right: 10px;
  }

  .refresh-btn {
    display: flex;
    align-items: center;
    padding: 2px 10px;
    background: white;
    color: black;
    border: 1px solid #DCDFE6;
    border-radius: 4px;
    cursor: pointer;
    font-size: 14px;
    transition: all 0.3s;

    &:hover {
      background: #1677ff;
      color: white;
      border-color: #1677ff;
    }

    .refresh-icon {
      margin-right: 6px;
      font-size: 14px;
    }
  }
}

@keyframes pulse {
  0% {
    opacity: 1;
  }

  50% {
    opacity: 0.4;
  }

  100% {
    opacity: 1;
  }
}

.mcp-tools-list {
  margin-top: 10px;

  .tools-grid {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .tool-btn {
    padding: 6px 12px;
    border-color: #1677ff;
    color: #1677ff;
    background-color: white;
    font-size: 12px;

    &:hover {
      background-color: #1677ff;
      color: white;
      border-color: #1677ff;
    }
  }

  .no-tools {
    text-align: center;
    color: #909399;
    font-size: 14px;
    padding: 10px 0;
  }
}

.no-code-tools-panel {
  margin-top: 16px;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
}

.no-code-tools-header,
.no-code-tools-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.no-code-tools-header {
  margin-bottom: 10px;

  h4 {
    margin: 0;
    font-size: 14px;
    font-weight: 600;
  }
}

.no-code-tools-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 180px;
  overflow-y: auto;
}

.no-code-tool-row {
  display: grid;
  grid-template-columns: auto 1fr auto auto;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fff;
}

.no-code-tool-main {
  min-width: 0;
}

.no-code-tool-name {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.no-code-tool-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.no-code-tools-empty {
  padding: 14px 0;
  color: #909399;
  text-align: center;
  font-size: 13px;
}

.no-code-tools-actions {
  justify-content: flex-end;
  margin-top: 10px;
}

.method-select {
  width: 160px;
}

.mcp-param-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}

.mcp-param-row {
  display: grid;
  grid-template-columns: 120px 110px 1fr 72px 36px;
  align-items: center;
  gap: 8px;
}
</style>
