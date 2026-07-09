UPDATE `sys_params`
SET `param_value` = JSON_SET(
  `param_value`,
  '$.features.knowledgeBase.enabled',
  CAST('true' AS JSON)
)
WHERE `param_code` = 'system-web.menu'
  AND JSON_VALID(`param_value`)
  AND JSON_CONTAINS_PATH(`param_value`, 'one', '$.features.knowledgeBase.enabled');

UPDATE `sys_params`
SET `param_value` = JSON_SET(
  COALESCE(NULLIF(`param_value`, ''), JSON_OBJECT()),
  '$.features.knowledgeBase',
  JSON_OBJECT(
    'name', 'feature.knowledgeBase.name',
    'enabled', CAST('true' AS JSON),
    'description', 'feature.knowledgeBase.description'
  )
)
WHERE `param_code` = 'system-web.menu'
  AND JSON_VALID(COALESCE(NULLIF(`param_value`, ''), JSON_OBJECT()))
  AND NOT JSON_CONTAINS_PATH(COALESCE(NULLIF(`param_value`, ''), JSON_OBJECT()), 'one', '$.features.knowledgeBase');
