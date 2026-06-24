<template>
  <el-dialog
    :visible.sync="dialogVisible"
    :close-on-click-modal="false"
    width="1120px"
    top="4vh"
    custom-class="custom-dialog"
    :show-close="false"
    class="model-edit-dialog"
  >
    <button class="custom-close-btn" @click="dialogVisible = false">×</button>
    <div class="dialog-scroll-body">
      <div class="dialog-title">
        {{
          modelData.duplicateMode
            ? $t("modelConfigDialog.duplicateModel")
            : modelData.id
              ? $t("modelConfigDialog.editModel")
              : $t("modelConfigDialog.addModel")
        }}
      </div>

      <div class="dialog-section-head">
        <div style="font-size: 20px; font-weight: bold; color: #3d4566">
          {{ $t("modelConfigDialog.modelInfo") }}
        </div>
        <div style="display: flex; align-items: center; gap: 20px">
          <div style="display: flex; align-items: center">
            <span style="margin-right: 8px">{{ $t("modelConfigDialog.enable") }}</span>
            <el-switch v-model="form.isEnabled" :active-value="1" :inactive-value="0" class="custom-switch"></el-switch>
          </div>
          <div style="display: none; align-items: center">
            <span style="margin-right: 8px">{{
              $t("modelConfigDialog.setDefault")
              }}</span>
            <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" class="custom-switch"></el-switch>
          </div>
        </div>
      </div>

      <div style="height: 2px; background: #e9e9e9; margin-bottom: 22px"></div>

      <el-form :model="form" ref="form" label-width="auto" label-position="left" class="custom-form">
        <div style="display: flex; gap: 20px; margin-bottom: 0">
          <el-form-item :label="$t('modelConfigDialog.configName')" prop="modelName" style="flex: 1">
            <el-input v-model="form.modelName" :placeholder="$t('modelConfigDialog.enterModelName')"
              class="custom-input-bg"></el-input>
          </el-form-item>
          <el-form-item :label="$t('modelConfigDialog.catalogItem')" prop="templateCode" style="flex: 1">
            <el-select v-model="form.templateCode" :placeholder="$t('modelConfigDialog.selectSupplier')"
              class="custom-select custom-input-bg" style="width: 100%" @focus="loadProviders" filterable
              @change="handleTemplateChange">
              <el-option
                v-for="item in providers"
                :key="item.templateCode"
                :label="item.label"
                :value="item.templateCode"
                :disabled="item.disabled"
              >
                <div class="provider-option">
                  <div class="provider-option-main">
                    <span class="provider-option-title">{{ item.title }}</span>
                    <span class="provider-option-meta">{{ item.meta }}</span>
                  </div>
                  <el-tag size="mini" :type="providerStatusType(item.raw)">
                    {{ providerStatusText(item.raw) }}
                  </el-tag>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
        </div>

        <div v-if="form.templateCode" class="catalog-detail">
          <div class="catalog-detail-main">
            <span class="catalog-detail-title">{{ getVendorProductText() }}</span>
            <el-tag size="mini" :type="providerStatusType(currentTemplate)">
              {{ providerStatusText(currentTemplate) }}
            </el-tag>
            <span class="catalog-detail-meta">{{ catalogMetaText }}</span>
          </div>
          <div class="catalog-detail-grid">
            <div v-if="currentTemplate.recommendedFor">
              <span class="catalog-detail-label">{{ $t("modelConfigDialog.recommendedFor") }}</span>
              {{ currentTemplate.recommendedFor }}
            </div>
            <div v-if="currentTemplate.avoidWhen">
              <span class="catalog-detail-label">{{ $t("modelConfigDialog.avoidWhen") }}</span>
              {{ currentTemplate.avoidWhen }}
            </div>
            <div v-if="currentTemplate.testContract">
              <span class="catalog-detail-label">{{ $t("modelConfigDialog.testContract") }}</span>
              {{ currentTemplate.testContract }}
            </div>
            <div v-if="currentTemplate.sourceCheckedAt">
              <span class="catalog-detail-label">{{ $t("modelConfigDialog.sourceCheckedAt") }}</span>
              {{ currentTemplate.sourceCheckedAt }}
            </div>
          </div>
        </div>

        <div style="display: flex; gap: 20px; margin-bottom: 0">
          <el-form-item :label="currentUpstreamLabel" prop="upstreamModelId" style="flex: 1">
            <el-select
              v-if="upstreamModelOptions.length"
              v-model="form.upstreamModelId"
              :placeholder="$t('modelConfigDialog.enterUpstreamModel')"
              class="custom-select custom-input-bg"
              style="width: 100%"
              filterable
              allow-create
              default-first-option
            >
              <el-option
                v-for="option in upstreamModelOptions"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
            <el-input v-else v-model="form.upstreamModelId" :placeholder="$t('modelConfigDialog.enterUpstreamModel')"
              class="custom-input-bg"></el-input>
          </el-form-item>
          <el-form-item prop="endpointUrl" style="flex: 1">
            <template slot="label">
              <span>{{ $t('modelConfigDialog.endpointUrl') }}</span>
              <el-tooltip :content="endpointHelpText" placement="top">
                <i class="el-icon-question field-help-icon"></i>
              </el-tooltip>
            </template>
            <el-input v-model="form.endpointUrl" :placeholder="endpointPlaceholder"
              class="custom-input-bg"></el-input>
            <div class="field-tip">{{ endpointHelpText }}</div>
          </el-form-item>
        </div>

        <el-collapse class="advanced-collapse">
          <el-collapse-item :title="$t('modelConfigDialog.advancedSettings')" name="advanced">
            <div style="display: flex; gap: 20px; margin-bottom: 0">
              <el-form-item :label="$t('modelConfigDialog.modelCode')" prop="modelCode" style="flex: 1">
                <el-input v-model="form.modelCode" :placeholder="$t('modelConfigDialog.enterModelCode')"
                  class="custom-input-bg"></el-input>
              </el-form-item>
              <el-form-item :label="$t('modelConfigDialog.protocol')" prop="protocol" style="flex: 1">
                <el-input v-model="form.protocol" :placeholder="$t('modelConfigDialog.enterProtocol')"
                  class="custom-input-bg"></el-input>
              </el-form-item>
            </div>
            <div style="display: flex; gap: 20px; margin-bottom: 0">
              <el-form-item :label="$t('modelConfig.vendorProduct')" style="flex: 1">
                <el-input :value="getVendorProductText()" class="custom-input-bg" disabled></el-input>
              </el-form-item>
              <el-form-item :label="$t('modelConfigDialog.sortOrder')" prop="sort" style="flex: 1">
                <el-input v-model.number="form.sort" type="number" :placeholder="$t('modelConfigDialog.enterSortOrder')"
                  class="custom-input-bg"></el-input>
              </el-form-item>
            </div>
            <el-form-item :label="$t('modelConfigDialog.docLink')" prop="docUrl">
              <el-input v-model="form.docLink" :placeholder="$t('modelConfigDialog.enterDocLink')"
                class="custom-input-bg"></el-input>
            </el-form-item>
            <el-form-item :label="$t('modelConfigDialog.remark')" prop="remark" class="prop-remark">
              <el-input v-model="form.remark" type="textarea" :rows="3" :placeholder="$t('modelConfigDialog.enterRemark')"
                :autosize="{ minRows: 3, maxRows: 5 }" class="custom-input-bg"></el-input>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      <div style="font-size: 20px; font-weight: bold; color: #3d4566; margin-bottom: 15px">
        {{ $t("modelConfigDialog.callInfo") }}
      </div>
      <div style="height: 2px; background: #e9e9e9; margin-bottom: 22px"></div>

      <el-form :model="form" ref="callInfoForm" label-width="auto" class="custom-form">
        <template v-for="section in visibleFieldSections">
          <div v-if="section.fields.length" :key="section.key" class="field-section">
            <div v-if="!section.collapsible" class="field-section-title">{{ section.title }}</div>
            <template v-if="section.collapsible">
              <el-collapse class="advanced-collapse">
                <el-collapse-item :title="section.title" :name="section.key">
                  <template v-for="(row, rowIndex) in chunkFields(section.fields)">
                    <div :key="`${section.key}-${rowIndex}`" class="field-row">
                      <model-field-control
                        v-for="field in row"
                        :key="field.prop"
                        :field="field"
                        :form="form"
                        :field-json-map="fieldJsonMap"
                        @json-change="handleJsonChange"
                      />
                    </div>
                  </template>
                </el-collapse-item>
              </el-collapse>
            </template>
            <template v-else>
              <template v-for="(row, rowIndex) in chunkFields(section.fields)">
                <div :key="`${section.key}-${rowIndex}`" class="field-row">
                  <model-field-control
                    v-for="field in row"
                    :key="field.prop"
                    :field="field"
                    :form="form"
                    :field-json-map="fieldJsonMap"
                    @json-change="handleJsonChange"
                  />
                </div>
              </template>
            </template>
          </div>
        </template>
      </el-form>
    </div>

    <div class="dialog-actions">
      <el-button
        type="primary"
        @click="handleSaveAndTest"
        class="test-btn"
        :loading="testing"
        :disabled="saving || testing || selectedTemplateBlocked"
      >
        {{ $t("modelConfigDialog.saveAndTest") }}
      </el-button>
      <el-button type="primary" @click="handleSave" class="save-btn" :loading="saving" :disabled="saving || testing || selectedTemplateBlocked">
        {{ $t("modelConfigDialog.save") }}
      </el-button>
    </div>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";

export default {
  name: "ModelEditDialog",
  components: {
    ModelFieldControl: {
      props: {
        field: { type: Object, required: true },
        form: { type: Object, required: true },
        fieldJsonMap: { type: Object, required: true },
      },
      methods: {
        updateValue(value) {
          this.$set(this.form[this.field.target], this.field.prop, value);
        },
        updateJsonValue(value) {
          this.$set(this.fieldJsonMap, this.field.prop, value);
        },
      },
      render(h) {
        const field = this.field;
        const form = this.form;
        const jsonMap = this.fieldJsonMap;
        const scopedSlots = {
          label: () => [
            h("span", field.label),
            field.helpText
              ? h("el-tooltip", { props: { content: field.helpText, placement: "top" } }, [
                  h("i", { class: "el-icon-question field-help-icon" }),
                ])
              : null,
          ],
        };
        let control;
        if (field.type === "json-textarea") {
          control = h("el-input", {
            class: "custom-input-bg",
            props: {
              value: jsonMap[field.prop],
              type: "textarea",
              rows: 3,
              placeholder: field.placeholder,
            },
            on: {
              input: this.updateJsonValue,
              change: (val) => this.$emit("json-change", field, val),
            },
          });
        } else if (field.type === "select") {
          control = h("el-select", {
            class: "custom-select custom-input-bg",
            style: { width: "100%" },
            props: { value: form[field.target][field.prop], placeholder: field.placeholder },
            on: { input: this.updateValue },
          }, (field.options || []).map((option) => h("el-option", {
            key: option.value,
            props: { label: option.label, value: option.value },
          })));
        } else if (field.type === "switch") {
          control = h("el-switch", {
            props: { value: form[field.target][field.prop] },
            on: { input: this.updateValue },
          });
        } else {
          control = h("el-input", {
            class: "custom-input-bg",
            props: {
              value: form[field.target][field.prop],
              placeholder: field.placeholder,
              type: field.type === "number" ? "number" : "text",
              showPassword: false,
            },
            on: { input: this.updateValue },
          });
        }
        return h("el-form-item", {
          class: "field-form-item",
          style: { flex: "1" },
          props: { prop: field.prop, label: field.label },
          scopedSlots,
        }, [control]);
      },
    },
  },
  props: {
    visible: { type: Boolean, default: false },
    modelData: {
      type: Object,
      default: () => ({}),
      validator: (value) => typeof value === "object" && !Array.isArray(value),
    },
    modelType: { type: String, required: true },
  },
  data() {
    return {
      dialogVisible: this.visible,
      providers: [],
      providersLoaded: false,
      saving: false,
      testing: false,
      allProvidersData: null,
      pendingProviderType: null,
      pendingModelData: null,
      dynamicCallInfoFields: [],
      fieldJsonMap: {}, // 用于存储JSON字段的字符串形式
      form: {
        id: "",
        modelType: "",
        modelCode: "",
        modelName: "",
        adapterCode: "",
        templateCode: "",
        vendorName: "",
        productName: "",
        protocol: "",
        upstreamModelId: "",
        endpointUrl: "",
        isDefault: false,
        isEnabled: false,
        docLink: "",
        remark: "",
        sort: 0,
        params: {},
        secrets: {},
      },
    };
  },
  computed: {
    visibleCallInfoFields() {
      return this.dynamicCallInfoFields.filter((field) => this.isFieldVisible(field));
    },
    visibleFieldSections() {
      const credential = [];
      const common = [];
      const advanced = [];
      this.visibleCallInfoFields.forEach((field) => {
        if (field.section === "credentials") {
          credential.push(field);
        } else if (field.section === "advanced") {
          advanced.push(field);
        } else {
          common.push(field);
        }
      });
      return [
        { key: "credentials", title: "凭据信息", fields: credential, collapsible: false },
        { key: "common", title: "常用参数", fields: common, collapsible: false },
        { key: "advanced", title: "高级参数", fields: advanced, collapsible: true },
      ];
    },
    currentTemplate() {
      return this.findTemplate(this.form.templateCode) || {};
    },
    selectedTemplateBlocked() {
      return this.isBlockedTemplateStatus(this.currentTemplate);
    },
    catalogMetaText() {
      const template = this.currentTemplate || {};
      return [
        template.accessModeLabel || template.accessMode,
        template.protocolLabel || template.protocol,
        template.providerCode,
      ].filter(Boolean).join(" · ");
    },
    currentUpstreamLabel() {
      return this.currentTemplate.upstreamResourceLabel || this.$t("modelConfigDialog.upstreamModel");
    },
    upstreamModelOptions() {
      const field = this.getUpstreamModelField(this.currentTemplate);
      return field && Array.isArray(field.options) ? field.options : [];
    },
    endpointPlaceholder() {
      return this.endpointDefaultValue
        || "云厂商默认地址可留空；自部署或兼容接口填写 https://... 或 wss://...";
    },
    endpointHelpText() {
      return "接口端点是厂商或自部署服务的 API 地址。云厂商适配器已有默认地址时可留空；只有接入自定义网关、兼容接口或私有部署时才需要填写。";
    },
    endpointDefaultValue() {
      const endpointField = (this.currentTemplate.fieldSchemas || []).find((field) =>
        this.endpointKeys().includes(field.key)
      );
      return endpointField && endpointField.defaultValue ? String(endpointField.defaultValue) : "";
    },
  },
  watch: {
    modelType() {
      this.resetProviders();
      this.loadProviders();
    },
    dialogVisible(val) {
      this.$emit("update:visible", val);
      if (!val) {
        this.resetForm();
      } else if (val && this.modelData.id) {
        this.loadModelData();
      }
    },
    visible(val) {
      this.dialogVisible = val;
      if (val) {
        this.loadProviders();
      }
    },
    "form.templateCode"(newVal) {
      if (newVal && this.providersLoaded) {
        this.loadProviderFields(newVal);
      }
    },
  },
  methods: {
    resetForm() {
      this.form = {
        id: "",
        modelType: "",
        modelCode: "",
        modelName: "",
        adapterCode: "",
        templateCode: "",
        vendorName: "",
        productName: "",
        protocol: "",
        upstreamModelId: "",
        endpointUrl: "",
        isDefault: false,
        isEnabled: false,
        docLink: "",
        remark: "",
        sort: 0,
        params: {},
        secrets: {},
      };
      this.fieldJsonMap = {};
      this.testing = false;
      this.saving = false;
    },
    resetProviders() {
      this.providers = [];
      this.providersLoaded = false;
    },
    providerStatusText(item) {
      if (!item) return "";
      if (item.deprecated || item.supportedStatus === "DEPRECATED") return "旧版兼容";
      if (item.supportedStatus === "NEEDS_REVIEW") return "需审核";
      if (item.supportedStatus === "PARTIAL") return "部分适配";
      if (item.supportedStatus === "NEEDS_ADAPTER") return "待接入";
      return "已适配";
    },
    providerStatusType(item) {
      if (!item) return "info";
      if (item.deprecated || item.supportedStatus === "DEPRECATED") return "info";
      if (item.supportedStatus === "PARTIAL" || item.supportedStatus === "NEEDS_REVIEW") return "warning";
      if (item.supportedStatus === "NEEDS_ADAPTER") return "danger";
      return "success";
    },
    isBlockedTemplateStatus(item = {}) {
      return item.selectable === false
        || ["NEEDS_ADAPTER", "PARTIAL", "NEEDS_REVIEW"].includes(String(item.supportedStatus || "").toUpperCase());
    },
    providerSort(item) {
      const statusRank = {
        SUPPORTED: 0,
        PARTIAL: 1,
        DEPRECATED: 2,
        NEEDS_ADAPTER: 3,
      };
      const selectableRank = item.selectable === false ? 1 : 0;
      return selectableRank * 10000 + (statusRank[item.supportedStatus] ?? 9) * 1000 + (item.sort || 999);
    },
    loadModelData() {
      if (this.modelData.id) {
        Api.model.getModelConfig(this.modelData.id, ({ data }) => {
          if (data.code === 0 && data.data) {
            let model = data.data;

            if (this.modelData.duplicateMode) {
              model.modelName =
                this.modelData.modelName + this.$t("modelConfigDialog.copySuffix");
              model.modelCode =
                this.modelData.modelCode + this.$t("modelConfigDialog.copySuffix");

            }
            this.pendingProviderType = model.templateCode || model.adapterCode || model.providerCode;
            this.pendingModelData = model;

            if (this.providersLoaded) {
              this.loadProviderFields(this.pendingProviderType);
            } else {
              this.loadProviders();
            }
          }
        });
      }
    },
    handleSave() {
      if (this.selectedTemplateBlocked) {
        this.$message.warning(this.$t("modelConfigDialog.catalogNotSelectable"));
        return;
      }
      this.saving = true; // 开始保存加载

      const formData = this.buildFormData();

      this.$emit("save", {
        provideCode: this.form.adapterCode,
        formData,
        done: () => {
          this.saving = false; // 保存完成后回调
        },
      });

      // 如果父组件不处理done回调，3秒后自动关闭加载状态
      setTimeout(() => {
        this.saving = false;
      }, 3000);
    },
    handleSaveAndTest() {
      if (this.selectedTemplateBlocked) {
        this.$message.warning(this.$t("modelConfigDialog.catalogNotSelectable"));
        return;
      }
      this.testing = true;
      const formData = this.buildFormData();

      this.$emit("test", {
        provideCode: this.form.adapterCode,
        formData,
        done: () => {
          this.testing = false;
        },
      });

      setTimeout(() => {
        this.testing = false;
      }, 30000);
    },
    buildFormData() {
      Object.keys(this.fieldJsonMap).forEach((key) => {
        const parsed = this.validateJson(this.fieldJsonMap[key]);
        if (parsed !== null) {
          const field = this.dynamicCallInfoFields.find((item) => item.prop === key);
          const target = field ? field.target : "params";
          this.$set(this.form[target], key, parsed);
        }
      });

      return {
        id: this.modelData.id,
        modelCode: this.form.modelCode,
        modelName: this.form.modelName,
        displayName: this.form.modelName,
        adapterCode: this.form.adapterCode,
        providerCode: this.form.adapterCode,
        templateCode: this.form.templateCode,
        vendorName: this.form.vendorName,
        productName: this.form.productName,
        protocol: this.form.protocol,
        upstreamModelId: this.form.upstreamModelId,
        endpointUrl: this.form.endpointUrl,
        params: { ...this.form.params },
        secrets: { ...this.form.secrets },
        isDefault: this.form.isDefault ? 1 : 0,
        isEnabled: this.form.isEnabled ? 1 : 0,
        docLink: this.form.docLink,
        remark: this.form.remark,
        sort: this.form.sort || 0,
      };
    },
    loadProviders(done) {
      if (this.providersLoaded) {
        done && done();
        return;
      }

      Api.model.getModelCatalog({ modelType: this.modelType }, (res) => {
        const data = res && res.data && res.data.code === 0 ? (res.data.data || []) : [];
        this.providers = data
          .filter((item) => !item.hidden && !item.deprecated)
          .sort((a, b) => this.providerSort(a) - this.providerSort(b))
          .map((item) => {
            const status = this.providerStatusText(item);
            const title = `${item.vendorName || ""} / ${item.productName || item.adapterName || item.providerCode}`;
            const meta = [item.accessModeLabel || item.accessMode, item.protocolLabel || item.protocol].filter(Boolean).join(" · ");
            return {
              label: `${title}${status ? ` ${status}` : ""}`,
              title,
              meta,
              templateCode: item.templateCode,
              providerCode: String(item.providerCode || ""),
              disabled: this.isBlockedTemplateStatus(item),
              raw: item,
            };
          });
        this.providersLoaded = true;
        this.allProvidersData = data;

        if (this.pendingProviderType) {
          this.loadProviderFields(this.pendingProviderType);
        }
        done && done();
      });
    },
    handleTemplateChange(templateCode) {
      const template = this.findTemplate(templateCode);
      if (!template) {
        return;
      }
      this.form.adapterCode = template.providerCode || "";
      this.form.providerCode = template.providerCode || "";
      this.form.vendorName = template.vendorName || "";
      this.form.productName = template.productName || "";
      this.form.protocol = template.protocolLabel || "";
      this.form.docLink = template.docLink || this.form.docLink;
      this.applyTemplateDefaults(template);
      this.loadProviderFields(templateCode);
    },
    findTemplate(templateCode) {
      return (this.allProvidersData || []).find((item) => item.templateCode === templateCode);
    },
    loadProviderFields(templateCode) {
      if (this.allProvidersData) {
        const provider = this.findTemplate(templateCode) || (this.allProvidersData || []).find(
          (p) => p.providerCode === templateCode
        );
        if (provider) {
          this.dynamicCallInfoFields = (provider.fieldSchemas || [])
            .filter((f) => !this.isMetadataField(f.key, provider))
            .map((f) => ({
              label: f.label || f.key,
              prop: f.key,
              target: this.getFieldTarget(f),
              type: this.getInputType(f),
              options: (f.options || []).map((option) => ({ label: option, value: option })),
              placeholder: this.getFieldPlaceholder(f),
              section: this.getFieldSection(f),
              helpText: f.helpText || this.getFieldHelpText(f.key),
              visibleWhen: f.visibleWhen || "",
              advanced: !!f.advanced,
            }));

          if (this.pendingModelData && (this.pendingProviderType === templateCode || this.pendingProviderType === provider.providerCode)) {
            if (!this.pendingModelData.templateCode) {
              this.pendingModelData.templateCode = provider.templateCode;
              this.pendingModelData.adapterCode = provider.providerCode;
              this.pendingModelData.providerCode = provider.providerCode;
            }
            this.processModelData(this.pendingModelData);
            this.pendingModelData = null;
            this.pendingProviderType = null;
          } else {
            this.applyTemplateDefaults(provider);
          }
        }
      }
    },
    getInputType(field) {
      if (field.inputType === "textarea" || field.type === "json") {
        return "json-textarea";
      }
      if (field.inputType === "switch" || field.type === "boolean") {
        return "switch";
      }
      if (field.inputType === "select" || (field.options || []).length > 0) {
        return "select";
      }
      return field.type === "number" ? "number" : "text";
    },
    getFieldPlaceholder(field) {
      const key = field.key || "";
      const label = field.label || key;
      const examples = {
        appid: "从火山引擎控制台复制 App ID，例如 9062696951",
        app_id: "从厂商控制台复制 App ID",
        appkey: "填写阿里云 NLS 项目 AppKey",
        access_token: "填写厂商控制台生成的 Access Token",
        token: "填写厂商控制台生成的 Token",
        api_key: "填写厂商控制台生成的 API Key",
        api_secret: "填写 API Secret",
        secret_key: "填写 Secret Key",
        access_key_id: "填写 AccessKey ID",
        access_key_secret: "填写 AccessKey Secret",
        authorization: "填写鉴权头内容，例如 Bearer ...",
        resource_id: "填写资源 ID，例如 volc.seedasr.sauc.duration",
        model_name: "填写上游模型 ID，例如 qwen-flash",
        model: "填写上游模型 ID",
        voice: "填写音色 ID",
        speaker: "填写音色或说话人 ID",
      };
      return examples[key] || `请输入${label}`;
    },
    getFieldTarget(field) {
      if (this.isSecretField(field.key)) {
        return "secrets";
      }
      return "params";
    },
    getFieldSection(field) {
      const group = String(field.group || "").toLowerCase();
      if (this.isSecretField(field.key) || group === "credentials") {
        return "credentials";
      }
      if (field.advanced || ["advanced", "generation", "audio"].includes(group)) {
        return "advanced";
      }
      return "common";
    },
    isMetadataField(key, template = {}) {
      if (this.endpointKeys().includes(key)) {
        return true;
      }
      if (key === (template.upstreamModelField || "") || ["model_name", "model", "resource_id"].includes(key)) {
        return true;
      }
      return [
        "type",
        "protocol",
      ].includes(key);
    },
    endpointKeys() {
      return ["base_url", "api_url", "url", "ws_url", "server_url", "host", "endpoint_url"];
    },
    getUpstreamModelField(template = {}) {
      const upstreamKey = template.upstreamModelField || "";
      return (template.fieldSchemas || []).find((field) =>
        field.key === upstreamKey || ["model_name", "model", "resource_id"].includes(field.key)
      );
    },
    applyTemplateDefaults(template = {}) {
      const upstreamKey = template.upstreamModelField || "";
      (template.fieldSchemas || []).forEach((field) => {
        const value = field.defaultValue;
        if (value === undefined || value === null || value === "") {
          return;
        }
        if (this.endpointKeys().includes(field.key)) {
          if (!this.form.endpointUrl) this.form.endpointUrl = value;
          return;
        }
        if (field.key === upstreamKey || ["model_name", "model", "resource_id"].includes(field.key)) {
          if (!this.form.upstreamModelId) this.form.upstreamModelId = value;
          return;
        }
        const target = this.getFieldTarget(field);
        if (this.form[target][field.key] === undefined || this.form[target][field.key] === "") {
          this.$set(this.form[target], field.key, value);
        }
      });
    },
    isFieldVisible(field) {
      if (!field.visibleWhen) {
        return true;
      }
      const match = String(field.visibleWhen).match(/^([a-zA-Z0-9_]+)=([^=]+)$/);
      if (!match) {
        return true;
      }
      const [, key, expected] = match;
      const actual = this.form.params[key] !== undefined ? this.form.params[key] : this.form.secrets[key];
      return String(actual) === String(expected);
    },
    getVendorProductText() {
      const vendor = this.form.vendorName || "";
      const product = this.form.productName || "";
      return vendor && product ? `${vendor} / ${product}` : vendor || product || "";
    },
    isSecretField(key) {
      return [
        "api_key",
        "api_password",
        "access_token",
        "token",
        "secret_key",
        "api_secret",
        "access_key_secret",
        "personal_access_token",
      ].includes(key);
    },
    chunkFields(fields) {
      const chunkSize = 2;
      const result = [];
      for (let i = 0; i < fields.length; i += chunkSize) {
        result.push(fields.slice(i, i + chunkSize));
      }
      return result;
    },
    getFieldHelpText(key) {
      return {
        appid: "厂商控制台应用 ID，本身不是密钥。",
        app_id: "厂商控制台应用 ID，本身不是密钥。",
        appkey: "厂商控制台应用 AppKey，本身不是 API Key。",
        access_token: "调用厂商接口的访问令牌。",
        api_key: "调用厂商接口的 API Key。",
        authorization: "完整鉴权头或令牌，按厂商文档要求填写。",
        access_key_id: "访问密钥 ID，本身不是密钥；需要与 AccessKey Secret 配套使用。",
        access_key_secret: "访问密钥 Secret，请勿填写到备注或公开文档。",
        secret_id: "访问密钥 ID，本身不是密钥；需要与 Secret Key 配套使用。",
        secret_key: "访问密钥 Secret，请勿填写到备注或公开文档。",
        resource_id: "云平台开通具体语音服务后得到的资源标识，不等同于配置名称。",
        enable_multilingual: "豆包流式 ASR 多语种识别开关，普通中文识别保持关闭即可。",
        end_window_size: "流式识别断句静音窗口，数值越大越不容易提前断句。",
        language: "指定识别语言编码，例如 zh-CN。",
        thinking: "是否开启模型思考模式，语音场景通常关闭以降低延迟。",
        reasoning_effort: "思考强度，仅在 thinking 启用时生效。",
      }[key] || "";
    },
    normalizeDynamicFieldValue(value, field) {
      if (value === undefined || value === null) {
        return field.type === "json-textarea" ? {} : "";
      }
      if (field.type === "json-textarea") {
        return this.ensureObject(value);
      }
      if (typeof value !== "string") {
        return String(value);
      }
      return value;
    },
    processModelData(model) {
      const params = { ...(model.params || {}) };
      const secrets = { ...(model.secrets || {}) };
      this.dynamicCallInfoFields.forEach((field) => {
        const target = field.target || "params";
        const source = target === "secrets" ? secrets : params;
        const value = this.normalizeDynamicFieldValue(source[field.prop], field);
        if (field.type === "json-textarea") {
          this.$set(this.fieldJsonMap, field.prop, this.formatJson(value));
        }
        if (target === "secrets") {
          secrets[field.prop] = value;
        } else {
          params[field.prop] = value;
        }
      });

      this.form = {
        id: model.id,
        modelType: model.modelType,
        modelCode: model.modelCode,
        modelName: model.modelName,
        adapterCode: model.adapterCode || model.providerCode || "",
        templateCode: model.templateCode || "",
        vendorName: model.vendorName || "",
        productName: model.productName || "",
        protocol: model.protocol || "",
        upstreamModelId: model.upstreamModelId || "",
        endpointUrl: model.endpointUrl || "",
        isDefault: model.isDefault,
        isEnabled: model.isEnabled,
        docLink: model.docLink,
        remark: model.remark,
        sort: Number(model.sort) || 0,
        params,
        secrets,
      };
    },
    applyAssistantDraft(payload = {}) {
      const draft = payload.configJson || {};
      const providerCode =
        payload.adapterCode || payload.providerCode || draft.type || this.form.adapterCode || "";
      const apply = () => {
        const template = this.findBestTemplate(providerCode, draft, payload.templateCode);
        const templateCode = template ? template.templateCode : this.form.templateCode;
        const model = {
          ...this.form,
          adapterCode: template ? template.providerCode : providerCode,
          providerCode: template ? template.providerCode : providerCode,
          templateCode,
          vendorName: template ? template.vendorName : this.form.vendorName,
          productName: template ? template.productName : this.form.productName,
          protocol: payload.protocol || draft.protocol || this.form.protocol,
          upstreamModelId:
            payload.upstreamModelId ||
            draft.model_name ||
            draft.model ||
            draft.resource_id ||
            draft.voice ||
            this.form.upstreamModelId,
          endpointUrl:
            payload.endpointUrl ||
            draft.base_url ||
            draft.api_url ||
            draft.url ||
            draft.host ||
            this.form.endpointUrl,
          docLink: payload.docLink || this.form.docLink,
          remark: payload.remark || this.form.remark,
          params: {
            ...this.form.params,
            ...this.extractDraftParams(draft),
          },
          secrets: {
            ...this.form.secrets,
            ...this.extractDraftSecrets(draft),
          },
        };
        this.pendingProviderType = templateCode;
        this.pendingModelData = model;
        this.loadProviderFields(templateCode);
      };
      this.loadProviders(apply);
    },
    findBestTemplate(providerCode, draft = {}, templateCode = "") {
      const templates = this.allProvidersData || [];
      if (templateCode) {
        const byCode = templates.find((item) => item.templateCode === templateCode);
        if (byCode) return byCode;
      }
      const haystack = [
        draft.base_url,
        draft.api_url,
        draft.url,
        draft.host,
        draft.model_name,
        draft.model,
        draft.resource_id,
        draft.voice,
      ].filter(Boolean).join(" ").toLowerCase();
      return templates.find((item) => {
        if (item.providerCode !== providerCode) return false;
        const text = `${item.templateCode || ""} ${item.vendorName || ""} ${item.productName || ""}`.toLowerCase();
        return haystack && text && haystack.split(/[/:._\-\s]+/).some((part) => part.length > 2 && text.includes(part));
      }) || templates.find((item) => item.providerCode === providerCode);
    },
    extractDraftParams(draft = {}) {
      const params = {};
      Object.keys(draft).forEach((key) => {
        if (!this.isMetadataField(key) && !this.isSecretField(key)) {
          params[key] = draft[key];
        }
      });
      return params;
    },
    extractDraftSecrets(draft = {}) {
      const secrets = {};
      Object.keys(draft).forEach((key) => {
        if (this.isSecretField(key)) {
          secrets[key] = draft[key];
        }
      });
      return secrets;
    },
    handleJsonChange(field, value) {
      const parsed = this.validateJson(value);
      if (parsed !== null) {
        this.$set(this.form[field.target], field.prop, parsed);
      }
    },
    validateJson(value) {
      try {
        const parsed = JSON.parse(value);
        if (typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)) {
          return parsed;
        }
        this.$message.error({
          message: '必须输入字典格式（如 {"key":"value"}），保存则使用原数据',
          showClose: true,
        });
        return null;
      } catch (e) {
        this.$message.error({
          message: 'JSON格式错误（如 {"key":"value"}），保存则使用原数据',
          showClose: true,
        });
        return null;
      }
    },
    formatJson(obj) {
      try {
        return JSON.stringify(obj, null, 2);
      } catch {
        return "";
      }
    },
    ensureObject(value) {
      return typeof value === "object" ? value : {};
    },

  },
};
</script>

<style lang="scss" scoped>
.model-edit-dialog ::v-deep .custom-dialog {
  position: relative;
  width: min(1120px, 92vw) !important;
  max-height: 92vh;
  border-radius: 12px;
  overflow: hidden;
  background: white;
}

.model-edit-dialog ::v-deep .custom-dialog .el-dialog__header {
  padding: 0;
  border-bottom: none;
}

.model-edit-dialog ::v-deep .el-dialog__body {
  padding: 0;
  display: flex;
  flex-direction: column;
  max-height: 92vh;
}

.dialog-scroll-body {
  margin: 0;
  padding: 24px 34px 18px;
  text-align: left;
  overflow-y: auto;
  max-height: calc(92vh - 76px);
  box-sizing: border-box;
}

.dialog-title {
  color: #3d4566;
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
  margin-bottom: 16px;
  text-align: center;
}

.dialog-section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.custom-close-btn {
  position: absolute;
  top: 14px;
  right: 16px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 2px solid #cfcfcf;
  background: none;
  font-size: 26px;
  font-weight: lighter;
  color: #cfcfcf;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
  padding: 0;
  outline: none;
}

.custom-close-btn:hover {
  color: #409eff;
  border-color: #409eff;
}

.custom-select .el-input__suffix {
  background: #e6e8ea;
  right: 6px;
  width: 20px;
  height: 20px;
  display: flex;
  justify-content: center;
  align-items: center;
  top: 9px;
}

.custom-select .el-input__suffix-inner {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
}

.custom-select .el-icon-arrow-up:before {
  content: "";
  display: inline-block;
  width: 0;
  height: 0;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 7px solid #c0c4cc;
  position: relative;
  top: -2px;
  transform: rotate(180deg);
}

.custom-form .el-form-item {
  margin-bottom: 20px;
}

.catalog-detail {
  border: 1px solid #e7ebf3;
  border-radius: 6px;
  background: #f8faff;
  padding: 10px 12px;
  margin: -6px 0 18px;
}

.catalog-detail-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.catalog-detail-title {
  color: #27324a;
  font-weight: 600;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-detail-meta {
  color: #6e7890;
  font-size: 12px;
}

.catalog-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 18px;
  margin-top: 8px;
  color: #596276;
  font-size: 12px;
  line-height: 1.5;
}

.catalog-detail-label {
  color: #3d4566;
  font-weight: 600;
  margin-right: 6px;
}

.custom-form .el-form-item__label {
  color: #3d4566;
  font-weight: normal;
  text-align: right;
  padding-right: 20px;
}

.field-section {
  margin-bottom: 10px;
}

.provider-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.provider-option-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.provider-option-title {
  color: #27324a;
  font-size: 13px;
  line-height: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-option-meta {
  font-size: 12px;
  line-height: 16px;
  color: #7b8499;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-section-title {
  color: #3d4566;
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 12px;
}

.field-row {
  display: flex;
  gap: 20px;
  margin-bottom: 0;
}

.field-help-icon {
  color: #8a94ad;
  font-size: 14px;
  margin-left: 5px;
  cursor: help;
}

.field-tip {
  color: #7b8499;
  font-size: 12px;
  line-height: 1.5;
  margin-top: 6px;
}

.field-form-item {
  min-width: 0;
}

.field-section .advanced-collapse {
  margin-top: 0;
}

.custom-form .el-form-item.prop-remark .el-form-item__label {
  margin-top: -4px;
}

.custom-input-bg .el-input__inner::-webkit-input-placeholder,
.custom-input-bg .el-textarea__inner::-webkit-input-placeholder {
  color: #9c9f9e;
}

.custom-input-bg .el-input__inner,
.custom-input-bg .el-textarea__inner {
  background-color: #f6f8fc;
}

.save-btn {
  background: #e6f0fd;
  color: #237ff4;
  border: 1px solid #b3d1ff;
  width: 150px;
  height: 40px;
  font-size: 16px;
  transition: all 0.3s ease;
}

.dialog-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  padding: 14px 0 18px;
  border-top: 1px solid #eef1f6;
  background: #fff;
  flex-shrink: 0;
}

.test-btn {
  background: #edf7ef;
  color: #16833a;
  border: 1px solid #b9dfc4;
  width: 150px;
  height: 40px;
  font-size: 16px;
  transition: all 0.3s ease;
}

.save-btn:hover,
.test-btn:hover {
  background: linear-gradient(to right, #237ff4, #9c40d5);
  color: white;
  border: none;
}

.custom-switch .el-switch__core {
  border-radius: 20px;
  height: 23px;
  background-color: #c0ccda;
  width: 35px;
  padding: 0 20px;
}

.custom-switch .el-switch__core:after {
  width: 15px;
  height: 15px;
  background-color: white;
  top: 3px;
  left: 4px;
  transition: all 0.3s;
}

.custom-switch.is-checked .el-switch__core {
  border-color: #b5bcf0;
  background-color: #cfd7fa;
  padding: 0 20px;
}

.custom-switch.is-checked .el-switch__core:after {
  left: 100%;
  margin-left: -18px;
  background-color: #1b47ee;
}

[style*="display: flex"] {
  gap: 20px;
}

.custom-input-bg .el-input__inner {
  height: 32px;
}

.custom-form .el-form-item {
  margin-bottom: 20px;
}

.custom-form .el-form-item__label {
  color: #3d4566;
  font-weight: normal;
  text-align: right;
  padding-right: 20px;
}
</style>
