<template>
  <div class="voice-gallery-field">
    <div class="voice-entry">
      <span class="voice-current">{{ selectedName }}</span>
      <el-button size="small" icon="el-icon-headset" @click="open">选择音色</el-button>
    </div>
    <el-dialog title="音色广场" :visible.sync="visible" append-to-body width="min(960px, 94vw)" top="7vh" @closed="$emit('stop')">
      <p class="gallery-intro">{{ realtime ? '选择当前实时模型的音色，或填写该服务已开通的音色 ID。' : '为智能体挑选声音。仅展示当前语音模型可用的音色。' }}</p>
      <div class="gallery-toolbar">
        <el-radio-group v-model="tab" size="small" @change="page = 1">
          <el-radio-button label="system">系统音色</el-radio-button>
          <el-radio-button label="clone">复刻音色</el-radio-button>
        </el-radio-group>
        <el-input v-model="search" clearable prefix-icon="el-icon-search" placeholder="搜索音色名称" @input="page = 1" />
        <el-select v-model="language" clearable placeholder="全部语言" @change="page = 1">
          <el-option v-for="item in languages" :key="item" :value="item" :label="item" />
        </el-select>
      </div>
      <div v-if="tab === 'clone'" class="clone-intro">
        <div><strong>我的复刻音色</strong><p>{{ realtime ? '实时模型的复刻音色需由对应服务开通，不能直接使用普通 TTS 复刻资源。' : '上传声音样本、完成训练后，在这里选择可用的复刻音色。' }}</p></div>
        <div v-if="!realtime"><el-button size="small" @click="$emit('refresh')">刷新音色</el-button><el-button size="small" @click="manageClones">管理 / 复刻音色 <i class="el-icon-top-right" /></el-button></div>
      </div>
      <div v-if="paged.length" class="voice-grid">
        <article v-for="voice in paged" :key="voice.value" class="voice-tile" :class="{ selected: draft === voice.value }">
          <div class="voice-avatar" :class="{ clone: voice.isClone }" aria-hidden="true">{{ (voice.label || '音').slice(0, 1) }}</div>
          <div class="voice-copy"><h3>{{ voice.label }}</h3><span>{{ voice.languages || '语言未标注' }}</span><span class="voice-kind">{{ voice.isClone ? '复刻音色' : '系统音色' }}</span></div>
          <div class="voice-actions">
            <el-button type="text" :disabled="!canPreview(voice)" @click="$emit('preview', voice.value)">{{ playingId === voice.value ? '暂停' : '试听' }}</el-button>
            <el-button :type="draft === voice.value ? 'primary' : 'default'" size="small" @click="draft = voice.value">{{ draft === voice.value ? '已选择' : '选用' }}</el-button>
          </div>
        </article>
      </div>
      <el-empty v-else :description="search || language ? '没有匹配的音色，试试其他筛选条件' : '当前模型暂无此类音色'" :image-size="70" />
      <el-pagination v-if="filtered.length > 12" layout="prev, pager, next" :total="filtered.length" :page-size="12" :current-page.sync="page" />
      <div v-if="realtime && allowCustom" class="custom-voice"><label>已开通的音色 ID</label><el-input v-model="draft" clearable placeholder="填写当前实时服务支持的音色 ID；留空使用模型默认音色" /></div>
      <span slot="footer"><el-button @click="visible = false">取消</el-button><el-button type="primary" :disabled="!realtime && !options.some(v => v.value === draft)" @click="confirm">使用此音色</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
export default {
  props: {
    value: { type: String, default: '' }, options: { type: Array, default: () => [] },
    realtime: Boolean, allowCustom: Boolean, playingId: { type: String, default: '' }
  },
  data() { return { visible: false, draft: '', tab: 'system', search: '', language: '', page: 1 }; },
  computed: {
    selectedName() { return this.options.find(v => v.value === this.value)?.label || this.value || (this.realtime ? '使用模型默认音色' : '尚未选择音色'); },
    languages() { return [...new Set(this.options.map(v => v.languages).filter(Boolean))]; },
    filtered() {
      const query = this.search.trim().toLowerCase();
      return this.options.filter(v => Boolean(v.isClone) === (this.tab === 'clone') &&
        (!query || (v.label || '').toLowerCase().includes(query)) && (!this.language || v.languages === this.language));
    },
    paged() { return this.filtered.slice((this.page - 1) * 12, this.page * 12); }
  },
  watch: { options() { this.page = 1; } },
  methods: {
    open() { this.draft = this.value; this.tab = this.options.find(v => v.value === this.value)?.isClone ? 'clone' : 'system'; this.search = ''; this.language = ''; this.page = 1; this.visible = true; },
    canPreview(voice) { return !this.realtime && Boolean(voice.isClone || voice.voiceDemo || voice.voice_demo); },
    confirm() {
      const value = this.draft.trim();
      if (value && !this.options.some(v => v.value === value) && !(this.realtime && this.allowCustom)) return;
      if (!value && !this.realtime) return;
      this.$emit('input', value); this.visible = false;
    },
    manageClones() { const route = this.$router.resolve('/voice-clone-management'); window.open(route.href, '_blank', 'noopener'); }
  }
};
</script>
<style scoped>
.voice-gallery-field { flex: 1; min-width: 0; }.voice-entry { display: flex; align-items: center; gap: 16px; }.voice-current { flex: 1; min-width: 0; overflow-wrap: anywhere; color: #343b50; }.gallery-intro { margin: -8px 0 24px; color: #858b9c; line-height: 1.7; }
.gallery-toolbar { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 24px; }.gallery-toolbar>.el-input { flex: 1; min-width: 160px; }.gallery-toolbar>.el-select { width: 150px; }.voice-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; max-height: 48vh; overflow-y: auto; padding: 2px; }
.voice-tile { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 22px; border: 1px solid #eceef5; border-radius: 16px; background: #fff; }.voice-tile.selected { border-color: #8c80dc; background: #faf9ff; }.voice-avatar { display: grid; place-items: center; width: 56px; height: 56px; border-radius: 18px; background: #eaf0ff; color: #657fc1; font-size: 24px; }.voice-avatar.clone { background: #f4eafa; color: #9f70b0; }.voice-copy { flex: 1; min-width: 100px; }.voice-copy h3 { margin: 0 0 10px; font-size: 16px; color: #353b4c; overflow-wrap: anywhere; }.voice-copy span { font-size: 12px; color: #9298a8; }.voice-kind { margin-left: 10px; }.voice-actions { display: flex; justify-content: space-between; align-items: center; width: 100%; }.clone-intro { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 18px; border-radius: 12px; background: #f8f9fc; margin-bottom: 20px; }.clone-intro p { margin: 8px 0 0; color: #888f9c; line-height: 1.6; }.custom-voice { margin-top: 20px; }.custom-voice label { display: block; margin-bottom: 8px; }.el-pagination { margin-top: 18px; text-align: center; }
@media(max-width: 640px) { .voice-grid { grid-template-columns: 1fr; }.clone-intro { flex-direction: column; align-items: flex-start; } }
</style>
