<template>
  <main class="ops-login">
    <section class="ops-login-story"><span class="ops-eyebrow">XIAOZHI OPERATIONS</span><h1>让服务<br>稳定向前。</h1><p>专注你的岗位，协同管理模型服务、额度和官方资源。</p></section>
    <section class="ops-login-card">
      <span class="ops-mark">智</span><h2>员工登录</h2><p class="ops-muted">使用员工账号和密码进入工作台。</p>
      <p v-if="identityPolicyLoading" class="ops-muted" role="status">正在读取登录设置…</p>
      <template v-else-if="!loginPolicyReady"><el-alert :title="loginPolicyNotice" type="error" :closable="false" /><el-button type="text" :disabled="busy" @click="prepareLogin">重新读取登录设置</el-button></template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form @submit.native.prevent="login">
        <el-form-item label="员工账号"><el-input v-model="form.username" autocomplete="username" maxlength="100" :disabled="busy" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" autocomplete="current-password" show-password :disabled="busy" /></el-form-item>
        <el-form-item v-if="captchaRequired" label="图形验证码"><div class="ops-captcha"><el-input v-model="form.captcha" autocomplete="off" maxlength="8" :disabled="busy" /><button type="button" aria-label="刷新图形验证码" :disabled="busy" @click="captcha"><img v-if="captchaUrl" :src="captchaUrl" alt="图形验证码" /><span v-else>刷新验证码</span></button></div></el-form-item>
        <el-form-item v-if="mfaRequired" label="动态验证码"><el-input v-model="form.otpCode" autocomplete="one-time-code" inputmode="numeric" maxlength="6" placeholder="身份验证器中的 6 位代码" :disabled="busy" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!loginPolicyReady || busy" class="ops-login-button">进入工作台</el-button>
      </el-form>
      <p v-if="identityPolicyAvailable"><router-link to="/enroll">申请员工身份</router-link> · <router-link to="/recover">身份恢复</router-link></p>
      <p v-else-if="identityPolicy && !identityPolicyError" class="ops-muted">{{ identityPolicyNotice }}</p>
      <p class="ops-login-footer">员工身份独立授权，不与用户智控台会话互通。</p>
    </section>
  </main>
</template>
<script>
import Api from '@/apis/api';
import { operations } from '@/apis/platform';
import identityPolicyState from './identityPolicyState';
import { getUUID, sm2Encrypt } from '@/utils';
export default {
  mixins: [identityPolicyState],
  data() { return { form: { username: '', password: '', captcha: '', otpCode: '' }, captchaId: '', captchaUrl: '', captchaGeneration: 0, loginDisposed: false, busy: false, error: '' }; },
  computed: {
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
      if (!this.form.username.trim() || !this.form.password) { this.error = '请填写员工账号和密码'; return; }
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
