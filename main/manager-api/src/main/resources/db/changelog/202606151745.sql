delete from `sys_params` where param_code = 'server.vision_explain';
INSERT INTO `sys_params` (id, param_code, param_value, value_type, param_type, remark)
VALUES (123, 'server.vision_explain', 'null', 'string', 1, '视觉分析接口地址，用于下发到设备，例如 http://192.168.1.100:8003/mcp/vision/explain');
