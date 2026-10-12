import { hasPermission } from '@/security/session';

export const operationsNavigation = [
  { path: '/services', label: '额度与密钥', icon: 'el-icon-wallet', permissions: ['allowance.read', 'pool.read', 'credentials.read', 'rates.read', 'allowance.review'] },
  { path: '/model-config', label: '模型目录', description: '维护模型配置与服务发布', icon: 'el-icon-cpu', permissions: ['model.catalog.read'] },
  { path: '/model-billing', label: '供应商成本', description: '核对官方账单、余额与资源包', icon: 'el-icon-pie-chart', permissions: ['supplier.bill.read'] },
  { path: '/official-resources', label: '官方资源', description: '维护角色模板与官方知识库', icon: 'el-icon-collection', permissions: ['official.resource.read'] },
  { path: '/function-plugin-management', label: '工具与提示词', description: '维护工具目录与提示词', icon: 'el-icon-set-up', permissions: ['prompt.catalog.read'] },
  { path: '/ota-management', label: '固件发布', description: '准备与管理设备固件版本', icon: 'el-icon-upload', permissions: ['ota.read'] },
  { path: '/approvals', label: '发布审批', description: '查看申请并核对发布内容', icon: 'el-icon-document-checked', permissions: ['ota.publish', 'ota.approve', 'official.resource.publish', 'official.resource.approve', 'model.catalog.publish', 'model.catalog.approve'] },
  { path: '/audit', label: '操作审计', description: '查询员工操作记录', icon: 'el-icon-document', permissions: ['audit.read'] },
  { path: '/iam', label: '岗位与授权', description: '管理员工身份与岗位授权', icon: 'el-icon-lock', permissions: ['iam.read'] },
  { path: '/params-management', label: '系统配置', description: '维护服务运行参数', icon: 'el-icon-setting', permissions: ['system.read'] },
  { path: '/server-side-management', label: '服务状态', description: '查看服务端运行状态', icon: 'el-icon-monitor', permissions: ['system.read'] },
];
const serviceTasks = [
  { tab: 'allowance', label: '用户额度', description: '查询与管理平台服务额度', icon: 'el-icon-wallet', permissions: ['allowance.read', 'pool.read'] },
  { tab: 'credentials', label: '模型密钥', description: '查看库存、分配与轮换凭据', icon: 'el-icon-key', permissions: ['credentials.read'] },
  { tab: 'rates', label: '服务定价', description: '查看平台计价规则与版本', icon: 'el-icon-tickets', permissions: ['rates.read'] },
  { tab: 'tasks', label: '平台任务', description: '查看后台任务使用的服务账户', icon: 'el-icon-cpu', permissions: ['allowance.read', 'pool.read', 'credentials.read'] },
  { tab: 'review', label: '用量复核', description: '处理需要人工核对的调用', icon: 'el-icon-document-checked', permissions: ['allowance.review'] },
];
export function availableOperations(profile) {
  return operationsNavigation.filter(item => item.permissions.some(permission => hasPermission(profile, permission)))
    .map(item => item.path === '/services' && !['allowance.read', 'credentials.read', 'rates.read'].some(permission => hasPermission(profile, permission))
      ? { ...item, label: hasPermission(profile, 'pool.read') ? '额度总池' : '用量复核' } : item);
}
export function availableOperationsTasks(profile) {
  const services = serviceTasks.filter(item => item.permissions.some(permission => hasPermission(profile, permission)))
    .map(item => ({ ...item, ...(item.tab === 'allowance' && !hasPermission(profile, 'allowance.read') ? { label: '额度总池', description: '查看额度总量、已发放与可发放额度' } : {}), key: item.tab, to: { path: '/services', query: { tab: item.tab } } }));
  const common = ['allowance', 'credentials', '/model-config', 'rates', '/model-billing', '/approvals', 'review', '/official-resources', '/ota-management', '/function-plugin-management', '/audit', '/iam', '/params-management', '/server-side-management', 'tasks'];
  const preferred = hasPermission(profile, 'supplier.bill.read') ? ['/model-billing', 'review', '/audit', '/approvals']
    : hasPermission(profile, 'official.resource.read') ? ['/official-resources', '/ota-management', '/function-plugin-management', '/approvals', '/params-management', '/server-side-management'] : [];
  const order = [...preferred, ...common.filter(key => !preferred.includes(key))];
  return [...services, ...availableOperations(profile).filter(item => item.path !== '/services').map(item => ({ ...item, key: item.path, to: item.path }))]
    .sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key));
}
export function canVisitOperations(profile, path) {
  const item = operationsNavigation.find(item => item.path === path);
  return profile?.audience === 'OPS' && (path === '/home' || Boolean(item && item.permissions.some(permission => hasPermission(profile, permission))));
}
