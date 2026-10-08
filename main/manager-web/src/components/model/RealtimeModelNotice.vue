<template>
  <el-alert
    v-if="realtime"
    class="realtime-model-notice"
    :type="adapterMismatch ? 'error' : 'warning'"
    :closable="false"
    show-icon
  >
    <template slot="title">
      <strong>{{ $t(adapterMismatch ? "realtimeModel.mismatchTitle" : "realtimeModel.title") }}</strong>
      <el-tag size="mini" type="warning">{{ $t("realtimeModel.badge") }}</el-tag>
    </template>
    <div class="realtime-model-notice__body">
      <p>{{ message }}</p>
      <p v-if="context === 'agent'">{{ $t("realtimeModel.agentFallback") }}</p>
      <p v-if="context === 'config'">{{ $t("realtimeModel.configTest") }}</p>
      <p v-if="context === 'test'">{{ $t("realtimeModel.testContract") }}</p>
      <p v-if="context === 'agent' || context === 'config'">{{ $t("realtimeModel.memoryBoundary") }}</p>
    </div>
  </el-alert>
</template>

<script>
import {
  getRealtimeAdapter,
  hasRealtimeAdapterMismatch,
  isDoubaoRealtimeModel,
  isRealtimeModel,
} from "@/utils/realtimeModel";

export default {
  name: "RealtimeModelNotice",
  props: {
    model: {
      type: Object,
      default: () => ({}),
    },
    context: {
      type: String,
      default: "config",
      validator: (value) => ["agent", "config", "test"].includes(value),
    },
  },
  computed: {
    realtime() {
      return isRealtimeModel(this.model);
    },
    adapterMismatch() {
      return hasRealtimeAdapterMismatch(this.model);
    },
    message() {
      if (this.adapterMismatch) {
        return this.$t("realtimeModel.mismatchDescription");
      }
      if (["doubao_duplex", "doubao-duplex"].includes(getRealtimeAdapter(this.model))) {
        return this.$t("realtimeModel.duplexDescription");
      }
      if (isDoubaoRealtimeModel(this.model)) {
        return this.context === "agent"
          ? this.$t("realtimeModel.doubaoAgentDescription")
          : this.$t("realtimeModel.doubaoConfigDescription");
      }
      return this.context === "agent"
        ? this.$t("realtimeModel.agentDescription")
        : this.$t("realtimeModel.configDescription");
    },
  },
};
</script>

<style scoped>
.realtime-model-notice {
  margin-top: 8px;
  text-align: left;
}

.realtime-model-notice ::v-deep .el-alert__title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.realtime-model-notice__body p {
  margin: 4px 0 0;
  color: #7a4b00;
  font-size: 12px;
  line-height: 1.55;
}
</style>
