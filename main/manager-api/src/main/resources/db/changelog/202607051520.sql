SET @doubao_big_template = 'TTS_DOUBAO_BIGTTS_STREAM';
SET @doubao_seed_template = 'TTS_DOUBAO_SEED_TTS_2_STREAM';

UPDATE `ai_model_template_field`
SET
  `field_label` = '音色 Voice_type',
  `input_type` = 'select',
  `options_json` = JSON_ARRAY(
    'zh_female_wanwanxiaohe_moon_bigtts',
    'zh_female_shuangkuaisisi_moon_bigtts',
    'zh_male_wennuanahu_moon_bigtts',
    'zh_male_shaonianzixin_moon_bigtts',
    'zh_female_linjianvhai_moon_bigtts',
    'zh_male_yangguangqingnian_moon_bigtts',
    'zh_male_jingqiangkanye_moon_bigtts',
    'zh_female_gaolengyujie_moon_bigtts',
    'zh_female_meilinvyou_moon_bigtts'
  ),
  `help_text` = '选择服务 10029 已开通音色的 Voice_type。角色配置中的声音音色可覆盖这里的默认音色。'
WHERE `template_id` = @doubao_big_template
  AND `field_key` = 'speaker';

UPDATE `ai_model_template_field`
SET
  `field_label` = '音色 Voice_type',
  `input_type` = 'select',
  `options_json` = JSON_ARRAY(
    'zh_female_sophie_uranus_bigtts',
    'zh_female_xiaohe_uranus_bigtts',
    'zh_female_vv_uranus_bigtts',
    'zh_male_yangguangqingnian_uranus_bigtts',
    'zh_male_m191_uranus_bigtts',
    'zh_male_shaonianzixin_uranus_bigtts',
    'zh_female_qingxinnvsheng_uranus_bigtts',
    'zh_female_cancan_uranus_bigtts',
    'zh_female_linjianvhai_uranus_bigtts',
    'zh_female_shuangkuaisisi_uranus_bigtts'
  ),
  `help_text` = '选择服务 10035 已开通音色的 Voice_type。角色配置中的声音音色可覆盖这里的默认音色。'
WHERE `template_id` = @doubao_seed_template
  AND `field_key` = 'speaker';

UPDATE `ai_model_template_field`
SET
  `field_label` = '语速(-50~100)',
  `help_text` = '范围 -50 到 100，0 为默认语速。'
WHERE `template_id` IN (@doubao_big_template, @doubao_seed_template)
  AND `field_key` = 'speech_rate';

UPDATE `ai_model_template_field`
SET
  `field_label` = '音量(-50~100)',
  `help_text` = '范围 -50 到 100，0 为默认音量。'
WHERE `template_id` IN (@doubao_big_template, @doubao_seed_template)
  AND `field_key` = 'loudness_rate';

UPDATE `ai_model_template_field`
SET
  `field_label` = '音高(-12~12)',
  `help_text` = '范围 -12 到 12，0 为默认音高。'
WHERE `template_id` IN (@doubao_big_template, @doubao_seed_template)
  AND `field_key` = 'pitch';

UPDATE `ai_model_provider`
SET
  `fields` = JSON_ARRAY(
    JSON_OBJECT('key', 'api_key', 'type', 'string', 'label', 'API Key'),
    JSON_OBJECT('key', 'speaker', 'type', 'string', 'label', '音色 Voice_type'),
    JSON_OBJECT('key', 'speech_rate', 'type', 'number', 'label', '语速(-50~100)', 'default', 0),
    JSON_OBJECT('key', 'loudness_rate', 'type', 'number', 'label', '音量(-50~100)', 'default', 0),
    JSON_OBJECT('key', 'pitch', 'type', 'number', 'label', '音高(-12~12)', 'default', 0)
  ),
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = 'SYSTEM_TTS_HSDSTTS_V2';
