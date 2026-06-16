-- 新增酒类推荐与图片展示插件
INSERT INTO `ai_model_provider` (
  `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
  `creator`, `create_date`, `updater`, `update_date`
)
SELECT
  'SYSTEM_PLUGIN_RECOMMEND_DRINK',
  'Plugin',
  'recommend_drink',
  '酒类推荐与图片展示',
  CAST(JSON_ARRAY(
    JSON_OBJECT(
      'key', 'description',
      'type', 'string',
      'label', '工具描述',
      'default', '当用户询问喝什么、想要酒类推荐、鸡尾酒推荐、某种口味或酒水外观时调用。只能基于本店已配置酒单或本地知识库酒单推荐；禁止编造酒名、价格、外观或库存。'
    ),
    JSON_OBJECT(
      'key', 'drink_menu',
      'type', 'json',
      'label', '本店酒单 JSON',
      'default', JSON_ARRAY()
    ),
    JSON_OBJECT(
      'key', 'dataset_ids',
      'type', 'json',
      'label', '酒单知识库 dataset_ids',
      'default', JSON_ARRAY()
    ),
    JSON_OBJECT(
      'key', 'knowledge_timeout',
      'type', 'string',
      'label', '知识库查询超时秒数',
      'default', '8'
    ),
    JSON_OBJECT(
      'key', 'enable_image_display',
      'type', 'boolean',
      'label', '是否向设备展示图片',
      'default', true
    ),
    JSON_OBJECT(
      'key', 'display_tool_name',
      'type', 'string',
      'label', '设备端图片展示 MCP 工具名',
      'default', 'self_screen_display_image'
    ),
    JSON_OBJECT(
      'key', 'display_timeout',
      'type', 'string',
      'label', '图片展示工具超时秒数',
      'default', '8'
    ),
    JSON_OBJECT(
      'key', 'image_urls',
      'type', 'json',
      'label', '酒水图片 URL 映射',
      'default', JSON_OBJECT()
    ),
    JSON_OBJECT(
      'key', 'default_image_url',
      'type', 'string',
      'label', '默认图片 URL',
      'default', ''
    ),
    JSON_OBJECT(
      'key', 'image_provider',
      'type', 'string',
      'label', '图片生成服务：none / openai',
      'default', 'none'
    ),
    JSON_OBJECT(
      'key', 'openai_api_key',
      'type', 'string',
      'label', 'OpenAI 图片生成 API Key',
      'default', ''
    ),
    JSON_OBJECT(
      'key', 'openai_base_url',
      'type', 'string',
      'label', 'OpenAI 兼容图片接口地址',
      'default', 'https://api.openai.com/v1'
    ),
    JSON_OBJECT(
      'key', 'image_model',
      'type', 'string',
      'label', '图片生成模型',
      'default', 'gpt-image-1'
    ),
    JSON_OBJECT(
      'key', 'image_size',
      'type', 'string',
      'label', '图片尺寸',
      'default', '1024x1024'
    ),
    JSON_OBJECT(
      'key', 'image_cache_dir',
      'type', 'string',
      'label', '图片缓存目录',
      'default', './data/vision'
    )
  ) AS CHAR),
  90,
  0,
  NOW(),
  0,
  NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `ai_model_provider` WHERE `id` = 'SYSTEM_PLUGIN_RECOMMEND_DRINK'
);

UPDATE `ai_model_provider`
SET
  `model_type` = 'Plugin',
  `provider_code` = 'recommend_drink',
  `name` = '酒类推荐与图片展示',
  `fields` = CAST(JSON_ARRAY(
    JSON_OBJECT(
      'key', 'description',
      'type', 'string',
      'label', '工具描述',
      'default', '当用户询问喝什么、想要酒类推荐、鸡尾酒推荐、某种口味或酒水外观时调用。只能基于本店已配置酒单或本地知识库酒单推荐；禁止编造酒名、价格、外观或库存。'
    ),
    JSON_OBJECT(
      'key', 'drink_menu',
      'type', 'json',
      'label', '本店酒单 JSON',
      'default', JSON_ARRAY()
    ),
    JSON_OBJECT(
      'key', 'dataset_ids',
      'type', 'json',
      'label', '酒单知识库 dataset_ids',
      'default', JSON_ARRAY()
    ),
    JSON_OBJECT(
      'key', 'knowledge_timeout',
      'type', 'string',
      'label', '知识库查询超时秒数',
      'default', '8'
    ),
    JSON_OBJECT(
      'key', 'enable_image_display',
      'type', 'boolean',
      'label', '是否向设备展示图片',
      'default', true
    ),
    JSON_OBJECT(
      'key', 'display_tool_name',
      'type', 'string',
      'label', '设备端图片展示 MCP 工具名',
      'default', 'self_screen_display_image'
    ),
    JSON_OBJECT(
      'key', 'display_timeout',
      'type', 'string',
      'label', '图片展示工具超时秒数',
      'default', '8'
    ),
    JSON_OBJECT(
      'key', 'image_urls',
      'type', 'json',
      'label', '酒水图片 URL 映射',
      'default', JSON_OBJECT()
    ),
    JSON_OBJECT(
      'key', 'default_image_url',
      'type', 'string',
      'label', '默认图片 URL',
      'default', ''
    ),
    JSON_OBJECT(
      'key', 'image_provider',
      'type', 'string',
      'label', '图片生成服务：none / openai',
      'default', 'none'
    ),
    JSON_OBJECT(
      'key', 'openai_api_key',
      'type', 'string',
      'label', 'OpenAI 图片生成 API Key',
      'default', ''
    ),
    JSON_OBJECT(
      'key', 'openai_base_url',
      'type', 'string',
      'label', 'OpenAI 兼容图片接口地址',
      'default', 'https://api.openai.com/v1'
    ),
    JSON_OBJECT(
      'key', 'image_model',
      'type', 'string',
      'label', '图片生成模型',
      'default', 'gpt-image-1'
    ),
    JSON_OBJECT(
      'key', 'image_size',
      'type', 'string',
      'label', '图片尺寸',
      'default', '1024x1024'
    ),
    JSON_OBJECT(
      'key', 'image_cache_dir',
      'type', 'string',
      'label', '图片缓存目录',
      'default', './data/vision'
    )
  ) AS CHAR),
  `sort` = 90,
  `update_date` = NOW()
WHERE `id` = 'SYSTEM_PLUGIN_RECOMMEND_DRINK';
