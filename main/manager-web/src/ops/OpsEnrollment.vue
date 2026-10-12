<template>
  <main class="ops-login"><section class="ops-login-card enrollment-card">
    <template v-if="!result"><h2>{{ recovering ? '恢复员工身份' : '申请员工身份' }}</h2>
      <template v-if="!identityPolicyAvailable"><el-alert :title="identityPolicyNotice" :type="identityPolicyError ? 'warning' : 'info'" :closable="false" /><el-button type="text" :loading="identityPolicyLoading" @click="prepareIdentityForm">重新查询</el-button></template>
      <template v-else><p class="ops-muted">本人设置密码和身份验证器，再由两位身份治理人员确认与批准。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form @submit.native.prevent="submit"><el-form-item label="员工账号"><el-input v-model="form.username" autocomplete="username" maxlength="100" /></el-form-item><el-form-item :label="recovering ? '新密码' : '设置密码'"><el-input v-model="form.password" type="password" autocomplete="new-password" show-password /><p class="ops-muted">14–128 位，包含大写字母、小写字母和数字。</p></el-form-item><el-form-item v-if="recovering" label="原恢复码"><el-input v-model="form.recoveryCode" type="password" autocomplete="off" /></el-form-item>
      <el-form-item label="绑定身份验证器"><p class="ops-muted">在身份验证器中手动添加账号，类型选择“基于时间”，输入以下设置密钥。</p><code class="totp-secret">{{ secret }}</code><p class="ops-muted">账号名称可填写“小智运营 / 员工账号”。密钥只在当前页面显示，请勿发给审批人员。</p></el-form-item>
      <el-form-item label="新验证器的动态码"><el-input v-model="form.otpCode" inputmode="numeric" maxlength="6" autocomplete="one-time-code" /></el-form-item>
      <el-form-item label="图形验证码"><div class="ops-captcha"><el-input v-model="form.captcha" autocomplete="off" maxlength="8" /><button type="button" aria-label="刷新图形验证码" @click="captcha"><img v-if="captchaUrl" :src="captchaUrl" alt="图形验证码" /></button></div></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!secret || !identityPolicyAvailable">提交身份申请</el-button></el-form></template>
    </template>
    <template v-else><h2>申请已提交</h2><p>申请编号：{{ result.id }}</p><p class="ops-muted">联系身份治理人员处理申请。批准后，使用您刚设置的密码和验证器登录；岗位权限另行申请。</p><el-alert title="请立即保存新的恢复码；关闭此页后无法再次查看。恢复码在身份申请批准后生效。" type="warning" :closable="false" /><code class="totp-secret">{{ result.recoveryCode }}</code></template>
    <p><router-link to="/login">返回员工登录</router-link></p>
  </section></main>
</template>
<script>
import Api from '@/apis/api';
import { platformRequest } from '@/apis/platform';
import { getUUID, sm2Encrypt } from '@/utils';
import { generateTotpSecret } from '@/security/totp';
import identityPolicyState from './identityPolicyState';
export default {
  mixins: [identityPolicyState],
  data() { return { form:{username:'',password:'',captcha:'',otpCode:'',recoveryCode:''},secret:'',captchaId:'',captchaUrl:'',error:'',busy:false,result:null }; },
  computed: { recovering() { return this.$route.path === '/recover'; } },
  created() { this.prepareIdentityForm(); },
  beforeDestroy() { this.secret=''; this.form.password=''; this.form.recoveryCode=''; this.result=null; if (this.captchaUrl) URL.revokeObjectURL(this.captchaUrl); },
  methods: {
    async prepareIdentityForm() {
      if (!await this.loadIdentityPolicy()) {
        this.secret=''; this.form.password=''; this.form.recoveryCode=''; this.form.otpCode='';
        return;
      }
      this.$store.dispatch('fetchPubConfig');
      try { if (!this.secret) this.secret=generateTotpSecret(); } catch (error) { this.error=error.message; }
      this.captcha();
    },
    captcha() { this.captchaId=getUUID(); this.form.captcha=''; Api.user.getCaptcha(this.captchaId,response=>{ if(this.captchaUrl) URL.revokeObjectURL(this.captchaUrl); this.captchaUrl=URL.createObjectURL(response.data); }); },
    async submit() {
      if(this.busy) return;
      if(!this.identityPolicyAvailable) { this.error=this.identityPolicyNotice; return; }
      if(!/^[a-zA-Z0-9_.@-]{3,100}$/.test(this.form.username) || this.form.password.length<14 || this.form.password.length>128 || !/[A-Z]/.test(this.form.password) || !/[a-z]/.test(this.form.password) || !/\d/.test(this.form.password)) { this.error='账号需 3–100 位字母、数字或 _.@-；密码需 14–128 位并包含大小写字母及数字'; return; }
      if(!/^\d{6}$/.test(this.form.otpCode) || !this.form.captcha || (this.recovering && !this.form.recoveryCode)) { this.error='请填写验证信息'; return; }
      this.busy=true; this.error='';
      try { const password=sm2Encrypt(this.$store.state.pubConfig.sm2PublicKey,this.form.captcha+this.form.password); this.result=await platformRequest('POST',this.recovering?'/ops/auth/recovery-request':'/ops/auth/enroll',{username:this.form.username,password,captchaId:this.captchaId,totpSecret:this.secret,otpCode:this.form.otpCode,...(this.recovering?{recoveryCode:this.form.recoveryCode}:{})}); this.secret=''; this.form.password=''; this.form.recoveryCode=''; this.form.otpCode=''; } catch (error) { this.error=error.message; await this.prepareIdentityForm(); } finally { this.busy=false; }
    },
  },
};
</script>
<style scoped>.enrollment-card{width:min(610px,92vw);margin:35px auto}.totp-secret{display:block;padding:16px;margin:12px 0;background:#eef3f8;color:#173c60;overflow-wrap:anywhere;letter-spacing:1px;user-select:all;line-height:1.8}</style>
