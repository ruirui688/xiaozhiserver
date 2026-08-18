ALTER TABLE `ai_reception_workspace_config`
  ADD COLUMN `seats_json` JSON NULL COMMENT '语义座位目录' AFTER `products_json`;
