<template><main class="ops-login"><section class="ops-login-story"><span class="ops-eyebrow">XIAOZHI OPERATIONS</span><h1>让服务<br>稳定向前。</h1><p>专注你的岗位，协同管理模型服务、额度和官方资源。</p></section><section class="ops-login-card"><span class="ops-mark">智</span><h2>员工登录</h2><p class="ops-muted">使用独立员工账号和动态验证码登录。</p><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-form @submit.native.prevent="login"><el-form-item label="员工账号"><el-input v-model="form.username" autocomplete="username" maxlength="100" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" autocomplete="current-password" show-password /></el-form-item><el-form-item label="图形验证码"><div class="ops-captcha"><el-input v-model="form.captcha" autocomplete="off" maxlength="8" /><button type="button" aria-label="刷新图形验证码" @click="captcha"><img v-if="captchaUrl" :src="captchaUrl" alt="图形验证码" /><span v-else>刷新验证码</span></button></div></el-form-item><el-form-item label="动态验证码"><el-input v-model="form.otpCode" autocomplete="one-time-code" inputmode="numeric" maxlength="6" placeholder="身份验证器中的 6 位代码" /></el-form-item><el-button native-type="submit" type="primary" :loading="busy" class="ops-login-button">进入工作台</el-button></el-form><p v-if="identityPolicyAvailable"><router-link to="/enroll">申请员工身份</router-link> · <router-link to="/recover">身份恢复</router-link></p><p v-else class="ops-muted">{{ identityPolicyNotice }} <el-button v-if="identityPolicyError" type="text" :loading="identityPolicyLoading" @click="loadIdentityPolicy">重试</el-button></p><p class="ops-login-footer">员工身份独立授权，不与用户智控台会话互通。</p></section></main></template>
<script>
import Api from '@/apis/api';
import { operations } from '@/apis/platform';
import identityPolicyState from './identityPolicyState';
import { getUUID, sm2Encrypt } from '@/utils';
export default {
  mixins: [identityPolicyState],
  data() { return { form: { username: '', password: '', captcha: '', otpCode: '' }, captchaId: '', captchaUrl: '', busy: false, error: '' }; },
  created() { this.$store.dispatch('fetchPubConfig'); this.captcha(); this.loadIdentityPolicy(); },
  beforeDestroy() { if (this.captchaUrl) URL.revokeObjectURL(this.captchaUrl); },
  methods: {
    captcha() { this.captchaId = getUUID(); this.form.captcha = ''; Api.user.getCaptcha(this.captchaId, response => { if (this.captchaUrl) URL.revokeObjectURL(this.captchaUrl); this.captchaUrl = URL.createObjectURL(response.data); }); },
    async login() {
      if (this.busy) return;
      this.error = '';
      if (!this.form.username.trim() || !this.form.password || !this.form.captcha || !/^\d{6}$/.test(this.form.otpCode)) { this.error = '请填写账号、密码、图形验证码和 6 位动态验证码'; return; }
      this.busy = true;
      try {
        const password = sm2Encrypt(this.$store.state.pubConfig.sm2PublicKey, this.form.captcha + this.form.password);
        const token = await operations.login({ username: this.form.username.trim(), password, captchaId: this.captchaId, otpCode: this.form.otpCode });
        this.$store.commit('setToken', JSON.stringify(token));
        const profile = await operations.me();
        if (profile?.audience !== 'OPS') throw new Error('员工身份不匹配');
        this.$store.commit('setUserInfo', profile); this.form.password = ''; this.form.otpCode = ''; await this.$router.replace('/home');
      } catch (error) { this.$store.commit('clearAuth'); this.error = error.message; this.captcha(); }
      finally { this.busy = false; }
    },
  },
};
</script>
