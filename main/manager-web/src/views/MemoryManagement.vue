<template>
  <div class="kg-page">
    <!-- Header -->
    <header class="kg-header">
      <div>
        <h1 class="kg-title">记忆知识图谱</h1>
        <p class="kg-sub">{{ agentName || agentId }}</p>
      </div>
      <div class="kg-header-r">
        <el-input v-model="search" placeholder="搜索记忆..." size="small" clearable prefix-icon="el-icon-search" style="width:220px" @input="doSearch" />
        <el-button size="small" icon="el-icon-refresh" @click="fetch" :loading="loading">刷新</el-button>
        <el-button size="small" icon="el-icon-back" @click="$router.push('/')">返回</el-button>
      </div>
    </header>

    <!-- Stats -->
    <div class="kg-stats">
      <div class="kg-stat"><em>{{ facts.length }}</em><span>事实记忆</span></div>
      <div class="kg-stat kg-stat--auto"><em>{{ autoN }}</em><span>自动抽取</span></div>
      <div class="kg-stat"><em>{{ cats.length }}</em><span>记忆分类</span></div>
      <div class="kg-stat kg-stat--warn"><em>{{ tombs.length }}</em><span>忘记规则</span></div>
    </div>

    <!-- Toolbar -->
    <div class="kg-toolbar">
      <div class="kg-toolbar-l">
        <el-input v-model="rememberText" placeholder="输入需要记住的内容…" size="small" clearable @keyup.enter.native="remember" style="width:240px" />
        <el-button type="primary" size="small" icon="el-icon-plus" @click="remember">记住</el-button>
        <el-input v-model="forgetText" placeholder="输入需要忘记的内容…" size="small" clearable @keyup.enter.native="forget" style="width:240px;margin-left:16px" />
        <el-button type="danger" size="small" icon="el-icon-delete" @click="forget">忘记</el-button>
      </div>
      <span class="kg-hint">拖拽画布平移 · 滚轮缩放 · 点击节点查看详情 · 双击空白复位</span>
    </div>

    <!-- Canvas -->
    <div class="kg-canvas" ref="canvas" @dblclick="resetView">
      <svg ref="svg" class="kg-svg"
        @mousedown="startPan"
        @wheel.prevent="doZoom"
        @mousemove="doMove"
        @mouseup="endDrag"
        @mouseleave="endDrag">
        <defs>
          <filter id="drop"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="#00000018" /></filter>
          <filter id="dropHl"><feDropShadow dx="0" dy="3" stdDeviation="6" flood-color="#3f6fd844" /></filter>
          <marker id="arrowhead" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6 Z" fill="#c8d0e0" /></marker>
        </defs>

        <!-- Background grid -->
        <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
          <circle cx="20" cy="20" r="1" fill="#e8ecf4" />
        </pattern>
        <rect width="100%" height="100%" fill="url(#grid)" />

        <g :transform="`translate(${px},${py}) scale(${zoom})`">
          <!-- Edges: bezier curves -->
          <path v-for="e in edges" :key="e.id"
            :d="e.path"
            fill="none"
            :stroke="e.hl ? '#3f6fd8' : '#c8d0e0'"
            :stroke-width="e.hl ? 2.5 : 1.5"
            :opacity="e.hl ? 1 : 0.5"
            marker-end="url(#arrowhead)"
            style="transition: stroke 0.3s, opacity 0.3s" />

          <!-- Nodes -->
          <g v-for="n in nodes" :key="n.id"
            :transform="`translate(${n.x},${n.y})`"
            :opacity="n.dim ? 0.1 : 1"
            class="kg-node"
            @click.stop="pick(n)"
            @mousedown.stop="startDrag($event, n)">
            <!-- Agent node (center, large circle) -->
            <template v-if="n.type==='agent'">
              <circle r="42" fill="#eef4ff" stroke="#3f6fd8" stroke-width="2.5"
                :filter="n===picked?'url(#dropHl)':'url(#drop)'" />
              <text text-anchor="middle" dy="-4" font-size="14" font-weight="700" fill="#2f3548">{{ n.label }}</text>
              <text text-anchor="middle" dy="14" font-size="11" fill="#687186">{{ n.sub }}</text>
            </template>

            <!-- Category node (medium circle) -->
            <template v-else-if="n.type==='cat'">
              <circle :r="26" :fill="n.bg" :stroke="n===picked?'#3f6fd8':n.stroke" stroke-width="2"
                :filter="n===picked?'url(#dropHl)':'url(#drop)'" />
              <text text-anchor="middle" dy="-2" font-size="12" font-weight="600" fill="#2f3548">{{ n.label }}</text>
              <text text-anchor="middle" dy="13" font-size="10" fill="#687186">{{ n.sub }}</text>
            </template>

            <!-- Fact card -->
            <template v-else-if="n.type==='fact'">
              <rect :x="-n.w/2" :y="-n.h/2" :width="n.w" :height="n.h" rx="8"
                :fill="n.bg" :stroke="n===picked?'#3f6fd8':n.stroke" stroke-width="1.5"
                :filter="n===picked?'url(#dropHl)':'url(#drop)'"
                style="transition: stroke 0.2s" />
              <text :x="-n.w/2+14" y="-6" font-size="13" font-weight="600" fill="#2f3548" text-anchor="start">
                <tspan v-if="n.line1.length <= 16">{{ n.line1 }}</tspan>
                <tspan v-else>{{ n.line1.slice(0, 16) }}…</tspan>
              </text>
              <text :x="-n.w/2+14" y="12" font-size="11" fill="#687186" text-anchor="start">
                {{ n.sub }}
              </text>
              <rect :x="n.w/2-14" y="-n.h/2+4" width="10" height="10" rx="5" :fill="n.dot" />
            </template>

            <!-- Tombstone -->
            <template v-else-if="n.type==='tomb'">
              <rect :x="-n.w/2" :y="-n.h/2" :width="n.w" :height="n.h" rx="6"
                :fill="n.bg" :stroke="n===picked?'#e05555':n.stroke" stroke-width="1.5"
                :filter="n===picked?'url(#dropHl)' : ''" />
              <text :x="-n.w/2+10" y="-4" font-size="12" font-weight="600" fill="#c53030" text-anchor="start">{{ n.label }}</text>
              <text :x="-n.w/2+10" y="12" font-size="10" fill="#718096" text-anchor="start">{{ n.sub }}</text>
            </template>
          </g>
        </g>
      </svg>
      <div class="kg-empty" v-if="!nodes.length && !loading">暂无记忆数据，开始对话后自动生成</div>
    </div>

    <!-- Legend -->
    <div class="kg-legend">
      <span><i class="kg-dot" style="background:#3f6fd8"></i> 智能体</span>
      <span><i class="kg-dot" style="background:#7c6fd6"></i> 分类</span>
      <span><i class="kg-dot" style="background:#2ecc71"></i> 自动记忆</span>
      <span><i class="kg-dot" style="background:#f0b060"></i> 手动记忆</span>
      <span><i class="kg-dot" style="background:#e05555"></i> 忘记规则</span>
    </div>

    <!-- Detail panel -->
    <transition name="slide">
      <div class="kg-detail" v-if="picked">
        <div class="kg-detail-bar">
          <span>
            <el-tag size="mini" :type="picked.type==='fact'?'success':picked.type==='tomb'?'danger':''">{{ typeLabel(picked.type) }}</el-tag>
            <strong>{{ picked.rec ? (picked.rec.canonicalText||picked.rec.patternText||'') : '' }}</strong>
          </span>
          <el-button size="mini" icon="el-icon-close" circle @click="picked=null" />
        </div>
        <div class="kg-detail-info" v-if="picked.type==='fact' && picked.rec">
          <div class="kg-detail-row"><span>分类</span><span>{{ catLabel(picked.rec.category) }}</span></div>
          <div class="kg-detail-row"><span>类型</span><span>{{ picked.rec.memoryType || '长期记忆' }}</span></div>
          <div class="kg-detail-row"><span>来源</span><span>{{ picked.rec.confirmationState==='auto' ? '自动抽取' : '手动创建' }}</span></div>
          <div class="kg-detail-row">
            <span>重要性</span>
            <el-progress :percentage="pct(picked.rec.importance)" :stroke-width="6" style="width:120px" :color="pct(picked.rec.importance)>=70?'#2ecc71':pct(picked.rec.importance)>=40?'#f0b060':'#909399'" />
          </div>
          <div class="kg-detail-row">
            <span>留存率</span>
            <el-progress :percentage="pct(picked.rec.confidence)" :stroke-width="6" style="width:120px" :color="pct(picked.rec.confidence)>=50?'#3f6fd8':pct(picked.rec.confidence)>=25?'#f0b060':'#e05555'" />
          </div>
          <div class="kg-detail-row"><span>创建时间</span><span>{{ picked.rec.createdAt || '-' }}</span></div>
        </div>
        <div class="kg-detail-info" v-else-if="picked.rec">
          <div class="kg-detail-row"><span>内容</span><span>{{ picked.rec.patternText || '-' }}</span></div>
          <div class="kg-detail-row"><span>原因</span><span>{{ picked.rec.reason || '-' }}</span></div>
        </div>
        <div class="kg-detail-actions" v-if="picked.type==='fact'">
          <el-button size="small" icon="el-icon-edit" @click="openEdit(picked.rec)">编辑</el-button>
          <el-button size="small" icon="el-icon-folder" @click="archiveFact(picked.rec)">归档</el-button>
          <el-button size="small" type="danger" icon="el-icon-delete" @click="deleteFact(picked.rec)">删除</el-button>
        </div>
      </div>
    </transition>

    <!-- Edit dialog -->
    <el-dialog title="编辑记忆" :visible.sync="editVisible" width="460px">
      <el-form :model="editForm" label-width="70px" size="small">
        <el-form-item label="类型"><el-input v-model="editForm.memoryType" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="editForm.category" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="editForm.canonicalText" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="重要性"><el-slider v-model="editForm.importance" :min="0" :max="1" :step="0.05" show-input /></el-form-item>
        <el-form-item label="置信度"><el-slider v-model="editForm.confidence" :min="0" :max="1" :step="0.05" show-input /></el-form-item>
      </el-form>
      <span slot="footer"><el-button size="small" @click="editVisible=false">取消</el-button><el-button type="primary" size="small" @click="saveEdit">保存</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import Api from '@/apis/api';

export default {
  name: 'MemoryManagement',
  data: () => ({
    agentId:'', agentName:'', loading:false, search:'',
    rememberText:'', forgetText:'',
    editVisible:false, editMemoryId:'', editForm:{},
    dashboard:{facts:[],candidates:[],tombstones:[],revisions:[],toolBehaviors:[]},
    nodes:[], edges:[], picked:null,
    px:0, py:0, zoom:1,
    dragging:null, dragOffX:0, dragOffY:0,
    panning:false, panSX:0, panSY:0
  }),
  computed: {
    facts(){return (this.dashboard.facts||[]).filter(f=>f.status!=='deleted')},
    autoN(){return this.facts.filter(f=>f.confirmationState==='auto').length},
    cats(){return [...new Set(this.facts.map(f=>f.category||'未分类'))]},
    tombs(){return (this.dashboard.tombstones||[]).filter(t=>t.status==='active')}
  },
  mounted(){
    this.agentId=this.$route.query.agentId||'';this.agentName=this.$route.query.agentName||'';
    if(!this.agentId){this.$message.error('缺少智能体ID');this.$router.push('/');return}
    this.fetch()
  },
  methods: {
    pct(v){return Math.round((Number(v)||0)*100)},
    catLabel(v){const m={explicit:'明确记住',manual:'手动维护'};return m[v]||v||'未分类'},
    typeLabel(t){const m={agent:'智能体',cat:'分类',fact:'事实记忆',tomb:'忘记规则'};return m[t]||t},

    fetch(){
      this.loading=true;
      Api.memory.dashboard(this.agentId,({data})=>{
        this.dashboard=data.data||this.dashboard;this.loading=false;
        this.$nextTick(()=>this.buildGraph())
      },()=>{this.loading=false})
    },

    buildGraph(){
      const W=this.$refs.svg?.clientWidth||1100, H=600;
      const facts=this.facts, tombs=this.tombs;
      const nodes=[], edges=[]; let nid=0;

      // Agent center
      const ax=90, ay=H/2;
      const aid=++nid;
      nodes.push({id:aid,type:'agent',label:this.agentName||'智能体',sub:`${facts.length} 条事实`,x:ax,y:ay});

      // Group facts by category
      const groups=new Map();
      facts.forEach(f=>{const c=f.category||'未分类';if(!groups.has(c))groups.set(c,[]);groups.get(c).push(f)});
      const catList=Array.from(groups.entries());

      // Category layout — evenly spaced vertically
      const catX=260, catSpacing=Math.min(110, Math.max(80, (H-100)/Math.max(catList.length,1)));
      const catStartY=ay-(catList.length-1)*catSpacing/2;
      const catNodes=[];

      catList.forEach(([name,items],i)=>{
        const cy=catStartY+i*catSpacing;
        const cid=++nid;
        const hue=(i*137.508)%360;
        const bg=`hsl(${hue},45%,92%)`, stroke=`hsl(${hue},40%,65%)`;
        nodes.push({id:cid,type:'cat',label:this.catLabel(name),sub:`${items.length} 条`,bg,stroke,x:catX,y:cy});
        catNodes.push({id:cid,x:catX,y:cy,items});
        edges.push({id:`e-${aid}-${cid}`,path:this.curve(ax+42,ay,catX-26,cy),hl:false});
      });

      // Fact cards — columns per category
      const cardW=190, cardH=52, cardGapX=210, cardGapY=60;
      const factStartX=440;
      let globalCol=0;

      catNodes.forEach(cn=>{
        const perCol=Math.min(8, Math.ceil(cn.items.length/Math.ceil(cn.items.length/8)));
        cn.items.forEach((item,i)=>{
          const col=Math.floor(i/perCol);
          const row=i%perCol;
          const x=factStartX+col*cardGapX;
          const y=cn.y-(perCol-1)*cardGapY/2+row*cardGapY;
          const isAuto=item.confirmationState==='auto';
          const fid=++nid;
          const text=item.canonicalText||item.patternText||'?';
          nodes.push({
            id:fid,type:'fact',
            line1:text, sub:isAuto?'自动抽取':'手动创建',
            w:cardW,h:cardH, x,y,
            bg:isAuto?'#f0faf5':'#fef9f0',
            stroke:isAuto?'#b8e0cc':'#e8d8b8',
            dot:isAuto?'#2ecc71':'#f0b060',
            rec:item
          });
          edges.push({id:`e-${cn.id}-${fid}`,path:this.curve(cn.x+26,cn.y,x-cardW/2,y),hl:false});
          globalCol=Math.max(globalCol,col+1);
        });
      });

      // Tombstones
      const tombX=factStartX+globalCol*cardGapX+40;
      tombs.forEach((t,i)=>{
        const y=60+i*40;
        const tid=++nid;
        nodes.push({id:tid,type:'tomb',label:(t.patternText||'?').slice(0,12),sub:t.reason||'忘记规则',w:150,h:36,x:tombX,y,bg:'#fff5f5',stroke:'#f5c0c0',rec:t});
        edges.push({id:`e-${aid}-${tid}`,path:this.curve(ax+42,ay,tombX-75,y),hl:false});
      });

      this.nodes=nodes;this.edges=edges;this.picked=null;
      this.px=0;this.py=0;this.zoom=1;
    },

    curve(x1,y1,x2,y2){
      const dx=Math.abs(x2-x1)*0.5;
      return `M${x1},${y1} C${x1+dx},${y1} ${x2-dx},${y2} ${x2},${y2}`;
    },

    pick(n){
      this.picked=n;this.search='';
      this.nodes.forEach(x=>{x.hl=(x===n);x.dim=false});
      this.edges.forEach(e=>e.hl=(e.id.includes(`-${n.id}`)));
    },

    doSearch(){
      const q=this.search.toLowerCase();
      this.nodes.forEach(n=>{
        const m=q&&((n.line1||n.label||'').toLowerCase().includes(q)||(n.sub||'').toLowerCase().includes(q));
        n.dim=q?!m:false;n.hl=q&&m;
      });
    },

    startDrag(e,n){if(e.button!==0)return;this.dragging=n;const r=this.$refs.svg.getBoundingClientRect();this.dragOffX=(e.clientX-r.left-this.px)/this.zoom-n.x;this.dragOffY=(e.clientY-r.top-this.py)/this.zoom-n.y},
    startPan(e){if(e.target===this.$refs.svg||e.target.tagName==='svg'){this.panning=true;this.panSX=e.clientX-this.px;this.panSY=e.clientY-this.py}},
    doMove(e){
      if(this.dragging){const r=this.$refs.svg.getBoundingClientRect();this.dragging.x=(e.clientX-r.left-this.px)/this.zoom-this.dragOffX;this.dragging.y=(e.clientY-r.top-this.py)/this.zoom-this.dragOffY;this.nodes=[...this.nodes]}
      else if(this.panning){this.px=e.clientX-this.panSX;this.py=e.clientY-this.panSY}
    },
    endDrag(){this.dragging=null;this.panning=false},
    doZoom(e){this.zoom=Math.max(0.3,Math.min(2.5,this.zoom*(e.deltaY>0?0.92:1.08)))},
    resetView(){this.px=0;this.py=0;this.zoom=1;this.picked=null},

    remember(){if(!this.rememberText.trim())return;Api.memory.remember(this.agentId,{text:this.rememberText.trim(),category:'manual'},()=>{this.$message.success('已写入');this.rememberText='';this.fetch()})},
    forget(){if(!this.forgetText.trim())return;Api.memory.forget(this.agentId,{text:this.forgetText.trim(),reason:'管理端手动忘记'},()=>{this.$message.success('已加入');this.forgetText='';this.fetch()})},
    openEdit(r){this.editMemoryId=r.memoryId;this.editForm={memoryType:r.memoryType,category:r.category,canonicalText:r.canonicalText,confidence:Number(r.confidence||.5),importance:Number(r.importance||.5)};this.editVisible=true},
    saveEdit(){Api.memory.updateFact(this.agentId,this.editMemoryId,this.editForm,()=>{this.$message.success('已保存');this.editVisible=false;this.fetch()})},
    archiveFact(r){Api.memory.archiveFact(this.agentId,r.memoryId,()=>{this.$message.success('已归档');this.fetch()})},
    deleteFact(r){this.$confirm('确定删除这条记忆吗？','提示',{type:'warning'}).then(()=>{Api.memory.deleteFact(this.agentId,r.memoryId,()=>{this.$message.success('已删除');this.fetch()})}).catch(()=>{})}
  }
};
</script>

<style scoped>
.kg-page{min-height:100vh;padding:24px;box-sizing:border-box;background:#f7f8fc;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}
.kg-header{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:16px}
.kg-title{font-size:24px;font-weight:800;color:#1a202c;margin:0;letter-spacing:-0.5px}
.kg-sub{font-size:13px;color:#718096;margin:3px 0 0}
.kg-header-r{display:flex;gap:10px;align-items:center}

.kg-stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:12px}
.kg-stat{display:flex;flex-direction:column;align-items:center;padding:16px;border-radius:12px;background:#fff;border:1px solid #e8ecf4;box-shadow:0 1px 3px rgba(0,0,0,.04)}
.kg-stat em{font-size:30px;font-weight:800;color:#1a202c;font-style:normal;line-height:1}
.kg-stat span{font-size:12px;color:#718096;margin-top:4px}
.kg-stat--auto em{color:#3f6fd8}
.kg-stat--warn em{color:#e05555}

.kg-toolbar{display:flex;align-items:center;padding:10px 16px;border-radius:10px;background:#fff;border:1px solid #e8ecf4;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.04)}
.kg-toolbar-l{display:flex;gap:8px;align-items:center;flex:1}
.kg-hint{font-size:11px;color:#a0aec0;white-space:nowrap}

.kg-canvas{position:relative;width:100%;height:580px;border-radius:12px;background:#fff;border:1px solid #e8ecf4;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.06)}
.kg-svg{width:100%;height:100%;display:block}
.kg-node{cursor:pointer;transition:opacity .25s}
.kg-empty{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);font-size:15px;color:#cbd5e0}

.kg-legend{display:flex;gap:20px;margin-top:10px;font-size:12px;color:#718096;padding:8px 16px;background:#fff;border-radius:8px;border:1px solid #e8ecf4}
.kg-dot{width:9px;height:9px;border-radius:50%;display:inline-block;margin-right:5px}

.kg-detail{margin-top:12px;padding:18px;border-radius:12px;background:#fff;border:1px solid #e8ecf4;box-shadow:0 2px 8px rgba(0,0,0,.06)}
.kg-detail-bar{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px}
.kg-detail-bar strong{font-size:15px;color:#1a202c;margin-left:8px}
.kg-detail-info{display:grid;gap:10px;margin-bottom:14px}
.kg-detail-row{display:flex;justify-content:space-between;align-items:center;font-size:13px;color:#4a5568}
.kg-detail-row span:last-child{font-weight:500;color:#1a202c;text-align:right;max-width:300px}
.kg-detail-actions{display:flex;gap:10px;padding-top:14px;border-top:1px solid #e8ecf4}

.slide-enter-active,.slide-leave-active{transition:all .3s ease}
.slide-enter,.slide-leave-to{opacity:0;transform:translateY(-10px)}

@media(max-width:768px){.kg-stats{grid-template-columns:repeat(2,1fr)}.kg-toolbar{flex-wrap:wrap}.kg-hint{display:none}.kg-canvas{height:400px}}
</style>
