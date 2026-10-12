import { operations } from '@/apis/platform';

const MANAGED_MESSAGE = '员工账号由部署维护人员管理，请联系维护人员';
const RETRY_MESSAGE = '暂时无法确认身份申请与恢复是否可办理，请重试';

// Unknown or failed policy reads never enable an identity change form.
export default {
  data() { return { identityPolicy: null, identityPolicyLoading: false, identityPolicyError: '' }; },
  computed: {
    identityPolicyAvailable() { return this.identityPolicy?.available === true && !this.identityPolicyLoading && !this.identityPolicyError; },
    identityPolicyNotice() {
      if (this.identityPolicyLoading) return '正在确认身份办理方式…';
      if (this.identityPolicyError) return this.identityPolicyError;
      return this.identityPolicy?.message || MANAGED_MESSAGE;
    },
  },
  methods: {
    async loadIdentityPolicy() {
      if (this.identityPolicyLoading) return false;
      this.identityPolicyLoading = true;
      this.identityPolicyError = '';
      this.identityPolicy = null;
      try {
        const policy = await operations.identityPolicy();
        if (!policy || typeof policy.available !== 'boolean') throw new Error('Invalid identity policy');
        this.identityPolicy = { available: policy.available, message: typeof policy.message === 'string' ? policy.message : '' };
        return policy.available;
      } catch (_) {
        this.identityPolicyError = RETRY_MESSAGE;
        return false;
      } finally { this.identityPolicyLoading = false; }
    },
  },
};
