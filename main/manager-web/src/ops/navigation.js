import { hasPermission } from '@/security/session';

export const operationsNavigation = [
  { path: '/services', label: '模型与额度', icon: 'el-icon-wallet', permissions: ['allowance.read', 'pool.read', 'credentials.read', 'rates.read', 'allowance.review'] },
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
  { tab: 'credentials', label: '供应商通道', description: '配置共享模型通道与供应商密钥', icon: 'el-icon-connection', permissions: ['credentials.read'] },
  { tab: 'rates', label: '服务定价', description: '查看平台计价规则与版本', icon: 'el-icon-tickets', permissions: ['rates.read'] },
  { tab: 'tasks', label: '平台任务', description: '查看后台任务使用的服务账户', icon: 'el-icon-cpu', permissions: ['allowance.read', 'pool.adjust', 'credentials.read'] },
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
  const persona = operationsPersona(profile).title;
  const preferred = persona === '审核与审计' ? ['/approvals', 'review', '/audit']
    : persona === '内容与设备' ? ['/official-resources', '/ota-management', '/function-plugin-management', '/approvals', '/params-management', '/server-side-management'] : [];
  const order = [...preferred, ...common.filter(key => !preferred.includes(key))];
  return [...services, ...availableOperations(profile).filter(item => item.path !== '/services').map(item => ({ ...item, key: item.path, to: item.path }))]
    .sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key));
}
export function canVisitOperations(profile, path) {
  const item = operationsNavigation.find(item => item.path === path);
  return profile?.audience === 'OPS' && (path === '/home' || Boolean(item && item.permissions.some(permission => hasPermission(profile, permission))));
}

export function operationsPersona(profile) {
  if (['credentials.read', 'allowance.read', 'rates.read', 'model.catalog.read', 'supplier.bill.read', 'pool.read'].some(permission => hasPermission(profile, permission))) return { title: '模型与额度', description: '管理模型通道、用户额度、服务定价与供应商财务' };
  if (['official.resource.read', 'ota.read', 'prompt.catalog.read'].some(permission => hasPermission(profile, permission))) return { title: '内容与设备', description: '维护官方资源、工具提示词与设备固件' };
  if (['allowance.review', 'model.catalog.approve', 'official.resource.approve', 'ota.approve', 'audit.read'].some(permission => hasPermission(profile, permission))) return { title: '审核与审计', description: '审核发布、复核用量与查询操作记录' };
  return { title: '运营工作台', description: '查看当前岗位可处理的工作' };
}

export function operationsPageTitle(profile, route) {
  if (route.path === '/login') return '员工登录 · 智控运营';
  if (['/recover', '/enroll'].includes(route.path)) return '员工账号 · 智控运营';
  const persona = operationsPersona(profile).title;
  const task = route.path === '/services' ? availableOperationsTasks(profile).find(item => item.to?.query?.tab === route.query?.tab) : null;
  const page = task?.label || availableOperations(profile).find(item => item.path === route.path)?.label;
  return [page && page !== persona ? page : '', persona, '智控运营'].filter(Boolean).join(' · ');
}
