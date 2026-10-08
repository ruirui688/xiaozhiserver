// The text editor accepts one word per line, optionally followed by |weight.
export function parseHotwordText(text) {
  const lines = String(text || '').split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  if (!lines.length) throw new Error('请至少输入一个热词');
  if (lines.length > 5000) throw new Error('每个词库最多 5000 个热词');
  const seen = new Set();
  return lines.map((line, index) => {
    const parts = line.split('|');
    const word = parts[0].trim();
    const weight = parts.length === 1 ? 4 : Number(parts[1].trim());
    if (parts.length > 2 || !word || /[\t\r\n]/.test(word)) {
      throw new Error(`第 ${index + 1} 行词条格式不正确`);
    }
    if (new TextEncoder().encode(word).length > 30) {
      throw new Error(`第 ${index + 1} 行热词过长，请缩短（通常最多 10 个汉字或 30 个英文字母）`);
    }
    if (!Number.isInteger(weight) || weight < 1 || weight > 10 || (parts.length === 2 && !parts[1].trim())) {
      throw new Error(`第 ${index + 1} 行权重须为 1 至 10 的整数`);
    }
    if (seen.has(word)) throw new Error(`第 ${index + 1} 行存在重复热词`);
    seen.add(word);
    return { word, weight };
  });
}

export function formatHotwordEntries(entries) {
  return (entries || []).map(entry => `${entry.word}|${entry.weight}`).join('\n');
}

export function hotwordStatus(status) {
  return ({ DRAFT: '草稿待发布', PUBLISHED: '已发布', PUBLISHING: '发布中', DELETING: '删除中', FAILED: '发布失败', DIRTY: '修改待发布' })[status] || status || '草稿待发布';
}
