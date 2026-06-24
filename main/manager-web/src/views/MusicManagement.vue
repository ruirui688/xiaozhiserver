<template>
  <div class="welcome">
    <HeaderBar />

    <div class="operation-bar">
      <div>
        <h2 class="page-title">音乐管理</h2>
        <div class="music-dir">服务器音乐目录：{{ musicDir || '-' }}</div>
      </div>
      <div class="right-operations">
        <el-input
          v-model="keyword"
          placeholder="搜索歌曲名称/别名/提示词"
          class="search-input"
          clearable
          @keyup.enter.native="handleSearch"
        />
        <el-button class="btn-search" @click="handleSearch">搜索</el-button>
      </div>
    </div>

    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="params-card" shadow="never">
            <div class="upload-bar">
              <el-upload
                ref="upload"
                action="#"
                accept=".mp3,.wav,.p3,audio/mpeg,audio/wav"
                multiple
                :auto-upload="false"
                :file-list="fileList"
                :on-change="handleFileChange"
                :on-remove="handleFileRemove"
                :before-remove="handleBeforeRemove"
              >
                <el-button type="primary" icon="el-icon-upload2">选择音频</el-button>
              </el-upload>
              <el-button
                type="success"
                icon="el-icon-check"
                :loading="uploading"
                :disabled="fileList.length === 0"
                @click="submitUpload"
              >
                上传到服务器
              </el-button>
              <el-button icon="el-icon-refresh" @click="fetchMusicList">刷新</el-button>
            </div>

            <el-table
              :data="musicList"
              class="transparent-table"
              v-loading="loading"
              element-loading-text="Loading"
              element-loading-spinner="el-icon-loading"
              element-loading-background="rgba(255, 255, 255, 0.7)"
            >
              <el-table-column label="歌曲文件" prop="name" min-width="220" />
              <el-table-column label="别名/说法" min-width="240" show-overflow-tooltip>
                <template slot-scope="scope">
                  <span class="alias-text">{{ formatAliases(scope.row.aliases) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="歌曲提示词" min-width="280" show-overflow-tooltip>
                <template slot-scope="scope">
                  <span class="description-text">{{ scope.row.description || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="相对路径" prop="path" min-width="260" show-overflow-tooltip />
              <el-table-column label="格式" prop="extension" width="90" align="center" />
              <el-table-column label="大小" width="120" align="center">
                <template slot-scope="scope">{{ formatSize(scope.row.size) }}</template>
              </el-table-column>
              <el-table-column label="更新时间" width="180" align="center">
                <template slot-scope="scope">{{ formatDateTime(scope.row.updateTime) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="270" align="center">
                <template slot-scope="scope">
                  <el-button size="mini" type="text" @click="togglePlay(scope.row)">
                    {{ playingPath === scope.row.path ? '停止' : '试听' }}
                  </el-button>
                  <el-button size="mini" type="text" @click="editMusicDescription(scope.row)">
                    编辑配置
                  </el-button>
                  <el-button size="mini" type="text" @click="renameMusic(scope.row)">
                    重命名
                  </el-button>
                  <el-button size="mini" type="text" class="delete-btn" @click="deleteMusic(scope.row)">
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="table_bottom">
              <div class="ctrl_btn">共 {{ total }} 首音乐</div>
            </div>

            <div v-if="!loading && musicList.length === 0" class="empty-state">
              <i class="el-icon-headset"></i>
              <div>还没有上传音乐</div>
              <span>上传 MP3 后，可为音乐添加别名和提示词，小智能按歌名、别名、提示词或随机播放。</span>
            </div>
          </el-card>
        </div>
      </div>
    </div>

    <el-dialog
      title="编辑音乐配置"
      :visible.sync="metadataDialogVisible"
      width="560px"
      append-to-body
    >
      <el-form label-position="top">
        <el-form-item label="歌曲文件">
          <el-input :value="metadataForm.name" disabled />
        </el-form-item>
        <el-form-item label="别名/近音词">
          <el-input
            v-model="metadataForm.aliasesText"
            type="textarea"
            :rows="3"
            placeholder="例如：品牌星光、星光之歌、主题歌"
          />
        </el-form-item>
        <el-form-item label="歌曲提示词">
          <el-input
            v-model="metadataForm.description"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="说明这首歌是什么、适合什么时候推荐、背景信息"
          />
        </el-form-item>
      </el-form>
      <span slot="footer" class="dialog-footer">
        <el-button @click="metadataDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="metadataSaving" @click="saveMusicMetadata">
          保存
        </el-button>
      </span>
    </el-dialog>

    <el-footer>
      <version-footer />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";
import VersionFooter from "@/components/VersionFooter.vue";

export default {
  name: "MusicManagement",
  components: { HeaderBar, VersionFooter },
  data() {
    return {
      keyword: "",
      loading: false,
      uploading: false,
      fileList: [],
      musicList: [],
      musicDir: "",
      total: 0,
      audio: null,
      playingPath: "",
      metadataDialogVisible: false,
      metadataSaving: false,
      metadataForm: {
        path: "",
        name: "",
        aliasesText: "",
        description: ""
      }
    };
  },
  created() {
    this.fetchMusicList();
  },
  beforeDestroy() {
    this.stopAudio();
  },
  methods: {
    fetchMusicList() {
      this.loading = true;
      Api.music.getMusicList({ keyword: this.keyword }, (res) => {
        this.loading = false;
        const body = res.data;
        if (body.code === 0) {
          this.musicList = body.data.files || [];
          this.musicDir = body.data.musicDir || "";
          this.total = body.data.total || 0;
        }
      });
    },
    handleSearch() {
      this.fetchMusicList();
    },
    handleFileChange(file, fileList) {
      const validFiles = [];
      const invalidFiles = [];
      fileList.forEach((item) => {
        const name = item.name || "";
        if (/\.(mp3|wav|p3)$/i.test(name)) {
          validFiles.push(item);
        } else {
          invalidFiles.push(name);
        }
      });
      if (invalidFiles.length > 0) {
        this.$message.warning("只支持 mp3、wav、p3 音频文件");
      }
      this.fileList = validFiles;
    },
    handleFileRemove(file, fileList) {
      this.fileList = fileList;
    },
    handleBeforeRemove() {
      return true;
    },
    submitUpload() {
      if (this.fileList.length === 0) {
        return;
      }
      const formData = new FormData();
      this.fileList.forEach((item) => {
        formData.append("files", item.raw);
      });

      this.uploading = true;
      Api.music.uploadMusic(formData, (res) => {
        this.uploading = false;
        if (res.data.code === 0) {
          this.$message.success("上传成功");
          this.fileList = [];
          if (this.$refs.upload) {
            this.$refs.upload.clearFiles();
          }
          this.fetchMusicList();
        }
      }, (res) => {
        this.uploading = false;
        this.$message.error(res?.data?.msg || "上传失败");
      });
    },
    togglePlay(row) {
      if (this.playingPath === row.path) {
        this.stopAudio();
        return;
      }
      this.stopAudio();
      this.audio = new Audio(Api.music.getPlayUrl(row.path));
      this.playingPath = row.path;
      this.audio.onended = this.stopAudio;
      this.audio.onerror = () => {
        this.$message.error("音频播放失败");
        this.stopAudio();
      };
      this.audio.play().catch(() => {
        this.$message.error("音频播放失败");
        this.stopAudio();
      });
    },
    stopAudio() {
      if (this.audio) {
        this.audio.pause();
        this.audio.currentTime = 0;
      }
      this.audio = null;
      this.playingPath = "";
    },
    renameMusic(row) {
      this.$prompt("请输入新的音乐文件名", "重命名", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        inputValue: row.name,
        inputPattern: /^(?!\.{1,2}$)[^\\/:*?"<>|\x00-\x1F]+$/,
        inputErrorMessage: "文件名不能包含 / \\ : * ? \" < > | 等字符"
      }).then(({ value }) => {
        const newName = (value || "").trim();
        if (!newName || newName === row.name) {
          return;
        }
        if (this.playingPath === row.path) {
          this.stopAudio();
        }
        Api.music.renameMusic(row.path, newName, (res) => {
          if (res.data.code === 0) {
            this.$message.success("重命名成功");
            this.fetchMusicList();
          }
        }, (res) => {
          this.$message.error(res?.data?.msg || "重命名失败");
        });
      }).catch(() => {});
    },
    editMusicDescription(row) {
      this.metadataForm = {
        path: row.path,
        name: row.name,
        aliasesText: (row.aliases || []).join("、"),
        description: row.description || ""
      };
      this.metadataDialogVisible = true;
    },
    saveMusicMetadata() {
      const description = (this.metadataForm.description || "").trim();
      if (description.length > 500) {
        this.$message.error("提示词最多 500 个字符");
        return;
      }
      const aliases = this.parseAliases(this.metadataForm.aliasesText);
      this.metadataSaving = true;
      Api.music.updateMusicMetadata(this.metadataForm.path, description, aliases, (res) => {
        this.metadataSaving = false;
        if (res.data.code === 0) {
          this.$message.success("音乐配置已保存");
          this.metadataDialogVisible = false;
          this.fetchMusicList();
        }
      }, (res) => {
        this.metadataSaving = false;
        this.$message.error(res?.data?.msg || "保存音乐配置失败");
      });
    },
    deleteMusic(row) {
      this.$confirm(`确认删除《${row.name}》吗？`, "提示", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning"
      }).then(() => {
        Api.music.deleteMusic(row.path, (res) => {
          if (res.data.code === 0) {
            this.$message.success("删除成功");
            if (this.playingPath === row.path) {
              this.stopAudio();
            }
            this.fetchMusicList();
          }
        }, (res) => {
          this.$message.error(res?.data?.msg || "删除失败");
        });
      }).catch(() => {});
    },
    formatSize(size) {
      if (!size) {
        return "0 B";
      }
      if (size < 1024) {
        return `${size} B`;
      }
      if (size < 1024 * 1024) {
        return `${(size / 1024).toFixed(1)} KB`;
      }
      return `${(size / 1024 / 1024).toFixed(1)} MB`;
    },
    formatDateTime(timestamp) {
      if (!timestamp) {
        return "-";
      }
      const normalizedTimestamp = Number(timestamp);
      if (!Number.isFinite(normalizedTimestamp)) {
        return "-";
      }
      const date = new Date(normalizedTimestamp);
      const pad = (value) => String(value).padStart(2, "0");
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
    },
    parseAliases(text) {
      return Array.from(new Set((text || "")
        .split(/[、,，;；\n\r]+/)
        .map((item) => item.trim())
        .filter(Boolean)));
    },
    formatAliases(aliases) {
      if (!aliases || aliases.length === 0) {
        return "-";
      }
      return aliases.join("、");
    }
  }
};
</script>

<style scoped>
.welcome {
  min-width: 900px;
  min-height: 506px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(145deg, #eef3ff, #f7f9fc);
}

.operation-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 40px 12px;
}

.page-title {
  margin: 0;
  font-size: 24px;
  color: #2d3748;
  text-align: left;
}

.music-dir {
  margin-top: 8px;
  color: #718096;
  font-size: 13px;
  text-align: left;
}

.right-operations {
  display: flex;
  gap: 12px;
  align-items: center;
}

.search-input {
  width: 260px;
}

.btn-search {
  background: #409eff;
  color: #fff;
  border-color: #409eff;
}

.main-wrapper {
  flex: 1;
  padding: 0 40px 20px;
  overflow: hidden;
}

.content-panel,
.content-area {
  height: 100%;
}

.params-card {
  height: 100%;
  border-radius: 8px;
  border: none;
  overflow: auto;
}

.upload-bar {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 18px;
  text-align: left;
}

.upload-bar ::v-deep .el-upload-list {
  max-width: 620px;
}

.transparent-table {
  width: 100%;
}

.description-text {
  color: #4a5568;
}

.alias-text {
  color: #4a5568;
}

.delete-btn {
  color: #f56c6c;
}

.table_bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18px;
  color: #718096;
  font-size: 13px;
}

.empty-state {
  margin: 70px auto 30px;
  color: #909399;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.empty-state i {
  font-size: 46px;
  color: #c0c4cc;
}

.empty-state span {
  font-size: 13px;
  color: #a0aec0;
}
</style>
