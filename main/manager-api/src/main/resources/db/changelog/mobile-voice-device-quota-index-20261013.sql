-- Cover the bounded phone-identity quota lookup without scanning a workspace's physical fleet.
ALTER TABLE ai_device
    ADD INDEX idx_ai_device_workspace_user_board (workspace_id, user_id, board);
