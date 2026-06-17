UPDATE `ai_model_provider`
SET `fields` = '[{"key":"base_url","label":"基础URL","type":"string"},{"key":"model_name","label":"模型名称","type":"string"},{"key":"api_key","label":"API密钥","type":"password"},{"key":"prompt","label":"视觉回复风格提示词","type":"string"},{"key":"max_tokens","label":"最大令牌数","type":"number"},{"key":"temperature","label":"温度","type":"number"},{"key":"top_p","label":"top_p值","type":"number"}]'
WHERE `id` = 'SYSTEM_VLLM_openai';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
  COALESCE(`config_json`, JSON_OBJECT()),
  '$.prompt',
  '你是设备的视觉助手，用自然、口语化的中文回答用户关于图片的问题。先给一句直接结论，再补充2到3个关键细节。不要使用编号、标题、报告格式或“以下是”“画面的主体是”“占据了图像”等生硬表达。如果画面不清楚，要直接说明不确定，只描述能看清的内容。',
  '$.max_tokens',
  180,
  '$.temperature',
  0.4,
  '$.top_p',
  0.9
)
WHERE `id` IN ('VLLM_ChatGLMVLLM', 'VLLM_QwenVLVLLM');
