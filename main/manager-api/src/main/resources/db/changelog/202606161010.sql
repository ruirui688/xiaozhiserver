UPDATE `ai_model_config`
SET
  `config_json` = JSON_REMOVE(
    JSON_SET(
      COALESCE(`config_json`, JSON_OBJECT()),
      '$.type', 'openai',
      '$.model_name', 'deepseek-v4-flash',
      '$.base_url', 'https://api.deepseek.com',
      '$.thinking', 'disabled',
      '$.reasoning_effort', 'high'
    ),
    '$.url',
    '$.temperature',
    '$.max_tokens',
    '$.top_p',
    '$.top_k',
    '$.frequency_penalty'
  ),
  `doc_link` = 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing',
  `remark` = 'DeepSeek配置说明：
1. 访问 https://platform.deepseek.com/ 注册并获取API密钥
2. 模型名称使用 deepseek-v4-flash
3. thinking 可填 enabled 或 disabled，默认 disabled
4. reasoning_effort 仅在 thinking=enabled 时生效，可填 high 或 max
5. deepseek-chat 与 deepseek-reasoner 将于北京时间 2026-07-24 23:59 弃用'
WHERE `id` = 'LLM_DeepSeekLLM';
