UPDATE `ai_model_provider`
SET `fields` = '[{"key":"max_chunk_chars","type":"string","label":"切片最大字符数","default":"1200"},{"key":"llm_enabled","type":"boolean","label":"启用大模型切片","default":false},{"key":"llm_base_url","type":"string","label":"OpenAI兼容接口地址","default":"https://dashscope.aliyuncs.com/compatible-mode/v1"},{"key":"llm_api_key","type":"password","label":"模型API密钥","default":""},{"key":"llm_model","type":"string","label":"切片模型","default":"qwen-plus"},{"key":"llm_timeout_seconds","type":"number","label":"单次模型超时秒数","default":90},{"key":"llm_max_input_chars","type":"number","label":"单次送入模型最大字符数","default":12000},{"key":"llm_max_tokens","type":"number","label":"模型输出最大Token数","default":4000}]',
    `update_date` = NOW()
WHERE `id` = 'SYSTEM_RAG_local_knowledge';

UPDATE `ai_model_config`
SET `config_json` = '{"type":"local_knowledge","max_chunk_chars":1200,"llm_enabled":false,"llm_base_url":"https://dashscope.aliyuncs.com/compatible-mode/v1","llm_api_key":"","llm_model":"qwen-plus","llm_timeout_seconds":90,"llm_max_input_chars":12000,"llm_max_tokens":4000}',
    `remark` = '本地知识库适配器：支持上传 txt/md/csv/xlsx/json/jsonl 和问答对文本。配置 llm_enabled=true 并填写阿里云百炼/DashScope 等 OpenAI 兼容接口密钥后，可由大模型按语义结构生成高质量切片。',
    `update_date` = NOW()
WHERE `id` = 'RAG_LocalKnowledge'
  AND (`config_json` IS NULL OR `config_json` NOT LIKE '%"llm_enabled"%');
