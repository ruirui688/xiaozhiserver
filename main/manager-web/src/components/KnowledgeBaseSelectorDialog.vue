<template>
  <el-dialog
    :title="$t('knowledgeBaseSelector.title')"
    :visible.sync="dialogVisible"
    width="900px"
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <div class="selector-layout">
      <div class="selector-main">
        <div class="selector-toolbar">
          <el-input
            v-model="searchName"
            :placeholder="$t('knowledgeBaseSelector.searchPlaceholder')"
            clearable
            class="selector-search"
            @keyup.enter.native="fetchKnowledgeBases"
          />
          <el-button type="primary" @click="fetchKnowledgeBases">
            {{ $t('knowledgeBaseSelector.search') }}
          </el-button>
        </div>

        <el-table
          v-loading="loading"
          :data="knowledgeBases"
          row-key="id"
          class="knowledge-table"
          @selection-change="handleSelectionChange"
          ref="knowledgeTable"
        >
          <el-table-column type="selection" width="55" />
          <el-table-column prop="name" :label="$t('knowledgeBaseSelector.name')" min-width="170" />
          <el-table-column prop="description" :label="$t('knowledgeBaseSelector.description')" min-width="260" show-overflow-tooltip>
            <template slot-scope="scope">
              <span>{{ scope.row.description || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="$t('knowledgeBaseSelector.documentCount')" width="110" align="center">
            <template slot-scope="scope">
              <span>{{ scope.row.documentCount || 0 }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="selector-pagination">
          <el-pagination
            small
            background
            layout="prev, pager, next, total"
            :current-page="currentPage"
            :page-size="pageSize"
            :total="total"
            @current-change="handlePageChange"
          />
        </div>
      </div>
    </div>

    <span slot="footer" class="dialog-footer">
      <el-button @click="dialogVisible = false">{{ $t('knowledgeBaseSelector.cancel') }}</el-button>
      <el-button type="primary" @click="saveSelection">{{ $t('knowledgeBaseSelector.save') }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";

export default {
  props: {
    value: Boolean,
    selected: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      dialogVisible: this.value,
      searchName: "",
      loading: false,
      knowledgeBases: [],
      selectedRows: [],
      currentPage: 1,
      pageSize: 10,
      total: 0
    };
  },
  watch: {
    value(val) {
      this.dialogVisible = val;
      if (val) {
        this.selectedRows = JSON.parse(JSON.stringify(this.selected || []));
        this.currentPage = 1;
        this.fetchKnowledgeBases();
      }
    },
    dialogVisible(val) {
      this.$emit("input", val);
    }
  },
  methods: {
    fetchKnowledgeBases() {
      this.loading = true;
      Api.knowledgeBase.getKnowledgeBaseList(
        {
          page: this.currentPage,
          page_size: this.pageSize,
          name: this.searchName
        },
        (res) => {
          this.loading = false;
          if (res.data && res.data.code === 0) {
            const pageData = res.data.data || {};
            this.knowledgeBases = (pageData.list || []).filter(item => item.status === 1);
            this.total = pageData.total || 0;
            this.$nextTick(this.restoreSelection);
          } else {
            this.$message.error(res.data?.msg || this.$t('knowledgeBaseSelector.fetchFailed'));
          }
        },
        () => {
          this.loading = false;
          this.$message.error(this.$t('knowledgeBaseSelector.fetchFailed'));
        }
      );
    },
    restoreSelection() {
      if (!this.$refs.knowledgeTable) {
        return;
      }
      this.$refs.knowledgeTable.clearSelection();
      const selectedIds = new Set(this.selectedRows.map(item => item.id));
      this.knowledgeBases.forEach(row => {
        if (selectedIds.has(row.id)) {
          this.$refs.knowledgeTable.toggleRowSelection(row, true);
        }
      });
    },
    handleSelectionChange(rows) {
      const pageIds = new Set(this.knowledgeBases.map(item => item.id));
      const offPageRows = this.selectedRows.filter(item => !pageIds.has(item.id));
      const merged = [...offPageRows, ...rows];
      const map = new Map();
      merged.forEach(item => {
        const previous = map.get(item.id) || this.selectedRows.find(row => row.id === item.id) || {};
        map.set(item.id, {
          ...item,
          queryPolicy: previous.queryPolicy || item.queryPolicy || ""
        });
      });
      this.selectedRows = Array.from(map.values());
    },
    handlePageChange(page) {
      this.currentPage = page;
      this.fetchKnowledgeBases();
    },
    saveSelection() {
      this.$emit("confirm", this.selectedRows.map(item => ({
        id: item.id,
        name: item.name,
        description: item.description,
        documentCount: item.documentCount || 0,
        queryPolicy: item.queryPolicy || ""
      })));
      this.dialogVisible = false;
    },
    handleClosed() {
      this.selectedRows = JSON.parse(JSON.stringify(this.selected || []));
    }
  },
  mounted() {
    this.selectedRows = JSON.parse(JSON.stringify(this.selected || []));
  }
};
</script>

<style lang="scss" scoped>
.selector-layout {
  display: block;
}

.selector-toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.selector-search {
  flex: 1;
}

.knowledge-table {
  width: 100%;
}

.selector-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

</style>
