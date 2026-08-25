<template>
  <el-dialog :title="title" :visible="dialogVisible" width="800px" class="knowledge-base-dialog" @close="handleClose">
    <el-form ref="knowledgeBaseForm" :model="form" :rules="rules" label-width="130px" size="medium">
      <div class="dialog-section">
        <div class="section-title">基础信息</div>
        <el-form-item :label="$t('knowledgeBaseDialog.name')" prop="name">
          <el-input v-model="form.name" :placeholder="$t('knowledgeBaseDialog.namePlaceholder')" clearable></el-input>
        </el-form-item>
        <el-form-item :label="$t('knowledgeBaseDialog.description')" prop="description">
          <el-input v-model="form.description" :placeholder="$t('knowledgeBaseDialog.descriptionPlaceholder')"
            type="textarea" :rows="4" maxlength="300" show-word-limit></el-input>
        </el-form-item>
      </div>

      <div class="dialog-section">
        <div class="section-title">检索模型</div>
        <el-form-item :label="$t('knowledgeBaseDialog.ragModel')" prop="ragModelId">
          <el-select v-model="form.ragModelId" :placeholder="$t('knowledgeBaseDialog.ragModelPlaceholder')" clearable
            filterable style="width: 100%" @focus="loadRAGModels">
            <el-option v-for="model in ragModels" :key="model.id" :label="model.modelName" :value="model.id">
            </el-option>
          </el-select>
        </el-form-item>
      </div>

      <div class="dialog-section">
        <div class="section-header">
          <div class="section-title">{{ $t('knowledgeBaseDialog.processingConfig') }}</div>
          <el-tag type="success" size="small">{{ $t('knowledgeBaseDialog.processingManagedStatus') }}</el-tag>
        </div>
        <el-form-item :label="$t('knowledgeBaseDialog.processingStrategy')">
          <el-radio-group v-model="parserForm.processing_strategy" size="small">
            <el-radio-button label="auto">{{ $t('knowledgeBaseDialog.processingAuto') }}</el-radio-button>
            <el-radio-button label="direct">{{ $t('knowledgeBaseDialog.processingDirect') }}</el-radio-button>
            <el-radio-button label="llm_enhanced">{{ $t('knowledgeBaseDialog.processingLlmEnhanced') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-alert :title="processingStrategyDescription" type="info" :closable="false"
          show-icon class="chunk-mode-alert"></el-alert>
        <div v-if="parserForm.processing_strategy !== 'direct'" class="chunk-config-fields">
          <el-form-item :label="$t('knowledgeBaseDialog.llmModelConfig')">
            <el-select v-model="parserForm.llm_model_id" :placeholder="$t('knowledgeBaseDialog.llmModelConfigPlaceholder')"
              filterable clearable style="width: 100%" @focus="loadLLMModels">
              <el-option v-for="model in llmModels" :key="model.id" :label="formatLLMModelLabel(model)" :value="model.id">
              </el-option>
            </el-select>
          </el-form-item>
        </div>
      </div>

      <el-collapse v-model="advancedPanels" class="advanced-collapse">
        <el-collapse-item :title="$t('knowledgeBaseDialog.retrievalConfig')" name="retrieval">
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item :label="$t('knowledgeBaseDialog.retrievalMode')">
                <el-radio-group v-model="retrievalForm.mode" size="small">
                  <el-radio-button label="normal">{{ $t('knowledgeBaseDialog.retrievalModeNormal') }}</el-radio-button>
                  <el-radio-button label="exhibition">{{ $t('knowledgeBaseDialog.retrievalModeExhibition') }}</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="$t('knowledgeBaseDialog.searchType')">
                <el-radio-group v-model="retrievalForm.search_type" size="small">
                  <el-radio-button label="semantic">{{ $t('knowledgeBaseDialog.searchTypeSemantic') }}</el-radio-button>
                  <el-radio-button label="hybrid">{{ $t('knowledgeBaseDialog.searchTypeHybrid') }}</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item :label="$t('knowledgeBaseDialog.semanticLevel')">
                <el-input-number v-model="retrievalForm.semantic_level" :min="1" :max="5" :step="1"
                  style="width: 100%"></el-input-number>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="$t('knowledgeBaseDialog.semanticTopK')">
                <el-input-number v-model="retrievalForm.semantic_top_k" :min="1" :max="50" :step="1"
                  style="width: 100%"></el-input-number>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item :label="$t('knowledgeBaseDialog.faqThreshold')">
                <el-input-number v-model="retrievalForm.faq_threshold" :min="0" :max="1" :step="0.01" :precision="2"
                  style="width: 100%"></el-input-number>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="$t('knowledgeBaseDialog.semanticThreshold')">
                <el-input-number v-model="retrievalForm.semantic_threshold" :min="0" :max="1" :step="0.01" :precision="2"
                  style="width: 100%"></el-input-number>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item :label="$t('knowledgeBaseDialog.rerankEnabled')">
            <el-switch v-model="retrievalForm.rerank_enabled"></el-switch>
          </el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">{{ $t('knowledgeBaseDialog.cancel') }}</el-button>
      <el-button type="primary" @click="handleSubmit">{{ $t('knowledgeBaseDialog.confirm') }}</el-button>
    </div>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";

export default {
  name: "KnowledgeBaseDialog",
  props: {
    title: {
      type: String,
      default: ""
    },
    visible: {
      type: Boolean,
      default: false
    },
    form: {
      type: Object,
      default: () => ({
        id: null,
        datasetId: null,
        ragModelId: null,
        name: "",
        description: "",
        parserConfig: ""
      })
    }
  },
  data() {
    return {
      dialogVisible: this.visible,
      ragModels: [],
      llmModels: [],
      advancedPanels: [],
      parserForm: this.defaultParserForm(),
      retrievalForm: this.defaultRetrievalForm(),
      parserConfigValue: {},
      rules: {
        name: [
          {
            required: true,
            message: this.$t("knowledgeBaseDialog.nameRequired"),
            trigger: "blur"
          },
          {
            min: 1,
            max: 50,
            message: this.$t("knowledgeBaseDialog.nameLength"),
            trigger: "blur"
          },
          {
            pattern: /^[\u4e00-\u9fa5a-zA-Z0-9\s-_]+$/,
            message: this.$t("knowledgeBaseDialog.namePattern"),
            trigger: "blur"
          }
        ],
        description: [
          {
            required: true,
            message: this.$t("knowledgeBaseDialog.descriptionRequired"),
            trigger: "blur"
          },
          {
            max: 300,
            message: this.$t("knowledgeBaseDialog.descriptionLength"),
            trigger: "blur"
          }
        ],
        ragModelId: [
          {
            required: true,
            message: this.$t("knowledgeBaseDialog.ragModelRequired"),
            trigger: "change"
          }
        ]
      }
    };
  },
  watch: {
    visible(val) {
      this.dialogVisible = val;
      if (val) {
        // 对话框显示时加载RAG模型列表
        this.loadRAGModels();
        this.loadLLMModels();

        // 如果是新增知识库且没有设置ragModelId，则默认选择第一个RAG模型
        if (!this.form.id && !this.form.ragModelId && this.ragModels.length > 0) {
          this.$set(this.form, 'ragModelId', this.ragModels[0].id);
        }

        if (this.$refs.knowledgeBaseForm) {
          this.$refs.knowledgeBaseForm.clearValidate();
        }
        this.syncParserFormFromValue();
      }
    },
    // 监听RAG模型列表变化，确保新增时能正确设置默认值
    ragModels(newModels) {
      if (newModels.length > 0) {
        // 如果是新增知识库且没有设置ragModelId，则默认选择第一个RAG模型
        if (!this.form.id && !this.form.ragModelId) {
          this.$set(this.form, 'ragModelId', newModels[0].id);
        }
      }
    }
  },
  computed: {
    processingStrategyDescription() {
      const key = {
        auto: 'knowledgeBaseDialog.processingAutoDescription',
        direct: 'knowledgeBaseDialog.processingDirectDescription',
        llm_enhanced: 'knowledgeBaseDialog.processingLlmEnhancedDescription'
      }[this.parserForm.processing_strategy];
      return this.$t(key || 'knowledgeBaseDialog.processingAutoDescription');
    }
  },
  methods: {
    defaultParserForm() {
      return {
        processing_strategy: "auto",
        llm_enabled: false,
        llm_model_id: ""
      };
    },
    defaultRetrievalForm() {
      return {
        mode: "normal",
        search_type: "semantic",
        semantic_level: 4,
        semantic_top_k: 10,
        faq_threshold: 1.0,
        semantic_threshold: 0.05,
        rerank_enabled: false
      };
    },
    normalizeNumber(value, defaultValue) {
      const numberValue = Number(value);
      return Number.isFinite(numberValue) ? numberValue : defaultValue;
    },
    getConfigValue(primaryConfig, secondaryConfig, key, defaultValue) {
      if (primaryConfig && Object.prototype.hasOwnProperty.call(primaryConfig, key)) {
        return primaryConfig[key];
      }
      if (secondaryConfig && Object.prototype.hasOwnProperty.call(secondaryConfig, key)) {
        return secondaryConfig[key];
      }
      return defaultValue;
    },
    syncParserFormFromValue() {
      const defaults = this.defaultParserForm();
      const retrievalDefaults = this.defaultRetrievalForm();
      if (!this.form || !this.form.parserConfig) {
        this.parserForm = defaults;
        this.retrievalForm = retrievalDefaults;
        this.parserConfigValue = {};
        return;
      }
      try {
        const parsed = typeof this.form.parserConfig === "string"
          ? JSON.parse(this.form.parserConfig)
          : this.form.parserConfig;
        const parserConfig = { ...(parsed || {}) };
        if (!Object.prototype.hasOwnProperty.call(parserConfig, "processing_strategy")) {
          parserConfig.processing_strategy = "llm_enhanced";
        }
        const retrievalConfig = parserConfig.retrieval_config || {};
        this.parserForm = {
          ...defaults,
          ...parserConfig
        };
        this.retrievalForm = {
          ...retrievalDefaults,
          ...retrievalConfig,
          mode: this.getConfigValue(retrievalConfig, parserConfig, "mode", retrievalDefaults.mode),
          search_type: this.getConfigValue(
            retrievalConfig,
            parserConfig,
            "search_type",
            retrievalDefaults.search_type
          ),
          semantic_level: this.normalizeNumber(
            this.getConfigValue(retrievalConfig, parserConfig, "semantic_level"),
            retrievalDefaults.semantic_level
          ),
          semantic_top_k: this.normalizeNumber(
            this.getConfigValue(retrievalConfig, parserConfig, "semantic_top_k"),
            retrievalDefaults.semantic_top_k
          ),
          faq_threshold: this.normalizeNumber(
            this.getConfigValue(retrievalConfig, parserConfig, "faq_threshold"),
            retrievalDefaults.faq_threshold
          ),
          semantic_threshold: this.normalizeNumber(
            this.getConfigValue(retrievalConfig, parserConfig, "semantic_threshold"),
            retrievalDefaults.semantic_threshold
          ),
          rerank_enabled: typeof retrievalConfig.rerank_enabled === "boolean"
            ? retrievalConfig.rerank_enabled
            : !!parserConfig.rerank_enabled
        };
        this.parserConfigValue = parserConfig;
      } catch (e) {
        this.parserForm = defaults;
        this.retrievalForm = retrievalDefaults;
        this.parserConfigValue = {};
      }
    },
    buildParserConfig() {
      const config = { ...this.parserConfigValue };
      delete config.llm_base_url;
      delete config.llm_api_key;
      delete config.llm_model;
      delete config.llm_max_input_chars;
      delete config.llm_max_tokens;
      return JSON.stringify({
        ...config,
        max_chunk_chars: 1200,
        processing_strategy: this.parserForm.processing_strategy,
        llm_enabled: this.parserForm.processing_strategy !== 'direct' && !!this.parserForm.llm_model_id,
        llm_model_id: this.parserForm.llm_model_id,
        retrieval_config: {
          mode: this.retrievalForm.mode,
          search_type: this.retrievalForm.search_type,
          semantic_level: this.retrievalForm.semantic_level,
          semantic_top_k: this.retrievalForm.semantic_top_k,
          faq_threshold: this.retrievalForm.faq_threshold,
          semantic_threshold: this.retrievalForm.semantic_threshold,
          rerank_enabled: !!this.retrievalForm.rerank_enabled
        }
      });
    },
    handleClose() {
      // 不重置表单字段，以便在编辑时能保留之前的选择
      // 只在对话框关闭时重置验证状态
      if (this.$refs.knowledgeBaseForm) {
        this.$refs.knowledgeBaseForm.clearValidate();
      }
      this.dialogVisible = false;
      this.$emit("update:visible", false);
    },
    handleSubmit() {
      if (this.parserForm.processing_strategy === 'llm_enhanced' && !this.parserForm.llm_model_id) {
        this.$message.error(this.$t('knowledgeBaseDialog.llmModelConfigRequired'));
        return;
      }
      this.$refs.knowledgeBaseForm.validate(valid => {
        if (valid) {
          this.$emit("submit", {
            ...this.form,
            parserConfig: this.buildParserConfig()
          });
        }
      });
    },
    loadRAGModels() {
      if (this.ragModels.length > 0) {
        return; // 已经加载过，避免重复加载
      }

      Api.model.getRAGModels((res) => {
        if (res.data && res.data.code === 0) {
          this.ragModels = res.data.data || [];

          if (!this.form.id && !this.form.ragModelId && this.ragModels.length > 0) {
            this.$set(this.form, 'ragModelId', this.ragModels[0].id);
          }
        } else {
          this.$message.error(this.$t('knowledgeBaseDialog.loadRAGModelsFailed'));
        }
      });
    },
    loadLLMModels() {
      if (this.llmModels.length > 0) {
        return;
      }
      Api.model.getModelNames("LLM", "", ({ data }) => {
        if (data && data.code === 0) {
          const list = Array.isArray(data.data) ? data.data : [];
          this.llmModels = list.filter(model => this.isOpenAiCompatibleModel(model));
        } else {
          this.$message.error(data?.msg || this.$t('knowledgeBaseDialog.loadLLMModelsFailed'));
        }
      });
    },
    isOpenAiCompatibleModel(model) {
      const adapterCode = String(model.adapterCode || model.type || '').toLowerCase();
      const protocol = String(model.protocol || '').toLowerCase();
      return adapterCode === 'openai' || protocol.includes('openai');
    },
    formatLLMModelLabel(model) {
      const name = model.modelName || model.displayName || model.id;
      const vendor = [model.vendorName, model.productName].filter(Boolean).join(" / ");
      return vendor ? `${name} · ${vendor}` : name;
    }
  }
};
</script>

<style lang="scss" scoped>
.knowledge-base-dialog {
  ::v-deep .el-dialog {
    border-radius: 8px;
    overflow: hidden;
  }

  ::v-deep .el-dialog__body {
    padding: 20px 30px;
  }

  ::v-deep .el-form-item {
    margin-bottom: 20px;
  }

  ::v-deep .el-form-item__label {
    font-weight: 500;
    color: #34495e;
    font-size: 14px;
  }

  .dialog-section {
    margin-bottom: 18px;
    padding: 16px 18px;
    border: 1px solid #e4e8f1;
    border-radius: 8px;
    background: #fff;
  }

  .section-title {
    margin-bottom: 14px;
    font-size: 15px;
    line-height: 20px;
    font-weight: 700;
    color: #2f3548;
  }

  .section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;

    .section-title {
      margin-bottom: 0;
    }
  }

  ::v-deep .el-input {
    .el-input__inner {
      height: 36px;
      font-size: 14px;
      border-radius: 4px;
      border: 1px solid #ddd;
      transition: all 0.3s ease;

      &:focus {
        border-color: #5f70f3;
        box-shadow: 0 0 0 2px rgba(95, 112, 243, 0.2);
      }
    }
  }

  ::v-deep .el-textarea {
    .el-textarea__inner {
      font-size: 14px;
      border-radius: 4px;
      border: 1px solid #ddd;
      transition: all 0.3s ease;

      &:focus {
        border-color: #5f70f3;
        box-shadow: 0 0 0 2px rgba(95, 112, 243, 0.2);
      }
    }
  }

  ::v-deep .el-select {
    .el-input__inner {
      height: 36px;
      font-size: 14px;
      border-radius: 4px;
      border: 1px solid #ddd;
      transition: all 0.3s ease;

      &:focus {
        border-color: #5f70f3;
        box-shadow: 0 0 0 2px rgba(95, 112, 243, 0.2);
      }
    }
  }

  .chunk-config-fields {
    margin-top: 16px;

    ::v-deep .el-form-item {
      margin-bottom: 16px;
    }
  }

  .advanced-collapse {
    margin-top: 2px;
    border-top: 1px solid #e4e8f1;
    border-bottom: 1px solid #e4e8f1;

    ::v-deep .el-collapse-item__header {
      padding: 0 18px;
      font-size: 15px;
      font-weight: 700;
      color: #2f3548;
      background: #f8fafc;
    }

    ::v-deep .el-collapse-item__content {
      padding: 18px 18px 4px;
    }
  }

  ::v-deep .el-empty {
    padding: 18px 0 4px;
  }

  .dialog-footer {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
  }
}

</style>
