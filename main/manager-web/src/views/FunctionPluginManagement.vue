<template>
  <div class="welcome">
    <HeaderBar />
    <ResourceCenterNav />

    <main class="plugin-page">
      <section class="page-hero">
        <div class="page-heading">
          <p class="page-subtitle">维护静态工具与活动目录。目录条目不等于某个智能体本轮实际可用的工具。</p>
        </div>
        <div class="hero-stats" aria-label="目录条目统计">
          <div class="stat-item">
            <strong>{{ pluginCounts.all }}</strong>
            <span>目录总数</span>
          </div>
          <div class="stat-item">
            <strong>{{ pluginCounts.builtin }}</strong>
            <span>工具条目</span>
          </div>
          <div class="stat-item">
            <strong>{{ pluginCounts.interaction }}</strong>
            <span>活动条目</span>
          </div>
          <div class="stat-item">
            <strong>{{ pluginCounts.retired }}</strong>
            <span>已退役</span>
          </div>
        </div>
        <div class="hero-actions">
          <el-button icon="el-icon-refresh" :loading="loading" @click="fetchPlugins">刷新</el-button>
          <el-button
            v-if="canManagePlugins"
            type="primary"
            icon="el-icon-plus"
            @click="openCreateDialog()"
          >
            新建互动玩法
          </el-button>
        </div>
      </section>

      <section class="management-shell">
        <aside class="filter-panel">
          <el-input
            v-model="searchName"
            prefix-icon="el-icon-search"
            placeholder="搜索名称、编码或调用说明"
            clearable
            class="search-input"
            @keyup.enter.native="handleSearch"
          />

          <div class="filter-section">
            <div class="filter-title">目录类型</div>
            <button
              type="button"
              :class="['filter-item', { active: activeType === 'all' }]"
              @click="setActiveType('all')"
            >
              <span><i class="el-icon-menu" />全部目录条目</span>
              <strong>{{ pluginCounts.all }}</strong>
            </button>
            <button
              type="button"
              :class="['filter-item', { active: activeType === 'builtin' }]"
              @click="setActiveType('builtin')"
            >
              <span><i class="el-icon-cpu" />工具条目</span>
              <strong>{{ pluginCounts.builtin }}</strong>
            </button>
            <button
              type="button"
              :class="['filter-item', { active: activeType === 'interaction' }]"
              @click="setActiveType('interaction')"
            >
              <span><i class="el-icon-chat-dot-round" />活动条目</span>
              <strong>{{ pluginCounts.interaction }}</strong>
            </button>
            <button type="button" :class="['filter-item', { active: activeType === 'retired' }]" @click="setActiveType('retired')">
              <span>已退役</span><strong>{{ pluginCounts.retired }}</strong>
            </button>
          </div>

          <div v-if="showCategoryFilter" class="filter-section category-section">
            <div class="filter-title">能力分类</div>
            <button
              v-for="category in categoryFilters"
              :key="category.key"
              type="button"
              :class="['filter-item', { active: activeCategory === category.key }]"
              @click="activeCategory = category.key"
            >
              <span>{{ category.label }}</span>
              <strong>{{ category.count }}</strong>
            </button>
          </div>

          <div class="usage-tip">
            <i class="el-icon-info" />
            <div>
              <strong>这里维护工具与插件目录</strong>
              <span>调用说明在目录统一维护；角色中选择能力、配置业务参数及玩法覆盖。</span>
            </div>
          </div>
        </aside>

        <div class="catalog-panel">
          <div class="catalog-toolbar">
            <div>
              <h2>{{ currentFilterTitle }}</h2>
              <p>找到 {{ visiblePlugins.length }} 条目录记录</p>
            </div>
            <div class="catalog-controls">
              <el-select v-model="sortMode" size="small" class="sort-select" aria-label="插件排序">
                <el-option label="按后台排序" value="sort" />
                <el-option label="按名称排序" value="name" />
              </el-select>
              <el-button-group class="view-switch">
                <el-tooltip content="列表视图" placement="top">
                  <el-button
                    size="small"
                    icon="el-icon-s-unfold"
                    :type="viewMode === 'list' ? 'primary' : 'default'"
                    aria-label="列表视图"
                    @click="viewMode = 'list'"
                  />
                </el-tooltip>
                <el-tooltip content="卡片视图" placement="top">
                  <el-button
                    size="small"
                    icon="el-icon-menu"
                    :type="viewMode === 'grid' ? 'primary' : 'default'"
                    aria-label="卡片视图"
                    @click="viewMode = 'grid'"
                  />
                </el-tooltip>
              </el-button-group>
            </div>
          </div>

          <div class="catalog-note">
            <i class="el-icon-warning-outline" />
            此页不包含设备、MCP 或当前会话动态生成的完整工具列表。支持范围为静态声明，实际可用性由模型协议、角色配置与会话状态决定。
          </div>

          <el-empty v-if="!loading && visiblePlugins.length === 0" description="没有找到匹配的能力" />
          <div
            v-else
            v-loading="loading"
            :class="viewMode === 'grid' ? 'plugin-grid' : 'plugin-list'"
          >
            <PluginResourceCard
              v-for="plugin in visiblePlugins"
              :key="plugin.id"
              :plugin="plugin"
              :layout="viewMode"
              :interaction="isInteractionPlugin(plugin)"
              :retired="isRetiredPlugin(plugin)"
              :can-manage="canEditPlugin(plugin)"
              :category="getFunctionCategory(plugin)"
              :category-key="getCategoryMeta(plugin).key"
              :description="getPluginDescription(plugin)"
              :injection="pluginInjection(plugin)"
              :config-text="getConfigText(plugin)"
              :configurable="isSharedConfigPlugin(plugin) && !isRetiredPlugin(plugin)"
              :configured="sharedConfigConfigured(plugin.providerCode)"
              :testing="Boolean(pluginTesting[plugin.providerCode])"
              @detail="openDetailDialog"
              @configure="openSharedConfig"
              @test="testSharedConfig"
              @edit="openEditDialog"
              @edit-prompt="openPromptDialog"
              @command="handleRowCommand"
            />
          </div>

          <div class="table-bottom">
            <span class="total-text">已显示 {{ visiblePlugins.length }} / {{ pluginCounts.all }}</span>
          </div>
        </div>
      </section>
    </main>

    <el-dialog
      :title="editingId ? '编辑互动玩法' : '新建互动玩法'"
      :visible.sync="formDialogVisible"
      width="820px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form ref="pluginForm" :model="pluginForm" :rules="rules" label-width="110px">
        <el-alert type="info" :closable="false" title="这里维护目录规则。角色默认继承；已有角色自定义内容保持不变。需要参考现有玩法时，可从目录复制。" />

        <div class="form-row">
          <el-form-item label="玩法名称" prop="name">
            <el-input v-model="pluginForm.name" placeholder="例如：成语接龙" />
          </el-form-item>
          <el-form-item label="排序" prop="sort">
            <el-input-number v-model="pluginForm.sort" :min="0" controls-position="right" />
          </el-form-item>
        </div>

        <el-form-item label="调用说明" prop="description">
          <el-input
            v-model="pluginForm.description"
            type="textarea"
            :rows="3"
            placeholder="说明用户表达什么意图时适合启动，以及哪些情况不应启动"
          />
        </el-form-item>

        <el-form-item label="玩法规则" prop="instructions">
          <el-input
            v-model="pluginForm.instructions"
            type="textarea"
            :rows="7"
            placeholder="写清楚角色、规则、互动方式、失败或接不上时如何提示"
          />
        </el-form-item>

        <el-form-item label="开场要求">
          <el-input
            v-model="pluginForm.openingMessage"
            type="textarea"
            :rows="3"
            placeholder="玩法开始时对用户说的第一句话"
          />
        </el-form-item>

        <el-collapse v-model="activeAdvancedPanels" class="advanced-collapse">
          <el-collapse-item title="高级配置（通常不用修改）" name="advanced">
            <el-form-item label="功能编码" prop="providerCode">
              <el-input
                v-model="pluginForm.providerCode"
                :disabled="Boolean(editingId)"
                placeholder="例如：idiom_chain"
              />
            </el-form-item>
            <el-form-item v-if="legacyParametersSchema" label="历史参数">
              <pre class="catalog-rule-text">{{ legacyParametersSchema }}</pre>
              <p>只读保留以兼容历史配置；活动启动使用固定参数，不受此字段影响。</p>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      <div slot="footer" class="dialog-footer">
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePlugin">保存玩法</el-button>
      </div>
    </el-dialog>

    <el-dialog
      title="功能说明"
      :visible.sync="detailDialogVisible"
      width="640px"
      :close-on-click-modal="false"
    >
      <div v-if="currentPlugin" class="detail-panel">
        <div class="detail-title">{{ currentPlugin.name }}</div>
        <div class="detail-code">{{ currentPlugin.providerCode }}</div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="分类">{{ getFunctionCategory(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item label="类型">
            {{ isRetiredPlugin(currentPlugin) ? "已退役" : (isInteractionPlugin(currentPlugin) ? "活动" : "工具") }}
          </el-descriptions-item>
          <el-descriptions-item label="调用说明">{{ getPluginDescription(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item label="工具来源">{{ getToolSourceLabel(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item label="如何启用">
            {{ getActivationHint(currentPlugin) }}
          </el-descriptions-item>
          <el-descriptions-item label="注入阶段">{{ pluginInjection(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item label="支持路径（声明）">{{ pluginModelPaths(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item v-if="isInteractionPlugin(currentPlugin)" label="目录玩法规则">
            <div class="catalog-rule-text">{{ pluginDefault(currentPlugin, 'instructions') || '目录未填写' }}</div>
          </el-descriptions-item>
          <el-descriptions-item v-if="isInteractionPlugin(currentPlugin)" label="目录开场要求">
            {{ pluginDefault(currentPlugin, 'opening_message') || '目录未填写' }}
          </el-descriptions-item>
        </el-descriptions>
        <p class="catalog-note">这是目录定义，不是当前模型请求预览。继承目录的角色会使用目录规则，显式自定义的角色继续使用自己的规则。</p>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button v-if="currentPlugin && canEditPlugin(currentPlugin)" @click="openPromptDialog(currentPlugin)">编辑调用说明</el-button>
        <el-button type="primary" @click="detailDialogVisible = false">知道了</el-button>
      </div>
    </el-dialog>

    <el-dialog
      title="编辑工具调用说明"
      :visible.sync="promptDialogVisible"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form :model="promptForm" label-width="110px">
        <el-form-item label="能力名称">
          <el-input :value="promptForm.name" disabled />
        </el-form-item>
        <el-form-item label="工具编码">
          <el-input :value="promptForm.providerCode" disabled />
        </el-form-item>
        <el-form-item label="调用说明" required>
          <el-input
            v-model="promptForm.description"
            type="textarea"
            :rows="9"
            maxlength="1000"
            show-word-limit
            placeholder="准确说明什么情况下调用这个工具，以及什么情况下不要调用"
          />
        </el-form-item>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="这是工具调用前的说明，统一在目录维护。保存后在安全轮次刷新；不会修改玩法规则、参数或启用范围。"
        />
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="promptDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="promptSaving" @click="saveToolPrompt">保存调用说明</el-button>
      </div>
    </el-dialog>

    <UserPluginConfigDialog
      :visible.sync="sharedConfigDialogVisible"
      :provider-code="sharedConfigProviderCode"
      :plugin-name="sharedConfigPluginName"
      @saved="handleSharedConfigSaved"
    />

    <el-footer>
      <VersionFooter />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";
import VersionFooter from "@/components/VersionFooter.vue";
import ResourceCenterNav from "@/components/resource/ResourceCenterNav.vue";
import PluginResourceCard from "@/components/resource/PluginResourceCard.vue";
import UserPluginConfigDialog from "@/components/UserPluginConfigDialog.vue";
import { catalogCounts, isInteractionPlugin, isRetiredPlugin, pluginActivation,
  pluginDefault, pluginFields, pluginInjection, pluginModelPaths } from "@/utils/pluginCatalog";

const SHARED_CONFIG_PROVIDERS = new Set(["get_weather", "web_search"]);

export default {
  name: "FunctionPluginManagement",
  components: {
    HeaderBar,
    VersionFooter,
    ResourceCenterNav,
    PluginResourceCard,
    UserPluginConfigDialog
  },
  data() {
    return {
      loading: false,
      saving: false,
      searchName: "",
      activeType: "all",
      activeCategory: "all",
      sortMode: "sort",
      viewMode: "list",
      plugins: [],
      allPluginProviders: [],
      formDialogVisible: false,
      detailDialogVisible: false,
      promptDialogVisible: false,
      promptSaving: false,
      promptForm: {
        id: "",
        name: "",
        providerCode: "",
        description: ""
      },
      currentPlugin: null,
      sharedConfigDialogVisible: false,
      sharedConfigProviderCode: "",
      sharedConfigPluginName: "",
      sharedConfigStatuses: {},
      pluginTesting: {},
      editingId: "",
      activeAdvancedPanels: [],
      pluginForm: this.createEmptyForm(),
    };
  },
  created() {
    this.fetchPlugins();
    this.fetchSharedConfigStatuses();
  },
  methods: {
    isInteractionPlugin, isRetiredPlugin, pluginDefault, pluginInjection, pluginModelPaths,
    canEditPlugin(plugin) { return this.canManagePlugins && !isRetiredPlugin(plugin); },
    createEmptyForm() {
      return {
        name: "",
        providerCode: "",
        sort: 90,
        description: "",
        instructions: "",
        openingMessage: ""
      };
    },
    fetchPlugins() {
      this.loading = true;
      const handleResponse = ({ data }) => {
        this.loading = false;
        if (data.code !== 0) {
          this.$message.error(data.msg || "获取插件列表失败");
          return;
        }
        const source = Array.isArray(data.data) ? data.data : (data.data.list || []);
        this.allPluginProviders = source.map((item) => ({
          ...item,
          fieldsMeta: this.parseFields(item.fields)
        }));
        this.plugins = this.allPluginProviders
          .filter((item) => item.modelType === "Plugin")
          .sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0));
      };
      if (!this.canManagePlugins) {
        Api.model.getPluginFunctionList(null, handleResponse, () => {
          this.loading = false;
          this.$message.error("获取插件列表失败");
        });
        return;
      }
      Api.model.getModelProvidersPage(
        {
          page: 1,
          limit: 1000,
          modelType: "Plugin"
        },
        handleResponse
      );
    },
    fetchSharedConfigStatuses() {
      Api.user.getPluginConfigs(({ data }) => {
        if (!data || data.code !== 0) {
          return;
        }
        const statuses = {};
        (data.data || []).forEach((item) => {
          statuses[item.providerCode] = Boolean(item.configured && item.secretConfigured);
        });
        this.sharedConfigStatuses = statuses;
      });
    },
    isSharedConfigPlugin(plugin) {
      return SHARED_CONFIG_PROVIDERS.has(plugin && plugin.providerCode);
    },
    sharedConfigConfigured(providerCode) {
      return Boolean(this.sharedConfigStatuses[providerCode]);
    },
    openSharedConfig(plugin) {
      this.sharedConfigProviderCode = plugin.providerCode;
      this.sharedConfigPluginName = plugin.name;
      this.sharedConfigDialogVisible = true;
    },
    handleSharedConfigSaved() {
      this.fetchSharedConfigStatuses();
    },
    testSharedConfig(plugin) {
      const providerCode = plugin.providerCode;
      if (!this.sharedConfigConfigured(providerCode)) {
        this.$message.warning("请先完成密钥配置");
        this.openSharedConfig(plugin);
        return;
      }
      this.$set(this.pluginTesting, providerCode, true);
      Api.user.testPluginConfig(providerCode, {}, ({ data }) => {
        this.$set(this.pluginTesting, providerCode, false);
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "插件测试失败");
          return;
        }
        const result = data.data || {};
        if (!result.success) {
          this.$message.error(result.message || "插件测试失败");
          return;
        }
        this.$message.success(result.message || "插件测试通过");
      });
    },
    handleSearch() {
      this.searchName = this.searchName.trim();
    },
    handleTypeChange(type) {
      if (type !== "builtin") {
        this.activeCategory = "all";
      }
    },
    setActiveType(type) {
      this.activeType = type;
      this.handleTypeChange(type);
    },
    parseFields(fields) { return pluginFields({ fields }); },
    fieldBoolean(plugin, key) {
      const value = this.getFieldDefault(plugin.fieldsMeta || [], key, false);
      return value === true || String(value).toLowerCase() === "true";
    },
    isRuntimeBuiltin(plugin) {
      return this.fieldBoolean(plugin, "runtime_builtin");
    },
    getToolSourceLabel(plugin) {
      const source = this.getFieldDefault(plugin.fieldsMeta || [], "tool_source", "server_plugin");
      const labels = {
        routing: "主模型路由工具",
        server_plugin: "服务端工具/插件",
        server_mcp: "服务器 MCP",
        device_iot: "设备 IoT",
        device_mcp: "设备 MCP",
        mcp_endpoint: "MCP 接入点"
      };
      return labels[source] || source;
    },
    getActivationHint(plugin) { return pluginActivation(plugin); },
    getFieldDefault(fields, key, fallback = "") {
      const field = fields.find((item) => item.key === key);
      return field && field.default !== undefined ? field.default : fallback;
    },
    getPluginDescription(plugin) {
      return pluginDefault(plugin, "description") || "目录未填写调用说明";
    },
    getFunctionCategory(plugin) {
      return this.getCategoryMeta(plugin).label;
    },
    getCategoryMeta(plugin) {
      const code = plugin.providerCode || "";
      if (isInteractionPlugin(plugin)) {
        return { key: "chat", label: "聊天玩法" };
      }
      if (["direct_answer", "handle_exit_intent", "set_session_voice", "reset_conversation_context"].includes(code)) {
        return { key: "session", label: "会话控制" };
      }
      if (code === "get_lunar") {
        return { key: "time", label: "日期时间" };
      }
      if (code.includes("weather")) {
        return { key: "life", label: "生活服务" };
      }
      if (code.includes("news") || code.includes("search")) {
        return { key: "info", label: "内容资讯" };
      }
      if (code.includes("music")) {
        return { key: "media", label: "音乐音频" };
      }
      if (code.includes("hass") || code.includes("homeassistant") || code.includes("call_device")) {
        return { key: "device", label: "设备控制" };
      }
      return { key: "tool", label: "工具能力" };
    },
    getConfigText(plugin) {
      if (isRetiredPlugin(plugin)) return "已退役 · 不可启用";
      if (isInteractionPlugin(plugin)) {
        return "编写玩法规则";
      }
      if (this.isRuntimeBuiltin(plugin)) {
        return "运行时按条件提供";
      }
      if (this.fieldBoolean(plugin, "catalog_only")) {
        return "专用配置启用";
      }
      return plugin.fieldsMeta && plugin.fieldsMeta.length > 0 ? "角色中配置参数" : "角色中勾选";
    },
    openDetailDialog(row) {
      this.currentPlugin = row;
      this.detailDialogVisible = true;
    },
    openPromptDialog(row) {
      if (!this.canEditPlugin(row)) return;
      this.currentPlugin = row;
      this.promptForm = {
        id: row.id,
        name: row.name,
        providerCode: row.providerCode,
        description: pluginDefault(row, "description")
      };
      this.detailDialogVisible = false;
      this.promptDialogVisible = true;
    },
    saveToolPrompt() {
      if (!this.currentPlugin || !this.canEditPlugin(this.currentPlugin)) return;
      const description = String(this.promptForm.description || "").trim();
      if (!description) {
        this.$message.error("工具调用说明不能为空");
        return;
      }
      const source = this.currentPlugin;
      const fields = (source.fieldsMeta || []).map((field) => ({ ...field }));
      const index = fields.findIndex((field) => field.key === "description");
      const promptField = {
        key: "description",
        type: "textarea",
        label: "工具调用说明",
        default: description,
        editing: false,
        selected: false
      };
      if (index >= 0) {
        fields.splice(index, 1, { ...fields[index], ...promptField });
      } else {
        fields.push(promptField);
      }
      this.promptSaving = true;
      Api.model.updateModelProvider({
        id: source.id,
        modelType: source.modelType,
        providerCode: source.providerCode,
        name: source.name,
        sort: Number(source.sort || 0),
        fields
      }, ({ data }) => {
        this.promptSaving = false;
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "保存调用说明失败");
          return;
        }
        this.$message.success("调用说明已保存，在线会话将在安全轮次刷新");
        this.promptDialogVisible = false;
        this.fetchPlugins();
      });
    },
    handleRowCommand(row, command) {
      if (command === "duplicate") {
        this.openCreateDialog(row);
      }
      if (command === "delete") {
        this.deletePlugin(row);
      }
    },
    openCreateDialog(source) {
      if (!this.canManagePlugins || (source && isRetiredPlugin(source))) return;
      this.editingId = "";
      this.activeAdvancedPanels = ["advanced"];
      this.pluginForm = this.createEmptyForm();
      if (source) {
        this.pluginForm = {
          ...this.pluginForm,
          name: source.name + " 副本",
          description: pluginDefault(source, "description"),
          instructions: pluginDefault(source, "instructions"),
          openingMessage: pluginDefault(source, "opening_message")
        };
      }
      this.formDialogVisible = true;
    },
    openEditDialog(row) {
      if (!this.canEditPlugin(row)) return;
      this.editingId = row.id;
      this.pluginForm = {
        name: row.name,
        providerCode: row.providerCode,
        sort: Number(row.sort || 90),
        description: this.getFieldDefault(row.fieldsMeta, "description"),
        instructions: this.getFieldDefault(row.fieldsMeta, "instructions"),
        openingMessage: this.getFieldDefault(row.fieldsMeta, "opening_message")
      };
      this.formDialogVisible = true;
    },
    resetForm() {
      this.editingId = "";
      this.pluginForm = this.createEmptyForm();
      if (this.$refs.pluginForm) {
        this.$refs.pluginForm.clearValidate();
      }
    },
    buildFields() {
      const editedKeys = ["custom_plugin", "description", "instructions", "opening_message"];
      const source = this.plugins.find(item => item.id === this.editingId);
      const preserved = pluginFields(source).filter(field => !editedKeys.includes(field.key));
      return [...preserved,
        {
          key: "custom_plugin",
          type: "boolean",
          label: "互动玩法",
          default: true,
          editing: false,
          selected: false
        },
        {
          key: "description",
          type: "textarea",
          label: "调用说明",
          default: this.pluginForm.description,
          editing: false,
          selected: false
        },
        {
          key: "instructions",
          type: "textarea",
          label: "玩法规则",
          default: this.pluginForm.instructions,
          editing: false,
          selected: false
        },
        {
          key: "opening_message",
          type: "textarea",
          label: "开场要求",
          default: this.pluginForm.openingMessage,
          editing: false,
          selected: false
        },
      ];
    },
    validateProviderCode() {
      const providerCode = this.pluginForm.providerCode.trim();
      if (isRetiredPlugin({ providerCode })) {
        this.$message.error("该功能编码已退役，不能重新创建或启用");
        return false;
      }
      if (!/^[a-z][a-z0-9_]*$/.test(providerCode)) {
        this.$message.error("功能编码必须以小写字母开头，只能包含小写字母、数字和下划线");
        return false;
      }

      const duplicated = this.allPluginProviders.some((item) => {
        return item.providerCode === providerCode && item.id !== this.editingId;
      });
      if (duplicated) {
        this.$message.error("功能编码已存在，请换一个编码");
        return false;
      }
      return true;
    },
    savePlugin() {
      if (!this.canManagePlugins || (this.editingId && isRetiredPlugin(this.plugins.find(item => item.id === this.editingId)))) return;
      this.$refs.pluginForm.validate((valid) => {
        if (!valid || !this.validateProviderCode()) {
          return;
        }

        const payload = {
          id: this.editingId,
          modelType: "Plugin",
          providerCode: this.pluginForm.providerCode.trim(),
          name: this.pluginForm.name.trim(),
          sort: Number(this.pluginForm.sort || 0),
          fields: this.buildFields()
        };

        this.saving = true;
        const request = this.editingId
          ? (params, callback) => Api.model.updateModelProvider(params, callback)
          : (params, callback) => Api.model.addModelProvider(params, callback);
        request(payload, ({ data }) => {
          this.saving = false;
          if (data.code !== 0) {
            this.$message.error(data.msg || "保存互动玩法失败");
            return;
          }
          this.$message.success("保存成功");
          this.formDialogVisible = false;
          this.fetchPlugins();
        });
      });
    },
    deletePlugin(row) {
      if (!this.canEditPlugin(row)) return;
      if (!isInteractionPlugin(row)) {
        this.$message.warning("内置功能不能在这里删除");
        return;
      }
      this.$confirm(
        `确定要删除“${row.name}”吗？如果角色已选择该功能，对应配置会失效。`,
        "删除互动玩法",
        {
          confirmButtonText: "删除",
          cancelButtonText: "取消",
          type: "warning"
        }
      ).then(() => {
        Api.model.deleteModelProviderByIds([row.id], ({ data }) => {
          if (data.code !== 0) {
            this.$message.error(data.msg || "删除失败");
            return;
          }
          this.$message.success("删除成功");
          this.fetchPlugins();
        });
      }).catch(() => {});
    }
  },
  computed: {
    legacyParametersSchema() {
      const source = this.plugins.find(item => item.id === this.editingId);
      const schema = pluginDefault(source, "parameters_schema", null);
      return schema ? JSON.stringify(schema, null, 2) : "";
    },
    canManagePlugins() {
      return Boolean(this.$store.state.userInfo && this.$store.state.userInfo.superAdmin);
    },
    rules() {
      return {
        name: [{ required: true, message: "请输入玩法名称", trigger: "blur" }],
        providerCode: [{ required: true, message: "请输入功能编码", trigger: "blur" }],
        description: [{ required: true, message: "请输入用户触发说法", trigger: "blur" }],
        instructions: [{ required: true, message: "请输入玩法规则", trigger: "blur" }]
      };
    },
    searchedPlugins() {
      const keyword = this.searchName.trim().toLowerCase();
      if (!keyword) {
        return this.plugins;
      }
      return this.plugins.filter((item) => {
        return (
          String(item.name || "").toLowerCase().includes(keyword) ||
          String(item.providerCode || "").toLowerCase().includes(keyword) ||
          String(pluginDefault(item, "description")).toLowerCase().includes(keyword)
        );
      });
    },
    visiblePlugins() {
      let list = [...this.searchedPlugins];
      if (this.activeType === "builtin") {
        list = list.filter((item) => !isRetiredPlugin(item) && !isInteractionPlugin(item));
      }
      else if (this.activeType === "interaction") {
        list = list.filter((item) => !isRetiredPlugin(item) && isInteractionPlugin(item));
      }

      if (this.activeType === "retired") list = list.filter(isRetiredPlugin);
      if (this.showCategoryFilter && this.activeCategory !== "all") {
        list = list.filter((item) => {
          return !isInteractionPlugin(item) &&
            this.getCategoryMeta(item).key === this.activeCategory;
        });
      }
      if (this.sortMode === "name") {
        return list.sort((a, b) => String(a.name || "").localeCompare(String(b.name || ""), "zh-CN"));
      }
      return list.sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0));
    },
    pluginCounts() { return catalogCounts(this.plugins); },
    showCategoryFilter() {
      return this.activeType === "builtin";
    },
    currentFilterTitle() {
      if (this.activeType === "retired") return "已退役目录条目";
      if (this.activeType === "interaction") {
        return "互动玩法";
      }
      if (this.activeType === "builtin") {
        const category = this.categoryFilters.find((item) => item.key === this.activeCategory);
        return category ? category.label : "工具条目";
      }
      return "全部目录条目";
    },
    builtinPluginsForCategory() {
      return this.searchedPlugins.filter((item) => !isRetiredPlugin(item) && !isInteractionPlugin(item));
    },
    categoryCounts() {
      return this.builtinPluginsForCategory.reduce((counts, item) => {
        const key = this.getCategoryMeta(item).key;
        counts[key] = (counts[key] || 0) + 1;
        return counts;
      }, {});
    },
    categoryFilters() {
      const categories = [
        { key: "session", label: "会话控制" },
        { key: "time", label: "日期时间" },
        { key: "life", label: "生活服务" },
        { key: "info", label: "内容资讯" },
        { key: "media", label: "音乐音频" },
        { key: "device", label: "设备控制" },
        { key: "tool", label: "工具能力" }
      ];
      return [
        {
          key: "all",
          label: "全部工具条目",
          count: this.builtinPluginsForCategory.length
        },
        ...categories.map((category) => ({
          ...category,
          count: this.categoryCounts[category.key] || 0
        }))
      ];
    }
  }
};
</script>

<style lang="scss" scoped>
.catalog-rule-text { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 300px; overflow: auto; }
.welcome {
  min-width: 960px;
  min-height: 100vh;
  height: 100vh;
  display: flex;
  position: relative;
  flex-direction: column;
  background: #f4f7fb;
  overflow-x: hidden;
  overflow-y: auto;
  text-align: left;
  scrollbar-gutter: stable;
}

.plugin-page {
  width: calc(100% - 48px);
  max-width: 1680px;
  margin: 0 auto;
  padding: 22px 0 28px;
  box-sizing: border-box;
}

.page-hero {
  display: grid;
  grid-template-columns: minmax(300px, 1fr) auto auto;
  align-items: center;
  gap: 28px;
  padding: 14px 20px;
  border: 1px solid #e5eaf2;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 8px 26px rgba(37, 51, 77, 0.06);
}

.page-subtitle {
  margin: 0;
  color: #687386;
  font-size: 13px;
  line-height: 20px;
}

.hero-stats {
  display: flex;
  align-items: center;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  background: #f8fafc;
}

.stat-item {
  display: grid;
  min-width: 92px;
  gap: 1px;
  padding: 10px 16px;
  text-align: center;
}

.stat-item + .stat-item {
  border-left: 1px solid #e2e8f0;
}

.stat-item strong {
  color: #26364d;
  font-size: 20px;
  line-height: 25px;
}

.stat-item span {
  color: #8a94a6;
  font-size: 11px;
}

.hero-actions {
  display: flex;
  gap: 10px;
  white-space: nowrap;
}

.management-shell {
  display: grid;
  grid-template-columns: 238px minmax(0, 1fr);
  align-items: start;
  gap: 18px;
  margin-top: 18px;
}

.filter-panel,
.catalog-panel {
  border: 1px solid #e5eaf2;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 8px 26px rgba(37, 51, 77, 0.05);
}

.filter-panel {
  position: sticky;
  top: 16px;
  padding: 16px;
}

.search-input {
  width: 100%;
}

.filter-section {
  margin-top: 20px;
}

.filter-title {
  margin: 0 8px 7px;
  color: #98a2b3;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.7px;
  text-transform: uppercase;
}

.filter-item {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  padding: 9px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #4f5d73;
  font: inherit;
  cursor: pointer;
  transition: 0.18s ease;
}

.filter-item:hover {
  background: #f5f7fb;
}

.filter-item.active {
  background: #ecf3ff;
  color: #2f6fdd;
  font-weight: 600;
}

.filter-item span {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.filter-item strong {
  min-width: 24px;
  padding: 1px 6px;
  border-radius: 10px;
  background: #edf0f5;
  color: #7b8799;
  font-size: 11px;
  font-weight: 600;
  text-align: center;
}

.filter-item.active strong {
  background: #fff;
  color: #2f6fdd;
}

.category-section {
  padding-top: 16px;
  border-top: 1px solid #edf0f4;
}

.usage-tip {
  display: flex;
  gap: 8px;
  margin-top: 20px;
  padding: 11px;
  border-radius: 10px;
  background: #f7f9fc;
  color: #7c8799;
}

.usage-tip i {
  margin-top: 2px;
  color: #5b8def;
}

.usage-tip div {
  display: grid;
  gap: 3px;
}

.usage-tip strong {
  color: #4a586e;
  font-size: 12px;
}

.usage-tip span {
  font-size: 11px;
  line-height: 17px;
}

.catalog-panel {
  min-height: 560px;
  padding: 20px;
}

.catalog-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid #edf0f4;
}

.catalog-toolbar h2 {
  margin: 0;
  color: #26364d;
  font-size: 18px;
}

.catalog-toolbar p {
  margin: 4px 0 0;
  color: #98a2b3;
  font-size: 12px;
}

.catalog-controls {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sort-select {
  width: 132px;
}

.catalog-note {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 14px 0;
  padding: 9px 11px;
  border-radius: 8px;
  background: #f8fafc;
  color: #758196;
  font-size: 12px;
}

.catalog-note i {
  color: #5d8ee8;
}

.plugin-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 14px;
}

.plugin-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

@media (max-width: 1280px) {
  .page-hero {
    grid-template-columns: 1fr auto;
  }

  .hero-stats {
    display: none;
  }

  .management-shell {
    grid-template-columns: 210px minmax(0, 1fr);
  }
}

@media (max-width: 1050px) {
  .management-shell {
    grid-template-columns: 1fr;
  }

  .filter-panel {
    position: static;
  }

  .filter-section:not(.category-section) {
    display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 6px;
  }

  .filter-title {
    grid-column: 1 / -1;
  }

  .usage-tip,
  .category-section {
    display: none;
  }
}

.table-bottom {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #edf0f4;
}

.total-text {
  color: #98a2b3;
  font-size: 12px;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.advanced-collapse {
  margin-left: 110px;
}

.detail-title {
  color: #303133;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.5;
}

.detail-code {
  color: #909399;
  font-size: 12px;
  margin-bottom: 16px;
}
</style>
