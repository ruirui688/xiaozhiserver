<template>
  <div class="memory-page">
    <div class="topbar">
      <div>
        <div class="title">记忆知识图谱</div>
        <div class="subtitle">{{ agentName || agentId }}</div>
      </div>
      <div class="topright">
        <el-input v-model="search" placeholder="搜索记忆..." size="small" clearable prefix-icon="el-icon-search" style="width:200px" @input="onSearch" />
        <el-button size="small" icon="el-icon-refresh" @click="loadData" :loading="loading">刷新</el-button>
        <el-button size="small" icon="el-icon-back" @click="$router.push('/home')">返回</el-button>
      </div>
    </div>

    <div class="statsbar">
      <div class="st"><b>{{ facts.length }}</b><span>事实</span></div>
      <div class="st blue"><b>{{ autoCount }}</b><span>自动</span></div>
      <div class="st"><b>{{ catCount }}</b><span>分类</span></div>
      <div class="st red"><b>{{ tombstones.length }}</b><span>忘记</span></div>
    </div>

    <div class="toolbar">
      <el-input v-model="rememberText" placeholder="记住..." size="small" clearable @keyup.enter.native="remember" style="flex:1" />
      <el-button type="primary" size="small" @click="remember">记住</el-button>
      <el-input v-model="forgetText" placeholder="忘记..." size="small" clearable @keyup.enter.native="forget" style="flex:1;margin-left:10px" />
      <el-button type="danger" size="small" @click="forget">忘记</el-button>
      <span class="hint">{{ hint }}</span>
    </div>

    <div class="graph-box" ref="box">
      <svg ref="svg" class="svg" @mousedown="onPan" @wheel.prevent="onZoom" @dblclick="resetView">
        <g :transform="`translate(${px},${py}) scale(${s})`">
          <line v-for="e in lines" :key="e.id" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
            :stroke="e.hl ? '#3f6fd8' : '#d4dae8'" :stroke-width="e.hl ? 2 : 1" />
          <g v-for="n in nodes" :key="n.id"
            :transform="`translate(${n.x},${n.y})`"
            :opacity="n.dim ? 0.12 : 1"
            @click.stop="pick(n)"
            @mousedown.stop="dragN($event,n)">
            <!-- fact card -->
            <template v-if="n.kind==='fact'">
              <rect :x="-n.w/2" :y="-n.h/2" :width="n.w" :height="n.h" rx="6"
                :fill="n.fill" :stroke="n===picked ? '#3f6fd8' : n.stroke" :stroke-width="n===picked ? 2.5 : 1"
                :filter="n===picked ? 'url(#sd)' : ''" />
              <text :x="-n.w/2+10" y="-4" font-size="11" font-weight="600" fill="#2f3548" text-anchor="start">
                {{ n.txt }}
              </text>
              <text :x="-n.w/2+10" y="12" font-size="9" fill="#909399" text-anchor="start">
                {{ n.sub }}
              </text>
            </template>
            <!-- circle node -->
            <template v-else>
              <circle :r="n.r" :fill="n.fill" :stroke="n===picked ? '#3f6fd8' : n.stroke" :stroke-width="n===picked ? 3 : 2"
                :filter="n===picked ? 'url(#sd)' : ''" />
              <text text-anchor="middle" :y="n.r+15" font-size="12" font-weight="700" fill="#2f3548">{{ n.txt }}</text>
              <text text-anchor="middle" :y="n.r+29" font-size="10" fill="#909399">{{ n.sub }}</text>
            </template>
          </g>
        </g>
      </svg>
      <div class="empty" v-if="!nodes.length && !loading">暂无记忆，与设备对话后自动生成</div>
    </div>

    <div class="legend">
      <span><i class="d" style="background:#3f6fd8"></i>智能体</span>
      <span><i class="d" style="background:#7c6fd6"></i>分类</span>
      <span><i class="d" style="background:#e8f4f0"></i>自动记忆</span>
      <span><i class="d" style="background:#f0ece6"></i>手动记忆</span>
      <span><i class="d" style="background:#d85858"></i>忘记规则</span>
      <span class="dim">点击节点查看详情 · 拖拽背景平移 · 滚轮缩放 · 双击复位</span>
    </div>

    <div class="detail" v-if="picked">
      <div class="dh">
        <span><el-tag size="mini" :type="picked.kind==='fact'?'success':picked.kind==='tombstone'?'danger':''">{{ kindLabel(picked.kind) }}</el-tag> <b style="margin-left:6px">{{ picked.rec ? (picked.rec.canonicalText||picked.rec.patternText||'') : '' }}</b></span>
        <el-button size="mini" icon="el-icon-close" circle @click="picked=null" />
      </div>
      <div class="dg" v-if="picked.rec && picked.kind==='fact'">
        <div class="dr"><span>分类</span><span>{{ catText(picked.rec.category) }}</span></div>
        <div class="dr"><span>类型</span><span>{{ picked.rec.memoryType||'长期记忆' }}</span></div>
        <div class="dr"><span>来源</span><span>{{ picked.rec.confirmationState==='auto'?'🤖 自动抽取':'📝 手动创建' }}</span></div>
        <div class="dr"><span>重要性</span><el-progress :percentage="pct(picked.rec.importance)" :stroke-width="6" style="width:100px" /></div>
        <div class="dr"><span>留存率</span><el-progress :percentage="pct(picked.rec.confidence)" :stroke-width="6" style="width:100px" /></div>
      </div>
      <div class="da" v-if="picked.kind==='fact'">
        <el-button size="mini" @click="openEdit(picked.rec)">编辑</el-button>
        <el-button size="mini" @click="archiveFact(picked.rec)">归档</el-button>
        <el-button size="mini" type="danger" @click="deleteFact(picked.rec)">删除</el-button>
      </div>
    </div>

    <el-dialog title="编辑记忆" :visible.sync="editVisible" width="440px">
      <el-form :model="editForm" label-width="70px" size="small">
        <el-form-item label="类型"><el-input v-model="editForm.memoryType" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="editForm.category" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="editForm.canonicalText" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="重要性"><el-slider v-model="editForm.importance" :min="0" :max="1" :step="0.05" show-input /></el-form-item>
        <el-form-item label="置信度"><el-slider v-model="editForm.confidence" :min="0" :max="1" :step="0.05" show-input /></el-form-item>
      </el-form>
      <span slot="footer"><el-button size="small" @click="editVisible=false">取消</el-button><el-button type="primary" size="small" @click="saveEdit">保存</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import Api from '@/apis/api';

const C = { agent:'#3f6fd8', cat:'#7c6fd6', factAuto:'#e8f4f0', factManual:'#f0ece6', tombstone:'#fff0f0' };
const S = { agent:'#2a50b5', cat:'#5e4fc1', factAuto:'#b8d8cc', factManual:'#d8d0c4', tombstone:'#d85858' };

export default {
  name: 'MemoryManagement',
  data() {
    return {
      agentId:'', agentName:'', loading:false, search:'',
      rememberText:'', forgetText:'',
      editVisible:false, editMemoryId:'', editForm:{},
      dashboard:{facts:[],candidates:[],tombstones:[],revisions:[],toolBehaviors:[]},
      nodes:[], lines:[], picked:null,
      px:0, py:0, s:1,
      dragNid:null, dragOx:0, dragOy:0,
      panning:false, panSx:0, panSy:0,
      hint:'点击节点查看详情 · 拖拽背景平移 · 滚轮缩放 · 双击复位'
    };
  },
  computed: {
    facts(){ return (this.dashboard.facts||[]).filter(f=>f.status!=='deleted'); },
    autoCount(){ return this.facts.filter(f=>f.confirmationState==='auto').length; },
    catCount(){ return new Set(this.facts.map(f=>f.category||'未分类')).size; },
    tombstones(){ return (this.dashboard.tombstones||[]).filter(t=>t.status==='active'); }
  },
  mounted(){
    this.agentId=this.$route.query.agentId||'';
    this.agentName=this.$route.query.agentName||'';
    if(!this.agentId){this.$message.error('缺少智能体ID');this.$router.push('/home');return;}
    this.loadData();
    this._mm=this.onMouseMove.bind(this);this._mu=this.onMouseUp.bind(this);
    window.addEventListener('mousemove',this._mm);window.addEventListener('mouseup',this._mu);
  },
  beforeDestroy(){window.removeEventListener('mousemove',this._mm);window.removeEventListener('mouseup',this._mu);},
  methods:{
    pct(v){return Math.round((Number(v)||0)*100);},
    catText(v){const m={explicit:'明确记住',manual:'手动维护'};return m[v]||v||'未分类';},
    kindLabel(t){const m={agent:'智能体',cat:'分类',fact:'事实记忆',tombstone:'忘记规则'};return m[t]||t;},

    loadData(){
      this.loading=true;
      Api.memory.dashboard(this.agentId,({data})=>{
        this.dashboard=data.data||this.dashboard;
        this.loading=false;
        this.$nextTick(()=>this.build());
      },()=>{this.loading=false;});
    },

    build(){
      const W=this.$refs.svg?.clientWidth||1000, H=580;
      const cx=80, cy=H/2;
      const facts=this.facts;
      const tombstones=this.tombstones;

      // Group by category
      const catMap=new Map();
      facts.forEach(f=>{const c=f.category||'未分类';if(!catMap.has(c))catMap.set(c,[]);catMap.get(c).push(f);});

      const cats=Array.from(catMap.entries());
      const nodes=[],lines=[];
      let nid=0;

      // Agent
      const aid=++nid;
      nodes.push({id:aid,kind:'agent',txt:this.agentName||'智能体',sub:`${facts.length} 条`,r:32,x:cx,y:cy,fill:C.agent,stroke:S.agent});

      // Categories vertical stack
      const catStartY=Math.max(cy-(cats.length-1)*90,60);
      const catX=220;
      const catNodes=[];
      cats.forEach(([name,items],i)=>{
        const y=catStartY+i*90;
        const id=++nid;
        nodes.push({id,kind:'cat',txt:this.catText(name),sub:`${items.length} 条`,r:22,x:catX,y,fill:C.cat,stroke:S.cat});
        catNodes.push({id,x:catX,y,items});
        lines.push({id:`l-${aid}-${id}`,x1:cx+32,y1:cy,x2:catX-22,y2:y,hl:false});
      });

      // Facts as cards per category, in columns
      const factStartX=380;
      const cardW=170, cardH=40, cardGap=48;
      const maxPerCat=8;
      catNodes.forEach(cn=>{
        cn.items.forEach((item,i)=>{
          const col=Math.floor(i/maxPerCat);
          const row=i%maxPerCat;
          const x=factStartX+col*(cardW+20);
          const y=cn.y-maxPerCat*cardGap/2+row*cardGap;
          const isAuto=item.confirmationState==='auto';
          const isFact=!item.candidateId;
          const txt=(item.canonicalText||item.patternText||'?');
          const short=txt.length>14?txt.slice(0,14)+'…':txt;
          const id=++nid;
          nodes.push({id,kind:'fact',txt:short,sub:isFact?(isAuto?'自动':'手动'):'候选',
            w:cardW,h:cardH,x,y,fill:isFact?(isAuto?C.factAuto:C.factManual):'#fff7e9',
            stroke:isFact?(isAuto?S.factAuto:S.factManual):'#d89a2b',
            rec:item});
          lines.push({id:`l-${cn.id}-${id}`,x1:cn.x+22,y1:cn.y,x2:x-cardW/2,y2:y,hl:false});
        });
      });

      // Tombstones at bottom
      const tombX=factStartX+Math.ceil(facts.length/maxPerCat)*(cardW+20)+60;
      tombstones.forEach((t,i)=>{
        const y=60+i*36;
        const id=++nid;
        const txt=(t.patternText||'忘记').slice(0,10);
        nodes.push({id,kind:'tombstone',txt,sub:'忘记',r:10,x:tombX,y,fill:C.tombstone,stroke:S.tombstone,rec:t});
        lines.push({id:`l-${aid}-${id}`,x1:cx+32,y1:cy,x2:tombX-10,y2:y,hl:false});
      });

      this.nodes=nodes;this.lines=lines;this.picked=null;
    },

    pick(n){this.picked=n;this.search='';this.nodes.forEach(x=>{x.hl=(x===n);x.dim=false;});this.lines.forEach(l=>l.hl=(l.id.includes('-'+n.id+'-')||l.id.endsWith('-'+n.id)));},
    onSearch(){const q=this.search.toLowerCase();this.nodes.forEach(n=>{const m=q&&((n.txt||'').toLowerCase().includes(q)||(n.sub||'').toLowerCase().includes(q));n.dim=q?!m:false;n.hl=q&&m;});},
    resetView(){this.px=0;this.py=0;this.s=1;this.picked=null;},

    dragN(e,n){if(e.button!==0)return;this.dragNid=n.id;const r=this.$refs.svg.getBoundingClientRect();this.dragOx=(e.clientX-r.left-this.px)/this.s-n.x;this.dragOy=(e.clientY-r.top-this.py)/this.s-n.y;},
    onPan(e){if(e.target===this.$refs.svg||e.target.tagName==='svg'){this.panning=true;this.panSx=e.clientX-this.px;this.panSy=e.clientY-this.py;}},
    onMouseMove(e){
      if(this.dragNid){const n=this.nodes.find(x=>x.id===this.dragNid);if(!n)return;const r=this.$refs.svg.getBoundingClientRect();n.x=(e.clientX-r.left-this.px)/this.s-this.dragOx;n.y=(e.clientY-r.top-this.py)/this.s-this.dragOy;this.nodes=[...this.nodes];}
      else if(this.panning){this.px=e.clientX-this.panSx;this.py=e.clientY-this.panSy;}
    },
    onMouseUp(){this.dragNid=null;this.panning=false;},
    onZoom(e){this.s=Math.max(0.3,Math.min(2.5,this.s*(e.deltaY>0?0.92:1.08)));},

    remember(){if(!this.rememberText.trim())return;Api.memory.remember(this.agentId,{text:this.rememberText.trim(),category:'manual'},()=>{this.$message.success('已写入');this.rememberText='';this.loadData();});},
    forget(){if(!this.forgetText.trim())return;Api.memory.forget(this.agentId,{text:this.forgetText.trim(),reason:'管理端手动忘记'},()=>{this.$message.success('已加入');this.forgetText='';this.loadData();});},
    openEdit(r){this.editMemoryId=r.memoryId;this.editForm={memoryType:r.memoryType,category:r.category,canonicalText:r.canonicalText,confidence:Number(r.confidence||.5),importance:Number(r.importance||.5)};this.editVisible=true;},
    saveEdit(){Api.memory.updateFact(this.agentId,this.editMemoryId,this.editForm,()=>{this.$message.success('已保存');this.editVisible=false;this.loadData();});},
    archiveFact(r){Api.memory.archiveFact(this.agentId,r.memoryId,()=>{this.$message.success('已归档');this.loadData();});},
    deleteFact(r){this.$confirm('确定删除？','提示',{type:'warning'}).then(()=>{Api.memory.deleteFact(this.agentId,r.memoryId,()=>{this.$message.success('已删除');this.loadData();});}).catch(()=>{});}
  }
};
</script>

<style scoped>
.memory-page{min-height:100vh;padding:20px 24px;box-sizing:border-box;background:#f5f7fb;}
.topbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;}
.title{font-size:22px;font-weight:700;color:#2f3548;}
.subtitle{font-size:12px;color:#909399;margin-top:2px;}
.topright{display:flex;gap:8px;align-items:center;}
.statsbar{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:8px;}
.st{padding:10px 14px;border-radius:8px;background:#fff;border:1px solid #e4e8f1;text-align:center;}
.st b{font-size:24px;font-weight:700;color:#2f3548;display:block;}
.st span{font-size:11px;color:#909399;}
.st.blue b{color:#3f6fd8;}.st.red b{color:#e05555;}
.toolbar{display:flex;gap:8px;align-items:center;padding:8px 12px;border-radius:8px;background:#fff;border:1px solid #e4e8f1;margin-bottom:8px;}
.hint{font-size:11px;color:#b0b8c8;margin-left:auto;white-space:nowrap;}
.graph-box{position:relative;width:100%;height:580px;border-radius:8px;background:#fff;border:1px solid #e4e8f1;overflow:hidden;}
.svg{width:100%;height:100%;display:block;}
.empty{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);font-size:14px;color:#c0c4cc;}
.legend{display:flex;gap:14px;margin-top:8px;font-size:11px;color:#909399;padding:6px 12px;background:#fff;border-radius:6px;border:1px solid #e4e8f1;}
.d{width:7px;height:7px;border-radius:50%;display:inline-block;margin-right:3px;}
.dim{color:#b0b8c8;margin-left:auto;}
.detail{margin-top:10px;padding:14px;border-radius:8px;background:#fff;border:1px solid #e4e8f1;}
.dh{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;}
.dg{display:grid;gap:8px;}
.dr{display:flex;justify-content:space-between;align-items:center;font-size:13px;color:#606266;}
.dr span:last-child{font-weight:500;color:#303133;}
.da{display:flex;gap:8px;margin-top:12px;padding-top:10px;border-top:1px solid #ebeef5;}
@media(max-width:768px){.statsbar{grid-template-columns:repeat(2,1fr)}.toolbar{flex-wrap:wrap}.hint{display:none}}
</style>
