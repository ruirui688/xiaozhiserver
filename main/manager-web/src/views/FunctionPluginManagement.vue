<template>
  <div class="welcome">
    <HeaderBar />
    <ResourceCenterNav />

    <div class="operation-bar">
      <div class="page-heading">
        <h2 class="page-title">插件中心</h2>
        <p class="page-subtitle">像逛能力商店一样查看系统工具与互动玩法，在角色配置中一键启用。</p>
      </div>
      <div class="right-operations">
        <el-input
          v-model="searchName"
          placeholder="搜索功能名称、用户说法或功能编码"
          clearable
          class="search-input"
          @keyup.enter.native="handleSearch"
        />
        <el-button class="btn-search" @click="handleSearch">搜索</el-button>
        <el-button v-if="canManagePlugins" type="primary" @click="openCreateDialog()">新建互动玩法</el-button>
      </div>
    </div>

    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="plugin-card" shadow="never">
            <div class="plugin-toolbar">
              <div class="filter-groups">
                <el-radio-group v-model="activeType" size="small" @change="handleTypeChange">
                  <el-radio-button label="all">全部工具 {{ pluginCounts.all }}</el-radio-button>
                  <el-radio-button label="builtin">内置工具 {{ pluginCounts.builtin }}</el-radio-button>
                  <el-radio-button label="interaction">互动玩法 {{ pluginCounts.interaction }}</el-radio-button>
                </el-radio-group>
                <el-radio-group
                  v-if="showCategoryFilter"
                  v-model="activeCategory"
                  size="small"
                  class="category-tabs"
                >
                  <el-radio-button
                    v-for="category in categoryFilters"
                    :key="category.key"
                    :label="category.key"
                  >
                    {{ category.label }} {{ category.count }}
                  </el-radio-button>
                </el-radio-group>
              </div>
              <span class="toolbar-summary">
                工具目录，不是全局开关
              </span>
            </div>
            <div class="scope-guide">
              <div class="scope-guide-item">
                <div class="scope-guide-label">这里管理</div>
                <div class="scope-guide-value">可被角色勾选的工具和玩法</div>
              </div>
              <div class="scope-guide-item">
                <div class="scope-guide-label">启用位置</div>
                <div class="scope-guide-value">智能体管理 -> 编辑角色 -> 功能</div>
              </div>
              <div class="scope-guide-item">
                <div class="scope-guide-label">独立配置</div>
                <div class="scope-guide-value">飞书、知识库、MCP 在角色编辑页绑定</div>
              </div>
            </div>
            <el-empty v-if="!loading && visiblePlugins.length === 0" description="没有找到匹配的插件" />
            <div v-else class="plugin-grid" v-loading="loading">
              <PluginResourceCard
                v-for="plugin in visiblePlugins"
                :key="plugin.id"
                :plugin="plugin"
                :interaction="isDeclarativeInteractionPlugin(plugin.fieldsMeta)"
                :can-manage="canManagePlugins"
                :category="getFunctionCategory(plugin)"
                :category-key="getCategoryMeta(plugin).key"
                :description="getPluginDescription(plugin)"
                :example="getExampleUtterance(plugin)"
                :config-text="getConfigText(plugin)"
                @detail="openDetailDialog"
                @edit="openEditDialog"
                @command="handleRowCommand"
              />
            </div>

            <div class="table-bottom">
              <span class="total-text">
                当前显示{{ visiblePlugins.length }}个功能，共{{ pluginCounts.all }}个
              </span>
            </div>
          </el-card>
        </div>
      </div>
    </div>

    <el-dialog
      :title="editingId ? '编辑互动玩法' : '新建互动玩法'"
      :visible.sync="formDialogVisible"
      width="820px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form ref="pluginForm" :model="pluginForm" :rules="rules" label-width="110px">
        <el-form-item v-if="!editingId" label="玩法类型">
          <el-radio-group v-model="selectedPresetCode" size="small" @change="applyInteractionPreset">
            <el-radio-button label="blank">自定义</el-radio-button>
            <el-radio-button
              v-for="template in interactionPresets"
              :key="template.providerCode"
              :label="template.providerCode"
            >
              {{ template.name }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <div v-if="editingId && getMatchingPreset(pluginForm.providerCode)" class="rule-upgrade">
          <span>推荐规则包含玩法流程、判断标准、主持技巧和异常处理，可让小智更懂怎么玩。</span>
          <el-button size="mini" type="primary" plain @click="applyRecommendedRules">
            套用推荐规则
          </el-button>
        </div>

        <div class="form-row">
          <el-form-item label="玩法名称" prop="name">
            <el-input v-model="pluginForm.name" placeholder="例如：成语接龙" />
          </el-form-item>
          <el-form-item label="排序" prop="sort">
            <el-input-number v-model="pluginForm.sort" :min="0" controls-position="right" />
          </el-form-item>
        </div>

        <el-form-item label="用户会怎么说" prop="description">
          <el-input
            v-model="pluginForm.description"
            type="textarea"
            :rows="3"
            placeholder="例如：当用户说“玩成语接龙”“来个接龙”时触发"
          />
        </el-form-item>

        <el-form-item label="智能体怎么玩" prop="instructions">
          <el-input
            v-model="pluginForm.instructions"
            type="textarea"
            :rows="7"
            placeholder="写清楚角色、规则、互动方式、失败或接不上时如何提示"
          />
        </el-form-item>

        <el-form-item label="开始时怎么说">
          <el-input
            v-model="pluginForm.openingMessage"
            type="textarea"
            :rows="3"
            placeholder="玩法开始时对用户说的第一句话"
          />
        </el-form-item>

        <el-collapse v-model="activeAdvancedPanels" class="advanced-collapse">
          <el-collapse-item title="高级配置（通常不用修改）" name="advanced">
            <el-form-item label="功能编码" prop="providerCode">
              <el-input
                v-model="pluginForm.providerCode"
                :disabled="Boolean(editingId)"
                placeholder="例如：idiom_chain"
              />
            </el-form-item>
            <el-form-item label="高级参数">
              <el-input
                v-model="pluginForm.parametersSchemaText"
                type="textarea"
                :rows="8"
                placeholder="请输入合法 JSON；通常保持默认即可"
              />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      <div slot="footer" class="dialog-footer">
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePlugin">保存玩法</el-button>
      </div>
    </el-dialog>

    <el-dialog
      title="功能说明"
      :visible.sync="detailDialogVisible"
      width="640px"
      :close-on-click-modal="false"
    >
      <div v-if="currentPlugin" class="detail-panel">
        <div class="detail-title">{{ currentPlugin.name }}</div>
        <div class="detail-code">{{ currentPlugin.providerCode }}</div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="分类">{{ getFunctionCategory(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item label="类型">
            {{ isDeclarativeInteractionPlugin(currentPlugin.fieldsMeta) ? "互动玩法" : "系统能力" }}
          </el-descriptions-item>
          <el-descriptions-item label="用户会怎么说">{{ getExampleUtterance(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item label="智能体会做什么">{{ getPluginDescription(currentPlugin) }}</el-descriptions-item>
          <el-descriptions-item label="如何启用">进入角色配置，打开功能管理后勾选这个功能。</el-descriptions-item>
        </el-descriptions>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="detailDialogVisible = false">知道了</el-button>
      </div>
    </el-dialog>

    <el-footer>
      <VersionFooter />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";
import VersionFooter from "@/components/VersionFooter.vue";
import ResourceCenterNav from "@/components/resource/ResourceCenterNav.vue";
import PluginResourceCard from "@/components/resource/PluginResourceCard.vue";

const DEFAULT_PARAMETERS_SCHEMA = {
  type: "object",
  properties: {
    user_message: {
      type: "string",
      description: "用户当前触发这个功能插件时说的话"
    },
    context: {
      type: "string",
      description: "可选的对话上下文或额外提示"
    }
  },
  required: ["user_message"]
};

export default {
  name: "FunctionPluginManagement",
  components: {
    HeaderBar,
    VersionFooter,
    ResourceCenterNav,
    PluginResourceCard
  },
  data() {
    return {
      loading: false,
      saving: false,
      searchName: "",
      activeType: "all",
      activeCategory: "all",
      plugins: [],
      allPluginProviders: [],
      formDialogVisible: false,
      detailDialogVisible: false,
      currentPlugin: null,
      editingId: "",
      selectedPresetCode: "blank",
      activeAdvancedPanels: [],
      pluginForm: this.createEmptyForm(),
      interactionPresets: [
        {
          name: "讲段子",
          providerCode: "tell_joke",
          sort: 90,
          examples: "讲个笑话、来个段子、逗我开心一下",
          description: "当用户想听笑话、段子、冷笑话，或想轻松一下时调用。",
          openingMessage: "给你来一个短一点、好懂一点的。",
          instructions: [
            "你是一个会控场的语音段子手，目标是让用户轻松一笑，而不是机械背笑话。",
            "玩法流程：先判断用户要的是冷笑话、生活段子、谐音梗、职场/校园轻松梗，用户没有指定类型时优先讲短段子。",
            "内容要求：每次只讲 1 个段子；铺垫控制在 2 到 4 句；最后必须有明确包袱；适合语音播报，不使用复杂排版和长列表。",
            "主持技巧：如果用户刚刚表达了疲惫、无聊、尴尬或想开心一下，要先接住情绪，再讲段子；讲完后可以问“还要不要换个冷一点的”。",
            "边界规则：避免低俗、歧视、攻击、血腥、政治敏感和冒犯现实个人；不拿用户本人开恶意玩笑。",
            "失败处理：如果一时没有合适段子，就用轻松自嘲过渡，然后换一个更简单的生活梗。",
            "互动延续：用户说“不好笑”“再来一个”“换个类型”时，要根据反馈调整类型，不要重复同一个段子。"
          ].join("\\n")
        },
        {
          name: "热梗",
          providerCode: "hot_meme",
          sort: 91,
          examples: "这个梗什么意思、接个热梗、最近有什么梗",
          description: "当用户询问网络热梗、流行语含义，或希望小智接梗、玩梗时调用。",
          openingMessage: "这个我按人话给你讲，不整太学术。",
          instructions: [
            "你是热梗解释和接话助手，目标是让用户听懂梗、用对梗，并在聊天里自然接住梗。",
            "玩法流程：先判断用户是在问含义、问出处、要接话、还是要生成一句玩梗回复；不同目的用不同回答方式。",
            "解释规则：解释时按“这个梗大概是什么意思 -> 常见使用场景 -> 情绪色彩 -> 举一个短例子”的顺序回答。",
            "接话规则：如果用户是在聊天中抛梗，不要长篇解释，优先用一句自然、轻松、不过度网络化的话接住。",
            "不确定处理：遇到可能过时、地域性强或你不确定的梗，要明确说“我不一定百分百准确”，再给最可能的解释。",
            "安全边界：不要传播恶意网暴、歧视、低俗羞辱或人身攻击类梗；遇到这类梗时，改成中性解释或提醒不建议这样用。",
            "互动技巧：解释完可以问用户“你是想知道意思，还是想让我帮你回一句”。"
          ].join("\\n")
        },
        {
          name: "成语接龙",
          providerCode: "idiom_chain",
          sort: 92,
          examples: "玩成语接龙、来一局成语接龙",
          description: "当用户想玩成语接龙时调用。",
          openingMessage: "来玩成语接龙。我先出一个常见的，你接最后一个字。",
          instructions: [
            "你是成语接龙主持人，要负责出题、判定、提示、控节奏，让用户觉得小智真的懂规则。",
            "基础规则：每轮只处理一个成语；用户回答应尽量是常见四字成语；用户成语的第一个字应接上上一轮成语的最后一个字；默认按字面首字判定，不主动扩展同音字接龙。",
            "发起流程：如果用户刚开始游戏，你先说明一句规则，然后给出一个常见、容易接的成语，不要选太生僻的开局；已用过的成语不能重复计分。",
            "判定流程：用户回答后先判断是不是成语，再判断是否接上最后一个字；接上了就简短肯定，然后立刻给出你的下一个成语。",
            "提示技巧：用户接不上、答错或沉默时，先给 1 个提示，比如“可以想一个以心开头的成语”；再错时给一个可接答案，并继续游戏。",
            "难度控制：优先选择常见、好接、适合语音的成语，不要为了显得聪明使用冷僻词。",
            "结束规则：用户说不玩了、换个游戏、结束时，要总结一句成绩，例如“你刚才接了 4 个，挺稳的”。",
            "输出要求：语音场景下保持短句，不要一次解释太多典故；每轮只给必要判断和下一个成语。"
          ].join("\\n")
        },
        {
          name: "你说我猜",
          providerCode: "guess_what_i_say",
          sort: 93,
          examples: "玩你说我猜、猜物游戏、我想好了你来猜",
          description: "当用户想让小智通过连续提问来猜出用户心里想的词或物品时调用。",
          openingMessage: "你先在心里想一个物品，不用告诉我。想好了就说一声，我来问问题猜。",
          instructions: [
            "你是二十问式猜词游戏玩家。用户心里想一个答案，小智主动通过连续提问缩小范围，最后猜出答案。",
            "开局流程：用户刚开始游戏时，让用户先在心里想一个物品、人物、动物、地点或常见概念，不要说出答案；用户说“想好了”“开始”“来”后，直接问第一个是/否问题。",
            "提问规则：每轮只问一个能用“对、不是、不确定”回答的问题；不要同时抛多个问题；不要让用户描述第一句提示；禁止问“答案是什么”“你心里想的是什么”“目标词是什么”。",
            "推理策略：先问大类，再问是否人造、是否用电、尺寸、使用场景、主要功能、是否便携、是否有屏幕、是否通信等区分性问题；每次都吸收用户上一轮回答，形成累计线索。",
            "猜测策略：线索足够时再明确猜一个答案；猜错后根据否定反馈继续提问，不要重复老答案；不确定时继续问更能区分候选的问题。",
            "节奏规则：可以按第几个问题推进，但不要机械啰嗦；问题总数尽量控制在二十个以内，必要时可以提前猜。",
            "边界规则：不猜真实个人隐私、敏感身份、攻击性称呼；如果题目不适合，建议换一个词。",
            "结束规则：用户说不玩了、结束、退出时，简短结束，可以说下次再玩；不要继续追问。",
            "输出要求：保持口语、短句、有互动感；每轮只推进一步，通常只输出一句问题或一个猜测。"
          ].join("\\n")
        },
        {
          name: "我想你猜",
          providerCode: "xiaozhi_thinks_you_guess",
          sort: 94,
          examples: "你想一个词我来猜、你出题我猜、我来猜你想的词",
          description: "当用户想让小智心里想一个词、由用户来猜时调用。",
          openingMessage: "我想好了，是一个常见词。第一个提示来了，你来猜。",
          instructions: [
            "这是主模型自由主持的猜词游戏：你先在心里选定一个常见词，用户根据提示来猜。",
            "每局必须一直围绕同一个答案推进，直到用户猜对、结束或重新开局；不要中途更换答案，也不要假装忘记。",
            "玩法流程：开局先给出类别和第一个提示；用户猜错时记录猜测并给下一个提示；用户要提示时只给一个新提示；用户猜对后揭晓答案并询问是否再来一局。",
            "禁止行为：不要提前说出答案；不要让用户直接看答案；不要中途换词；不要假装忘记自己想的词。",
            "结束规则：用户说不玩了、结束、不猜了或换游戏时，结束当前局。",
            "输出要求：每轮只说 1 到 2 句，适合语音播报。"
          ].join("\\n")
        }
      ]
    };
  },
  created() {
    this.fetchPlugins();
  },
  methods: {
    createEmptyForm() {
      return {
        name: "",
        providerCode: "",
        sort: 90,
        description: "",
        instructions: "",
        openingMessage: "",
        parametersSchemaText: JSON.stringify(DEFAULT_PARAMETERS_SCHEMA, null, 2)
      };
    },
    fetchPlugins() {
      this.loading = true;
      const handleResponse = ({ data }) => {
        this.loading = false;
        if (data.code !== 0) {
          this.$message.error(data.msg || "获取插件列表失败");
          return;
        }
        const source = Array.isArray(data.data) ? data.data : (data.data.list || []);
        this.allPluginProviders = source.map((item) => ({
          ...item,
          fieldsMeta: this.parseFields(item.fields)
        }));
        this.plugins = this.allPluginProviders
          .filter((item) => item.modelType === "Plugin")
          .sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0));
      };
      if (!this.canManagePlugins) {
        Api.model.getPluginFunctionList(null, handleResponse, () => {
          this.loading = false;
          this.$message.error("获取插件列表失败");
        });
        return;
      }
      Api.model.getModelProvidersPage(
        {
          page: 1,
          limit: 1000,
          modelType: "Plugin"
        },
        handleResponse
      );
    },
    handleSearch() {
      this.searchName = this.searchName.trim();
    },
    handleTypeChange(type) {
      if (type !== "builtin") {
        this.activeCategory = "all";
      }
    },
    parseFields(fields) {
      if (Array.isArray(fields)) {
        return fields;
      }
      try {
        return JSON.parse(fields || "[]");
      } catch (error) {
        return [];
      }
    },
    isDeclarativeInteractionPlugin(fields) {
      const customField = fields.find((field) => field.key === "custom_plugin");
      if (!customField) {
        return false;
      }
      if (typeof customField.default === "boolean") {
        return customField.default;
      }
      return String(customField.default).toLowerCase() === "true";
    },
    getFieldDefault(fields, key, fallback = "") {
      const field = fields.find((item) => item.key === key);
      return field && field.default !== undefined ? field.default : fallback;
    },
    getPluginDescription(plugin) {
      const description = this.getFieldDefault(plugin.fieldsMeta, "description");
      if (description) {
        return description;
      }
      if (this.isDeclarativeInteractionPlugin(plugin.fieldsMeta)) {
        return this.getFieldDefault(plugin.fieldsMeta, "instructions", "互动玩法，智能体按规则对话。");
      }
      const descriptions = {
        get_weather: "查询指定城市天气，返回当前天气和预报信息。",
        play_music: "从服务器音乐库选择歌曲并播放。",
        meeting_minutes: "进入会议记录流程，结束后生成会议纪要。",
        get_news: "获取新闻摘要，适合普通新闻查询。",
        get_news_from_chinanews: "获取中新网新闻内容。",
        get_news_from_newsnow: "获取 NewsNow 热点新闻和热榜内容。",
        hass_get_state: "读取 Home Assistant 中设备或实体的当前状态。",
        hass_set_state: "向 Home Assistant 下发设备状态修改指令。",
        hass_play_music: "通过 Home Assistant 媒体播放器播放音乐。",
        web_search: "联网搜索问题并把结果交给智能体整理回答。",
        call_device: "通过通讯录呼叫另一个已绑定设备。"
      };
      return descriptions[plugin.providerCode] || "作为工具暴露给大模型，由模型按用户意图决定是否调用。";
    },
    getFunctionCategory(plugin) {
      return this.getCategoryMeta(plugin).label;
    },
    getCategoryMeta(plugin) {
      const code = plugin.providerCode || "";
      if (this.isDeclarativeInteractionPlugin(plugin.fieldsMeta)) {
        return { key: "chat", label: "聊天玩法" };
      }
      if (code.includes("weather")) {
        return { key: "life", label: "生活服务" };
      }
      if (code.includes("news") || code.includes("search")) {
        return { key: "info", label: "内容资讯" };
      }
      if (code.includes("music")) {
        return { key: "media", label: "音乐音频" };
      }
      if (code.includes("hass") || code.includes("homeassistant") || code.includes("call_device")) {
        return { key: "device", label: "设备控制" };
      }
      return { key: "tool", label: "工具能力" };
    },
    getExampleUtterance(plugin) {
      const preset = this.interactionPresets.find((item) => item.providerCode === plugin.providerCode);
      if (preset) {
        return preset.examples;
      }
      const examples = {
        get_weather: "今天上海天气怎么样",
        play_music: "播放一首歌",
        meeting_minutes: "开始记录会议",
        get_news: "今天有什么新闻",
        get_news_from_chinanews: "看看中新网新闻",
        get_news_from_newsnow: "看看今天热搜",
        hass_get_state: "客厅灯现在开着吗",
        hass_set_state: "把客厅灯打开",
        hass_play_music: "用音箱播放音乐",
        web_search: "帮我查一下这个问题",
        call_device: "呼叫客厅设备"
      };
      return examples[plugin.providerCode] || this.getPluginDescription(plugin);
    },
    getConfigText(plugin) {
      if (this.isDeclarativeInteractionPlugin(plugin.fieldsMeta)) {
        return "编写玩法规则";
      }
      return plugin.fieldsMeta && plugin.fieldsMeta.length > 0 ? "角色中配置参数" : "角色中勾选";
    },
    openDetailDialog(row) {
      this.currentPlugin = row;
      this.detailDialogVisible = true;
    },
    handleRowCommand(row, command) {
      if (command === "duplicate") {
        this.openCreateDialog(row);
      }
      if (command === "delete") {
        this.deletePlugin(row);
      }
    },
    getMatchingPreset(providerCode) {
      return this.interactionPresets.find((item) => item.providerCode === providerCode);
    },
    applyRecommendedRules() {
      const preset = this.getMatchingPreset(this.pluginForm.providerCode);
      if (!preset) {
        return;
      }
      this.pluginForm = {
        ...this.pluginForm,
        description: preset.description,
        instructions: preset.instructions,
        openingMessage: preset.openingMessage,
        parametersSchemaText: JSON.stringify(DEFAULT_PARAMETERS_SCHEMA, null, 2)
      };
      this.$message.success("已套用推荐规则，可继续按你的场景微调");
    },
    openCreateDialog(source) {
      this.editingId = "";
      if (source && source.fieldsMeta) {
        this.selectedPresetCode = "blank";
        this.activeAdvancedPanels = ["advanced"];
        this.pluginForm = {
          name: `${source.name} 副本`,
          providerCode: "",
          sort: Number(source.sort || 90),
          description: this.getPluginDescription(source),
          instructions: this.getFieldDefault(
            source.fieldsMeta,
            "instructions",
            `参考“${source.name}”的触发场景，编写新的互动规则。`
          ),
          openingMessage: this.getFieldDefault(source.fieldsMeta, "opening_message"),
          parametersSchemaText: JSON.stringify(
            this.getFieldDefault(source.fieldsMeta, "parameters_schema", DEFAULT_PARAMETERS_SCHEMA),
            null,
            2
          )
        };
      } else if (source) {
        this.selectedPresetCode = source.providerCode;
        this.activeAdvancedPanels = [];
        this.pluginForm = {
          name: source.name,
          providerCode: source.providerCode,
          sort: source.sort,
          description: source.description,
          instructions: source.instructions,
          openingMessage: source.openingMessage,
          parametersSchemaText: JSON.stringify(DEFAULT_PARAMETERS_SCHEMA, null, 2)
        };
      } else {
        this.selectedPresetCode = "blank";
        this.activeAdvancedPanels = ["advanced"];
        this.pluginForm = this.createEmptyForm();
      }
      this.formDialogVisible = true;
    },
    openEditDialog(row) {
      this.editingId = row.id;
      this.pluginForm = {
        name: row.name,
        providerCode: row.providerCode,
        sort: Number(row.sort || 90),
        description: this.getFieldDefault(row.fieldsMeta, "description"),
        instructions: this.getFieldDefault(row.fieldsMeta, "instructions"),
        openingMessage: this.getFieldDefault(row.fieldsMeta, "opening_message"),
        parametersSchemaText: JSON.stringify(
          this.getFieldDefault(row.fieldsMeta, "parameters_schema", DEFAULT_PARAMETERS_SCHEMA),
          null,
          2
        )
      };
      this.formDialogVisible = true;
    },
    applyInteractionPreset(providerCode) {
      if (providerCode === "blank") {
        this.pluginForm = this.createEmptyForm();
        this.activeAdvancedPanels = ["advanced"];
        return;
      }
      const preset = this.getMatchingPreset(providerCode);
      if (!preset) {
        return;
      }
      this.pluginForm = {
        name: preset.name,
        providerCode: preset.providerCode,
        sort: preset.sort,
        description: preset.description,
        instructions: preset.instructions,
        openingMessage: preset.openingMessage,
        parametersSchemaText: JSON.stringify(DEFAULT_PARAMETERS_SCHEMA, null, 2)
      };
      this.activeAdvancedPanels = [];
    },
    resetForm() {
      this.editingId = "";
      this.pluginForm = this.createEmptyForm();
      if (this.$refs.pluginForm) {
        this.$refs.pluginForm.clearValidate();
      }
    },
    buildFields(parametersSchema) {
      return [
        {
          key: "custom_plugin",
          type: "boolean",
          label: "互动玩法",
          default: true,
          editing: false,
          selected: false
        },
        {
          key: "description",
          type: "textarea",
          label: "用户会怎么说",
          default: this.pluginForm.description,
          editing: false,
          selected: false
        },
        {
          key: "instructions",
          type: "textarea",
          label: "智能体怎么玩",
          default: this.pluginForm.instructions,
          editing: false,
          selected: false
        },
        {
          key: "opening_message",
          type: "textarea",
          label: "开始时怎么说",
          default: this.pluginForm.openingMessage,
          editing: false,
          selected: false
        },
        {
          key: "parameters_schema",
          type: "json",
          label: "高级参数",
          default: parametersSchema,
          editing: false,
          selected: false
        }
      ];
    },
    validateProviderCode() {
      const providerCode = this.pluginForm.providerCode.trim();
      if (!/^[a-z][a-z0-9_]*$/.test(providerCode)) {
        this.$message.error("功能编码必须以小写字母开头，只能包含小写字母、数字和下划线");
        return false;
      }

      const duplicated = this.allPluginProviders.some((item) => {
        return item.providerCode === providerCode && item.id !== this.editingId;
      });
      if (duplicated) {
        this.$message.error("功能编码已存在，请换一个编码");
        return false;
      }
      return true;
    },
    parseParametersSchema() {
      try {
        const parsed = JSON.parse(this.pluginForm.parametersSchemaText || "{}");
        if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
          throw new Error("schema must be object");
        }
        return parsed;
      } catch (error) {
        this.$message.error("高级参数必须是合法的 JSON 对象");
        return null;
      }
    },
    savePlugin() {
      this.$refs.pluginForm.validate((valid) => {
        if (!valid || !this.validateProviderCode()) {
          return;
        }

        const parametersSchema = this.parseParametersSchema();
        if (!parametersSchema) {
          return;
        }

        const payload = {
          id: this.editingId,
          modelType: "Plugin",
          providerCode: this.pluginForm.providerCode.trim(),
          name: this.pluginForm.name.trim(),
          sort: Number(this.pluginForm.sort || 0),
          fields: this.buildFields(parametersSchema)
        };

        this.saving = true;
        const request = this.editingId
          ? (params, callback) => Api.model.updateModelProvider(params, callback)
          : (params, callback) => Api.model.addModelProvider(params, callback);
        request(payload, ({ data }) => {
          this.saving = false;
          if (data.code !== 0) {
            this.$message.error(data.msg || "保存互动玩法失败");
            return;
          }
          this.$message.success("保存成功");
          this.formDialogVisible = false;
          this.fetchPlugins();
        });
      });
    },
    deletePlugin(row) {
      if (!this.isDeclarativeInteractionPlugin(row.fieldsMeta)) {
        this.$message.warning("内置功能不能在这里删除");
        return;
      }
      this.$confirm(
        `确定要删除“${row.name}”吗？如果角色已选择该功能，对应配置会失效。`,
        "删除互动玩法",
        {
          confirmButtonText: "删除",
          cancelButtonText: "取消",
          type: "warning"
        }
      ).then(() => {
        Api.model.deleteModelProviderByIds([row.id], ({ data }) => {
          if (data.code !== 0) {
            this.$message.error(data.msg || "删除失败");
            return;
          }
          this.$message.success("删除成功");
          this.fetchPlugins();
        });
      }).catch(() => {});
    }
  },
  computed: {
    canManagePlugins() {
      return Boolean(this.$store.state.userInfo && this.$store.state.userInfo.superAdmin);
    },
    rules() {
      return {
        name: [{ required: true, message: "请输入玩法名称", trigger: "blur" }],
        providerCode: [{ required: true, message: "请输入功能编码", trigger: "blur" }],
        description: [{ required: true, message: "请输入用户触发说法", trigger: "blur" }],
        instructions: [{ required: true, message: "请输入玩法规则", trigger: "blur" }]
      };
    },
    searchedPlugins() {
      const keyword = this.searchName.trim().toLowerCase();
      if (!keyword) {
        return this.plugins;
      }
      return this.plugins.filter((item) => {
        return (
          String(item.name || "").toLowerCase().includes(keyword) ||
          String(item.providerCode || "").toLowerCase().includes(keyword) ||
          String(this.getExampleUtterance(item) || "").toLowerCase().includes(keyword)
        );
      });
    },
    visiblePlugins() {
      let list = this.searchedPlugins;
      if (this.activeType === "builtin") {
        list = list.filter((item) => !this.isDeclarativeInteractionPlugin(item.fieldsMeta));
      }
      else if (this.activeType === "interaction") {
        list = list.filter((item) => this.isDeclarativeInteractionPlugin(item.fieldsMeta));
      }

      if (this.showCategoryFilter && this.activeCategory !== "all") {
        list = list.filter((item) => {
          return !this.isDeclarativeInteractionPlugin(item.fieldsMeta) &&
            this.getCategoryMeta(item).key === this.activeCategory;
        });
      }
      return list;
    },
    pluginCounts() {
      const interaction = this.plugins.filter((item) => this.isDeclarativeInteractionPlugin(item.fieldsMeta)).length;
      return {
        all: this.plugins.length,
        interaction,
        builtin: this.plugins.length - interaction
      };
    },
    showCategoryFilter() {
      return this.activeType === "builtin";
    },
    builtinPluginsForCategory() {
      return this.searchedPlugins.filter((item) => !this.isDeclarativeInteractionPlugin(item.fieldsMeta));
    },
    categoryCounts() {
      return this.builtinPluginsForCategory.reduce((counts, item) => {
        const key = this.getCategoryMeta(item).key;
        counts[key] = (counts[key] || 0) + 1;
        return counts;
      }, {});
    },
    categoryFilters() {
      const categories = [
        { key: "life", label: "生活服务" },
        { key: "info", label: "内容资讯" },
        { key: "media", label: "音乐音频" },
        { key: "device", label: "设备控制" },
        { key: "tool", label: "工具能力" }
      ];
      return [
        {
          key: "all",
          label: "全部系统能力",
          count: this.builtinPluginsForCategory.length
        },
        ...categories.map((category) => ({
          ...category,
          count: this.categoryCounts[category.key] || 0
        }))
      ];
    }
  }
};
</script>

<style lang="scss" scoped>
.welcome {
  min-width: 900px;
  min-height: 506px;
  height: 100vh;
  display: flex;
  position: relative;
  flex-direction: column;
  background-size: cover;
  background: linear-gradient(to bottom right, #dce8ff, #e4eeff, #e6cbfd) center;
  overflow: hidden;
}

.operation-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
}

.page-heading {
  min-width: 320px;
}

.page-title {
  font-size: 24px;
  margin: 0 0 6px;
}

.page-subtitle {
  margin: 0;
  color: #606266;
  font-size: 14px;
}

.right-operations {
  display: flex;
  gap: 10px;
  margin-left: auto;
}

.search-input {
  width: 240px;
}

.btn-search {
  background: linear-gradient(135deg, #6b8cff, #a966ff);
  border: none;
  color: white;
}

.main-wrapper {
  flex: 1;
  min-height: 0;
  height: auto;
  margin: 0 22px;
  border-radius: 15px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  position: relative;
  background: rgba(237, 242, 255, 0.5);
  display: flex;
  flex-direction: column;
}

.content-panel {
  flex: 1;
  display: flex;
  overflow: hidden;
  height: 100%;
  border-radius: 15px;
  background: transparent;
  border: 1px solid #fff;
}

.content-area {
  flex: 1;
  height: 100%;
  min-width: 600px;
  overflow: auto;
  background-color: white;
  display: flex;
}

.plugin-card {
  width: 100%;
  border: none;
  border-radius: 0;
}

.plugin-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.plugin-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 4px 0 16px;
}

.filter-groups {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.category-tabs {
  display: block;
}

::v-deep .category-tabs .el-radio-button__inner {
  font-size: 13px;
}

.toolbar-summary {
  color: #606266;
  font-size: 14px;
  line-height: 32px;
  white-space: nowrap;
}

.scope-guide {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.scope-guide-item {
  padding: 10px 12px;
  border: 1px solid #d8e5ff;
  border-radius: 6px;
  background: #f7faff;
}

.scope-guide-label {
  color: #3a63c7;
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
}

.scope-guide-value {
  margin-top: 3px;
  color: #3d4566;
  font-size: 13px;
  line-height: 20px;
}

.function-name {
  color: #303133;
  font-weight: 600;
  line-height: 1.5;
}

.function-code {
  color: #909399;
  font-size: 12px;
  line-height: 1.4;
}

.description-text {
  display: block;
  line-height: 1.5;
  color: #3d4566;
}

@media (max-width: 1280px) {
  .plugin-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.danger-action {
  color: #f56c6c;
}

.table-bottom {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding-top: 16px;
}

.total-text {
  color: #606266;
  font-size: 14px;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.rule-upgrade {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 0 18px 110px;
  padding: 10px 12px;
  border: 1px solid #d9ecff;
  border-radius: 6px;
  background: #f5faff;
  color: #606266;
  font-size: 14px;
}

.advanced-collapse {
  margin-left: 110px;
}

.detail-title {
  color: #303133;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.5;
}

.detail-code {
  color: #909399;
  font-size: 12px;
  margin-bottom: 16px;
}
</style>
