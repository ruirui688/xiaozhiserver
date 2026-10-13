<template>
  <div class="welcome">
    <el-container style="height: 100%">
      <el-header>
        <div style="
            display: flex;
            align-items: center;
            margin-top: 11px;
            margin-left: 11px;
            gap: 10px;
          ">
          <img loading="lazy" alt="" src="@/assets/xiaozhi-logo.png" style="width: 42px; height: 42px" />
          <img loading="lazy" alt="" :src="xiaozhiAiIcon" style="height: 20px" />
        </div>
      </el-header>
      <div class="login-person">
        <img loading="lazy" alt="" src="@/assets/login/login-person.png" style="width: 100%" />
      </div>
      <el-main style="position: relative">
        <div class="login-box" @keyup.enter="login">
          <div style="
              display: flex;
              align-items: center;
              gap: 20px;
              margin-bottom: 39px;
              padding: 0 30px;
            ">
            <img loading="lazy" alt="" src="@/assets/login/hi.png" style="width: 34px; height: 34px" />
            <div class="login-text">{{ $t("login.title") }}</div>

            <div class="login-welcome">
              {{ $t("login.welcome") }}
            </div>

          </div>
          <div style="padding: 0 30px">
            <!-- 用户名登录 -->
            <template v-if="!isMobileLogin">
              <div class="input-box">
                <img loading="lazy" alt="" class="input-icon" src="@/assets/login/username.png" />
                <el-input v-model="form.username" :placeholder="$t('login.usernamePlaceholder')" />
              </div>
            </template>

            <!-- 手机号登录 -->
            <template v-else>
              <div class="input-box">
                <div style="display: flex; align-items: center; width: 100%">
                  <el-select v-model="form.areaCode" style="width: 220px; margin-right: 10px">
                    <el-option v-for="item in mobileAreaList" :key="item.key" :label="`${item.name} (${item.key})`"
                      :value="item.key" />
                  </el-select>
                  <el-input v-model="form.mobile" :placeholder="$t('login.mobilePlaceholder')" />
                </div>
              </div>
            </template>

            <div class="input-box">
              <img loading="lazy" alt="" class="input-icon" src="@/assets/login/password.png" />
              <el-input v-model="form.password" :placeholder="$t('login.passwordPlaceholder')" type="password"
                show-password />
            </div>
            <div style="
                display: flex;
                align-items: center;
                margin-top: 20px;
                width: 100%;
                gap: 10px;
              ">
              <div class="input-box" style="width: calc(100% - 130px); margin-top: 0">
                <img loading="lazy" alt="" class="input-icon" src="@/assets/login/shield.png" />
                <el-input v-model="form.captcha" :placeholder="$t('login.captchaPlaceholder')" style="flex: 1" />
              </div>
              <img loading="lazy" v-if="captchaUrl" :src="captchaUrl" alt="验证码"
                style="width: 150px; height: 40px; cursor: pointer" @click="fetchCaptcha" />
            </div>
            <div style="
                font-weight: 400;
                font-size: 14px;
                text-align: left;
                color: #5778ff;
                display: flex;
                justify-content: space-between;
                margin-top: 20px;
              ">
              <div v-if="allowUserRegister" style="cursor: pointer" @click="goToRegister">
                {{ $t("login.register") }}
              </div>
              <div style="cursor: pointer" @click="goToForgetPassword" v-if="enableMobileRegister">
                {{ $t("login.forgetPassword") }}
              </div>
            </div>
          </div>
          <div class="login-btn" :class="{ disabled: loginSubmitting }" @click="login">{{ $t("login.login") }}</div>

          <!-- 登录方式切换按钮 -->
          <div class="login-type-container" v-if="enableMobileRegister">
            <div style="display: flex; gap: 10px">
              <el-tooltip :content="$t('login.mobileLogin')" placement="bottom">
                <el-button :type="isMobileLogin ? 'primary' : 'default'" icon="el-icon-mobile" circle
                  @click="switchLoginType('mobile')"></el-button>
              </el-tooltip>
              <el-tooltip :content="$t('login.usernameLogin')" placement="bottom">
                <el-button :type="!isMobileLogin ? 'primary' : 'default'" icon="el-icon-user" circle
                  @click="switchLoginType('username')"></el-button>
              </el-tooltip>
            </div>
          </div>
          <div style="font-size: 14px; color: #979db1">
            {{ $t("login.agreeTo") }}
            <div style="display: inline-block; color: #5778ff; cursor: pointer" @click="openPage('/user-agreement.html')">
              {{ $t("login.userAgreement") }}
            </div>
            {{ $t("login.and") }}
            <div style="display: inline-block; color: #5778ff; cursor: pointer" @click="openPage('/privacy-policy.html')">
              {{ $t("login.privacyPolicy") }}
            </div>
          </div>
        </div>
      </el-main>
      <el-footer>
        <version-footer />
      </el-footer>
    </el-container>
  </div>
</template>

<script>
import Api from "@/apis/api";
import VersionFooter from "@/components/VersionFooter.vue";
import { getUUID, goToPage, showDanger, showSuccess, sm2Encrypt, validateMobile } from "@/utils";
import { mapState } from "vuex";
import featureManager from "@/utils/featureManager";
import { resolvePostAuthRedirect } from "@/utils/workspaceInvitation";

export default {
  name: "login",
  components: {
    VersionFooter,
  },
  computed: {
    ...mapState({
      allowUserRegister: (state) => state.pubConfig.allowUserRegister,
      enableMobileRegister: (state) => state.pubConfig.enableMobileRegister,
      mobileAreaList: (state) => state.pubConfig.mobileAreaList,
      sm2PublicKey: (state) => state.pubConfig.sm2PublicKey,
    }),
    xiaozhiAiIcon() {
      return require("@/assets/xiaozhi-ai.png");
    },
  },
  data() {
    return {
      activeName: "username",
      form: {
        username: "",
        password: "",
        captcha: "",
        captchaId: "",
        areaCode: "+86",
        mobile: "",
      },
      captchaUuid: "",
      captchaUrl: "",
      isMobileLogin: false,
      loginSubmitting: false,
    };
  },
  mounted() {
    this.fetchCaptcha();
    this.$store.dispatch("fetchPubConfig").then(() => {
      // 根据配置决定默认登录方式
      this.isMobileLogin = this.enableMobileRegister;
    });
  },
  methods: {
    openPage(url) {
      const lang = this.$i18n ? this.$i18n.locale : 'zh_CN';
      if (!lang.startsWith('zh')) {
        url = url.replace('.html', '-en.html');
      }
      window.open(url, '_blank');
    },
    fetchCaptcha() {
      // 处理手动清空localstorage导致无法获取验证码的问题
      const token = localStorage.getItem('token')
      if (token) {
        this.goAfterAuth();
      } else {
        this.captchaUuid = getUUID();

        Api.user.getCaptcha(this.captchaUuid, (res) => {
          if (res.status === 200) {
            const blob = new Blob([res.data], { type: res.data.type });
            this.captchaUrl = URL.createObjectURL(blob);
          } else {
            showDanger("验证码加载失败，点击刷新");
          }
        });
      }
    },

    // 切换登录方式
    switchLoginType(type) {
      this.isMobileLogin = type === "mobile";
      // 清空表单
      this.form.username = "";
      this.form.mobile = "";
      this.form.password = "";
      this.form.captcha = "";
      this.fetchCaptcha();
    },

    // 封装输入验证逻辑
    validateInput(input, messageKey) {
      if (!input.trim()) {
        showDanger(this.$t(messageKey));
        return false;
      }
      return true;
    },
    
    getUserInfo() {
      Api.user.getUserInfo(({ data }) => {
        if (data.code === 0) {
          this.$store.commit("setUserInfo", data.data);
          this.loginSubmitting = false;
          this.goAfterAuth();
        } else {
          this.loginSubmitting = false;
          showDanger("用户信息获取失败");
        }
      });
    },

    async login() {
      if (this.loginSubmitting) {
        return;
      }
      if (this.isMobileLogin) {
        // 手机号登录验证
        if (!validateMobile(this.form.mobile, this.form.areaCode)) {
          showDanger(this.$t('login.requiredMobile'));
          return;
        }
        // 拼接手机号作为用户名
        this.form.username = this.form.areaCode + this.form.mobile;
      } else {
        // 用户名登录验证
        if (!this.validateInput(this.form.username, 'login.requiredUsername')) {
          return;
        }
      }

      // 验证密码
      if (!this.validateInput(this.form.password, 'login.requiredPassword')) {
        return;
      }
      // 验证验证码
      if (!this.validateInput(this.form.captcha, 'login.requiredCaptcha')) {
        return;
      }
      if (!this.sm2PublicKey) {
        showDanger(this.$t('sm2.publicKeyNotConfigured'));
        return;
      }
      // 加密密码
      let encryptedPassword;
      try {
        // 拼接验证码和密码
        const captchaAndPassword = this.form.captcha + this.form.password;
        encryptedPassword = sm2Encrypt(this.sm2PublicKey, captchaAndPassword);
      } catch (error) {
        console.error("密码加密失败:", error);
        showDanger(this.$t('sm2.encryptionFailed'));
        return;
      }

      const plainUsername = this.form.username;

      this.form.captchaId = this.captchaUuid;

      // 加密
      const loginData = {
        username: plainUsername,
        password: encryptedPassword,
        captchaId: this.form.captchaId
      };

      this.loginSubmitting = true;
      Api.user.login(
        loginData,
        ({ data }) => {
          showSuccess(this.$t('login.loginSuccess'));
          this.$store.commit("setToken", JSON.stringify(data.data));
          this.getUserInfo();
        },
        (err) => {
          this.loginSubmitting = false;
          // 直接使用后端返回的国际化消息
          let errorMessage = (err && err.data && err.data.msg) || "登录失败，请刷新验证码后重试";

          showDanger(errorMessage);
          this.fetchCaptcha();
        }
      );
    },

    goToRegister() {
      this.$router.push({
        path: "/register",
        query: { redirect: this.postAuthRedirect() },
      });
    },
    goToForgetPassword() {
      goToPage("/retrieve-password");
    },
    postAuthRedirect() {
      return resolvePostAuthRedirect(this.$route.query && this.$route.query.redirect);
    },
    goAfterAuth() {
      const target = this.postAuthRedirect();
      if (this.$route.fullPath !== target) goToPage(target, true);
    }
  },
};
</script>
<style lang="scss" scoped>
@import "./auth.scss";

.login-type-container {
  margin: 10px 20px;
  display: flex;
  justify-content: center;
}

.rotate-down {
  transform: rotate(180deg);
  transition: transform 0.3s ease;
}

.el-icon-arrow-down {
  transition: transform 0.3s ease;
}

:deep(.el-button--primary) {
  background-color: #5778ff;
  border-color: #5778ff;

  &:hover,
  &:focus {
    background-color: #4a6ae8;
    border-color: #4a6ae8;
  }

  &:active {
    background-color: #3d5cd6;
    border-color: #3d5cd6;
  }
}
</style>
