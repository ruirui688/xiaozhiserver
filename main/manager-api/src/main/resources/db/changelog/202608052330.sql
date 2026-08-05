-- 修复历史工作空间邀请占位：过期邀请、已成为成员的邀请以及同一用户的重复邀请不再占用名额。
UPDATE `sys_workspace_invitation`
SET `status` = 'EXPIRED', `updated_at` = NOW()
WHERE `status` = 'PENDING' AND `expires_at` <= NOW();

UPDATE `sys_workspace_invitation` invitation
JOIN `sys_workspace_member` member
  ON member.`workspace_id` = invitation.`workspace_id`
 AND member.`user_id` = invitation.`invitee_user_id`
 AND member.`status` = 1
SET invitation.`status` = 'REVOKED', invitation.`updated_at` = NOW()
WHERE invitation.`status` = 'PENDING';

UPDATE `sys_workspace_invitation` invitation
JOIN (
  SELECT `id`
  FROM (
    SELECT `id`, ROW_NUMBER() OVER (
      PARTITION BY `workspace_id`, `invitee_user_id`
      ORDER BY `created_at` DESC, `id` DESC
    ) AS invitation_rank
    FROM `sys_workspace_invitation`
    WHERE `status` = 'PENDING' AND `expires_at` > NOW()
  ) ranked
  WHERE ranked.invitation_rank > 1
) duplicate_invitation ON duplicate_invitation.`id` = invitation.`id`
SET invitation.`status` = 'REVOKED', invitation.`updated_at` = NOW();

-- 生产历史行曾发生 UTF-8 字节被错误解码后再次写入的问题。
-- 只修复保留 ID 的系统默认模板，以及仍与已知污染提示词逐字节一致的智能体，避免覆盖用户自定义内容。
UPDATE `ai_agent`
SET `system_prompt` = '你是小智，一个友好、自然、简洁的语音助手。请优先使用适合语音播报的简短表达。',
    `updated_at` = NOW()
WHERE HEX(`system_prompt`) = 'C3A4C2BDC2A0C3A6CB9CC2AFC3A5C2B0C28FC3A6E284A2C2BAC3AFC2BCC592C3A4C2B8E282ACC3A4C2B8C2AAC3A5C28FE280B9C3A5C2A5C2BDC3A3E282ACC281C3A8E280A1C2AAC3A7E2809EC2B6C3A3E282ACC281C3A7C2AEE282ACC3A6C2B4C281C3A7C5A1E2809EC3A8C2AFC2ADC3A9C5B8C2B3C3A5C5A0C2A9C3A6E280B0E280B9C3A3E282ACE2809AC3A8C2AFC2B7C3A4C2BCCB9CC3A5E280A6CB86C3A4C2BDC2BFC3A7E2809DC2A8C3A9E282ACE2809AC3A5C290CB86C3A8C2AFC2ADC3A9C5B8C2B3C3A6E28099C2ADC3A6C5A0C2A5C3A7C5A1E2809EC3A7C2AEE282ACC3A7C5B8C2ADC3A8C2A1C2A8C3A8C2BEC2BEC3A3E282ACE2809A';

UPDATE `ai_agent_template`
SET `template_name` = '系统默认角色',
    `agent_name` = '小智',
    `system_prompt` = '你是小智，一个友好、自然、简洁的语音助手。请优先使用适合语音播报的简短表达。',
    `updated_at` = NOW()
WHERE `id` = 'system_default_template_20260804';

-- 设备断开或页面关闭遗留的旧采集任务进入明确终态，后续新任务由定时清理继续兜底。
UPDATE `ai_agent_voice_print_enrollment`
SET `status` = 'EXPIRED', `update_date` = NOW()
WHERE `status` = 'COLLECTING'
  AND `create_date` < DATE_SUB(NOW(), INTERVAL 2 MINUTE);
