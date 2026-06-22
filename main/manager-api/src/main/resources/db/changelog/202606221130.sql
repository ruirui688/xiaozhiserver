delete from `sys_params` where param_code = 'model.assistant.llm_model_id';
INSERT INTO `sys_params` (id, param_code, param_value, value_type, param_type, remark)
VALUES (620, 'model.assistant.llm_model_id', 'null', 'string', 1, '模型配置智能助手使用的已配置 LLM 模型 ID');
