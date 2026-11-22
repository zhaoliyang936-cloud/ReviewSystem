<script setup>
import { computed,onBeforeUnmount,onMounted,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { api } from '../api'

const route=useRoute(),router=useRouter()
const task=ref(null),index=ref(0),answers=ref({}),message=ref(''),error=ref(''),saving=ref(false),uploading=ref(false),submitting=ref(false)
const preparing=ref(false),prepareTimedOut=ref(false),prepareFailed=ref(false),prepareFailureReason=ref('')
const submitKey=crypto.randomUUID()
let timer,pollTimer,pollStartedAt=0

const item=computed(()=>task.value?.items[index.value])
const answer=computed({get:()=>ensureAnswer(item.value)||{},set:v=>{if(item.value)answers.value[item.value.id]=isPlainObject(v)?v:emptyAnswer(item.value)}})
const mix=computed(()=>task.value?.mixRatio||null)
const degradedCount=computed(()=>mix.value?.degraded?.length||0)

function blankCount(content){
 const stem=content?.stem||''
 return (stem.match(/\{\{blank\}\}/g)||[]).length||1
}
function isPlainObject(v){return v!==null&&typeof v==='object'&&!Array.isArray(v)}
function emptyAnswer(i){
 if(i.questionType==='CHOICE')return{selectedOption:''}
 if(i.questionType==='FILL')return{blanks:Array.from({length:blankCount(i.content)},()=>'')}
 return{text:'',code:''}
}
function ensureAnswer(i){
 if(!i)return null
 if(!isPlainObject(answers.value[i.id]))answers.value[i.id]=emptyAnswer(i)
 return answers.value[i.id]
}
function initial(i){
 // i.draft 是 Vue 响应式 Proxy，structuredClone 会抛 DataCloneError 并中断 load() 的初始化循环，必须用 JSON 深拷贝
 if(isPlainObject(i.draft))return JSON.parse(JSON.stringify(i.draft))
 return emptyAnswer(i)
}
async function initReadyTask(){
 preparing.value=false
 prepareTimedOut.value=false
 if(task.value.status==='PREPARATION_FAILED'){
  prepareFailed.value=true
  prepareFailureReason.value=task.value.mixRatio?.preparationFailure?.reason||'AI_WORKER_STALE'
  return
 }
 prepareFailed.value=false
 for(const i of task.value.items)answers.value[i.id]=initial(i)
 if(task.value.status==='READY')task.value=await api(`/api/review/tasks/${route.params.id}/start`,{method:'POST'})
 else if(task.value.status==='COMPLETED')router.replace(`/review/tasks/${route.params.id}/result`)
 else if(task.value.status==='CANCELLED')error.value='这个任务已取消，无法继续作答。'
}
async function pollPreparing(){
 const POLL_MAX_MS=Math.max(120000,((task.value?.preparationTimeoutSeconds??90)*1000)+30000)
 if(Date.now()-pollStartedAt>=POLL_MAX_MS){
  try{
   task.value=await api(`/api/review/tasks/${route.params.id}`)
   if(task.value.status!=='PREPARING'){await initReadyTask();return}
  }catch(e){error.value=e.message}
  preparing.value=false;prepareTimedOut.value=true;return
 }
 try{
  task.value=await api(`/api/review/tasks/${route.params.id}`)
  if(task.value.status!=='PREPARING'){await initReadyTask();return}
  pollTimer=setTimeout(pollPreparing,1000)
 }catch(e){preparing.value=false;error.value=e.message}
}
async function load(){
 error.value='';prepareTimedOut.value=false;prepareFailed.value=false
 try{
  task.value=await api(`/api/review/tasks/${route.params.id}`)
  if(task.value.status==='PREPARING'){
   preparing.value=true;pollStartedAt=Date.now();pollTimer=setTimeout(pollPreparing,1000);return
  }
  await initReadyTask()
 }catch(e){error.value=e.message}
}
async function save(){
 clearTimeout(timer)
 if(!item.value||task.value.status!=='IN_PROGRESS')return
 const payload=ensureAnswer(item.value)
 saving.value=true;message.value=''
 try{
  const saved=await api(`/api/review/tasks/${task.value.id}/items/${item.value.id}/draft`,{method:'PUT',body:{answer:payload,draftVersion:item.value.draftVersion}})
  item.value.draftVersion=saved.draftVersion
  message.value='已保存'
 }catch(e){
  if(e.code==='STATE_CONFLICT')message.value='草稿版本冲突，已重新加载'
  else error.value=`未保存：${e.message}`
  if(e.code==='STATE_CONFLICT')await load()
 }finally{saving.value=false}
}
async function move(next){await save();index.value=next;error.value=''}
watch(answers,()=>{clearTimeout(timer);message.value='';timer=setTimeout(save,5000)},{deep:true})

async function upload(event){
 const file=event.target.files?.[0]
 if(!file)return
 uploading.value=true;error.value=''
 try{
  const body=new FormData();body.append('file',file)
  const made=await api(`/api/review/tasks/${task.value.id}/items/${item.value.id}/assets`,{method:'POST',body})
  item.value.assets.push(made)
 }catch(e){error.value=e.message}finally{uploading.value=false;event.target.value=''}
}
async function removeAsset(asset){
 if(!confirm('删除这张作答图片？'))return
 try{
  await api(`/api/review/tasks/${task.value.id}/items/${item.value.id}/assets/${asset.id}`,{method:'DELETE'})
  item.value.assets=item.value.assets.filter(a=>a.id!==asset.id)
 }catch(e){error.value=e.message}
}
function unanswered(){
 return task.value.items.filter(i=>{
  const a=answers.value[i.id]||{}
  if(i.questionType==='CHOICE')return !a.selectedOption
  if(i.questionType==='FILL')return !(a.blanks||[]).some(v=>v&&v.trim())
  return !a.text?.trim()&&!a.code?.trim()
 }).length
}
async function submit(){
 await save()
 const left=unanswered()
 if(!confirm(left?`还有 ${left} 题未作答，仍要提交吗？`:'提交后不能再修改，继续吗？'))return
 submitting.value=true;error.value=''
 try{
  await api(`/api/review/tasks/${task.value.id}/submit`,{method:'POST',headers:{'Idempotency-Key':submitKey},body:{answers:task.value.items.map(i=>({itemId:i.id,answer:ensureAnswer(i),assetIds:(i.assets||[]).map(a=>a.id)}))}})
  router.push(`/review/tasks/${task.value.id}/result`)
 }catch(e){error.value=e.message}finally{submitting.value=false}
}
onMounted(load)
onBeforeUnmount(()=>{clearTimeout(timer);clearTimeout(pollTimer)})
</script>

<template>
<div class="practice-shell">
 <header class="practice-top">
  <div><span class="practice-kicker">复习任务</span><h1 class="practice-title">第 {{index+1}} / {{task?.items.length||0}} 题</h1></div>
  <RouterLink to="/review">返回推荐</RouterLink>
 </header>

 <p v-if="error" class="p-error" role="alert">{{error}}</p>

 <div v-if="mix" class="p-card r-mix">
  <span class="p-pill">共 {{task.questionCount}} 题</span>
  <span>计划 题库 {{mix.planned?.bank??0}} + AI {{mix.planned?.ai??0}}</span>
  <span class="p-muted">实际 题库 {{mix.actual?.bank??0}} + AI {{mix.actual?.ai??0}}</span>
  <span v-if="mix?.shortage && (task?.status === 'PREPARING' || (mix?.actual?.ai ?? 0) > 0)" class="p-muted">题库可用题不足 {{ mix.shortage.bankShortfall }} 题，已由 AI 补位</span>
  <span v-else-if="mix?.shortage" class="p-muted">题库可用题不足 {{ mix.shortage.bankShortfall }} 题，本次共 {{ mix.shortage.actual }} 题</span>
  <span v-if="degradedCount" class="p-muted">{{degradedCount}} 个 AI 槽位已由题库题补位</span>
 </div>

 <div v-if="preparing" class="p-card">
  <p>正在准备题目（AI {{mix?.planned?.ai??3}} 题）…</p>
  <button class="p-primary" disabled>开始作答</button>
 </div>
 <div v-else-if="prepareFailed" class="p-card">
  <p class="p-error">题目准备失败（{{prepareFailureReason}}），请返回重新创建。</p>
  <RouterLink class="p-secondary" to="/review">返回重新创建</RouterLink>
 </div>
 <div v-else-if="prepareTimedOut" class="p-card">
  <p class="p-error">题目仍在准备中，可以稍后刷新；若长时间无变化，请返回重新创建。</p>
  <button class="p-secondary" @click="load">刷新题目</button>
 </div>
 <template v-else-if="item">
  <div class="p-card">
   <div class="p-actions">
    <span class="p-pill">{{item.questionType}}</span>
    <span>{{item.maxScore}} 分</span>
    <span class="p-muted">{{saving?'保存中…':message}}</span>
   </div>
   <h2 class="r-stem">{{item.content.stem}}</h2>
   <p v-if="item.reason" class="p-muted r-reason">
    推荐理由：{{item.reason.pool==='REVIEW'?'复习池':'诊断池'}} · 优先级 {{Number(item.reason.priority??0).toFixed(3)}} · 薄弱 {{Number(item.reason.weakness??0).toFixed(2)}} · 距上次 {{Number(item.reason.daysSincePractice??0).toFixed(1)}} 天
   </p>

   <div v-if="item.questionType==='CHOICE'" class="p-options">
    <label v-for="o in item.content.options" :key="o.key" class="p-option">
     <input v-model="answer.selectedOption" type="radio" :value="o.key">{{o.key}}. {{o.text}}
    </label>
   </div>

   <div v-else-if="item.questionType==='FILL'">
    <label v-for="(_,i) in answer.blanks" :key="i" class="p-field">第 {{i+1}} 空
     <input v-model="answer.blanks[i]">
    </label>
   </div>

   <template v-else>
    <label class="p-field">文字作答
     <textarea v-model="answer.text" placeholder="写下思路与说明"></textarea>
    </label>
    <label class="p-field">代码（可选）
     <textarea v-model="answer.code" class="p-code" placeholder="输入伪代码或程序代码"></textarea>
    </label>
    <div class="p-assets">
     <span v-for="a in item.assets" :key="a.id">
      <img :src="a.url" alt="作答附件">
      <button class="p-secondary" @click="removeAsset(a)">删除</button>
     </span>
    </div>
    <label class="p-field">作答图片（最多 3 张，PNG/JPEG）
     <input type="file" accept="image/png,image/jpeg" :disabled="uploading||item.assets.length>=3" @change="upload">
    </label>
   </template>
  </div>

  <div class="p-card">
   <div class="p-progress">
    <button v-for="(_,i) in task.items" :key="i" :class="{active:i===index}" @click="move(i)">{{i+1}}</button>
   </div>
   <div class="p-actions r-actions">
    <button class="p-secondary" :disabled="index===0" @click="move(index-1)">上一题</button>
    <button class="p-secondary" :disabled="index===task.items.length-1" @click="move(index+1)">下一题</button>
    <button class="p-primary" :disabled="submitting" @click="submit">{{submitting?'提交中…':'提交任务'}}</button>
   </div>
   <p class="p-muted r-reason">任务开始后不能取消；草稿每隔 5 秒自动保存一次。</p>
  </div>
 </template>
 <p v-else-if="!error" class="p-muted">正在加载任务…</p>
</div>
</template>

<style scoped>
.r-mix{display:flex;gap:14px;flex-wrap:wrap;align-items:center;font-size:14px;padding:14px 20px}
.r-stem{margin:12px 0 6px}
.r-reason{font-size:13px;margin:6px 0 12px}
.r-actions{margin-top:18px}
</style>
