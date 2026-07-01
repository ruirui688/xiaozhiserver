<template>
  <el-dialog
    :visible="visible"
    title="Role Test"
    width="500px"
    @close="handleClose"
  >
    <div class="test-content">
      <div class="test-section">
        <label class="test-label">Test Message:</label>
        <el-input
          type="textarea"
          v-model="testMessage"
          rows="4"
          placeholder="Enter a test message..."
        />
      </div>
      <div class="test-actions">
        <el-button type="primary" @click="sendTest">Send Test</el-button>
        <el-button @click="handleClose">Close</el-button>
      </div>
      <div v-if="testResult" class="test-result">
        <h4>Test Result:</h4>
        <p>{{ testResult }}</p>
      </div>
    </div>
  </el-dialog>
</template>

<script>
export default {
  name: "RoleTestDialog",
  props: {
    visible: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      testMessage: "",
      testResult: "",
    };
  },
  methods: {
    handleClose() {
      this.testMessage = "";
      this.testResult = "";
      this.$emit("close");
    },
    sendTest() {
      // Placeholder for testing logic
      this.testResult = `Test sent: ${this.testMessage}`;
    },
  },
};
</script>

<style scoped>
.test-content {
  padding: 10px 0;
}
.test-section {
  margin-bottom: 16px;
}
.test-label {
  display: block;
  margin-bottom: 8px;
  font-weight: 500;
  color: #606266;
}
.test-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-bottom: 16px;
}
.test-result {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 12px;
  margin-top: 10px;
}
.test-result h4 {
  margin: 0 0 8px 0;
  color: #303133;
}
.test-result p {
  margin: 0;
  color: #606266;
  white-space: pre-wrap;
}
</style>
