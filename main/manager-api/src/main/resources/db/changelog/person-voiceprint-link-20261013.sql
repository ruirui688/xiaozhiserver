ALTER TABLE `ai_agent_voice_print`
  ADD COLUMN `person_id` VARCHAR(32) NULL COMMENT '关联的人物档案ID；一个人物最多关联一个逻辑声纹' AFTER `workspace_id`,
  ADD UNIQUE KEY `uk_agent_voice_print_person` (`person_id`),
  ADD CONSTRAINT `fk_agent_voice_print_person`
    FOREIGN KEY (`person_id`) REFERENCES `ai_person_profile` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
