const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { describe, test } = require('node:test');

describe('optional dynamic knowledge sources', () => {
  test('are configured inside one selected knowledge base only', () => {
    const page = fs.readFileSync(
      path.resolve(__dirname, '../src/views/KnowledgeFileUpload.vue'), 'utf8');
    const panel = fs.readFileSync(
      path.resolve(__dirname, '../src/components/knowledge/KnowledgeSourcePanel.vue'), 'utf8');

    assert.match(page, /:dataset-id="datasetId"/);
    assert.match(page, /动态数据源/);
    assert.match(panel, /只有这里明确添加并启用的数据源才会定时同步/);
    assert.match(panel, /新版本完成切片后才会替换旧版本/);
    assert.match(panel, /syncKnowledgeSource\(this\.datasetId, row\.id/);
  });

  test('does not expose credentials in the connector editor', () => {
    const panel = fs.readFileSync(
      path.resolve(__dirname, '../src/components/knowledge/KnowledgeSourcePanel.vue'), 'utf8');
    assert.equal(/api.?key|password|credential/i.test(panel.replace(/禁止在 URL 中放 API Key 或 Token。/, '')), false);
  });
});
