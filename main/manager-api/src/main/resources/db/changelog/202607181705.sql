ALTER TABLE `ai_agent_feishu_config`
  ADD COLUMN `message_broadcast_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否在默认群收到新消息时向在线设备播报' AFTER `message_read_enabled`,
  ADD COLUMN `event_verification_token_ciphertext` text DEFAULT NULL COMMENT '飞书事件订阅Verification Token密文' AFTER `message_broadcast_enabled`;
