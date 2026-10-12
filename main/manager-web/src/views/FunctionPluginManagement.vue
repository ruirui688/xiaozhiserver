<template>
  <div class="welcome">
    <HeaderBar />
    <ResourceCenterNav v-if="!isOps" />

    <main class="plugin-page">
      <section class="page-hero">
        <div class="page-heading">
          <h1>{{ isOps ? "官方工具与提示词" : "给智能体添加新本领" }}</h1>
          <p class="page-subtitle">{{ isOps ? "维护批准的公共工具目录和调用说明。" : "先找到需要的功能，再选择智能体完成设置。" }}</p>
        </div>
        <div class="hero-stats" aria-label="功能分类统计">
          <div class="stat-item">
            <strong>{{ pluginCounts.plugins }}</strong>
            <span>可添加插件</span>
          </div>
          <div class="stat-item">
            <strong>{{ pluginCounts.activities }}</strong>
            <span>互动玩法</span>
          </div>
          <div class="stat-item">
            <strong>{{ pluginCounts.integrations }}</strong>
            <span>专用集成</span>
          </div>
        </div>
        <div class="hero-actions">
          <el-button icon="el-icon-refresh" :loading="loading || sharedConfigLoading" @click="refreshCatalog">刷新</el-button>
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
            placeholder="搜索功能，例如天气、音乐"
            clearable
            class="search-input"
            @keyup.enter.native="handleSearch"
          />

          <div class="filter-section">
            <div class="filter-title">我想添加</div>
            <button
              v-for="section in mainSections"
              :key="section.key"
              type="button"
              :class="['filter-item', { active: activeType === section.key }]"
              :aria-pressed="activeType === section.key"
              @click="setActiveType(section.key)"
            >
              <span><i :class="section.icon" />{{ section.label }}</span>
              <strong>{{ pluginCounts[section.key] }}</strong>
            </button>
          </div>

          <details class="advanced-directory">
            <summary>高级目录</summary>
            <button v-for="section in advancedSections" :key="section.key" type="button"
              :class="['filter-item', { active: activeType === section.key }]"
              :aria-pressed="activeType === section.key" @click="setActiveType(section.key)">
              <span>{{ section.label }}</span><strong>{{ pluginCounts[section.key] }}</strong>
            </button>
          </details>

          <div v-if="showCategoryFilter" class="filter-section category-section">
            <div class="filter-title">按用途筛选</div>
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
              <strong>添加后才会用于对话</strong>
              <span>选好功能后，点击设置按钮，选择要使用它的智能体。</span>
            </div>
          </div>
        </aside>

        <div class="catalog-panel">
          <div class="catalog-toolbar">
            <div>
              <h2>{{ currentFilterTitle }}</h2>
              <p>{{ sectionDescription }}</p>
            </div>
            <div class="catalog-controls">
              <el-select v-model="sortMode" size="small" class="sort-select" aria-label="插件排序">
                <el-option label="默认排序" value="sort" />
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

          <div v-if="catalogError" class="catalog-error" role="alert">
            <span>{{ catalogError }}</span><el-button size="small" @click="fetchPlugins">重新加载</el-button>
          </div>
          <el-empty v-else-if="!loading && visiblePlugins.length === 0" :description="emptyDescription">
            <p v-if="activeType === 'activities' && !searchName && !pluginCounts.activities" class="empty-guidance">
              管理员创建玩法后，可在智能体的功能设置中添加。
            </p>
            <el-button v-if="activeType === 'activities' && !searchName && canManagePlugins"
              type="primary" @click="openCreateDialog()">新建互动玩法</el-button>
            <el-button v-else-if="searchName || activeCategory !== 'all'" @click="clearFilters">清除筛选</el-button>
          </el-empty>
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
              :allow-setup="!isOps"
              :configurable="!isOps && isSharedConfigPlugin(plugin) && !isRetiredPlugin(plugin)"
              :config-state="sharedConfigState(plugin.providerCode)"
              :testing="Boolean(pluginTesting[plugin.providerCode])"
              @detail="openDetailDialog"
              @setup="openSetup"
              @configure="openSharedConfig"
              @test="testSharedConfig"
            />
          </div>

          <div class="table-bottom">
            <span class="total-text">本分类共 {{ pluginCounts[activeType] }} 项，当前显示 {{ visiblePlugins.length }} 项</span>
          </div>
        </div>
      </section>
    </main>

    <el-dialog
      class="catalog-editor-dialog"
      :title="editingId ? '编辑互动玩法' : '新建互动玩法'"
      :visible.sync="formDialogVisible"
      width="min(820px, calc(100vw - 32px))"
      top="6vh"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form ref="pluginForm" :model="pluginForm" :rules="rules" label-width="110px">
        <el-alert type="info" :closable="false" title="这里的规则作为默认玩法。智能体已经自定义的规则不会被覆盖；也可以复制已有玩法后修改。" />

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

    <PluginDetailDialog
      :visible.sync="detailDialogVisible"
      :plugin="currentPlugin"
      :can-manage="Boolean(currentPlugin && canEditPlugin(currentPlugin))"
      :configurable="Boolean(currentPlugin && isSharedConfigPlugin(currentPlugin))"
      :config-state="sharedConfigState(currentPlugin && currentPlugin.providerCode)"
      :testing="Boolean(currentPlugin && pluginTesting[currentPlugin.providerCode])"
      @setup="openSetup" @configure="openSharedConfig" @test="testSharedConfig"
      @edit-prompt="openPromptDialog" @edit="openEditDialog" @command="handleRowCommand"
    />

    <PluginSetupDialog :visible.sync="setupVisible" :plugin="setupPlugin" />

    <el-dialog
      class="catalog-editor-dialog"
      title="编辑工具调用说明"
      :visible.sync="promptDialogVisible"
      width="min(720px, calc(100vw - 32px))"
      top="6vh"
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
          title="这段说明帮助模型判断何时使用功能。保存后，使用该目录的智能体会在当前回复结束后更新；不会自动添加插件或修改玩法规则。"
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
import PluginDetailDialog from "@/components/resource/PluginDetailDialog.vue";
import PluginSetupDialog from "@/components/resource/PluginSetupDialog.vue";
import UserPluginConfigDialog from "@/components/UserPluginConfigDialog.vue";
import { isInteractionPlugin, isRetiredPlugin, pluginDefault, pluginFields } from "@/utils/pluginCatalog";
import { pluginPresentation, pluginSectionCounts, filterCatalogPlugins } from "@/utils/pluginPresentation";

const SHARED_CONFIG_PROVIDERS = new Set(["get_weather", "web_search"]);

export default {
  name: "FunctionPluginManagement",
  components: {
    HeaderBar,
    VersionFooter,
    ResourceCenterNav,
    PluginResourceCard,
    PluginDetailDialog,
    PluginSetupDialog,
    UserPluginConfigDialog
  },
  data() {
    return {
      loading: false,
      saving: false,
      searchName: "",
      activeType: "plugins",
      activeCategory: "all",
      sortMode: "sort",
      viewMode: "list",
      plugins: [],
      allPluginProviders: [],
      catalogError: "",
      setupVisible: false,
      setupPlugin: null,
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
      sharedConfigLoading: false,
      sharedConfigError: false,
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
    isInteractionPlugin, isRetiredPlugin, pluginDefault,
    canEditPlugin(plugin) { return this.canManagePlugins && !isRetiredPlugin(plugin); },
    refreshCatalog() {
      this.fetchPlugins();
      this.fetchSharedConfigStatuses();
    },
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
      this.catalogError = "";
      const handleFailure = () => {
        this.loading = false;
        this.catalogError = "暂时无法加载功能，请稍后重试。";
      };
      const handleResponse = (response) => {
        const data = response && response.data;
        this.loading = false;
        if (!data || data.code !== 0 || !data.data) {
          handleFailure();
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
      if (!this.isOps) {
        Api.model.getPluginFunctionList(null, handleResponse, handleFailure);
        return;
      }
      Api.model.getModelProvidersPage(
        {
          page: 1,
          limit: 1000,
          modelType: "Plugin"
        },
        handleResponse,
        handleFailure
      );
    },
    fetchSharedConfigStatuses() {
      if (this.isOps) return;
      this.sharedConfigLoading = true;
      this.sharedConfigError = false;
      Api.user.getPluginConfigs((response) => {
        const data = response && response.data;
        this.sharedConfigLoading = false;
        if (!data || data.code !== 0 || !Array.isArray(data.data)) {
          this.sharedConfigError = true;
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
      return this.sharedConfigState(providerCode) === "saved";
    },
    sharedConfigState(providerCode) {
      if (this.sharedConfigLoading) return "loading";
      if (this.sharedConfigError) return "error";
      return this.sharedConfigStatuses[providerCode] ? "saved" : "missing";
    },
    openSetup(plugin) {
      if (this.isOps) return;
      this.detailDialogVisible = false;
      this.setupPlugin = plugin;
      this.setupVisible = true;
    },
    openSharedConfig(plugin) {
      if (this.isOps) return;
      this.sharedConfigProviderCode = plugin.providerCode;
      this.sharedConfigPluginName = plugin.name;
      this.sharedConfigDialogVisible = true;
    },
    handleSharedConfigSaved() {
      this.fetchSharedConfigStatuses();
    },
    testSharedConfig(plugin) {
      if (this.isOps) return;
      const providerCode = plugin.providerCode;
      if (this.sharedConfigState(providerCode) === "loading") {
        this.$message.warning("正在读取服务配置，请稍后重试");
        return;
      }
      if (this.sharedConfigState(providerCode) === "error") {
        this.$message.warning("服务配置状态读取失败，请打开配置确认");
        this.openSharedConfig(plugin);
        return;
      }
      if (!this.sharedConfigConfigured(providerCode)) {
        this.$message.warning("请先完成密钥配置");
        this.openSharedConfig(plugin);
        return;
      }
      this.$set(this.pluginTesting, providerCode, true);
      Api.user.testPluginConfig(providerCode, {}, (response) => {
        const data = response && response.data;
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
    setActiveType(type) {
      this.activeType = type;
      this.activeCategory = "all";
    },
    clearFilters() {
      this.searchName = "";
      this.activeCategory = "all";
    },
    parseFields(fields) { return pluginFields({ fields }); },
    getFieldDefault(fields, key, fallback = "") {
      const field = fields.find((item) => item.key === key);
      return field && field.default !== undefined ? field.default : fallback;
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
        this.$message.success("调用说明已保存，将在当前回复结束后更新");
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
      this.detailDialogVisible = false;
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
      this.detailDialogVisible = false;
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
          this.detailDialogVisible = false;
          this.fetchPlugins();
        });
      }).catch(() => {});
    }
  },
  computed: {
    isOps() { return this.$store.state.userInfo?.audience === 'OPS'; },
    legacyParametersSchema() {
      const source = this.plugins.find(item => item.id === this.editingId);
      const schema = pluginDefault(source, "parameters_schema", null);
      return schema ? JSON.stringify(schema, null, 2) : "";
    },
    canManagePlugins() {
      return this.$store.state.userInfo?.audience === 'OPS' && (this.$store.state.userInfo.permissions || []).includes('prompt.catalog.write');
    },
    rules() {
      return {
        name: [{ required: true, message: "请输入玩法名称", trigger: "blur" }],
        providerCode: [{ required: true, message: "请输入功能编码", trigger: "blur" }],
        description: [{ required: true, message: "请输入用户触发说法", trigger: "blur" }],
        instructions: [{ required: true, message: "请输入玩法规则", trigger: "blur" }]
      };
    },
    mainSections() {
      return [
        { key: "plugins", label: "可添加插件", icon: "el-icon-connection" },
        { key: "activities", label: "互动玩法", icon: "el-icon-chat-dot-round" },
        { key: "integrations", label: "专用集成", icon: "el-icon-link" }
      ];
    },
    advancedSections() {
      return [{ key: "system", label: "系统内置" }, { key: "retired", label: "已停用" }];
    },
    visiblePlugins() {
      return filterCatalogPlugins(this.plugins, {
        section: this.activeType, category: this.activeCategory,
        search: this.searchName, sort: this.sortMode
      });
    },
    pluginCounts() { return pluginSectionCounts(this.plugins); },
    showCategoryFilter() { return this.activeType === "plugins"; },
    currentFilterTitle() {
      const section = [...this.mainSections, ...this.advancedSections].find(item => item.key === this.activeType);
      return section ? section.label : "可添加插件";
    },
    sectionDescription() {
      return {
        plugins: "按需要添加，不必全部开启。点击设置可选择智能体。",
        activities: "为智能体编写游戏、朗诵等互动规则，再添加到需要的智能体。",
        integrations: "这些功能需要在对应设置中绑定服务或资料。",
        system: "这些功能由系统或管理员管理，通常无需手动添加。",
        retired: "这些旧功能已停止使用，可查看说明和替代设置。"
      }[this.activeType];
    },
    emptyDescription() {
      if (this.searchName || this.activeCategory !== "all") return "没有找到匹配的功能";
      return this.activeType === "activities" ? "还没有互动玩法" : "这个分类暂时没有功能";
    },
    categoryFilters() {
      const available = filterCatalogPlugins(this.plugins, { section: "plugins", search: this.searchName });
      const categories = new Map();
      available.forEach(plugin => {
        const category = pluginPresentation(plugin).category;
        const item = categories.get(category.key) || { ...category, count: 0 };
        item.count += 1;
        categories.set(category.key, item);
      });
      return [{ key: "all", label: "全部用途", count: available.length }, ...categories.values()];
    }
  }
};
</script>

<style lang="scss" scoped>
.catalog-editor-dialog ::v-deep .el-dialog { display: flex; flex-direction: column; max-height: 88vh; margin-bottom: 0; }
.catalog-editor-dialog ::v-deep .el-dialog__body { min-height: 0; max-height: calc(88vh - 140px); overflow-y: auto; }
.catalog-editor-dialog ::v-deep .el-dialog__header,
.catalog-editor-dialog ::v-deep .el-dialog__footer { flex-shrink: 0; }
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

.page-heading h1 { margin: 0 0 6px; color: #293e59; font-size: 20px; }
.advanced-directory { margin-top: 16px; padding-top: 13px; border-top: 1px solid #edf0f4; }
.advanced-directory summary { padding: 4px 8px; color: #8490a2; font-size: 12px; cursor: pointer; }
.advanced-directory[open] summary { margin-bottom: 8px; }
.catalog-error { display: flex; gap: 12px; align-items: center; justify-content: center; padding: 60px 12px; color: #7d8798; }
.empty-guidance { max-width: 360px; color: #8490a2; font-size: 13px; }

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
  margin-top: 18px;
}

.plugin-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 18px;
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

</style>
