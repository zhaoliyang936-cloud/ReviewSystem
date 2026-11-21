<script setup>
import { onMounted,ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

const router=useRouter()
const data=ref(null),count=ref(10),error=ref(''),notice=ref(''),busy=ref(false)
const key=crypto.randomUUID()

async function load(){
 error.value=''
 try{data.value=await api(`/api/review/recommendations?count=${count.value}`)}catch(e){error.value=e.message}
}
async function generate(){
 busy.value=true;error.value='';notice.value=''
 try{
  const created=await api('/api/review/tasks',{method:'POST',headers:{'Idempotency-Key':key},body:{questionCount:count.value}})
  router.push(`/review/tasks/${created.id}`)
 }catch(e){
  if(e.code==='ASSESSMENT_UPDATING')notice.value='掌握度评估仍在更新，请等几秒后再试。'
  else if(e.code==='STATE_CONFLICT'&&e.activityId)notice.value=`还有进行中的${e.kind==='TASK'?'复习任务':'普通练习'}，请先完成它。`
  else if(e.code==='NO_AVAILABLE_QUESTION')notice.value='当前没有可用的推荐题目，先完成一次练习再回来。'
  else error.value=e.message
 }finally{busy.value=false}
}
function num(v,d=2){return v==null?'—':Number(v).toFixed(d)}
onMounted(load)
</script>

<template>
<div class="practice-shell">
 <header class="practice-top">
  <div><span class="practice-kicker">REVIEW</span><h1 class="practice-title">复习任务</h1></div>
  <RouterLink to="/">返回首页</RouterLink>
 </header>

 <p v-if="error" class="p-error" role="alert">{{error}}</p>
 <p v-if="notice" class="p-notice" role="status">{{notice}}</p>

 <div class="p-card">
  <div class="p-actions">
   <label class="r-label">题目数量
    <select v-model.number="count" @change="load">
     <option v-for="n in 10" :key="n" :value="n">{{n}}</option>
    </select>
   </label>
   <button class="p-primary" :disabled="busy" @click="generate">{{busy?'正在生成…':'生成复习任务'}}</button>
   <button class="p-secondary" :disabled="busy" @click="load">重新计算推荐</button>
  </div>
  <p v-if="data" class="p-muted r-note">
   规则版本 {{data.ruleVersion}}；优先级 = {{num(data.parameters.weaknessWeight)}}×薄弱 + {{num(data.parameters.timeWeight)}}×间隔 + {{num(data.parameters.errorWeight)}}×错误率。
   复习池 {{num(data.parameters.reviewSlots,0)}} 个、诊断池 {{num(data.parameters.diagnosticSlots,0)}} 个名额，n≥{{data.parameters.reviewMinimumN}} 进入复习池。
  </p>
  <p v-if="data?.updating" class="p-notice">掌握度评估仍在更新中，下面的分数可能稍有滞后。</p>
 </div>

 <template v-if="data">
  <div class="p-card">
   <h2 class="r-h2">知识点优先级</h2>
   <table class="p-table">
    <thead><tr><th>知识点</th><th>池</th><th>优先级</th><th>已答证据数 n</th></tr></thead>
    <tbody>
     <tr v-for="k in data.plannedKnowledge" :key="k.knowledgeId">
      <td>{{k.knowledgeCode}}</td>
      <td><span class="p-pill">{{k.pool==='REVIEW'?'复习':'诊断'}}</span></td>
      <td>{{num(k.priority,3)}}</td>
      <td>{{k.n}}</td>
     </tr>
     <tr v-if="!data.plannedKnowledge.length"><td colspan="4" class="p-muted">暂无可用知识点</td></tr>
    </tbody>
   </table>
  </div>

  <div class="p-card">
   <h2 class="r-h2">选题明细（{{data.items.length}} 题）</h2>
   <table class="p-table">
    <thead><tr><th>#</th><th>知识点</th><th>题型</th><th>池</th><th>M_final</th><th>薄弱</th><th>间隔</th><th>错误率</th><th>说明</th></tr></thead>
    <tbody>
     <tr v-for="(it,i) in data.items" :key="it.questionId+'-'+i">
      <td>{{i+1}}</td>
      <td>{{it.knowledgeCode}}</td>
      <td>{{it.questionType}}</td>
      <td><span class="p-pill">{{it.reason.pool==='REVIEW'?'复习':'诊断'}}</span></td>
      <td>{{num(it.reason.finalScore)}}</td>
      <td>{{num(it.reason.weakness)}}</td>
      <td>{{num(it.reason.daysSincePractice,1)}}</td>
      <td>{{it.reason.errorDenominator?`${it.reason.errorNumerator}/${it.reason.errorDenominator}`:'—'}}</td>
      <td class="p-muted">{{it.reason.selectionCategory}}<template v-if="it.reason.cooldownRelaxed">（放宽冷却）</template></td>
     </tr>
     <tr v-if="!data.items.length"><td colspan="9" class="p-muted">没有可推荐的题目</td></tr>
    </tbody>
   </table>
   <p v-for="s in data.shortfall" :key="s.pool" class="p-muted r-note">{{s.pool}}池缺少 {{s.missing}} 题（{{s.reason}}）。</p>
  </div>
 </template>
 <p v-else-if="!error" class="p-muted">正在计算推荐…</p>
</div>
</template>

<style scoped>
.r-label{display:flex;align-items:center;gap:8px;color:#3b475c}
.r-label select{border:1px solid #cfd6e1;border-radius:8px;padding:7px 10px;font:inherit;background:#fff}
.r-note{margin:12px 0 0;font-size:13px;line-height:1.7}
.r-h2{margin:0 0 14px;font-size:18px}
.p-notice{background:#fdf6e8;border:1px solid #f0dcb4;color:#7a5a17;border-radius:10px;padding:11px 14px;margin:12px 0}
.p-table td,.p-table th{font-size:14px}
</style>
