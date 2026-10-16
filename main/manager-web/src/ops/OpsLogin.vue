<template>
  <main class="ops-login ops-role-login">
    <section class="ops-login-card">
      <div class="ops-login-heading"><span class="ops-mark">智</span><h1>运营管理</h1></div><p class="ops-muted">选择身份，输入对应的登录密钥。</p>
      <p v-if="identityPolicyLoading" class="ops-muted" role="status">正在读取登录设置…</p>
      <template v-else-if="!loginPolicyReady"><el-alert :title="loginPolicyNotice" type="error" :closable="false" /><el-button type="text" :disabled="busy" @click="prepareLogin">重新读取登录设置</el-button></template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form @submit.native.prevent="login">
        <el-form-item label="管理身份"><el-radio-group v-model="form.username" class="ops-role-options" :disabled="busy" @change="changeIdentity"><el-radio v-for="identity in identities" :key="identity.username" :label="identity.username" border><span class="ops-role-label">{{ identity.title }}</span><span class="ops-role-description">{{ identity.description }}</span></el-radio></el-radio-group></el-form-item>
        <el-form-item label="登录密钥"><el-input ref="loginSecret" v-model="form.password" type="password" autocomplete="current-password" placeholder="输入所选身份的登录密钥" show-password :disabled="busy" /></el-form-item>
        <el-form-item v-if="captchaRequired" label="图形验证码"><div class="ops-captcha"><el-input v-model="form.captcha" autocomplete="off" maxlength="8" :disabled="busy" /><button type="button" aria-label="刷新图形验证码" :disabled="busy" @click="captcha"><img v-if="captchaUrl" :src="captchaUrl" alt="图形验证码" /><span v-else>刷新验证码</span></button></div></el-form-item>
        <el-form-item v-if="mfaRequired" label="动态验证码"><el-input v-model="form.otpCode" autocomplete="one-time-code" inputmode="numeric" maxlength="6" placeholder="身份验证器中的 6 位代码" :disabled="busy" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!loginPolicyReady || busy" class="ops-login-button">进入{{ selectedIdentity ? selectedIdentity.title : '工作台' }}</el-button>
      </el-form>
      <p v-if="identityPolicyAvailable"><router-link to="/enroll">申请员工身份</router-link> · <router-link to="/recover">身份恢复</router-link></p>
      <p class="ops-login-footer">三个身份分别授权，均不能查看用户的私密聊天内容。</p>
    </section>
  </main>
</template>
<script>
import Api from '@/apis/api';
import { operations } from '@/apis/platform';
import identityPolicyState from './identityPolicyState';
import { operationsIdentities } from './identities';
import { getUUID, sm2Encrypt } from '@/utils';
export default {
  mixins: [identityPolicyState],
  data() { return { identities: operationsIdentities, form: { username: 'ops-keys', password: '', captcha: '', otpCode: '' }, captchaId: '', captchaUrl: '', captchaGeneration: 0, loginDisposed: false, busy: false, error: '' }; },
  computed: {
    selectedIdentity() { return this.identities.find(identity => identity.username === this.form.username); },
    loginPolicyReady() {
      return !this.identityPolicyLoading && !this.identityPolicyError
        && typeof this.identityPolicy?.captchaRequired === 'boolean'
        && typeof this.identityPolicy?.mfaRequired === 'boolean';
    },
    loginPolicyNotice() { return this.identityPolicyError ? '暂时无法读取登录设置，请重试' : '登录设置不完整，请重新读取'; },
    captchaRequired() { return this.loginPolicyReady && this.identityPolicy.captchaRequired; },
    mfaRequired() { return this.loginPolicyReady && this.identityPolicy.mfaRequired; },
  },
  created() { this.$store.dispatch('fetchPubConfig'); this.prepareLogin(); },
  beforeDestroy() { this.loginDisposed = true; this.clearCaptcha(); this.form.password = ''; this.form.otpCode = ''; },
  methods: {
    changeIdentity() {
      this.form.password = ''; this.form.otpCode = ''; this.error = '';
      if (this.captchaRequired) this.captcha();
      this.$nextTick(() => this.$refs.loginSecret?.focus());
    },
    async prepareLogin() {
      if (this.loginDisposed || this.identityPolicyLoading) return;
      this.clearCaptcha(); this.form.otpCode = '';
      await this.loadIdentityPolicy();
      if (!this.loginDisposed && this.captchaRequired) this.captcha();
    },
    clearCaptcha() {
      this.captchaGeneration++; this.captchaId = ''; this.form.captcha = '';
      if (this.captchaUrl) URL.revokeObjectURL(this.captchaUrl);
      this.captchaUrl = '';
    },
    captcha() {
      if (this.loginDisposed || !this.captchaRequired) return;
      this.clearCaptcha();
      const generation = this.captchaGeneration;
      this.captchaId = getUUID();
      Api.user.getCaptcha(this.captchaId, response => {
        if (this.loginDisposed || generation !== this.captchaGeneration || !this.captchaRequired) return;
        this.captchaUrl = URL.createObjectURL(response.data);
      });
    },
    async login() {
      if (this.busy) return;
      this.error = '';
      if (!this.loginPolicyReady) { this.error = this.identityPolicyLoading ? '正在读取登录设置，请稍候' : this.loginPolicyNotice; return; }
      if (!this.selectedIdentity) { this.error = '请选择管理身份'; return; }
      if (!this.form.password) { this.error = '请输入登录密钥'; return; }
      if (this.captchaRequired && (!this.form.captcha.trim() || !this.captchaId)) { this.error = '请填写图形验证码'; return; }
      if (this.mfaRequired && !/^\d{6}$/.test(this.form.otpCode)) { this.error = '请填写 6 位动态验证码'; return; }
      const publicKey = this.$store.state.pubConfig?.sm2PublicKey;
      if (!publicKey) { this.error = '登录配置尚未就绪，请稍后重试'; this.$store.dispatch('fetchPubConfig'); return; }
      this.busy = true;
      try {
        const password = sm2Encrypt(publicKey, (this.captchaRequired ? this.form.captcha : '') + this.form.password);
        const payload = { username: this.form.username.trim(), password };
        if (this.captchaRequired) payload.captchaId = this.captchaId;
        if (this.mfaRequired) payload.otpCode = this.form.otpCode;
        const token = await operations.login(payload);
        this.$store.commit('setToken', JSON.stringify(token));
        const profile = await operations.me();
        if (profile?.audience !== 'OPS') throw new Error('员工身份不匹配');
        this.$store.commit('setUserInfo', profile); this.form.password = ''; this.form.otpCode = ''; await this.$router.replace('/home');
      } catch (error) { this.$store.commit('clearAuth'); this.error = error.message || '登录失败，请重试'; await this.prepareLogin(); }
      finally { this.busy = false; }
    },
  },
};
</script>

<style scoped>
.ops-role-login { display: flex; justify-content: center; align-items: center; padding: 32px 16px; box-sizing: border-box; background: #f4f6f8; }
.ops-role-login .ops-login-card { width: 440px; max-width: 100%; padding: 32px; box-sizing: border-box; background: #fff; border: 1px solid #e3e8ee; border-radius: 16px; }
.ops-login-heading { display: flex; align-items: center; gap: 12px; }
.ops-login-heading h1 { margin: 0; font-size: 24px; }
.ops-role-options { display: grid; gap: 10px; width: 100%; }
.ops-role-options >>> .el-radio.is-bordered { display: flex; align-items: center; width: 100%; height: auto; min-height: 64px; margin: 0; padding: 12px 14px; border-radius: 8px; }
.ops-role-options >>> .el-radio.is-checked { background: #f1f7ff; }
.ops-role-options >>> .el-radio__label { display: grid; gap: 6px; white-space: normal; }
.ops-role-label { font-size: 15px; font-weight: 600; }
.ops-role-description { font-size: 12px; color: #687b89; line-height: 1.5; }
.ops-role-login .ops-login-footer { margin-bottom: 0; }
@media (max-width: 480px) { .ops-role-login { padding: 16px; } .ops-role-login .ops-login-card { padding: 24px 20px; } }
</style>
