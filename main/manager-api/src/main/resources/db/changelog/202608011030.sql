ALTER TABLE `sys_user_token`
  DROP INDEX `user_id`,
  ADD INDEX `idx_sys_user_token_user_id` (`user_id`);
