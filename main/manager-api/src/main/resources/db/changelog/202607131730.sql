CREATE TABLE IF NOT EXISTS `ai_agent_voice_print_sample` (
  `id` varchar(32) NOT NULL COMMENT '声纹样本ID',
  `voice_print_id` varchar(32) NOT NULL COMMENT '声纹ID',
  `agent_id` varchar(32) NOT NULL COMMENT '关联的智能体ID',
  `audio_id` varchar(32) NOT NULL COMMENT '注册样本音频ID',
  `duration_ms` bigint DEFAULT NULL COMMENT '样本音频时长毫秒',
  `create_date` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voice_print_sample_audio` (`voice_print_id`, `audio_id`),
  KEY `idx_voice_print_sample_voice_print_id` (`voice_print_id`),
  KEY `idx_voice_print_sample_agent_id` (`agent_id`),
  KEY `idx_voice_print_sample_audio_id` (`audio_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体声纹注册样本表';

INSERT INTO `ai_agent_voice_print_sample` (
  `id`,
  `voice_print_id`,
  `agent_id`,
  `audio_id`,
  `duration_ms`,
  `create_date`
)
SELECT
  REPLACE(UUID(), '-', ''),
  `id`,
  `agent_id`,
  `audio_id`,
  NULL,
  `create_date`
FROM `ai_agent_voice_print`
WHERE `audio_id` IS NOT NULL
  AND `audio_id` <> '';
