export function categoryLabel(value) {
  return value || '未分类'
}

export function memoryTypeLabel(value) {
  return value || '长期记忆'
}

export function statusLabel(value) {
  if (value === 'active') return '活跃'
  if (value === 'archived') return '已归档'
  if (value === 'deleted') return '已删除'
  if (value === 'pending') return '待确认'
  if (value === 'rejected') return '已拒绝'
  return value || '未知'
}

export function sensitivityLabel(value) {
  if (value === 'sensitive') return '敏感'
  if (value === 'personal') return '个人'
  return '普通'
}

export function tierLabel(value) {
  const v = (value || '').toLowerCase()
  if (v === 'long_term') return '长期记忆'
  if (v === 'short_term') return '短期记忆'
  if (v === 'working') return '工作记忆'
  return value || '标准'
}
