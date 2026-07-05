SET @doubao_big_model_id = 'TTS_HuoshanDoubleStreamTTS';
SET @doubao_seed_model_id = 'TTS_HSDSTTS_V2';

INSERT INTO `ai_tts_voice`
(`id`, `tts_model_id`, `name`, `tts_voice`, `languages`, `voice_demo`, `remark`, `reference_audio`, `reference_text`, `sort`, `creator`, `create_date`, `updater`, `update_date`)
VALUES
('TTS_HuoshanDoubleStreamTTS_01', @doubao_big_model_id, '湾湾小何', 'zh_female_wanwanxiaohe_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 1, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_02', @doubao_big_model_id, '爽快思思/Skye', 'zh_female_shuangkuaisisi_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 2, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_03', @doubao_big_model_id, '温暖阿虎/Alvin', 'zh_male_wennuanahu_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 3, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_04', @doubao_big_model_id, '少年梓辛/Brayan', 'zh_male_shaonianzixin_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 4, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_05', @doubao_big_model_id, '邻家女孩', 'zh_female_linjianvhai_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 5, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_06', @doubao_big_model_id, '阳光青年', 'zh_male_yangguangqingnian_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 6, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_07', @doubao_big_model_id, '京腔侃爷/Harmony', 'zh_male_jingqiangkanye_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 7, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_08', @doubao_big_model_id, '高冷御姐', 'zh_female_gaolengyujie_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 8, 1, NOW(), 1, NOW()),
('TTS_HuoshanDoubleStreamTTS_09', @doubao_big_model_id, '魅力女友', 'zh_female_meilinvyou_moon_bigtts', '普通话、英语', NULL, '语音合成大模型服务 10029 可用音色。', NULL, NULL, 9, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_SOPHIE', @doubao_seed_model_id, '魅力苏菲', 'zh_female_sophie_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 1, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_XIAOHE', @doubao_seed_model_id, '湾湾小何', 'zh_female_xiaohe_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 2, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_VIVI', @doubao_seed_model_id, 'Vivi', 'zh_female_vv_uranus_bigtts', '普通话、日语、印尼语、西班牙语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 3, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_YOUTH', @doubao_seed_model_id, '阳光青年', 'zh_male_yangguangqingnian_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 4, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_YUNZHOU', @doubao_seed_model_id, '云舟', 'zh_male_m191_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 5, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_ZIXIN', @doubao_seed_model_id, '少年梓辛/Brayan', 'zh_male_shaonianzixin_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 6, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_QINGXIN', @doubao_seed_model_id, '清新女声', 'zh_female_qingxinnvsheng_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 7, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_CANCAN', @doubao_seed_model_id, '知性灿灿', 'zh_female_cancan_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 8, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_LINJIA', @doubao_seed_model_id, '邻家女孩', 'zh_female_linjianvhai_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 9, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_SISI', @doubao_seed_model_id, '爽快思思', 'zh_female_shuangkuaisisi_uranus_bigtts', '普通话、英语', NULL, '豆包语音合成模型 2.0 服务 10035 可用音色。', NULL, NULL, 10, 1, NOW(), 1, NOW())
ON DUPLICATE KEY UPDATE
  `tts_model_id` = VALUES(`tts_model_id`),
  `name` = VALUES(`name`),
  `tts_voice` = VALUES(`tts_voice`),
  `languages` = VALUES(`languages`),
  `remark` = VALUES(`remark`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);
