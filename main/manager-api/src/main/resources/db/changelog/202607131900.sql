CREATE TABLE IF NOT EXISTS `ai_agent_voice_print_enrollment` (
  `id` varchar(32) NOT NULL COMMENT '声纹采集任务ID',
  `agent_id` varchar(32) NOT NULL COMMENT '关联的智能体ID',
  `source_name` varchar(50) NOT NULL COMMENT '声纹来源的人姓名',
  `introduce` varchar(200) DEFAULT NULL COMMENT '描述声纹来源的人',
  `device_id` varchar(64) DEFAULT NULL COMMENT '采集设备ID/MAC',
  `required_samples` int NOT NULL DEFAULT 3 COMMENT '目标采集样本数',
  `status` varchar(20) NOT NULL DEFAULT 'COLLECTING' COMMENT '采集任务状态',
  `voice_print_id` varchar(32) DEFAULT NULL COMMENT '完成注册后的声纹ID',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `create_date` datetime DEFAULT NULL COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `update_date` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_voice_print_enrollment_agent_status` (`agent_id`, `status`),
  KEY `idx_voice_print_enrollment_device` (`device_id`),
  KEY `idx_voice_print_enrollment_voice_print` (`voice_print_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体声纹采集任务表';

CREATE TABLE IF NOT EXISTS `ai_agent_voice_print_enrollment_sample` (
  `id` varchar(32) NOT NULL COMMENT '声纹采集任务样本ID',
  `enrollment_id` varchar(32) NOT NULL COMMENT '声纹采集任务ID',
  `agent_id` varchar(32) NOT NULL COMMENT '关联的智能体ID',
  `audio_id` varchar(32) NOT NULL COMMENT '注册样本音频ID',
  `create_date` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voice_print_enrollment_sample_audio` (`enrollment_id`, `audio_id`),
  KEY `idx_voice_print_enrollment_sample_enrollment` (`enrollment_id`),
  KEY `idx_voice_print_enrollment_sample_agent` (`agent_id`),
  KEY `idx_voice_print_enrollment_sample_audio` (`audio_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体声纹采集任务样本表';
