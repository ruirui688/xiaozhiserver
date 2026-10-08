<template>
  <el-dialog title="给设备起个名字" :visible.sync="visible" width="420px" append-to-body
    :close-on-click-modal="false" :before-close="close">
    <p class="alias-hint">例如：客厅小智、书房助手、我的小伙伴</p>
    <el-input v-model="alias" maxlength="64" show-word-limit clearable autofocus
      placeholder="输入设备别名" :disabled="saving" @keyup.enter.native="save" />
    <p class="alias-hint">清空后显示“未命名设备”，不影响绑定的智能体。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <span slot="footer">
      <el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </span>
  </el-dialog>
</template>
<script>
import Api from '@/apis/api';
import { deviceRequest } from '@/utils/deviceCenter';
export default {
  props: { canWrite: Boolean },
  data() { return { visible: false, deviceId: '', alias: '', saving: false, error: '' }; },
  methods: {
    open(device) {
      if (!this.canWrite || this.saving) return;
      this.deviceId = device.id;
      this.alias = device.alias || '';
      this.error = '';
      this.visible = true;
    },
    close(done) { if (!this.saving) done(); },
    async save() {
      if (!this.canWrite || this.saving || !this.deviceId) return;
      const alias = this.alias.trim();
      if (alias.length > 64) { this.error = '别名最多 64 个字符'; return; }
      this.saving = true; this.error = '';
      try {
        await deviceRequest(done => Api.device.updateDeviceInfo(this.deviceId, { alias }, done));
        this.$emit('saved', { id: this.deviceId, alias });
        this.visible = false;
        this.$message.success(alias ? '设备别名已保存' : '已清空设备别名');
      } catch (error) { this.error = error.message; }
      finally { this.saving = false; }
    }
  }
};
</script>
<style scoped>
.alias-hint { color: #7c8496; font-size: 13px; line-height: 1.7; text-align: left; }
</style>
