<template>
  <el-dialog :title="title" :visible="dialogVisible" width="800px" class="knowledge-base-dialog" @close="handleClose">
    <el-form ref="knowledgeBaseForm" :model="form" :rules="rules" label-width="130px" size="medium">
      <el-form-item :label="$t('knowledgeBaseDialog.name')" prop="name">
        <el-input v-model="form.name" :placeholder="$t('knowledgeBaseDialog.namePlaceholder')" clearable></el-input>
      </el-form-item>
      <el-form-item :label="$t('knowledgeBaseDialog.description')" prop="description">
        <el-input v-model="form.description" :placeholder="$t('knowledgeBaseDialog.descriptionPlaceholder')"
          type="textarea" :rows="4" maxlength="300" show-word-limit></el-input>
      </el-form-item>
      <el-form-item :label="$t('knowledgeBaseDialog.ragModel')" prop="ragModelId">
        <el-select v-model="form.ragModelId" :placeholder="$t('knowledgeBaseDialog.ragModelPlaceholder')" clearable
          filterable style="width: 100%" @focus="loadRAGModels">
          <el-option v-for="model in ragModels" :key="model.id" :label="model.modelName" :value="model.id">
          </el-option>
        </el-select>
      </el-form-item>
      <el-divider content-position="left">{{ $t('knowledgeBaseDialog.chunkModelConfig') }}</el-divider>
      <div class="chunk-config-panel" :class="{ 'is-disabled': !parserForm.llm_enabled }">
        <div class="chunk-config-header">
          <div>
            <div class="chunk-config-title">{{ $t('knowledgeBaseDialog.llmEnabled') }}</div>
            <div class="chunk-config-status">
              {{ parserForm.llm_enabled ? $t('knowledgeBaseDialog.llmEnabledStatus') : $t('knowledgeBaseDialog.llmDisabledStatus') }}
            </div>
          </div>
          <el-switch v-model="parserForm.llm_enabled"></el-switch>
        </div>

        <div class="chunk-config-fields" v-if="parserForm.llm_enabled">
          <el-form-item :label="$t('knowledgeBaseDialog.llmModelConfig')">
            <el-select v-model="parserForm.llm_model_id" :placeholder="$t('knowledgeBaseDialog.llmModelConfigPlaceholder')"
              filterable clearable style="width: 100%" @focus="loadLLMModels">
              <el-option v-for="model in llmModels" :key="model.id" :label="formatLLMModelLabel(model)" :value="model.id">
              </el-option>
            </el-select>
          </el-form-item>
          <div class="chunk-config-metrics">
            <div class="metric-control">
              <span class="metric-label">{{ $t('knowledgeBaseDialog.llmMaxInputChars') }}</span>
              <el-input-number v-model="parserForm.llm_max_input_chars" :min="500" :max="20000" :step="500"
                controls-position="right"></el-input-number>
            </div>
            <div class="metric-control">
              <span class="metric-label">{{ $t('knowledgeBaseDialog.llmMaxTokens') }}</span>
              <el-input-number v-model="parserForm.llm_max_tokens" :min="200" :max="8000" :step="200"
                controls-position="right"></el-input-number>
            </div>
          </div>
        </div>
      </div>
      <el-divider content-position="left">{{ $t('knowledgeBaseDialog.retrievalConfig') }}</el-divider>
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
  methods: {
    defaultParserForm() {
      return {
        llm_enabled: false,
        llm_model_id: "",
        llm_base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1",
        llm_api_key: "",
        llm_model: "qwen-plus",
        llm_max_input_chars: 12000,
        llm_max_tokens: 4000
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
        const parserConfig = parsed || {};
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
        console.warn("parserConfig解析失败，使用默认切片模型配置", e);
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
      return JSON.stringify({
        ...config,
        max_chunk_chars: 1200,
        llm_enabled: !!this.parserForm.llm_enabled,
        llm_model_id: this.parserForm.llm_model_id,
        llm_max_input_chars: this.parserForm.llm_max_input_chars,
        llm_max_tokens: this.parserForm.llm_max_tokens,
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
      console.log('KnowledgeBaseDialog handleSubmit called');
      if (this.parserForm.llm_enabled && !this.parserForm.llm_model_id) {
        this.$message.error(this.$t('knowledgeBaseDialog.llmModelConfigRequired'));
        return;
      }
      this.$refs.knowledgeBaseForm.validate(valid => {
        console.log('Form validation result:', valid);
        if (valid) {
          console.log('Emitting submit event with form:', this.form);
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

      console.log('开始加载RAG模型列表');
      Api.model.getRAGModels((res) => {
        console.log('RAG模型列表响应:', res);
        if (res.data && res.data.code === 0) {
          this.ragModels = res.data.data || [];
          console.log('RAG模型列表加载成功，共', this.ragModels.length, '个模型');

          // 如果是新增知识库且没有设置ragModelId，则默认选择第一个RAG模型
          if (!this.form.id && !this.form.ragModelId && this.ragModels.length > 0) {
            this.$set(this.form, 'ragModelId', this.ragModels[0].id);
            console.log('已设置默认RAG模型:', this.ragModels[0].id);
          }
        } else {
          console.error('获取RAG模型列表失败:', res.data?.msg);
          this.$message.error(this.$t('knowledgeBaseDialog.loadRAGModelsFailed'));
        }
      });
    },
    loadLLMModels() {
      if (this.llmModels.length > 0) {
        return;
      }
      const params = {
        modelType: "LLM",
        modelName: "",
        page: 1,
        limit: 100
      };
      Api.model.getModelList(params, ({ data }) => {
        if (data && data.code === 0) {
          const list = data.data && data.data.list ? data.data.list : [];
          this.llmModels = list.filter(model => Number(model.isEnabled) === 1 && Number(model.testStatus) === 1);
        } else {
          this.$message.error(data?.msg || this.$t('knowledgeBaseDialog.loadLLMModelsFailed'));
        }
      });
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
    border-radius: 20px;
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

  .chunk-config-panel {
    margin-bottom: 22px;
    padding: 18px 20px;
    border: 1px solid #dfe6f2;
    border-radius: 8px;
    background: #f8fafc;
    transition: border-color 0.2s ease, background 0.2s ease;

    &.is-disabled {
      background: #fbfcfe;
      border-color: #e8edf5;
    }
  }

  .chunk-config-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
  }

  .chunk-config-title {
    font-size: 16px;
    line-height: 22px;
    font-weight: 600;
    color: #1f2d3d;
  }

  .chunk-config-status {
    margin-top: 4px;
    font-size: 13px;
    line-height: 18px;
    color: #7b8794;
  }

  .chunk-config-fields {
    ::v-deep .el-form-item {
      margin-bottom: 16px;
    }
  }

  .chunk-config-metrics {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .metric-control {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    min-height: 44px;
    padding: 0 12px;
    border: 1px solid #dfe6f2;
    border-radius: 6px;
    background: #fff;

    ::v-deep .el-input-number {
      width: 180px;
    }
  }

  .metric-label {
    flex: 1;
    min-width: 0;
    font-size: 14px;
    line-height: 20px;
    color: #34495e;
  }

  .dialog-footer {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
  }
}

@media (max-width: 768px) {
  .knowledge-base-dialog {
    .chunk-config-metrics {
      grid-template-columns: 1fr;
    }

    .metric-control {
      align-items: flex-start;
      flex-direction: column;
      padding: 12px;

      ::v-deep .el-input-number {
        width: 100%;
      }
    }
  }
}
</style>
