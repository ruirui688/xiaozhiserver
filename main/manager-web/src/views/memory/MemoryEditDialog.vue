<template>
  <el-dialog title="编辑记忆" :visible.sync="dialogVisible" width="500px" @close="handleCancel">
    <el-form :model="form" label-width="80px" size="small">
      <el-form-item label="类型">
        <el-select v-model="form.memoryType" filterable allow-create placeholder="记忆类型由LLM自动归类，也可手动输入" style="width:100%">
          <el-option v-for="t in existingTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="分类">
        <el-select v-model="form.category" filterable allow-create placeholder="由LLM自动归类，也可手动输入" style="width:100%">
          <el-option v-for="c in existingCategories" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="内容">
        <el-input v-model="form.canonicalText" type="textarea" :rows="4" placeholder="记忆的事实内容" />
      </el-form-item>
      <el-form-item label="敏感度">
        <el-select v-model="form.sensitivity" style="width:100%">
          <el-option label="普通" value="normal" />
          <el-option label="个人" value="personal" />
          <el-option label="敏感" value="sensitive" />
        </el-select>
      </el-form-item>
      <el-form-item label="重要性">
        <el-slider v-model="form.importance" :min="0" :max="1" :step="0.05" show-input />
      </el-form-item>
      <el-form-item label="置信度">
        <el-slider v-model="form.confidence" :min="0" :max="1" :step="0.05" show-input />
      </el-form-item>
    </el-form>
    <span slot="footer">
      <el-button size="small" @click="handleCancel">取消</el-button>
      <el-button type="primary" size="small" @click="handleSave">保存</el-button>
    </span>
  </el-dialog>
</template>

<script>
export default {
  name: 'MemoryEditDialog',
  props: {
    visible: { type: Boolean, default: false },
    memory: { type: Object, default: () => ({}) },
    memoryId: { type: String, default: '' },
    existingTypes: { type: Array, default: () => [] },
    existingCategories: { type: Array, default: () => [] }
  },
  data() {
    return {
      form: { memoryType: '', category: '', canonicalText: '', sensitivity: 'normal', importance: 0.5, confidence: 0.5 }
    }
  },
  computed: {
    dialogVisible: {
      get() { return this.visible },
      set() { }
    }
  },
  watch: {
    memory: {
      immediate: true,
      handler(m) {
        if (m && Object.keys(m).length) {
          this.form = {
            memoryType: m.memoryType || '',
            category: m.category || '',
            canonicalText: m.canonicalText || '',
            sensitivity: m.sensitivity || 'normal',
            importance: Number(m.importance || 0.5),
            confidence: Number(m.confidence || 0.5)
          }
        }
      }
    }
  },
  methods: {
    handleSave() {
      this.$emit('save', this.memoryId, { ...this.form })
    },
    handleCancel() {
      this.$emit('cancel')
    }
  }
}
</script>
