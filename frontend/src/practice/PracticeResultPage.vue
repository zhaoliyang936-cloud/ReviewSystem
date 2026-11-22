<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'

const route = useRoute(), result = ref(null), error = ref(''), retrying = ref({})

async function load() {
  try {
    result.value = await api(`/api/practices/${route.params.id}/result`)
  } catch (e) {
    error.value = e.message
  }
}

async function retryReview(itemId) {
  retrying.value[itemId] = true
  error.value = ''
  try {
    result.value = await api(`/api/practices/${route.params.id}/items/${itemId}/retry-review`, { method: 'POST' })
  } catch (e) {
    error.value = e.message
  } finally {
    retrying.value[itemId] = false
  }
}

function label(item) {
  if (item.result.grading_status === 'AI_REVIEW_PENDING') return '待建议评分'
  if (item.result.grading_status === 'AI_REVIEW_FAILED') return 'AI 评分失败'
  return item.result.passed ? '正确' : '错误'
}
function score(value) { return value == null ? '—' : Number(value).toFixed(1) }
function display(value) { if (value == null) return '—'; return typeof value === 'object' ? JSON.stringify(value) : String(value) }

onMounted(load)
</script>

<template>
  <div class="practice-shell">
    <header class="practice-top">
      <div><span class="practice-kicker">PRACTICE RESULT</span><h1 class="practice-title">练习结果</h1></div>
      <RouterLink to="/practice/history">练习历史</RouterLink>
    </header>
    <p v-if="error" class="p-error" role="alert">{{error}}</p>
    <template v-if="result">
      <article v-for="item in result.items" :key="item.itemId" class="p-card" :class="{'p-result-correct':item.result.passed===true,'p-result-wrong':item.result.passed===false}">
        <div class="p-actions"><strong>第 {{item.position+1}} 题</strong><span class="p-pill">{{item.type}}</span><span>{{label(item)}}</span></div>
        <h2>{{item.content.stem}}</h2>
        <p v-if="item.result.grading_mode==='LLM_SUGGESTED'&&item.result.grading_status==='AUTO_GRADED'">
          建议分：{{score(item.result.raw_score)}} / {{score(item.result.max_score)}} · 归一化：{{score(item.result.normalized_score)}} · 通过：{{item.result.passed?'是':'否'}}
        </p>
        <p v-else>得分：{{score(item.result.raw_score)}} / {{score(item.result.max_score)}} · 归一化：{{score(item.result.normalized_score)}} · 通过：{{item.result.passed==null?'—':item.result.passed?'是':'否'}}</p>
        <p><strong>我的答案：</strong>{{display(item.submittedAnswer)}}</p>
        <p><strong>参考答案：</strong>{{display(item.correctAnswer)}}</p>
        <p v-if="item.explanation"><strong>解析：</strong>{{item.explanation}}</p>
        <div v-if="item.rubric"><strong>评分要点：</strong><pre>{{JSON.stringify(item.rubric,null,2)}}</pre></div>
        <div v-if="item.result.missing_points?.length">
          <strong>缺失要点：</strong>
          <ul><li v-for="(mp, idx) in item.result.missing_points" :key="idx">{{mp}}</li></ul>
        </div>
        <p v-if="item.result.reason" class="p-muted"><strong>AI 评语：</strong>{{item.result.reason}}</p>
        <p v-if="item.result.grading_status==='AI_REVIEW_PENDING'&&item.result.unavailable_reason" class="p-muted">
          AI 暂不可用（{{item.result.unavailable_reason}}），已保留待评分状态
        </p>
        <p v-else-if="item.result.grading_status==='AI_REVIEW_FAILED'" class="p-error">
          AI 评分未完成，本次不计入掌握度
          <button class="p-secondary" :disabled="retrying[item.itemId]" @click="retryReview(item.itemId)">
            {{retrying[item.itemId] ? '重试中…' : '重试评分'}}
          </button>
        </p>
        <p v-else-if="item.result.provisional" class="p-muted">{{item.result.suggestion_disclaimer || 'AI 建议分不构成正式成绩'}}</p>
      </article>
      <div class="p-card p-actions"><RouterLink class="p-primary" to="/practice/new">再练一次</RouterLink><RouterLink to="/">返回首页</RouterLink></div>
    </template>
  </div>
</template>
