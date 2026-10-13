<template>
  <article class="person-card">
    <div class="person-card__main">
      <div class="person-card__avatar">
        <img v-if="faceUrl" :src="faceUrl" :alt="`${profile.name}的人脸照片`" />
        <div v-else class="person-card__avatar-placeholder">
          <i class="el-icon-user" />
        </div>
        <span v-if="profile.gender && profile.gender !== 'UNKNOWN'" class="person-card__gender">
          {{ profile.gender === 'MALE' ? '♂' : '♀' }}
        </span>
      </div>

      <div class="person-card__identity">
        <div class="person-card__title-row">
          <h3>{{ profile.name }}</h3>
          <span :class="['person-card__enabled', { disabled: profile.status !== 1 }]">
            {{ profile.status === 1 ? "启用" : "停用" }}
          </span>
        </div>
        <p :title="profile.description || '暂无介绍'">{{ profile.description || "暂无介绍" }}</p>
        <div class="person-card__biometrics">
          <span :class="['biometric-state', { ready: profile.faceReady }]">
            <i class="el-icon-camera" />
            {{ profile.faceReady ? `人脸已上传 · v${profile.faceVersion}` : "人脸未上传" }}
          </span>
          <button
            type="button"
            :class="['biometric-state', 'biometric-state--button', { ready: profile.voicePrintReady }]"
            :disabled="!canWrite"
            @click="$emit('voiceprint', profile)"
          >
            <i class="el-icon-microphone" />
            {{ profile.voicePrintReady ? "声纹已录入" : "声纹未录入" }}
          </button>
        </div>
      </div>
    </div>

    <div class="person-card__divider" />

    <div class="person-card__footer">
      <div class="person-card__binding" :title="agentTitle">
        <span class="binding-count">{{ agentCount }}</span>
        <span>{{ agentSummary }}</span>
        <span v-if="profile.createDate" class="create-date">· {{ formatDate(profile.createDate) }}</span>
      </div>

      <div v-if="canWrite" class="person-card__actions">
        <el-tooltip content="接待与预约" placement="top">
          <el-button type="text" icon="el-icon-s-custom" aria-label="接待与预约" @click="$emit('reception', profile)" />
        </el-tooltip>
        <el-tooltip v-if="profile.unpublishedChanges" content="发布变更" placement="top">
          <el-button type="text" icon="el-icon-upload2" aria-label="发布变更" @click="$emit('publish', profile)" />
        </el-tooltip>
        <el-tooltip content="编辑用户信息" placement="top">
          <el-button type="text" icon="el-icon-edit" aria-label="编辑用户信息" @click="$emit('edit', profile)" />
        </el-tooltip>
        <el-dropdown trigger="click" @command="handleCommand">
          <el-button type="text" icon="el-icon-more" aria-label="更多用户操作" />
          <el-dropdown-menu slot="dropdown">
            <el-dropdown-item command="delete" class="danger-item">删除用户信息</el-dropdown-item>
          </el-dropdown-menu>
        </el-dropdown>
      </div>
    </div>
  </article>
</template>

<script>
export default {
  name: "PersonProfileCard",
  props: {
    profile: {
      type: Object,
      required: true
    },
    faceUrl: {
      type: String,
      default: ""
    },
    canWrite: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    agentCount() {
      return Array.isArray(this.profile.agentNames) ? this.profile.agentNames.length : 0;
    },
    agentSummary() {
      if (!this.agentCount) return "未绑定智能体";
      if (this.agentCount === 1) return this.profile.agentNames[0];
      return `${this.profile.agentNames[0]} 等 ${this.agentCount} 个智能体`;
    },
    agentTitle() {
      return this.agentCount ? this.profile.agentNames.join("、") : "未绑定智能体";
    }
  },
  methods: {
    formatDate(value) {
      const text = String(value || "");
      if (/^\d{4}-\d{2}-\d{2}/.test(text)) return text.slice(0, 10);
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return "";
      const year = date.getFullYear();
      const month = String(date.getMonth() + 1).padStart(2, "0");
      const day = String(date.getDate()).padStart(2, "0");
      return `${year}/${month}/${day}`;
    },
    handleCommand(command) {
      if (command === "delete") this.$emit("delete", this.profile);
    }
  }
};
</script>

<style lang="scss" scoped>
.person-card {
  min-width: 0;
  padding: 22px 24px 16px;
  border: 1px solid #edf0f5;
  border-radius: 22px;
  background: #fff;
  box-shadow: 0 12px 30px rgba(62, 76, 112, 0.08);
  transition: 0.2s ease;
}

.person-card:hover {
  transform: translateY(-2px);
  border-color: #d7e2f7;
  box-shadow: 0 16px 34px rgba(62, 76, 112, 0.12);
}

.person-card__main {
  display: flex;
  min-height: 132px;
  align-items: center;
  gap: 20px;
}

.person-card__avatar {
  position: relative;
  width: 116px;
  height: 116px;
  flex: 0 0 116px;
  overflow: visible;
  border-radius: 50%;
  background: #f1f4f8;
}

.person-card__avatar img,
.person-card__avatar-placeholder {
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: 50%;
}

.person-card__avatar img {
  object-fit: cover;
}

.person-card__avatar-placeholder {
  display: grid;
  place-items: center;
  color: #a3acbc;
  font-size: 36px;
}

.person-card__gender {
  position: absolute;
  right: 0;
  bottom: 3px;
  display: grid;
  width: 25px;
  height: 25px;
  place-items: center;
  border: 3px solid #fff;
  border-radius: 50%;
  background: #416fc8;
  color: #fff;
  font-size: 14px;
  font-weight: 700;
}

.person-card__identity {
  min-width: 0;
  flex: 1;
}

.person-card__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.person-card__title-row h3 {
  overflow: hidden;
  margin: 0;
  color: #202638;
  font-size: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.person-card__enabled {
  flex-shrink: 0;
  padding: 3px 8px;
  border-radius: 10px;
  background: #e8f8ef;
  color: #32a06e;
  font-size: 11px;
}

.person-card__enabled.disabled {
  background: #f1f3f6;
  color: #8b94a4;
}

.person-card__identity > p {
  overflow: hidden;
  margin: 8px 0 13px;
  color: #8b93a2;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.person-card__biometrics {
  display: grid;
  gap: 7px;
}

.biometric-state {
  display: inline-flex;
  width: max-content;
  max-width: 100%;
  align-items: center;
  gap: 7px;
  color: #9aa1ad;
  font-size: 13px;
}

.biometric-state.ready {
  color: #2ebb61;
}

.biometric-state--button {
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  font: inherit;
}

.biometric-state--button:not(:disabled):hover {
  color: #3d78d6;
}

.biometric-state--button:disabled {
  cursor: default;
}

.person-card__divider {
  height: 1px;
  margin: 14px 0 12px;
  background: #edf0f4;
}

.person-card__footer {
  display: flex;
  min-height: 32px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.person-card__binding {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  color: #7e8797;
  font-size: 12px;
}

.person-card__binding > span:not(.binding-count) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.binding-count {
  display: inline-grid;
  width: 25px;
  height: 25px;
  flex: 0 0 25px;
  place-items: center;
  border-radius: 50%;
  background: #ffcf68;
  color: #75571b;
  font-weight: 700;
}

.create-date {
  color: #a0a7b2;
}

.person-card__actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 3px;
}

.person-card__actions .el-button {
  margin: 0;
  color: #748091;
}

.person-card__actions .el-button:hover {
  color: #3979dc;
}

.danger-item {
  color: #f56c6c;
}

@media (max-width: 1280px) {
  .person-card__avatar {
    width: 92px;
    height: 92px;
    flex-basis: 92px;
  }

  .person-card__main {
    min-height: 108px;
  }

  .create-date {
    display: none;
  }
}
</style>
