<template>
  <el-form-item class="field-form-item" :prop="field.prop" :label="field.label" style="flex: 1">
    <template slot="label">
      <span>{{ field.label }}</span>
      <el-tooltip v-if="field.helpText" :content="field.helpText" placement="top">
        <i class="el-icon-question field-help-icon"></i>
      </el-tooltip>
    </template>
    <el-input
      v-if="field.type === 'json-textarea'"
      class="custom-input-bg"
      :value="fieldJsonMap[field.prop]"
      type="textarea"
      :rows="3"
      :placeholder="field.placeholder"
      @input="updateJsonValue"
      @change="val => $emit('json-change', field, val)"
    />
    <el-select
      v-else-if="field.type === 'select' || field.type === 'model-select'"
      class="custom-select custom-input-bg"
      style="width: 100%"
      :value="form[field.target][field.prop]"
      :placeholder="field.placeholder"
      filterable
      clearable
      :allow-create="!!field.allowCreate"
      :default-first-option="!!field.allowCreate"
      :loading="!!field.loading"
      @input="updateValue"
    >
      <el-option
        v-for="option in (field.options || [])"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      >
        <div class="model-reference-option">
          <div class="model-reference-title">{{ option.label }}</div>
          <div v-if="option.meta" class="model-reference-meta">{{ option.meta }}</div>
        </div>
      </el-option>
    </el-select>
    <el-switch
      v-else-if="field.type === 'switch'"
      :value="form[field.target][field.prop]"
      @input="updateValue"
    />
    <el-input
      v-else
      class="custom-input-bg"
      :value="form[field.target][field.prop]"
      :placeholder="field.placeholder"
      :type="field.type === 'number' ? 'number' : 'text'"
      :show-password="false"
      @input="updateValue"
    />
  </el-form-item>
</template>

<script>
export default {
  name: 'ModelFieldControl',
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
};
</script>
