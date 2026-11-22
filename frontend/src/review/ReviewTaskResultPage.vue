<script setup>
import { computed,onMounted,ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'

const route=useRoute(),result=ref(null),error=ref(''),retrying=ref({})
async function load(){try{result.value=await api(`/api/review/tasks/${route.params.id}/result`)}catch(e){error.value=e.message}}
async function retryReview(itemId){
 retrying.value[itemId]=true;error.value=''
 try{result.value=await api(`/api/review/tasks/${route.params.id}/items/${itemId}/retry-review`,{method:'POST'})}
 catch(e){error.value=e.message}
 finally{retrying.value[itemId]=false}
}
onMounted(load)

const summary=computed(()=>{
 const items=result.value?.items||[]
 return{
  total:items.length,
  correct:items.filter(i=>i.result?.passed===true).length,
  wrong:items.filter(i=>i.result?.passed===false).length,
  pending:items.filter(i=>i.result?.grading_status==='AI_REVIEW_PENDING').length,
  failed:items.filter(i=>i.result?.grading_status==='AI_REVIEW_FAILED').length
 }
})
function statusText(i){
 const s=i.result?.grading_status
 if(s==='AI_REVIEW_PENDING')return '待建议评分'
 if(s==='AI_REVIEW_FAILED')return '评分失败，可重试'
 return i.result?.passed?'正确':'错误'
}
function statusClass(i){
 if(i.result?.passed===true)return 'p-result-correct'
 if(i.result?.passed===false)return 'p-result-wrong'
 return 'r-pending'
}
function score(v){return v==null?'—':Number(v).toFixed(1)}
function myAnswer(item){
 const v=item.submittedAnswer
 if(v==null)return '未作答'
 if(item.type==='CHOICE')return v.selectedOption||'未作答'
 if(item.type==='FILL'){
  const b=v.blanks||[]
  return b.length?b.map((x,i)=>`第 ${i+1} 空：${x||'（空）'}`).join('；'):'未作答'
 }
 const parts=[]
 if(v.text)parts.push(v.text)
 if(v.code)parts.push('代码：\n'+v.code)
 return parts.length?parts.join('\n\n'):'未作答'
}
function referenceAnswer(item){
 const v=item.correctAnswer
 if(v==null)return '—'
 if(item.type==='CHOICE')return v.correctOption||'—'
 if(item.type==='FILL'){
  const b=v.blanks||[]
  return b.map((x,i)=>`第 ${i+1} 空：${(x.accepted||[]).join(' / ')||'—'}`).join('；')
 }
 return v.referenceAnswer||JSON.stringify(v)
}
</script>

<template>
<div class="practice-shell">
 <header class="practice-top">
  <div><span class="practice-kicker">REVIEW RESULT</span><h1 class="practice-title">复习任务结果</h1></div>
  <RouterLink to="/review">返回推荐</RouterLink>
 </header>

 <p v-if="error" class="p-error" role="alert">{{error}}</p>

 <template v-if="result">
  <div class="p-card">
   <div class="p-actions">
    <span class="p-pill">共 {{summary.total}} 题</span>
    <span class="p-success">正确 {{summary.correct}}</span>
    <span class="p-error">错误 {{summary.wrong}}</span>
    <span v-if="summary.pending" class="p-muted">待建议评分 {{summary.pending}}</span>
    <span v-if="summary.failed" class="p-muted">评分失败 {{summary.failed}}</span>
   </div>
   <p class="p-muted r-note">主观题当前只进入建议评分流程，分数为参考值，不构成正式成绩；掌握度评估会在评分完成后更新。</p>
  </div>

  <article v-for="item in result.items" :key="item.itemId" class="p-card" :class="statusClass(item)">
   <div class="p-actions">
    <strong>第 {{item.position+1}} 题</strong>
    <span class="p-pill">{{item.type}}</span>
    <span>{{statusText(item)}}</span>
    <span v-if="item.result?.grading_mode==='LLM_SUGGESTED'&&item.result?.grading_status==='AUTO_GRADED'" class="p-muted">
     建议分 {{score(item.result?.raw_score)}} / {{score(item.maxScore)}} · 归一化 {{score(item.result?.normalized_score)}}
    </span>
    <span v-else class="p-muted">得分 {{score(item.result?.raw_score)}} / {{score(item.maxScore)}}</span>
   </div>
   <h2 class="r-stem">{{item.content.stem}}</h2>

   <div class="p-grid p-two r-block">
    <div><div class="r-label">我的答案</div><pre class="r-pre">{{myAnswer(item)}}</pre></div>
    <div><div class="r-label">参考答案</div><pre class="r-pre">{{referenceAnswer(item)}}</pre></div>
   </div>

   <p v-if="item.explanation"><strong>解析：</strong>{{item.explanation}}</p>

   <details v-if="item.rubric" class="r-details">
    <summary>评分要点</summary>
    <pre class="r-pre">{{JSON.stringify(item.rubric,null,2)}}</pre>
   </details>

   <div v-if="item.assets?.length" class="p-assets r-assets">
    <a v-for="a in item.assets" :key="a.id" :href="a.url" target="_blank"><img :src="a.url" alt="作答附件"></a>
   </div>

   <div v-if="item.result?.missing_points?.length" class="r-block">
    <div class="r-label">缺失要点</div>
    <ul><li v-for="(mp,idx) in item.result.missing_points" :key="idx">{{mp}}</li></ul>
   </div>
   <p v-if="item.result?.reason" class="p-muted r-note"><strong>AI 评语：</strong>{{item.result.reason}}</p>

   <p v-if="item.result?.grading_status==='AI_REVIEW_PENDING'&&item.result?.unavailable_reason" class="p-muted r-note">
    AI 暂不可用（{{item.result.unavailable_reason}}），已保留待评分状态
   </p>
   <p v-else-if="item.result?.grading_status==='AI_REVIEW_PENDING'" class="p-muted r-note">
    这道主观题已保存，等待 AI 给出建议评分；评分完成前不计入掌握度。
   </p>
   <p v-else-if="item.result?.grading_status==='AI_REVIEW_FAILED'" class="p-error">
    AI 评分未完成，本次不计入掌握度
    <button class="p-secondary" :disabled="retrying[item.itemId]" @click="retryReview(item.itemId)">
     {{retrying[item.itemId]?'重试中…':'重试评分'}}
    </button>
   </p>
   <p v-else-if="item.result?.provisional" class="p-muted r-note">
    {{item.result.suggestion_disclaimer || 'AI 建议分不构成正式成绩'}}
   </p>
  </article>

  <div class="p-card p-actions">
   <RouterLink class="p-primary" to="/review">再来一组</RouterLink>
   <RouterLink to="/">返回首页</RouterLink>
  </div>
 </template>
 <p v-else-if="!error" class="p-muted">正在加载结果…</p>
</div>
</template>

<style scoped>
.r-pending{border-left:4px solid #c9a227}
.r-stem{margin:12px 0}
.r-block{margin:12px 0}
.r-label{color:#697386;font-size:13px;margin-bottom:6px}
.r-pre{margin:0;white-space:pre-wrap;word-break:break-word;background:#f8fafb;border:1px solid #e6ebf1;border-radius:9px;padding:12px;font:13px/1.7 Consolas,monospace;color:#22303f}
.r-details{margin:10px 0}
.r-details summary{cursor:pointer;color:#286a64;font-size:14px}
.r-assets{margin-top:12px}
.r-note{font-size:13px;line-height:1.7}
</style>
