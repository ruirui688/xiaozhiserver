SET @managed_memory_id = 'Memory_managed_memory';
SET @managed_memory_template = 'MEMORY_MANAGED_GOVERNED';

INSERT INTO `ai_model_provider` (
  `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
  `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  'SYSTEM_Memory_managed_memory',
  'Memory',
  'managed_memory',
  '智控台治理记忆',
  JSON_ARRAY(
    JSON_OBJECT(
      'key', 'maintenance_llm',
      'label', '维护LLM模型ID',
      'type', 'string',
      'help', '可选。填写模型管理中的 LLM 配置 ID，用于长期记忆候选抽取。'
    )
  ),
  5,
  1,
  NOW(),
  1,
  NOW()
)
ON DUPLICATE KEY UPDATE
  `model_type` = VALUES(`model_type`),
  `provider_code` = VALUES(`provider_code`),
  `name` = VALUES(`name`),
  `fields` = VALUES(`fields`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);

INSERT INTO `ai_model_template` (
  `id`, `template_code`, `model_type`, `adapter_code`, `vendor_name`, `product_name`,
  `capability`, `access_mode`, `deployment`, `protocol`, `upstream_model_field`, `doc_link`,
  `enabled`, `sort`, `supported_status`, `selectable`, `hidden`, `deprecated`,
  `recommended_for`, `avoid_when`, `test_contract`, `source_checked_at`,
  `setup_hints_json`, `known_limitations_json`, `aliases_json`
)
VALUES (
  @managed_memory_template,
  @managed_memory_template,
  'Memory',
  'managed_memory',
  '本地系统',
  '智控台治理记忆',
  'memory_governance',
  'manager-api',
  'local',
  'managed-memory',
  '',
  '',
  1,
  5,
  'SUPPORTED',
  1,
  0,
  0,
  '全模块部署、需要可审计长期记忆、管理端确认、忘记名单和权限治理的智能体。',
  '只想接入 PowerMem 本地向量库或第三方记忆服务，且不需要管理端确认审计时。',
  '配置检查通过后由 manager-api /config/memory/** 承担事件、候选、事实、删除和查询链路。',
  '2026-06-29',
  JSON_ARRAY(
    '推荐作为本项目默认长期记忆入口，Python provider 只负责声明模式。',
    '聊天事件上报、候选抽取、事实确认、审计和运行时查询统一走 manager-api 长期记忆层。',
    'maintenance_llm 只保存模型管理里的 LLM 配置 ID，不复制 API Key。'
  ),
  JSON_ARRAY(
    '需要 manager-api 可用并完成长期记忆表迁移。',
    '候选记忆默认需要管理端确认后才进入事实层。',
    '当前不直接内置 PowerMem 的向量/图数据库，后续可在 manager-api 检索层扩展混合召回。'
  ),
  JSON_ARRAY('governed memory', 'managed memory', 'long term memory')
)
ON DUPLICATE KEY UPDATE
  `model_type` = VALUES(`model_type`),
  `adapter_code` = VALUES(`adapter_code`),
  `vendor_name` = VALUES(`vendor_name`),
  `product_name` = VALUES(`product_name`),
  `capability` = VALUES(`capability`),
  `access_mode` = VALUES(`access_mode`),
  `deployment` = VALUES(`deployment`),
  `protocol` = VALUES(`protocol`),
  `upstream_model_field` = VALUES(`upstream_model_field`),
  `doc_link` = VALUES(`doc_link`),
  `enabled` = VALUES(`enabled`),
  `sort` = VALUES(`sort`),
  `supported_status` = VALUES(`supported_status`),
  `selectable` = VALUES(`selectable`),
  `hidden` = VALUES(`hidden`),
  `deprecated` = VALUES(`deprecated`),
  `recommended_for` = VALUES(`recommended_for`),
  `avoid_when` = VALUES(`avoid_when`),
  `test_contract` = VALUES(`test_contract`),
  `source_checked_at` = VALUES(`source_checked_at`),
  `setup_hints_json` = VALUES(`setup_hints_json`),
  `known_limitations_json` = VALUES(`known_limitations_json`),
  `aliases_json` = VALUES(`aliases_json`);

DELETE FROM `ai_model_template_field`
WHERE `template_id` = @managed_memory_template;

INSERT INTO `ai_model_template_field` (
  `template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`,
  `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`,
  `options_json`, `help_text`, `sort`
)
VALUES (
  @managed_memory_template,
  'maintenance_llm',
  '维护 LLM 模型 ID',
  'advanced',
  'string',
  'text',
  'maintenance_llm',
  1,
  NULL,
  0,
  0,
  NULL,
  NULL,
  '可选。填写模型管理中的 LLM 配置 ID，用于把普通用户发言抽取为待确认候选记忆；不要填写 API Key。',
  1
);

INSERT INTO `ai_model_config` (
  `id`, `model_type`, `model_code`, `model_name`, `adapter_code`, `template_code`,
  `vendor_name`, `product_name`, `protocol`, `upstream_model_id`, `endpoint_url`,
  `is_default`, `is_enabled`, `test_status`, `test_message`, `test_time`,
  `config_json`, `doc_link`, `remark`, `sort`, `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @managed_memory_id,
  'Memory',
  'managed_memory',
  '智控台治理记忆',
  'managed_memory',
  @managed_memory_template,
  '本地系统',
  '智控台治理记忆',
  'managed-memory',
  NULL,
  NULL,
  0,
  1,
  1,
  '本地治理型记忆配置，无需外部连通性测试。',
  NOW(),
  JSON_OBJECT('type', 'managed_memory', 'maintenance_llm', ''),
  '',
  '推荐的长期记忆治理入口：保留 PowerMem 的检索/画像思路，同时使用本项目 manager-api 的候选确认、事实审计、忘记名单、设备/智能体作用域和运行时降级能力。',
  5,
  1,
  NOW(),
  1,
  NOW()
)
ON DUPLICATE KEY UPDATE
  `model_type` = VALUES(`model_type`),
  `model_code` = VALUES(`model_code`),
  `model_name` = VALUES(`model_name`),
  `adapter_code` = VALUES(`adapter_code`),
  `template_code` = VALUES(`template_code`),
  `vendor_name` = VALUES(`vendor_name`),
  `product_name` = VALUES(`product_name`),
  `protocol` = VALUES(`protocol`),
  `is_enabled` = VALUES(`is_enabled`),
  `test_status` = VALUES(`test_status`),
  `test_message` = VALUES(`test_message`),
  `test_time` = VALUES(`test_time`),
  `config_json` = JSON_SET(COALESCE(`config_json`, JSON_OBJECT()), '$.type', 'managed_memory'),
  `doc_link` = VALUES(`doc_link`),
  `remark` = VALUES(`remark`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);
