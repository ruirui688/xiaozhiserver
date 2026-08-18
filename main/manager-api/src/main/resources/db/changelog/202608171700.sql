CREATE TABLE `ai_reception_workspace_config` (
  `id` VARCHAR(32) NOT NULL,
  `workspace_id` VARCHAR(32) NOT NULL,
  `company_name` VARCHAR(120) NULL,
  `company_intro_text` VARCHAR(500) NULL,
  `faq_json` JSON NULL COMMENT '版本化常见问答数组',
  `products_json` JSON NULL COMMENT '版本化餐品目录数组',
  `order_qr_content` VARCHAR(1024) NULL COMMENT '点餐二维码内容',
  `service_contact_text` VARCHAR(200) NULL,
  `config_version` BIGINT NOT NULL DEFAULT 1,
  `status` TINYINT NOT NULL DEFAULT 1,
  `creator` BIGINT NULL,
  `updater` BIGINT NULL,
  `create_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reception_workspace_config` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间接待配置';
