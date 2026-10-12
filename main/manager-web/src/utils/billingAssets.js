export function assetObservations(asset = {}) { return Array.isArray(asset.sourceObservations) ? asset.sourceObservations : []; }
export function primaryObservation(asset = {}) {
  const rows = assetObservations(asset);
  return rows.find(row => row.availableAmount !== null && row.availableAmount !== undefined && row.availableAmount !== '') || rows[0] || {};
}
export function assetProduct(asset = {}) { const row = primaryObservation(asset); return asset.product || row.productName || row.product || '产品待确认'; }
export function assetState(asset = {}) {
  const row = primaryObservation(asset);
  const alerts = [...(asset.alerts || []), ...(row.alerts || [])].map(item => item.code);
  if (alerts.includes('EXPIRED') || ['EXPIRED', 'Expired'].includes(row.status)) return 'expired';
  if (alerts.includes('DEPLETED') || ['EXHAUSTED', 'UsedUp'].includes(row.status)) return 'exhausted';
  return 'current';
}
export function filterAssets(assets, product, status = 'current') {
  return assets.filter(asset => (!product || assetProduct(asset) === product) && (status === 'all' || assetState(asset) === status));
}
