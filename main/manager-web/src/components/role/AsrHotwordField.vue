<template>
  <section class="hotword-panel">
    <div class="hotword-panel__header">
      <div class="hotword-panel__heading">
        <span class="hotword-panel__icon"><i class="el-icon-s-order" /></span>
        <div>
          <div class="hotword-panel__title">{{ $t('roleConfig.asrHotword') }}</div>
          <div class="hotword-panel__subtitle">
            {{ configuredCount ? `已保存 ${configuredCount} 个候选，当前生效：${activeId || '未选择'}` : '未配置火山热词表' }}
          </div>
        </div>
      </div>
      <el-button
        type="text"
        :icon="expanded ? 'el-icon-arrow-up' : 'el-icon-setting'"
        class="hotword-panel__manage"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起' : '管理热词' }}
      </el-button>
    </div>

    <el-collapse-transition>
      <div v-show="expanded" class="hotword-panel__body">
        <div class="hotword-panel__body-head">
          <span>在资源中心直接创建、发布热词库并应用到智能体；下方保留已有词表 ID 的兼容配置。</span>
          <router-link to="/asr-hotword-management" target="_blank" class="hotword-panel__console-link">打开热词库</router-link>
          <a
            href="https://console.volcengine.com/speech/new/hot-word?projectName=default"
            target="_blank"
            rel="noopener noreferrer"
            class="hotword-panel__console-link"
          >
            <span>{{ $t('roleConfig.asrHotwordConsole') }}</span>
            <i class="el-icon-top-right"></i>
          </a>
        </div>

        <div class="hotword-list">
          <div
            v-for="(id, index) in rows"
            :key="`hotword-${index}`"
            class="hotword-row"
          >
            <div class="hotword-row__input">
              <el-input
                :value="id"
                clearable
                maxlength="128"
                :placeholder="$t('roleConfig.asrHotwordPlaceholder')"
                @input="updateId(index, $event)"
                @blur="normalizeRows"
              />
              <div v-if="id && !isValidId(id)" class="hotword-row__error">
                {{ $t('roleConfig.asrHotwordInvalid') }}
              </div>
            </div>
            <el-button
              size="mini"
              :type="isActive(id) ? 'primary' : 'default'"
              :plain="isActive(id)"
              :disabled="!isValidId(id)"
              class="hotword-row__active"
              @click="selectActive(index)"
            >
              {{ isActive(id)
                ? $t('roleConfig.asrHotwordActive')
                : $t('roleConfig.asrHotwordSetActive') }}
            </el-button>
            <el-button
              type="text"
              icon="el-icon-delete"
              class="hotword-row__remove"
              :aria-label="$t('roleConfig.asrHotwordRemove')"
              @click="removeId(index)"
            />
          </div>
        </div>

        <div class="hotword-panel__footer">
          <el-button
            type="text"
            icon="el-icon-plus"
            :disabled="rows.length >= maxItems"
            @click="addId"
          >
            {{ $t('roleConfig.asrHotwordAdd') }}
          </el-button>
          <span>{{ $t('roleConfig.asrHotwordCount', { count: configuredCount, max: maxItems }) }}</span>
        </div>

        <div class="hotword-panel__notice">
          <i class="el-icon-info"></i>
          <span>{{ $t('roleConfig.asrHotwordNotice') }}</span>
        </div>
      </div>
    </el-collapse-transition>
  </section>
</template>

<script>
import {
  MAX_ASR_HOTWORD_IDS,
  normalizeAsrHotwordConfig,
} from "@/utils/roleConfigDraft";

export default {
  name: "AsrHotwordField",
  props: {
    ids: { type: Array, default: () => [] },
    activeId: { type: String, default: "" },
    maxItems: { type: Number, default: MAX_ASR_HOTWORD_IDS },
  },
  data() {
    return {
      expanded: false,
    };
  },
  computed: {
    rows() {
      return this.ids.length > 0 ? this.ids : [""];
    },
    configuredCount() {
      return this.ids.filter((id) => String(id || "").trim()).length;
    },
  },
  methods: {
    isValidId(id) {
      return /^[A-Za-z0-9_-]{1,128}$/.test(String(id || "").trim());
    },
    isActive(id) {
      const normalizedId = String(id || "").trim();
      return Boolean(normalizedId) && normalizedId === String(this.activeId || "").trim();
    },
    emitChange(ids, activeId) {
      this.$emit("change", { ids, activeId });
    },
    updateId(index, value) {
      const previousId = String(this.rows[index] || "").trim();
      const nextIds = [...this.rows];
      nextIds.splice(index, 1, value);
      let nextActiveId = this.activeId;
      if (previousId && previousId === String(this.activeId || "").trim()) {
        nextActiveId = value;
      } else if (!String(nextActiveId || "").trim() && index === 0) {
        nextActiveId = value;
      }
      this.emitChange(nextIds, nextActiveId);
    },
    selectActive(index) {
      const selectedId = String(this.rows[index] || "").trim();
      if (selectedId) this.emitChange([...this.rows], selectedId);
    },
    addId() {
      if (this.rows.length >= this.maxItems) return;
      this.emitChange([...this.rows, ""], this.activeId);
    },
    removeId(index) {
      const removedId = String(this.rows[index] || "").trim();
      const nextIds = this.rows.filter((item, itemIndex) => itemIndex !== index);
      const nextActiveId = removedId && removedId === String(this.activeId || "").trim()
        ? ""
        : this.activeId;
      const normalized = normalizeAsrHotwordConfig(nextIds, nextActiveId);
      this.emitChange(normalized.ids, normalized.activeId);
    },
    normalizeRows() {
      const normalized = normalizeAsrHotwordConfig(this.rows, this.activeId);
      this.emitChange(normalized.ids, normalized.activeId);
    },
  },
};
</script>

<style lang="scss" scoped>
.hotword-panel {
  width: 100%;
  margin: 2px 0 12px;
  padding: 10px 12px;
  border: 1px solid #e5e9f2;
  border-radius: 8px;
  background: #fafbfe;
  box-sizing: border-box;
}

.hotword-panel__header,
.hotword-panel__heading,
.hotword-panel__body-head,
.hotword-panel__footer,
.hotword-row {
  display: flex;
  align-items: center;
}

.hotword-panel__header {
  justify-content: space-between;
  gap: 12px;
}

.hotword-panel__heading {
  min-width: 0;
  gap: 9px;
}

.hotword-panel__icon {
  display: inline-flex;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #edf2ff;
  color: #5f7fe8;
}

.hotword-panel__title {
  color: #30384f;
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}

.hotword-panel__subtitle {
  overflow: hidden;
  color: #8a93a8;
  font-size: 11px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hotword-panel__manage {
  flex-shrink: 0;
  margin: 0;
}

.hotword-panel__body {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #e7ebf3;
}

.hotword-panel__body-head {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 9px;
  color: #8a93a8;
  font-size: 11px;
}

.hotword-panel__console-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #337ecc;
  font-size: 12px;
  text-decoration: none;
  white-space: nowrap;
}

.hotword-panel__console-link:hover,
.hotword-panel__console-link:focus {
  color: #1d5fa7;
  text-decoration: underline;
}

.hotword-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hotword-row {
  gap: 8px;
}

.hotword-row__input {
  flex: 1;
  min-width: 0;
}

.hotword-row__active {
  flex: 0 0 66px;
  margin: 0;
}

.hotword-row__remove {
  flex: 0 0 24px;
  margin: 0;
  color: #a3aabd;
}

.hotword-row__remove:hover {
  color: #f56c6c;
}

.hotword-row__error {
  margin-top: 3px;
  color: #f56c6c;
  font-size: 11px;
  line-height: 16px;
}

.hotword-panel__footer {
  justify-content: space-between;
  margin-top: 6px;
  color: #a0a7b8;
  font-size: 11px;
}

.hotword-panel__notice {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-top: 7px;
  padding-top: 8px;
  border-top: 1px dashed #e0e5ef;
  color: #7a849b;
  font-size: 11px;
  line-height: 17px;
}

.hotword-panel__notice i {
  margin-top: 2px;
  color: #6d8ee8;
}
</style>
