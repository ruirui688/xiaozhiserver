CREATE TABLE `ai_person_profile` (
  `id` VARCHAR(32) NOT NULL COMMENT '人物档案ID',
  `workspace_id` VARCHAR(32) NOT NULL COMMENT '所属工作空间ID',
  `name` VARCHAR(30) NOT NULL COMMENT '姓名',
  `gender` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT '性别：MALE/FEMALE/UNKNOWN',
  `birthday` DATE NULL COMMENT '生日',
  `group_name` VARCHAR(60) NULL COMMENT '分组名称',
  `description` VARCHAR(200) NULL COMMENT '人物介绍',
  `face_file_path` VARCHAR(512) NULL COMMENT '归一化人脸图片相对路径',
  `face_content_type` VARCHAR(64) NULL COMMENT '人脸图片内容类型',
  `face_sha256` CHAR(64) NULL COMMENT '人脸图片SHA-256',
  `face_version` BIGINT NOT NULL DEFAULT 0 COMMENT '人脸资源版本',
  `recognition_action` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '识别后动作：NONE/SPEAK_TEXT',
  `greeting_text` VARCHAR(500) NULL COMMENT '识别后设备播报文本',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用/1启用',
  `creator` BIGINT NULL COMMENT '创建者',
  `updater` BIGINT NULL COMMENT '更新者',
  `create_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_person_profile_workspace_status` (`workspace_id`, `status`),
  KEY `idx_person_profile_workspace_group` (`workspace_id`, `group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间人物档案';

CREATE TABLE `ai_person_device_binding` (
  `id` VARCHAR(32) NOT NULL COMMENT '绑定ID',
  `workspace_id` VARCHAR(32) NOT NULL COMMENT '所属工作空间ID',
  `person_id` VARCHAR(32) NOT NULL COMMENT '人物档案ID',
  `device_id` VARCHAR(32) NOT NULL COMMENT '设备ID',
  `creator` BIGINT NULL COMMENT '创建者',
  `create_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_person_device_binding` (`person_id`, `device_id`),
  KEY `idx_person_device_binding_device` (`device_id`, `person_id`),
  KEY `idx_person_device_binding_workspace` (`workspace_id`),
  CONSTRAINT `fk_person_device_binding_person` FOREIGN KEY (`person_id`) REFERENCES `ai_person_profile` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_person_device_binding_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人物人脸下发设备绑定';

CREATE TABLE `ai_person_recognition_event` (
  `id` VARCHAR(32) NOT NULL COMMENT '事件ID',
  `workspace_id` VARCHAR(32) NOT NULL COMMENT '所属工作空间ID',
  `device_id` VARCHAR(32) NOT NULL COMMENT '识别设备ID',
  `person_id` VARCHAR(32) NOT NULL COMMENT '识别人物ID',
  `device_event_id` VARCHAR(128) NOT NULL COMMENT '设备侧幂等事件ID',
  `confidence` DECIMAL(6,5) NULL COMMENT '识别置信度0到1',
  `occurred_at` DATETIME NULL COMMENT '设备侧发生时间',
  `action_type` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '实际返回动作',
  `create_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务端接收时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_person_recognition_device_event` (`device_id`, `device_event_id`),
  KEY `idx_person_recognition_workspace_time` (`workspace_id`, `create_date`),
  KEY `idx_person_recognition_person_time` (`person_id`, `create_date`),
  CONSTRAINT `fk_person_recognition_person` FOREIGN KEY (`person_id`) REFERENCES `ai_person_profile` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_person_recognition_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地设备人脸识别事件审计';
