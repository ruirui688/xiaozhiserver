import { hasPermission } from '@/security/session';

export const operationsNavigation = [
  { path: '/approvals', label: '发布审批', icon: 'el-icon-document-checked', permissions: ['ota.publish', 'ota.approve', 'official.resource.publish', 'official.resource.approve', 'model.catalog.publish', 'model.catalog.approve'] },
  { path: '/services', label: '额度与凭据', icon: 'el-icon-wallet', permissions: ['allowance.read', 'pool.read', 'credentials.read', 'rates.read', 'allowance.review'] },
  { path: '/model-billing', label: '供应商成本', icon: 'el-icon-pie-chart', permissions: ['supplier.bill.read'] },
  { path: '/model-config', label: '模型服务目录', icon: 'el-icon-cpu', permissions: ['model.catalog.read'] },
  { path: '/official-resources', label: '官方资源', icon: 'el-icon-collection', permissions: ['official.resource.read'] },
  { path: '/ota-management', label: '固件发布', icon: 'el-icon-upload', permissions: ['ota.read'] },
  { path: '/function-plugin-management', label: '工具与提示词', icon: 'el-icon-set-up', permissions: ['prompt.catalog.read'] },
  { path: '/audit', label: '操作审计', icon: 'el-icon-document', permissions: ['audit.read'] },
  { path: '/iam', label: '岗位与授权', icon: 'el-icon-lock', permissions: ['iam.read'] },
  { path: '/params-management', label: '系统配置', icon: 'el-icon-setting', permissions: ['system.read'] },
  { path: '/server-side-management', label: '服务状态', icon: 'el-icon-monitor', permissions: ['system.read'] },
];
export function availableOperations(profile) {
  return operationsNavigation.filter(item => item.permissions.some(permission => hasPermission(profile, permission)));
}
export function canVisitOperations(profile, path) {
  const item = operationsNavigation.find(item => item.path === path);
  return profile?.audience === 'OPS' && (path === '/home' || Boolean(item && item.permissions.some(permission => hasPermission(profile, permission))));
}
