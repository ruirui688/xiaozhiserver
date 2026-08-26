CREATE TABLE `ai_person_face_asset` (
  `id` VARCHAR(32) NOT NULL COMMENT '不可变人脸资源ID',
  `workspace_id` VARCHAR(32) NOT NULL COMMENT '所属工作空间ID',
  `person_id` VARCHAR(32) NOT NULL COMMENT '人物档案ID',
  `asset_version` BIGINT NOT NULL COMMENT '人物内单调递增资源版本',
  `file_path` VARCHAR(512) NOT NULL COMMENT '归一化人脸图片相对路径',
  `content_type` VARCHAR(64) NOT NULL DEFAULT 'image/jpeg',
  `sha256` CHAR(64) NOT NULL,
  `creator` BIGINT NULL,
  `create_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_person_face_asset_version` (`person_id`, `asset_version`),
  KEY `idx_person_face_asset_workspace_person` (`workspace_id`, `person_id`),
  CONSTRAINT `fk_person_face_asset_person` FOREIGN KEY (`person_id`)
    REFERENCES `ai_person_profile` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人物不可变人脸资源版本';

ALTER TABLE `ai_person_profile`
  ADD COLUMN `current_face_asset_id` VARCHAR(32) NULL COMMENT '当前管理端草稿人脸资源ID' AFTER `face_version`;

INSERT INTO `ai_person_face_asset` (
  `id`, `workspace_id`, `person_id`, `asset_version`, `file_path`, `content_type`, `sha256`, `creator`, `create_date`
)
SELECT
  LEFT(SHA2(CONCAT('person-face:', p.`id`, ':', p.`face_version`), 256), 32),
  p.`workspace_id`, p.`id`, GREATEST(p.`face_version`, 1), p.`face_file_path`,
  COALESCE(NULLIF(p.`face_content_type`, ''), 'image/jpeg'), p.`face_sha256`, p.`creator`, p.`update_date`
FROM `ai_person_profile` p
WHERE p.`face_file_path` IS NOT NULL AND p.`face_sha256` IS NOT NULL;

UPDATE `ai_person_profile` p
JOIN `ai_person_face_asset` a ON a.`person_id` = p.`id`
  AND a.`asset_version` = GREATEST(p.`face_version`, 1)
SET p.`current_face_asset_id` = a.`id`, p.`face_version` = a.`asset_version`;

CREATE TABLE `ai_person_agent_binding` (
  `id` VARCHAR(32) NOT NULL COMMENT '人物与智能体绑定ID',
  `workspace_id` VARCHAR(32) NOT NULL,
  `person_id` VARCHAR(32) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `desired_active` TINYINT NOT NULL DEFAULT 1 COMMENT '管理端期望绑定状态',
  `published_active` TINYINT NOT NULL DEFAULT 0 COMMENT '最近发布状态',
  `published_person_name` VARCHAR(30) NULL,
  `published_group_name` VARCHAR(60) NULL,
  `published_face_asset_id` VARCHAR(32) NULL,
  `published_recognition_action` VARCHAR(32) NOT NULL DEFAULT 'NONE',
  `published_greeting_text` VARCHAR(500) NULL,
  `creator` BIGINT NULL,
  `updater` BIGINT NULL,
  `create_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_person_agent_binding` (`person_id`, `agent_id`),
  KEY `idx_person_agent_binding_agent_published` (`agent_id`, `published_active`),
  KEY `idx_person_agent_binding_workspace` (`workspace_id`),
  CONSTRAINT `fk_person_agent_binding_person` FOREIGN KEY (`person_id`)
    REFERENCES `ai_person_profile` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_person_agent_binding_agent` FOREIGN KEY (`agent_id`)
    REFERENCES `ai_agent` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人物与智能体人脸库发布绑定';

INSERT INTO `ai_person_agent_binding` (
  `id`, `workspace_id`, `person_id`, `agent_id`, `desired_active`, `published_active`,
  `published_person_name`, `published_group_name`, `published_face_asset_id`,
  `published_recognition_action`, `published_greeting_text`, `creator`, `updater`
)
SELECT
  LEFT(SHA2(CONCAT('person-agent:', p.`id`, ':', d.`agent_id`), 256), 32),
  p.`workspace_id`, p.`id`, d.`agent_id`, 1, IF(a.`id` IS NULL, 0, 1),
  p.`name`, p.`group_name`, a.`id`, p.`recognition_action`, p.`greeting_text`, p.`creator`, p.`updater`
FROM `ai_person_profile` p
JOIN `ai_person_device_binding` b ON b.`person_id` = p.`id` AND b.`workspace_id` = p.`workspace_id`
JOIN `ai_device` d ON d.`id` = b.`device_id` AND d.`workspace_id` = p.`workspace_id`
LEFT JOIN `ai_person_face_asset` a ON a.`id` = p.`current_face_asset_id`
WHERE d.`agent_id` IS NOT NULL AND d.`agent_id` <> ''
GROUP BY p.`id`, d.`agent_id`, p.`workspace_id`, p.`name`, p.`group_name`, a.`id`,
  p.`recognition_action`, p.`greeting_text`, p.`creator`, p.`updater`;

CREATE TABLE `ai_agent_face_release` (
  `id` VARCHAR(32) NOT NULL,
  `workspace_id` VARCHAR(32) NOT NULL,
  `agent_id` VARCHAR(32) NOT NULL,
  `release_version` BIGINT NOT NULL DEFAULT 0 COMMENT '人脸库发布版本',
  `publish_date` DATETIME NULL,
  `creator` BIGINT NULL,
  `updater` BIGINT NULL,
  `create_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_face_release_agent` (`agent_id`),
  KEY `idx_agent_face_release_workspace` (`workspace_id`),
  CONSTRAINT `fk_agent_face_release_agent` FOREIGN KEY (`agent_id`)
    REFERENCES `ai_agent` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体人脸库发布版本';

INSERT INTO `ai_agent_face_release` (
  `id`, `workspace_id`, `agent_id`, `release_version`, `publish_date`, `creator`, `updater`
)
SELECT LEFT(SHA2(CONCAT('agent-face-release:', b.`agent_id`), 256), 32),
  b.`workspace_id`, b.`agent_id`, 1, NOW(), MIN(b.`creator`), MIN(b.`updater`)
FROM `ai_person_agent_binding` b
WHERE b.`published_active` = 1
GROUP BY b.`workspace_id`, b.`agent_id`;
