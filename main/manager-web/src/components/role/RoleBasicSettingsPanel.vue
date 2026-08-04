<template>
  <div class="basic-config-grid">
    <div class="basic-name-block">
      <div class="basic-field-heading">
        <el-tooltip :content="$t('roleConfig.tooltip.agentName')" placement="top" effect="light" popper-class="custom-tooltip">
          <span>{{ $t('roleConfig.agentName') }}</span>
        </el-tooltip>
        <small>用于设备和页面显示</small>
      </div>
      <el-form-item label-width="0" class="basic-name-item">
        <el-input
          :value="agentName"
          maxlength="64"
          show-word-limit
          @input="$emit('update:agentName', $event)"
        />
      </el-form-item>
    </div>

    <div class="basic-prompt-block">
      <div class="basic-field-heading">
        <el-tooltip :content="$t('roleConfig.tooltip.roleIntroduction')" placement="top" effect="light" popper-class="custom-tooltip">
          <span>{{ $t('roleConfig.roleIntroduction') }}</span>
        </el-tooltip>
        <small>决定角色身份、表达方式和回复边界</small>
      </div>
      <el-form-item label-width="0" class="basic-prompt-item">
        <el-input
          :value="systemPrompt"
          type="textarea"
          :rows="12"
          resize="vertical"
          :placeholder="$t('roleConfig.pleaseEnterContent')"
          maxlength="2000"
          show-word-limit
          @input="$emit('update:systemPrompt', $event)"
        />
      </el-form-item>
    </div>
  </div>
</template>

<script>
export default {
  name: "RoleBasicSettingsPanel",
  props: {
    agentName: { type: String, default: "" },
    systemPrompt: { type: String, default: "" },
  },
};
</script>

<style lang="scss" scoped>
.basic-config-grid {
  display: grid;
  grid-template-columns: minmax(190px, 260px) minmax(0, 1fr);
  align-items: start;
  gap: 18px;
}

.basic-name-block,
.basic-prompt-block {
  min-width: 0;
}

.basic-field-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 7px;
  color: #3d4566;
  font-size: 12px;
  line-height: 20px;
}

.basic-field-heading > span {
  font-weight: 600;
}

.basic-field-heading small {
  overflow: hidden;
  color: #a1a7b7;
  font-size: 10px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.basic-name-item,
.basic-prompt-item {
  margin-bottom: 0;
}

.basic-prompt-item ::v-deep .el-textarea__inner {
  min-height: 322px !important;
  line-height: 1.75;
}

@media (max-width: 1250px) {
  .basic-config-grid {
    grid-template-columns: minmax(180px, 230px) minmax(0, 1fr);
  }
}

@media (max-width: 800px) {
  .basic-config-grid {
    grid-template-columns: 1fr;
  }
}
</style>
