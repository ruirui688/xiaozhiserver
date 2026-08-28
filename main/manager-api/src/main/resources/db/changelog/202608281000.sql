-- 将人物介绍纳入已发布的人脸库快照，确保设备可获得页面维护的背景信息。
ALTER TABLE `ai_person_agent_binding`
  ADD COLUMN `published_description` VARCHAR(200) NULL COMMENT '已发布的人物介绍/背景信息' AFTER `published_group_name`;

UPDATE `ai_person_agent_binding` b
JOIN `ai_person_profile` p ON p.`id` = b.`person_id`
  AND p.`workspace_id` = b.`workspace_id`
SET b.`published_description` = p.`description`
WHERE b.`published_active` = 1;

-- 既有设备需要重新协商一次，才能收到新增的描述字段。
UPDATE `ai_agent_face_release` r
JOIN (
  SELECT DISTINCT `workspace_id`, `agent_id`
  FROM `ai_person_agent_binding`
  WHERE `published_active` = 1
) active ON active.`workspace_id` = r.`workspace_id` AND active.`agent_id` = r.`agent_id`
SET r.`release_version` = r.`release_version` + 1,
    r.`publish_date` = NOW();
