import { isInteractionPlugin, isRetiredPlugin, pluginBoolean, pluginDefault } from './pluginCatalog';

// Product help only: never used as a model prompt or as tool authorization.
const SYSTEM_CODES = new Set([
  'direct_answer', 'handle_exit_intent', 'reset_conversation_context',
  'get_lunar', 'end_interaction', 'change_role',
]);
const CATEGORIES = {
  life: '生活查询', info: '新闻与搜索', media: '音乐播放',
  device: '设备控制', meeting: '会议记录', chat: '互动玩法',
  knowledge: '知识问答', collaboration: '办公协作', session: '对话控制', tool: '其他功能',
};
const HELP = {
  get_weather: {
    title: '天气查询', category: 'life',
    summary: '查询指定城市的天气和天气预报。',
    preparation: '建议先配置和风天气并测试连接；查询天气预警必须配置。',
    usageExample: '北京明天天气怎么样？',
    setupSteps: ['在此页配置天气服务地址、密钥和默认城市，并测试连接。', '选择智能体，在「模型与能力 → 功能管理」中勾选天气插件。', '保存智能体配置后，再询问天气。'],
  },
  web_search: {
    title: '联网搜索', category: 'info',
    summary: '从互联网查找近期信息和公开资料。',
    preparation: '先配置搜索服务并测试连接，再为智能体添加。',
    usageExample: '帮我查一下最近的航天新闻。',
    setupSteps: ['在此页选择搜索服务、保存密钥并测试连接。', '选择智能体，在「模型与能力 → 功能管理」中勾选「联网搜索」。', '保存智能体配置后，再提出需要联网的问题。'],
  },
  play_music: {
    title: '播放本地音乐', category: 'media',
    summary: '在设备上播放服务器曲库中的音乐。',
    preparation: '请管理员先在「参数字典 → 音乐管理」上传 MP3 歌曲。',
    usageExample: '播放一首曲库里的歌。',
    setupSteps: ['请管理员在「参数字典 → 音乐管理」上传 MP3 歌曲。', '选择智能体，在「模型与能力 → 功能管理」中勾选音乐播放插件。', '保存智能体配置后，在设备上点播曲库中的歌曲。'],
  },
  get_news_from_chinanews: {
    title: '新闻播报', category: 'info',
    summary: '获取中国新闻网的新闻内容。',
    preparation: '在智能体中添加；需要服务器能访问新闻来源。',
    usageExample: '播报一下今天的新闻。',
  },
  get_news_from_newsnow: {
    title: '热点资讯', category: 'info',
    summary: '从 NewsNow 获取各平台的热门话题。',
    preparation: '在智能体中添加，并检查热点服务参数。',
    usageExample: '今天有哪些热门话题？',
  },
  meeting_minutes: {
    title: '会议记录', category: 'meeting',
    summary: '开始记录会议，在结束时生成总结。',
    preparation: '在智能体中添加；需要发送飞书纪要时另配飞书。',
    usageExample: '开始记录这次会议。',
    setupSteps: ['选择智能体，在「模型与能力 → 功能管理」中勾选会议记录并保存配置。', '如需自动发送纪要，再进入「外部集成 → 管理飞书配置」，配置应用和目标群。', '打开「设备使用飞书」及「会议结束后发送纪要」并保存；仅口头总结无需配置飞书。'],
  },
  hass_get_state: {
    title: '查询智能家居状态', category: 'device',
    summary: '查看 Home Assistant 中设备的当前状态。',
    preparation: '需有 Home Assistant 服务，在此插件参数中填写地址、令牌和设备列表。',
    usageExample: '客厅的灯现在开着吗？',
    setupSteps: ['选择智能体，在「模型与能力 → 功能管理」中勾选 HomeAssistant 设备状态查询。', '选中该插件，填写服务器地址、访问令牌和设备列表。', '保存功能选择及智能体配置，再按设备名称查询状态。'],
  },
  hass_set_state: {
    title: '控制智能家居', category: 'device',
    summary: '控制 Home Assistant 中已接入的设备。',
    preparation: '先添加并配置 HomeAssistant 设备状态查询，再添加设备控制。',
    usageExample: '把客厅的灯打开。',
    setupSteps: ['在智能体的「模型与能力 → 功能管理」中同时勾选 HomeAssistant 设备状态查询和设备状态修改。', '在设备状态查询插件中填写服务器地址、访问令牌和设备列表；控制功能共用这份连接配置。', '保存功能选择及智能体配置，再按设备名称发出控制请求。'],
  },
  hass_play_music: {
    title: '智能家居音乐', category: 'media',
    summary: '通过 Home Assistant 的媒体设备播放音乐。',
    preparation: '需配置 Home Assistant 连接，并在其中安装和配置 Music Assistant。',
    usageExample: '用客厅音箱播放音乐。',
    setupSteps: ['先在 Home Assistant 中配置 Music Assistant 及媒体播放器。', '在智能体功能管理中同时勾选 HomeAssistant 设备状态查询和音乐播放。', '在设备状态查询插件中填写服务器地址、访问令牌及媒体播放器实体，保存功能选择与智能体配置。'],
  },
  call_device: {
    title: '设备通话', category: 'device',
    summary: '向已配置的联系人设备发起通话。',
    preparation: '先在顶部「通讯录」配置设备联系人、备注和呼叫权限；设备需支持通话。',
    usageExample: '呼叫通讯录里的设备。',
    setupSteps: ['若顶部没有「通讯录」，请联系管理员启用通讯录功能。', '进入通讯录，选择设备，设置联系人备注及允许呼叫的设备并保存；双方都要有联系人关系。', '再为设备使用的智能体添加设备通话，保存配置后按联系人备注呼叫。'],
  },
  feishu_assistant: {
    title: '飞书助手', category: 'collaboration', setupTarget: 'feishu',
    summary: '让智能体使用已授权的飞书协作能力。',
    preparation: '在智能体的「外部集成」中配置飞书应用和权限。',
    setupSteps: ['选择智能体，进入「外部集成 → 管理飞书配置」。', '填写飞书应用配置，按需要授权群聊和操作范围并保存。'],
  },
  search_from_local_knowledge: {
    title: '本地知识库问答', category: 'knowledge', setupTarget: 'knowledge',
    summary: '根据你上传到知识库的资料回答问题。',
    preparation: '先在知识库中准备资料，再将知识库绑定到智能体。',
    setupSteps: ['在资源中心的知识库中创建知识库并上传资料。', '选择智能体，进入「模型与能力 → 知识库管理」，选择需要的知识库。', '保存智能体配置后，再提问资料中的内容。'],
  },
  search_from_ragflow: {
    title: 'RAGFlow 知识库问答', category: 'knowledge', setupTarget: 'knowledge',
    summary: '查询接入 RAGFlow 的知识库资料。',
    preparation: '需先完成 RAGFlow 模型与知识库接入，再绑定到智能体。',
    setupSteps: ['由管理员在模型配置中完成 RAGFlow 接入，并创建对应知识库。', '选择智能体，在「模型与能力 → 知识库管理」中绑定该知识库。', '保存智能体配置后，再提问资料中的内容。'],
  },
  direct_answer: {
    title: '普通问答', category: 'session',
    summary: '处理不需要调用外部功能的普通对话。',
    preparation: '系统根据对话模型处理，无需勾选或配置插件。',
  },
  handle_exit_intent: {
    title: '结束对话', category: 'session',
    summary: '在用户明确告别时结束当前设备对话。',
    preparation: '系统对话控制，无需手动添加。', usageExample: '再见，下次聊。',
  },
  reset_conversation_context: {
    title: '重新开始当前对话', category: 'session',
    summary: '清除当前会话上下文，重新开始聊天。',
    preparation: '系统对话控制，不删除聊天记录或长期记忆。',
    usageExample: '清空当前对话，我们重新开始。',
  },
  get_lunar: {
    title: '农历查询', category: 'life',
    summary: '查询农历等日期信息。',
    preparation: '系统按对话能力提供，无需手动添加。',
    usageExample: '今天农历几月几日？',
  },
  end_interaction: {
    title: '结束当前玩法', category: 'session',
    summary: '退出当前互动玩法，继续普通聊天。',
    preparation: '添加互动玩法后由系统配套提供，无需单独勾选。',
    usageExample: '不玩了，换个话题吧。',
  },
  change_role: {
    title: '对话中的角色切换', category: 'session',
    summary: '使用服务端预先配置的角色切换能力。',
    preparation: '此项需由服务端管理员配置；日常更换智能体请到设备中心操作。',
    setupSteps: ['切换设备使用的智能体：进入设备中心，在设备卡片中选择智能体。', '此处保留旧版对话角色切换的技术说明，不提供插件勾选。'],
  },
  set_session_voice: {
    title: '对话中调整声音（已停用）', category: 'session', setupTarget: 'voice',
    summary: '已停止通过聊天更换音色或调整本次会话声音。',
    preparation: '在智能体设置中更换默认音色；设备播放音量在设备端调整。',
    setupSteps: ['选择智能体；普通对话进入「语音链路」，实时语音模型进入「模型与能力」。', '调整默认音色或实时音色，确认后保存智能体配置。', '这里不能恢复已停用的对话调音插件。'],
  },
  manage_memory: {
    title: '对话中管理记忆（已停用）', category: 'session', setupTarget: 'memory',
    summary: '记忆已改为独立管理，不再作为可勾选插件。',
    preparation: '到智能体的记忆设置选择记忆模式；已有记忆在记忆管理中查看。',
    setupSteps: ['选择智能体，进入「记忆与记录」选择记忆模式并保存。', '需要查看或整理已有记忆时，进入记忆管理。'],
  },
};

export function pluginSection(plugin) {
  if (isRetiredPlugin(plugin)) return 'retired';
  const metadata = (plugin && plugin.catalogMetadata) || {};
  const code = plugin && plugin.providerCode;
  if (SYSTEM_CODES.has(code) || ['runtime', 'intent_allowlist'].includes(metadata.activationMode)
      || metadata.capabilityType === 'routing') return 'system';
  if (isInteractionPlugin(plugin)) return 'activities';
  if (pluginBoolean(plugin, 'runtime_builtin')) return 'system';
  if (metadata.selectable === false || metadata.activationMode === 'binding'
      || pluginBoolean(plugin, 'catalog_only')) return 'integrations';
  return 'plugins';
}

export function pluginPresentation(plugin) {
  const section = pluginSection(plugin);
  const help = HELP[plugin && plugin.providerCode] || {};
  const canAdd = section === 'plugins' || section === 'activities';
  const category = help.category || (section === 'activities' ? 'chat' : 'tool');
  const setupTarget = help.setupTarget || (canAdd ? 'functions' : null);
  const labels = { functions: '添加到智能体', knowledge: '绑定知识库', feishu: '配置飞书', voice: '前往语音设置', memory: '前往记忆设置' };
  return {
    section,
    title: help.title || (plugin && plugin.name) || '未命名功能',
    category: { key: category, label: CATEGORIES[category] },
    summary: help.summary || (section === 'activities' ? '按照设定规则与用户进行语言互动。' : '查看功能说明，确认用途和配置要求。'),
    preparation: help.preparation || (canAdd ? '选择智能体，在功能管理中勾选并保存。' : '请查看管理员提供的专用配置说明。'),
    usageExample: help.usageExample || '',
    setupTarget,
    setupLabel: labels[setupTarget] || '',
    setupSteps: help.setupSteps || (canAdd
      ? ['选择需要使用此功能的智能体。', '在「模型与能力 → 功能管理」中勾选此项，按需填写参数或玩法规则。', '确认功能选择后，保存智能体配置，再开始对话。']
      : [help.preparation || '该条目没有可直接打开的配置入口，请联系管理员确认。']),
    availabilityNote: section === 'retired' ? '此功能已停用，不能通过编辑说明重新启用。'
      : ['get_weather', 'web_search'].includes(plugin && plugin.providerCode)
        ? '服务配置按账号保存。团队中其他账号创建的智能体，需要由所属账号配置；是否已添加请进入智能体设置查看。'
      : '此页提供配置指南。某个智能体是否已启用，请进入该智能体的设置查看。',
  };
}

export function pluginSectionCounts(plugins) {
  return plugins.reduce((counts, plugin) => {
    counts[pluginSection(plugin)] += 1;
    counts.total += 1;
    return counts;
  }, { plugins: 0, activities: 0, integrations: 0, system: 0, retired: 0, total: 0 });
}

export function filterCatalogPlugins(plugins, { section = 'plugins', category = 'all', search = '', sort = 'sort' } = {}) {
  const query = search.trim().toLowerCase();
  return plugins.filter(plugin => {
    const display = pluginPresentation(plugin);
    if (display.section !== section) return false;
    if (category !== 'all' && display.category.key !== category) return false;
    return !query || [plugin.name, plugin.providerCode, display.title, display.summary,
      display.preparation, display.usageExample, pluginDefault(plugin, 'description')]
      .some(value => String(value || '').toLowerCase().includes(query));
  }).sort((a, b) => sort === 'name'
    ? pluginPresentation(a).title.localeCompare(pluginPresentation(b).title, 'zh-CN')
    : Number(a.sort || 0) - Number(b.sort || 0));
}
