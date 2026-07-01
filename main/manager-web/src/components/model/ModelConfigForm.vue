<template>
  <div>
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
                      @json-change="onJsonChange"
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
                  @json-change="onJsonChange"
                />
              </div>
            </template>
          </template>
        </div>
      </template>
    </el-form>
  </div>
</template>

<script>
import ModelFieldControl from './ModelFieldControl.vue';

export default {
  name: 'ModelConfigForm',
  components: { ModelFieldControl },
  props: {
    form: { type: Object, required: true },
    dynamicCallInfoFields: { type: Array, required: true },
    fieldJsonMap: { type: Object, required: true },
    visibleFieldSections: { type: Array, required: true },
  },
  methods: {
    onJsonChange(field, value) {
      this.$emit('json-change', field, value);
    },
    chunkFields(fields) {
      const chunkSize = 2;
      const result = [];
      for (let i = 0; i < fields.length; i += chunkSize) {
        result.push(fields.slice(i, i + chunkSize));
      }
      return result;
    },
  },
};
</script>

<style lang="scss" scoped>
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

.custom-form .el-form-item__label {
  color: #3d4566;
  font-weight: normal;
  text-align: right;
  padding-right: 20px;
}

.field-section {
  margin-bottom: 10px;
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

.custom-input-bg .el-input__inner::-webkit-input-placeholder,
.custom-input-bg .el-textarea__inner::-webkit-input-placeholder {
  color: #9c9f9e;
}

.custom-input-bg .el-input__inner,
.custom-input-bg .el-textarea__inner {
  background-color: #f6f8fc;
}

.custom-input-bg .el-input__inner {
  height: 32px;
}

[style*="display: flex"] {
  gap: 20px;
}
</style>
