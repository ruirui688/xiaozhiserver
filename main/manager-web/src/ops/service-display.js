export const serviceTabs = [
  { id: 'allowance', label: '用户额度', permissions: ['allowance.read', 'pool.read'] },
  { id: 'credentials', label: '模型密钥', permissions: ['credentials.read'] },
  { id: 'rates', label: '服务定价', permissions: ['rates.read'] },
  { id: 'review', label: '用量复核', permissions: ['allowance.review'] },
  { id: 'tasks', label: '平台任务', permissions: ['allowance.read', 'pool.read', 'credentials.read'] },
];

export function exactUserId(value) {
  const text = typeof value === 'string' ? value : Number.isSafeInteger(value) ? String(value) : '';
  return /^\d+$/.test(text) && /[1-9]/.test(text) ? text : '';
}

export function accountName(row) {
  return (typeof row.username === 'string' && row.username.trim()) || exactUserId(row.userId) || '未知用户';
}

export function accountLabel(row) {
  const id = exactUserId(row.userId);
  return typeof row.username === 'string' && row.username.trim() ? `${row.username.trim()} · ${id}` : id;
}

export function mergeAccountOptions(rows) {
  return [...new Map(rows.filter(row => exactUserId(row.userId)).map(row => [exactUserId(row.userId), row])).values()];
}
