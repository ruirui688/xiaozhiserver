ALTER TABLE `ai_reception_workspace_config`
  ADD COLUMN `stage_show_json` JSON NULL COMMENT '舞台位置、返回位置和表演动作' AFTER `seats_json`;
