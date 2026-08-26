<template>
  <section class="template-center">
    <div class="template-toolbar">
      <div class="scope-tabs">
        <button v-for="scope in scopes" :key="scope.value"
          :class="['scope-tab', { active: activeScope === scope.value }]"
          type="button" @click="activeScope = scope.value">
          {{ scope.label }}
        </button>
      </div>
      <el-input v-model.trim="keyword" clearable prefix-icon="el-icon-search"
        :placeholder="$t('home.templateSearchPlaceholder')" class="template-search" />
    </div>

    <div v-loading="loading" class="template-grid">
      <article v-for="template in filteredTemplates" :key="template.id" class="template-card"
        tabindex="0" @click="$emit('preview', template)" @keyup.enter="$emit('preview', template)">
        <div class="template-card__top">
          <span :class="['scope-badge', `scope-${template.scope || 'WORKSPACE'}`]">
            {{ scopeLabel(template.scope) }}
          </span>
          <span v-if="template.reviewStatus === 'PENDING'" class="review-badge">审核中</span>
        </div>
        <div class="template-card__identity">
          <span class="template-card__avatar">{{ avatarText(template) }}</span>
          <div>
            <h3>{{ template.templateName }}</h3>
            <p>角色：{{ template.agentName || '未命名角色' }}</p>
          </div>
        </div>
        <div class="template-card__summary">预览人设、模型与能力边界后再创建，不绑定音色。</div>
        <div class="template-card__footer">
          <span>v{{ template.version }}</span>
          <el-button type="primary" plain size="small" @click.stop="$emit('preview', template)">
            查看预览
          </el-button>
        </div>
      </article>
      <el-empty v-if="!loading && filteredTemplates.length === 0"
        :description="$t('home.noAccessibleTemplates')" />
    </div>
  </section>
</template>

<script>
export default {
  name: 'AgentTemplateCenter',
  props: {
    templates: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    canWrite: { type: Boolean, default: true },
    quotaFull: { type: Boolean, default: false }
  },
  data() {
    return {
      activeScope: 'ALL',
      keyword: '',
      scopes: [
        { value: 'ALL', label: '全部' },
        { value: 'OFFICIAL', label: '官方模板' },
        { value: 'PUBLIC', label: '模板中心' },
        { value: 'WORKSPACE', label: '工作空间' }
      ]
    }
  },
  computed: {
    filteredTemplates() {
      const keyword = this.keyword.toLowerCase();
      return this.templates.filter(template => {
        const scopeMatches = this.activeScope === 'ALL' || template.scope === this.activeScope;
        const text = `${template.templateName || ''} ${template.agentName || ''}`.toLowerCase();
        return scopeMatches && (!keyword || text.includes(keyword));
      });
    }
  },
  methods: {
    scopeLabel(scope) {
      return {
        OFFICIAL: '官方',
        PUBLIC: '模板中心',
        WORKSPACE: '工作空间'
      }[scope] || '官方';
    },
    avatarText(template) {
      return String(template.agentName || template.templateName || '智').trim().slice(0, 1);
    }
  }
}
</script>

<style scoped>
.template-center {
  padding: 22px 0 12px;
}

.template-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.scope-tabs {
  display: flex;
  gap: 8px;
  padding: 5px;
  background: rgba(255, 255, 255, 0.65);
  border-radius: 12px;
}

.scope-tab {
  border: 0;
  border-radius: 9px;
  padding: 9px 16px;
  color: #68728f;
  background: transparent;
  cursor: pointer;
}

.scope-tab.active {
  color: #3f63df;
  background: #fff;
  box-shadow: 0 5px 15px rgba(65, 92, 175, 0.12);
}

.template-search {
  width: 300px;
}

.template-grid {
  min-height: 250px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
  gap: 24px;
  align-items: start;
}

.template-card {
  min-height: 230px;
  padding: 20px;
  box-sizing: border-box;
  border: 1px solid rgba(111, 137, 210, 0.15);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 14px 34px rgba(67, 91, 159, 0.1);
  cursor: pointer;
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}

.template-card:hover,
.template-card:focus { outline: none; transform: translateY(-3px); border-color: rgba(78, 111, 226, .4); box-shadow: 0 18px 38px rgba(67, 91, 159, .16); }

.template-card__top,
.template-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.template-card__identity { display: flex; align-items: center; gap: 14px; margin: 22px 0 14px; }
.template-card__avatar { display: grid; place-items: center; flex: 0 0 52px; width: 52px; height: 52px; border-radius: 16px; color: #fff; font-size: 20px; font-weight: 700; background: linear-gradient(145deg, #5478ff, #8c6df1); }
.template-card h3 {
  margin: 0 0 5px;
  color: #303852;
  font-size: 21px;
}

.template-card p {
  margin: 0;
  color: #7b849e;
  line-height: 1.6;
}

.template-card__summary { min-height: 38px; margin-bottom: 18px; color: #8c95aa; font-size: 13px; line-height: 1.6; }

.template-card__footer {
  color: #9098ad;
  font-size: 12px;
}

.scope-badge,
.review-badge {
  display: inline-flex;
  padding: 5px 10px;
  border-radius: 9px;
  font-size: 12px;
}

.scope-OFFICIAL { color: #a86500; background: #fff0ca; }
.scope-PUBLIC { color: #3867d8; background: #e7efff; }
.scope-WORKSPACE { color: #587087; background: #edf2f7; }
.review-badge { color: #b47a16; background: #fff6df; }

@media (max-width: 1050px) {
  .template-toolbar { align-items: stretch; flex-direction: column; }
  .template-search { width: 100%; }
}
</style>
