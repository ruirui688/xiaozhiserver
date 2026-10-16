// Login destinations only. Permissions always come from the authenticated server profile.
export const operationsIdentities = Object.freeze([
  { username: 'ops-keys', title: '模型与额度', description: '模型通道、用户额度、供应商财务' },
  { username: 'ops-resources', title: '内容与设备', description: '官方资源、工具提示词、设备固件' },
  { username: 'ops-finance', title: '审核与审计', description: '发布审核、用量复核、操作审计' },
].map(identity => Object.freeze(identity)));
