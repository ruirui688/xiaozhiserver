-- 新增独立 SLM 供应器与默认配置，模型需在页面配置并测试通过后再启用
DELETE FROM `ai_model_provider` WHERE `id` IN ('SYSTEM_SLM_openai', 'SYSTEM_SLM_ollama', 'SYSTEM_SLM_xinference');
INSERT INTO `ai_model_provider` (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`) VALUES
('SYSTEM_SLM_openai', 'SLM', 'openai', 'OpenAI兼容小参数模型', '[{"key":"base_url","label":"基础URL","type":"string"},{"key":"model_name","label":"模型名称","type":"string"},{"key":"api_key","label":"API密钥","type":"string"},{"key":"temperature","label":"温度","type":"number"},{"key":"max_tokens","label":"最大令牌数","type":"number"},{"key":"top_p","label":"top_p值","type":"number"},{"key":"top_k","label":"top_k值","type":"number"},{"key":"frequency_penalty","label":"频率惩罚","type":"number"}]', 1, 1, NOW(), 1, NOW()),
('SYSTEM_SLM_ollama', 'SLM', 'ollama', 'Ollama小参数模型', '[{"key":"model_name","label":"模型名称","type":"string"},{"key":"base_url","label":"服务地址","type":"string"}]', 2, 1, NOW(), 1, NOW()),
('SYSTEM_SLM_xinference', 'SLM', 'xinference', 'Xinference小参数模型', '[{"key":"model_name","label":"模型名称","type":"string"},{"key":"base_url","label":"服务地址","type":"string"}]', 3, 1, NOW(), 1, NOW());

DELETE FROM `ai_model_config` WHERE `id` IN ('SLM_ChatGLMSLM', 'SLM_OllamaSLM', 'SLM_XinferenceSmallSLM');
INSERT INTO `ai_model_config` (`id`, `model_type`, `model_code`, `model_name`, `is_default`, `is_enabled`, `test_status`, `test_message`, `test_time`, `config_json`, `doc_link`, `remark`, `sort`, `creator`, `create_date`, `updater`, `update_date`) VALUES
('SLM_ChatGLMSLM', 'SLM', 'ChatGLMSLM', '智谱AI小参数模型', 1, 0, 0, NULL, NULL, '{\"type\":\"openai\",\"model_name\":\"glm-4-flash\",\"base_url\":\"https://open.bigmodel.cn/api/paas/v4/\",\"api_key\":\"你的api_key\"}', NULL, NULL, 1, 1, NOW(), 1, NOW()),
('SLM_OllamaSLM', 'SLM', 'OllamaSLM', 'Ollama本地小模型', 0, 0, 0, NULL, NULL, '{\"type\":\"ollama\",\"model_name\":\"qwen2.5:3b\",\"base_url\":\"http://localhost:11434\"}', NULL, NULL, 2, 1, NOW(), 1, NOW()),
('SLM_XinferenceSmallSLM', 'SLM', 'XinferenceSmallSLM', 'Xinference小模型', 0, 0, 0, NULL, NULL, '{\"type\":\"xinference\",\"model_name\":\"qwen2.5:3b-AWQ\",\"base_url\":\"http://localhost:9997\"}', NULL, NULL, 3, 1, NOW(), 1, NOW());
