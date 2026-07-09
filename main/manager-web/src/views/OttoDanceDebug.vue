<template>
  <div class="otto-page">
    <div class="debug-header">
      <div class="debug-brand">
        <strong>Otto MCP Studio</strong>
        <span>舞蹈调试工作台</span>
      </div>
      <div class="debug-status" :class="statusKind">{{ statusText }}</div>
    </div>

    <main class="debug-layout">
      <aside class="side-panel">
        <section class="viewer-card">
          <div class="preview">
            <div ref="robotTwin" class="robot-twin"></div>
          </div>
          <div class="preview-footer">
            <span ref="robotStatus">加载3D模型...</span>
            <el-button size="mini" icon="el-icon-refresh-right" @click="resetCamera">复位视角</el-button>
          </div>
        </section>

        <section class="side-body">
          <div class="connect-row">
            <el-input v-model.trim="deviceHost" placeholder="设备 IP，例如 10.119.129.112" @keyup.enter.native="connectDevice" />
            <el-button type="primary" :loading="connecting" @click="connectDevice">连接</el-button>
          </div>
          <div class="connect-row">
            <el-button @click="refreshSnapshot">刷新状态</el-button>
            <el-button @click="resetTools">刷新设备</el-button>
          </div>
          <div class="status-line" :class="statusKind">{{ statusText }}</div>
          <div class="metric-grid">
            <div v-for="item in metrics" :key="item.label" class="metric">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
          <el-collapse class="status-collapse">
            <el-collapse-item title="连接状态" name="status">
              <div class="note">{{ capabilityNote }}</div>
              <label>最近结果</label>
              <pre>{{ resultText }}</pre>
            </el-collapse-item>
          </el-collapse>
        </section>
      </aside>

      <section class="work-panel">
        <el-tabs v-model="activeTab" class="debug-tabs">
          <el-tab-pane label="动作" name="actions">
            <div class="action-toolbar">
              <el-input v-model.trim="actionFilter" placeholder="搜索动作工具" />
              <el-button type="danger" plain @click="quickStop">停止</el-button>
            </div>
            <div class="category-row">
              <el-button v-for="item in actionCategories" :key="item.value" size="mini"
                :type="currentActionCategory === item.value ? 'primary' : 'default'"
                @click="currentActionCategory = item.value">
                {{ item.label }}
              </el-button>
            </div>
            <div class="action-grid">
              <div v-for="action in filteredActions" :key="actionKey(action)" class="action-card">
                <strong>{{ action.label || action.action || action.name }}</strong>
                <span>{{ action.description || action.kind || action.category || 'Otto action' }}</span>
                <div v-for="param in actionParams(action)" :key="param.name" class="param-row">
                  <label>{{ param.name }}</label>
                  <el-input-number v-if="param.type === 'integer'" v-model="actionArgs[actionKey(action)][param.name]"
                    size="mini" :min="param.min" :max="param.max" controls-position="right" />
                  <el-input v-else v-model="actionArgs[actionKey(action)][param.name]" size="mini" />
                </div>
                <div class="card-actions">
                  <el-button size="mini" @click="previewAction(action)">预览</el-button>
                  <el-button size="mini" type="primary" @click="runAction(action)">下发</el-button>
                </div>
              </div>
              <div v-if="filteredActions.length === 0" class="empty">连接后会在这里显示固件内置动作工具。</div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="舵机" name="servo">
            <div class="servo-head">
              <el-input :value="servoModelName" readonly />
              <el-checkbox v-model="servoRealtime">实时同步</el-checkbox>
              <el-button size="mini" @click="homeAllServos">复位</el-button>
              <el-button size="mini" @click="loadManagementState(false)">刷新</el-button>
            </div>
            <div class="servo-grid">
              <div v-for="servo in servoCatalog" :key="servo.id" class="servo-card">
                <div class="servo-title">
                  <strong>{{ servo.label || servo.name || servo.id }}</strong>
                  <span>{{ servoState[servo.id] ? servoState[servo.id].angle : servo.home }}°</span>
                </div>
                <el-slider v-model="servoState[servo.id].angle" :min="servo.min_angle || 0"
                  :max="servo.max_angle || 180" @input="onServoInput(servo)" />
              </div>
              <div v-if="servoCatalog.length === 0" class="empty">连接后会按设备上报的舵机模型生成滑条。</div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="校准" name="trim">
            <div class="note">偏差校准独立于舵机运动滑块，保存后写入设备配置。</div>
            <div class="servo-grid">
              <div v-for="servo in servoCatalog" :key="servo.id" class="servo-card">
                <div class="servo-title">
                  <strong>{{ servo.label || servo.name || servo.id }}</strong>
                  <span>{{ servoState[servo.id] ? servoState[servo.id].trim : 0 }}</span>
                </div>
                <el-slider v-model="servoState[servo.id].trim" :min="-30" :max="30" />
                <el-button size="mini" type="primary" @click="saveTrim(servo)">保存</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="编舞" name="frames">
            <div class="note">编舞只保存逻辑舵机角度序列。预览只驱动左侧3D模型；下发播放会调用设备实时角度工具。</div>
            <div class="frame-toolbar">
              <el-input v-model.trim="frameName" placeholder="帧名称，例如 hello-1" />
              <el-input-number v-model="frameDuration" :min="100" :max="5000" controls-position="right" />
            </div>
            <div class="frame-actions">
              <el-button type="primary" @click="captureFrame">捕获当前姿态</el-button>
              <el-button @click="previewFrames">仅预览</el-button>
              <el-button @click="playFrames">下发播放</el-button>
              <el-button @click="exportFrames">导出JSON</el-button>
              <el-button @click="importFrames">导入JSON</el-button>
              <el-button type="danger" plain @click="clearFrames">清空</el-button>
            </div>
            <el-input v-model="framesJson" type="textarea" :rows="8" placeholder="这里会显示/粘贴编舞 JSON" />
            <div class="frame-list">
              <div v-for="(frame, index) in frames" :key="index" class="frame-item">
                <strong>{{ frame.name || `frame-${index + 1}` }}</strong>
                <span>{{ frame.durationMs }}ms</span>
                <code>{{ JSON.stringify(frame.pose) }}</code>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="Tools" name="tools">
            <div class="tool-workbench">
              <div>
                <div class="action-toolbar">
                  <el-input v-model.trim="toolFilter" placeholder="搜索 tool 名称或参数" />
                  <el-button @click="resetTools">刷新</el-button>
                </div>
                <div class="tool-list">
                  <div v-for="tool in filteredTools" :key="tool.name" class="tool-item"
                    :class="{ active: selectedTool && selectedTool.name === tool.name }" @click="selectTool(tool)">
                    <strong>{{ tool.name }}</strong>
                    <span>{{ Object.keys(tool.inputSchema && tool.inputSchema.properties || {}).join(', ') || 'no args' }}</span>
                  </div>
                  <div v-if="filteredTools.length === 0" class="empty">没有匹配的工具，或尚未连接设备。</div>
                </div>
              </div>
              <div class="tool-detail">
                <h3>{{ selectedTool ? selectedTool.name : '未选择工具' }}</h3>
                <p>{{ selectedTool ? selectedTool.description : '从 Tools 页选择一个 MCP tool 后，可以在这里填写参数并调用。' }}</p>
                <div v-for="param in selectedToolParams" :key="param.name" class="param-row">
                  <label>{{ param.name }}</label>
                  <el-input-number v-if="param.type === 'integer'" v-model="toolArgs[param.name]" size="mini"
                    :min="param.min" :max="param.max" controls-position="right" />
                  <el-switch v-else-if="param.type === 'boolean'" v-model="toolArgs[param.name]" />
                  <el-input v-else v-model="toolArgs[param.name]" size="mini" />
                </div>
                <el-button type="primary" :disabled="!selectedTool" @click="callSelectedTool">调用</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="提示词" name="config">
            <div class="note">提示词会写入设备 NVS。已经注册到当前会话的 MCP tool 描述通常需要重启设备或重新初始化协议后才会完全生效。</div>
            <label>头部触摸工具提示词</label>
            <el-input v-model="headPrompt" type="textarea" :rows="5" />
            <el-button type="primary" @click="saveConfig('head_prompt', headPrompt)">保存头部提示词</el-button>
            <label>身体触摸工具提示词</label>
            <el-input v-model="bodyPrompt" type="textarea" :rows="5" />
            <el-button type="primary" @click="saveConfig('body_prompt', bodyPrompt)">保存身体提示词</el-button>
            <el-button @click="loadConfig">重新读取配置</el-button>
          </el-tab-pane>

          <el-tab-pane label="原始数据" name="raw">
            <label>最新快照</label>
            <pre>{{ JSON.stringify(latest, null, 2) }}</pre>
            <label>最后一次调用原文</label>
            <pre>{{ JSON.stringify(lastRaw, null, 2) }}</pre>
          </el-tab-pane>
        </el-tabs>
      </section>
    </main>
  </div>
</template>

<script>
import Api from '@/apis/api';
import { createRobotTwinViewer } from '@/otto-debug/robot_twin_viewer';
import { createOttoActionPreviewPlayer } from '@/otto-debug/otto_action_preview';

const SERVO_IDS = ['left_leg', 'right_leg', 'left_foot', 'right_foot', 'left_hand', 'right_hand'];
const MODEL_URL = `${process.env.BASE_URL || '/'}otto-debug/models/otto_robot_pose_20260707_142305.glb`;

export default {
  name: 'OttoDanceDebug',
  data() {
    return {
      deviceId: this.$route.query.deviceId || '',
      deviceHost: '',
      activeTab: 'actions',
      connecting: false,
      connected: false,
      statusText: '等待连接',
      statusKind: 'muted',
      latest: {},
      lastRaw: {},
      resultText: '暂无调用结果',
      tools: [],
      nextCursor: '',
      selectedTool: null,
      toolArgs: {},
      toolFilter: '',
      actionFilter: '',
      actionSchema: null,
      actionArgs: {},
      currentActionCategory: 'all',
      servoModelName: '未连接',
      servoCatalog: [],
      servoState: {},
      servoRealtime: true,
      frames: [],
      framesJson: '',
      frameName: '',
      frameDuration: 600,
      headPrompt: '',
      bodyPrompt: '',
      refreshTimer: null,
      robotTwinViewer: null,
      actionPreview: null
    };
  },
  computed: {
    deviceTitle() {
      return this.$route.query.alias || this.$route.query.mac || this.$route.query.model || this.deviceId;
    },
    metrics() {
      const d = this.latestData();
      return [
        ['音量', this.pick(d, 'debug.volume')],
        ['亮度', this.pick(d, 'debug.brightness')],
        ['MCP Tools', this.tools.length || this.pick(d, 'debug.tools_count')],
        ['芯片', this.pick(d, 'runtime.chip.target')],
        ['内部 SRAM', this.formatBytes(this.pick(d, 'runtime.heap.internal_free'))],
        ['PSRAM', this.formatBytes(this.pick(d, 'runtime.heap.psram_free'))],
        ['MAC', this.pick(d, 'runtime.chip.mac')],
        ['舵机模型', this.servoModelName]
      ].filter(([, value]) => value !== undefined && value !== null && value !== '')
        .map(([label, value]) => ({ label, value }));
    },
    capabilityNote() {
      if (!this.servoCatalog.length && !this.tools.length) return '连接后会显示设备上报的完整管理状态。';
      const parts = [`关节 ${this.servoCatalog.length}`, `工具 ${this.tools.length}`];
      if (this.findToolByName('self.otto.set_servo_angle')) parts.push('支持实时角度');
      if (this.findToolByName('self.otto.set_trim')) parts.push('支持微调保存');
      if (this.findToolByName('self.otto.action_schema')) parts.push('支持动作 schema');
      return parts.join('；');
    },
    actionCategories() {
      const categories = new Set(this.actions.map(item => item.category || 'motion'));
      return [{ value: 'all', label: `全部 ${this.actions.length}` }]
        .concat([...categories].map(value => {
          const count = this.actions.filter(item => (item.category || 'motion') === value).length;
          return { value, label: `${value} ${count}` };
        }));
    },
    actions() {
      const schemaActions = this.pick(this.actionSchema, 'actions');
      if (Array.isArray(schemaActions) && schemaActions.length) return schemaActions;
      return this.tools
        .filter(tool => /^self\.otto\./.test(tool.name) && !['self.otto.action_schema', 'self.otto.get_status'].includes(tool.name))
        .map(tool => ({ name: tool.name, label: tool.name.replace('self.otto.', ''), tool: tool.name, category: 'tool' }));
    },
    filteredActions() {
      const q = this.actionFilter.toLowerCase();
      return this.actions.filter(action => {
        const text = `${action.name || ''} ${action.action || ''} ${action.label || ''} ${action.tool || ''}`.toLowerCase();
        const categoryOk = this.currentActionCategory === 'all' || (action.category || 'motion') === this.currentActionCategory;
        return categoryOk && (!q || text.includes(q));
      });
    },
    filteredTools() {
      const q = this.toolFilter.toLowerCase();
      return this.tools.filter(tool => {
        const args = Object.keys(this.toolProps(tool)).join(' ');
        return !q || `${tool.name} ${args}`.toLowerCase().includes(q);
      });
    },
    selectedToolParams() {
      if (!this.selectedTool) return [];
      return this.paramsFromSchema(this.toolProps(this.selectedTool));
    }
  },
  mounted() {
    this.robotTwinViewer = createRobotTwinViewer({
      container: this.$refs.robotTwin,
      status: this.$refs.robotStatus,
      modelUrl: MODEL_URL,
      contactPreview: true,
      contactDebug: true,
      renderFps: 60,
      backgroundFps: 5,
      smoothServoPreview: false,
      servoPreviewSpeedDps: 360
    });
    this.actionPreview = createOttoActionPreviewPlayer({
      getCurrentPose: this.servoPoseSnapshot,
      getCatalog: () => this.servoCatalog,
      applyPose: (pose, meta = {}) => {
        if (this.robotTwinViewer && this.robotTwinViewer.setServoPose) {
          this.robotTwinViewer.setServoPose(pose, this.servoCatalog, {
            model: meta.model || this.servoModelName,
            immediate: true,
            snapContact: Boolean(meta.snapContact)
          });
        }
      },
      onStatus: (text) => this.setResult(text, 'ok')
    });
    this.refreshTimer = setInterval(() => {
      if (this.connected) this.refreshSnapshot(false);
    }, 8000);
  },
  beforeDestroy() {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    if (this.robotTwinViewer && this.robotTwinViewer.dispose) this.robotTwinViewer.dispose();
  },
  methods: {
    connectDevice() {
      if (!this.deviceHost) {
        this.$message.warning('请输入设备IP');
        return;
      }
      this.connecting = true;
      this.request(resolve => Api.ottoDebug.connect(this.deviceId, this.deviceHost, resolve))
        .then((data) => {
          this.connected = true;
          this.statusText = '调试会话已连接';
          this.statusKind = 'ok';
          this.setResult(JSON.stringify(this.extractResult(data), null, 2), 'ok');
          return this.refreshSnapshot().then(() => this.resetTools());
        })
        .catch(error => {
          this.connected = false;
          this.statusText = '设备未响应';
          this.statusKind = 'bad';
          this.setResult(error.message, 'bad');
        })
        .finally(() => {
          this.connecting = false;
        });
    },
    refreshSnapshot(showError = true) {
      return this.request(resolve => Api.ottoDebug.snapshot(this.deviceId, resolve))
        .then(data => {
          this.latest = data;
          this.statusText = `在线，来自 ${data.from || '-'}`;
          this.statusKind = 'ok';
          this.applyStateFromSnapshot();
        })
        .catch(error => {
          this.statusText = '快照超时';
          this.statusKind = 'warn';
          if (showError) this.setResult(error.message, 'bad');
        });
    },
    resetTools() {
      this.tools = [];
      this.nextCursor = '';
      return this.loadToolsPage().then(() => this.loadRemainingTools()).then(() => this.loadManagementState(true));
    },
    loadRemainingTools() {
      if (!this.nextCursor) return Promise.resolve();
      return this.loadToolsPage().then(() => this.loadRemainingTools());
    },
    loadToolsPage() {
      return this.request(resolve => Api.ottoDebug.listTools(this.deviceId, {
        cursor: this.nextCursor || '',
        limit: 8
      }, resolve)).then(data => {
        const result = this.extractResult(data);
        const page = this.pick(result, 'tools') || [];
        this.nextCursor = this.pick(result, 'nextCursor') || '';
        this.tools = this.tools.concat(page);
      });
    },
    loadManagementState(quiet) {
      const tool = this.findToolByName('self.management.get_state');
      if (!tool) {
        this.applyFallbackServoCatalog();
        if (!quiet) this.setResult('设备没有返回完整管理状态，使用默认Otto舵机模型。', 'warn');
        return Promise.resolve();
      }
      return this.callToolByName(tool.name, {}, true)
        .then(text => {
          const state = this.parseJsonFromText(text);
          if (!state) throw new Error('设备管理状态不是有效JSON');
          this.applyManagementState(state);
          return this.loadActionSchema(true);
        })
        .catch(error => {
          this.applyFallbackServoCatalog();
          if (!quiet) this.setResult(error.message, 'warn');
        });
    },
    loadActionSchema(quiet) {
      const tool = this.findToolByName('self.otto.action_schema');
      if (!tool) return Promise.resolve();
      return this.callToolByName(tool.name, {}, true)
        .then(text => {
          this.actionSchema = this.parseJsonFromText(text);
          this.prepareActionArgs();
        })
        .catch(error => {
          this.actionSchema = null;
          if (!quiet) this.setResult(`动作 schema 读取失败: ${error.message}`, 'warn');
        });
    },
    applyManagementState(state) {
      this.servoModelName = state.model || 'Otto';
      const servos = state.servos || state.servo_catalog || [];
      this.servoCatalog = servos.map((servo, index) => ({
        id: servo.id || SERVO_IDS[index] || `servo_${index}`,
        label: servo.label || servo.name || servo.id || `Servo ${index}`,
        index: servo.index !== undefined ? servo.index : index,
        home: Number(servo.home !== undefined ? servo.home : 90),
        current: Number(servo.current_angle !== undefined ? servo.current_angle : servo.current !== undefined ? servo.current : 90),
        trim: Number(servo.trim || 0),
        min_angle: Number(servo.min_angle !== undefined ? servo.min_angle : 0),
        max_angle: Number(servo.max_angle !== undefined ? servo.max_angle : 180)
      }));
      this.servoCatalog.forEach(servo => {
        this.ensureServoState(servo.id, servo.current).angle = servo.current;
        this.ensureServoState(servo.id, servo.current).trim = servo.trim;
      });
      this.updateRobotTwin();
    },
    applyFallbackServoCatalog() {
      if (this.servoCatalog.length) return;
      this.servoModelName = 'Otto';
      this.servoCatalog = SERVO_IDS.map((id, index) => ({
        id,
        label: id,
        index,
        home: index === 4 ? 45 : index === 5 ? 135 : 90,
        current: index === 4 ? 45 : index === 5 ? 135 : 90,
        trim: 0,
        min_angle: 0,
        max_angle: 180
      }));
      this.servoCatalog.forEach(servo => this.ensureServoState(servo.id, servo.home));
      this.updateRobotTwin();
    },
    applyStateFromSnapshot() {
      const result = this.extractResult(this.latest);
      if (result && result.management) this.applyManagementState(result.management);
    },
    selectTool(tool) {
      this.selectedTool = tool;
      this.toolArgs = {};
      this.selectedToolParams.forEach(param => {
        this.$set(this.toolArgs, param.name, param.defaultValue);
      });
    },
    callSelectedTool() {
      if (!this.selectedTool) return;
      this.callToolByName(this.selectedTool.name, this.toolArgs, false);
    },
    callToolByName(name, args = {}, quiet = false, fast = false) {
      const caller = fast ? Api.ottoDebug.callToolFast : Api.ottoDebug.callTool;
      return this.request(resolve => caller(this.deviceId, { name, arguments: args }, resolve))
        .then(data => {
          this.lastRaw = data;
          const text = this.extractToolText(data);
          if (!quiet) this.setResult(`${name}\n${text}`, 'ok');
          return text;
        });
    },
    previewAction(action) {
      const name = action.action || action.name;
      const args = this.actionArgs[this.actionKey(action)] || {};
      if (this.actionPreview && action.action) {
        this.actionPreview.playAction(action.action, args);
      } else if (this.actionPreview && name) {
        this.actionPreview.playAction(name.replace('self.otto.', ''), args);
      }
    },
    runAction(action) {
      const args = this.actionArgs[this.actionKey(action)] || {};
      if (action.tool && action.action) {
        return this.callToolByName(action.tool, Object.assign({ action: action.action }, args), false, true);
      }
      return this.callToolByName(action.tool || action.name, args, false, true);
    },
    quickStop() {
      const tool = this.findToolByName('self.otto.stop');
      if (this.actionPreview) this.actionPreview.stop();
      if (tool) this.callToolByName(tool.name, {}, false, true);
    },
    onServoInput(servo) {
      this.updateRobotTwin();
      if (!this.servoRealtime) return;
      const tool = this.findToolByName('self.otto.set_servo_angle');
      if (!tool) return;
      const state = this.ensureServoState(servo.id, servo.home);
      const args = {
        servo: servo.index,
        servo_number: servo.index,
        angle: state.angle
      };
      this.callToolByName(tool.name, args, true, true).catch(() => {});
    },
    homeAllServos() {
      this.servoCatalog.forEach(servo => {
        this.ensureServoState(servo.id, servo.home).angle = servo.home;
      });
      this.updateRobotTwin();
    },
    saveTrim(servo) {
      const tool = this.findToolByName('self.otto.set_trim');
      if (!tool) {
        this.setResult('设备没有提供舵机校准保存命令。', 'warn');
        return;
      }
      const state = this.ensureServoState(servo.id, servo.home);
      this.callToolByName(tool.name, {
        servo: servo.index,
        servo_index: servo.index,
        trim: state.trim,
        trim_value: state.trim
      });
    },
    captureFrame() {
      this.frames.push({
        name: this.frameName || `frame-${this.frames.length + 1}`,
        durationMs: this.frameDuration,
        pose: this.servoPoseSnapshot()
      });
      this.exportFrames();
    },
    previewFrames() {
      let delay = 0;
      this.frames.forEach(frame => {
        setTimeout(() => {
          if (this.robotTwinViewer && this.robotTwinViewer.setServoPose) {
            this.robotTwinViewer.setServoPose(frame.pose, this.servoCatalog, { immediate: true, snapContact: true });
          }
        }, delay);
        delay += Number(frame.durationMs || 600);
      });
    },
    playFrames() {
      const tool = this.findToolByName('self.otto.set_servo_angle');
      if (!tool) {
        this.setResult('设备没有提供实时舵机角度命令。', 'warn');
        return;
      }
      let delay = 0;
      this.frames.forEach(frame => {
        setTimeout(() => {
          Object.entries(frame.pose || {}).forEach(([id, angle]) => {
            const servo = this.servoCatalog.find(item => item.id === id);
            if (servo) this.callToolByName(tool.name, { servo: servo.index, servo_number: servo.index, angle }, true, true);
          });
        }, delay);
        delay += Number(frame.durationMs || 600);
      });
      this.setResult(`已下发 ${this.frames.length} 个关键帧。`, 'ok');
    },
    exportFrames() {
      this.framesJson = JSON.stringify({ kind: 'otto_choreography', frames: this.frames }, null, 2);
    },
    importFrames() {
      try {
        const data = JSON.parse(this.framesJson || '{}');
        this.frames = Array.isArray(data.frames) ? data.frames : [];
      } catch (error) {
        this.setResult(`编舞JSON导入失败: ${error.message}`, 'bad');
      }
    },
    clearFrames() {
      this.frames = [];
      this.framesJson = '';
    },
    loadConfig() {
      this.request(resolve => Api.ottoDebug.getConfig(this.deviceId, resolve)).then(data => {
        const cfg = this.extractResult(data) || {};
        this.headPrompt = cfg.head_prompt || '';
        this.bodyPrompt = cfg.body_prompt || '';
        this.setResult('提示词配置已读取', 'ok');
      });
    },
    saveConfig(key, value) {
      if ((value || '').length > 1200) {
        this.$message.warning('提示词不能超过1200字符');
        return;
      }
      this.request(resolve => Api.ottoDebug.setConfig(this.deviceId, { key, value }, resolve))
        .then(data => this.setResult(JSON.stringify(this.extractResult(data), null, 2), 'ok'));
    },
    resetCamera() {
      if (this.robotTwinViewer && this.robotTwinViewer.resetCamera) this.robotTwinViewer.resetCamera();
    },
    updateRobotTwin() {
      if (this.robotTwinViewer && this.robotTwinViewer.setServoPose) {
        this.robotTwinViewer.setServoPose(this.servoPoseSnapshot(), this.servoCatalog, { model: this.servoModelName });
      }
    },
    servoPoseSnapshot() {
      const pose = {};
      this.servoCatalog.forEach(servo => {
        pose[servo.id] = this.ensureServoState(servo.id, servo.home).angle;
      });
      return pose;
    },
    ensureServoState(id, fallback = 90) {
      if (!this.servoState[id]) {
        this.$set(this.servoState, id, { angle: Number(fallback || 90), trim: 0 });
      }
      return this.servoState[id];
    },
    actionKey(action) {
      return action.action || action.name || action.tool || action.label;
    },
    actionParams(action) {
      if (!this.actionArgs[this.actionKey(action)]) this.prepareOneActionArgs(action);
      const schema = action.parameters || action.inputSchema || {};
      return this.paramsFromSchema(schema.properties || schema);
    },
    prepareActionArgs() {
      this.actions.forEach(action => this.prepareOneActionArgs(action));
    },
    prepareOneActionArgs(action) {
      const key = this.actionKey(action);
      if (this.actionArgs[key]) return;
      const args = {};
      this.paramsFromSchema((action.parameters || {}).properties || action.parameters || {}).forEach(param => {
        args[param.name] = param.defaultValue;
      });
      this.$set(this.actionArgs, key, args);
    },
    paramsFromSchema(properties) {
      return Object.entries(properties || {}).map(([name, schema]) => ({
        name,
        type: schema.type || 'string',
        min: schema.minimum !== undefined ? schema.minimum : 0,
        max: schema.maximum !== undefined ? schema.maximum : 180,
        defaultValue: schema.default !== undefined ? schema.default : schema.type === 'integer' ? 0 : schema.type === 'boolean' ? false : ''
      })).filter(param => param.name !== 'action');
    },
    toolProps(tool) {
      return (tool && tool.inputSchema && tool.inputSchema.properties) || {};
    },
    findToolByName(name) {
      return this.tools.find(tool => tool.name === name);
    },
    request(executor) {
      return new Promise(resolve => executor(resolve)).then(({ data }) => {
        if (!data || data.code !== 0) throw new Error((data && data.msg) || '请求失败');
        return data.data;
      });
    },
    extractResult(envelope) {
      return this.pick(envelope, 'data.data.result') ||
        this.pick(envelope, 'data.result') ||
        this.pick(envelope, 'result') ||
        this.pick(envelope, 'data') ||
        envelope;
    },
    extractToolText(envelope) {
      const result = this.extractResult(envelope);
      const content = result && result.content;
      if (Array.isArray(content)) return content.map(item => item.text || JSON.stringify(item)).join('\n');
      return typeof result === 'string' ? result : JSON.stringify(result, null, 2);
    },
    latestData() {
      return this.extractResult(this.latest) || {};
    },
    parseJsonFromText(text) {
      const value = String(text || '').trim();
      const start = value.indexOf('{');
      const end = value.lastIndexOf('}');
      if (start < 0 || end < start) return null;
      try {
        return JSON.parse(value.slice(start, end + 1));
      } catch (error) {
        return null;
      }
    },
    setResult(text, kind) {
      this.resultText = text || '';
      if (kind === 'bad') this.$message.error(text);
    },
    pick(obj, path) {
      return path.split('.').reduce((value, key) => value && value[key] !== undefined ? value[key] : undefined, obj);
    },
    formatBytes(value) {
      if (value === undefined || value === null || value === '') return undefined;
      const n = Number(value);
      if (!Number.isFinite(n)) return value;
      if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
      if (n >= 1024) return `${(n / 1024).toFixed(1)} KB`;
      return `${n} B`;
    }
  }
};
</script>

<style scoped>
.otto-page {
  min-width: 960px;
  height: 100vh;
  background: #eef2f6;
  color: #171a1f;
  overflow: hidden;
}

.debug-header {
  height: 74px;
  padding: 0 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #14181e;
  color: #fff;
  box-shadow: 0 1px 0 rgba(255, 255, 255, .06);
}

.debug-brand {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.debug-brand strong {
  color: #fff;
  font-size: 21px;
  line-height: 1;
  letter-spacing: 0;
  white-space: nowrap;
}

.debug-brand span {
  color: #f3bd80;
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.debug-status {
  color: #98a2b3;
  font-size: 16px;
  white-space: nowrap;
}

.debug-layout {
  height: calc(100vh - 74px);
  display: grid;
  grid-template-columns: minmax(360px, 430px) minmax(0, 1fr);
  gap: 18px;
  padding: 18px;
  overflow: hidden;
}

.side-panel,
.work-panel {
  min-height: 0;
  background: #fff;
  border: 1px solid #dfe5ee;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 14px 36px rgba(15, 23, 42, .08);
}

.side-panel {
  display: flex;
  flex-direction: column;
}

.viewer-card {
  flex: 0 0 auto;
  background: #fbf4e9;
  border-bottom: 1px solid #e6edf5;
}

.preview {
  height: clamp(350px, 45vh, 500px);
  position: relative;
  background: #fbf4e9;
}

.robot-twin {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, #fbf4e9 0%, #f6efe3 100%);
}

.preview-footer {
  min-height: 58px;
  padding: 10px 16px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fbf4e9;
}

.preview-footer span {
  min-width: 0;
  color: #667085;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side-body,
.debug-tabs ::v-deep .el-tab-pane {
  padding: 18px;
}

.side-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: #fff;
}

.connect-row,
.action-toolbar,
.frame-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  margin-bottom: 12px;
}

.connect-row + .connect-row {
  grid-template-columns: 1fr 1fr;
}

.status-line {
  margin: 12px 0;
  color: #667085;
  font-size: 15px;
  line-height: 1.45;
}

.muted { color: #87909f; }
.ok { color: #067647; }
.warn { color: #b54708; }
.bad { color: #d92d20; }

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.metric {
  min-height: 58px;
  padding: 10px 12px;
  border: 1px solid #edf1f7;
  border-radius: 7px;
  background: #fbfcfe;
}

.metric span {
  display: block;
  color: #87909f;
  font-size: 12px;
}

.metric strong {
  display: block;
  margin-top: 5px;
  color: #182230;
  font-size: 16px;
  overflow-wrap: anywhere;
}

.status-collapse {
  margin-top: 12px;
}

.note {
  padding: 10px 12px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  color: #9a3412;
  border-radius: 7px;
  margin-bottom: 12px;
}

.work-panel {
  display: flex;
  flex-direction: column;
}

.debug-tabs {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.debug-tabs ::v-deep .el-tabs__header {
  margin: 0;
  padding: 14px 18px;
  background: #fbf7ef;
  border-bottom: 1px solid #e6edf5;
}

.debug-tabs ::v-deep .el-tabs__nav-wrap::after {
  display: none;
}

.debug-tabs ::v-deep .el-tabs__active-bar {
  display: none;
}

.debug-tabs ::v-deep .el-tabs__nav {
  display: flex;
  gap: 12px;
}

.debug-tabs ::v-deep .el-tabs__item {
  height: 46px;
  padding: 0 18px !important;
  display: inline-flex;
  align-items: center;
  border: 1px solid #d7e0ec;
  border-radius: 7px;
  background: #fff;
  color: #182230;
  font-size: 16px;
  line-height: 46px;
}

.debug-tabs ::v-deep .el-tabs__item.is-active {
  background: #f45116;
  border-color: #f45116;
  color: #fff;
  font-weight: 700;
}

.debug-tabs ::v-deep .el-tabs__content {
  min-height: 0;
}

.category-row,
.frame-actions,
.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
}

.category-row .el-button {
  margin-left: 0;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
}

.action-card,
.tool-item,
.frame-item,
.tool-detail,
.servo-card {
  border: 1px solid #e4eaf2;
  border-radius: 8px;
  background: #fff;
}

.action-card {
  min-height: 118px;
  padding: 12px;
  display: grid;
  gap: 8px;
}

.action-card span,
.tool-item span {
  color: #667085;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.param-row {
  display: grid;
  gap: 5px;
}

.param-row label,
label {
  font-size: 12px;
  color: #87909f;
}

.servo-head {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto auto;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}

.servo-grid,
.frame-list,
.tool-list {
  display: grid;
  gap: 8px;
}

.servo-card {
  padding: 12px;
}

.servo-title {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.servo-title span {
  color: #f45116;
  font-weight: 700;
}

.frame-actions .el-button {
  flex: 1;
  min-width: 110px;
}

.frame-item {
  padding: 10px;
}

.frame-item code {
  display: block;
  color: #87909f;
  overflow-wrap: anywhere;
}

.tool-workbench {
  display: grid;
  grid-template-columns: minmax(240px, .9fr) minmax(320px, 1.1fr);
  gap: 14px;
}

.tool-item {
  padding: 10px;
  cursor: pointer;
}

.tool-item.active {
  border-color: #f45116;
  background: #fff7ed;
}

.tool-detail {
  padding: 12px;
}

pre {
  white-space: pre-wrap;
  word-break: break-word;
  background: #17120d;
  color: #d0d5dd;
  padding: 12px;
  border-radius: 8px;
  margin: 8px 0 12px;
  overflow: auto;
}

.empty {
  max-width: 360px;
  color: #87909f;
  padding: 8px 0;
  font-size: 16px;
  line-height: 1.45;
}

.otto-page ::v-deep .el-button--primary {
  background-color: #f45116;
  border-color: #f45116;
}

.otto-page ::v-deep .el-button--primary:hover,
.otto-page ::v-deep .el-button--primary:focus {
  background-color: #e54810;
  border-color: #e54810;
}

.otto-page ::v-deep .el-button--danger.is-plain {
  background: #fff5f5;
}

.otto-page ::v-deep .el-input__inner {
  height: 42px;
  border-color: #d7e0ec;
  border-radius: 7px;
  font-size: 16px;
}

.otto-page ::v-deep .el-button {
  border-radius: 7px;
  font-size: 15px;
}

.otto-page ::v-deep .el-button--mini {
  font-size: 13px;
}

@media (max-width: 1240px) {
  .otto-page {
    min-width: 0;
    overflow: auto;
  }

  .debug-layout,
  .tool-workbench {
    height: auto;
    grid-template-columns: 1fr;
  }
}
</style>
