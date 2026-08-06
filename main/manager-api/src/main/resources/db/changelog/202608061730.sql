ALTER TABLE `ai_device`
  ADD COLUMN `speech_input_mode` VARCHAR(16) NOT NULL DEFAULT 'server' COMMENT '语音输入路由: server/device/auto' AFTER `config_version`,
  ADD COLUMN `speech_output_mode` VARCHAR(16) NOT NULL DEFAULT 'server' COMMENT '语音输出路由: server/device/auto' AFTER `speech_input_mode`,
  ADD CONSTRAINT `chk_ai_device_speech_input_mode`
    CHECK (`speech_input_mode` IN ('server', 'device', 'auto')),
  ADD CONSTRAINT `chk_ai_device_speech_output_mode`
    CHECK (`speech_output_mode` IN ('server', 'device', 'auto'));
