UPDATE `ai_model_provider`
SET `fields` = '[{"key":"max_chunk_chars","type":"string","label":"切片最大字符数","default":"1200"},{"key":"llm_enabled","type":"boolean","label":"启用大模型切片精修","default":false},{"key":"llm_base_url","type":"string","label":"OpenAI兼容接口地址","default":"https://dashscope.aliyuncs.com/compatible-mode/v1"},{"key":"llm_api_key","type":"password","label":"模型API密钥","default":""},{"key":"llm_model","type":"string","label":"切片精修模型","default":"qwen-plus"},{"key":"llm_timeout_seconds","type":"number","label":"单次模型超时秒数","default":90},{"key":"llm_max_input_chars","type":"number","label":"单次送入模型最大字符数","default":3000},{"key":"llm_max_tokens","type":"number","label":"模型输出最大Token数","default":1200}]',
    `update_date` = NOW()
WHERE `id` = 'SYSTEM_RAG_local_knowledge';

UPDATE `ai_model_config`
SET `config_json` = '{"type":"local_knowledge","max_chunk_chars":1200,"llm_enabled":false,"llm_base_url":"https://dashscope.aliyuncs.com/compatible-mode/v1","llm_api_key":"","llm_model":"qwen-plus","llm_timeout_seconds":90,"llm_max_input_chars":3000,"llm_max_tokens":1200}',
    `remark` = '本地知识库适配器：支持上传 txt/md/csv/xlsx/json/jsonl 和问答对文本。默认使用规则切片；配置 llm_enabled=true 并填写 OpenAI 兼容接口密钥后，可调用阿里云百炼/DashScope 等模型对切片进行高精度清洗。',
    `update_date` = NOW()
WHERE `id` = 'RAG_LocalKnowledge'
  AND (`config_json` IS NULL OR `config_json` NOT LIKE '%"llm_enabled"%');
