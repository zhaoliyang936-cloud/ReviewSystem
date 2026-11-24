<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import AiTutoringPanel from '../ai/AiTutoringPanel.vue'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

const route = useRoute()
const result = ref(null)
const error = ref('')
const retrying = ref({})
const explainJobs = ref({})
const explainBusy = ref({})
const explainErrors = ref({})
const explainErrorCodes = ref({})
let pollTimers = {}

const totals = computed(() => {
  const items = result.value?.items || []
  let evaluatedRaw = 0
  let evaluatedMax = 0
  let pendingCount = 0
  for (const it of items) {
    if (it.result?.raw_score != null) {
      evaluatedRaw += Number(it.result.raw_score)
      evaluatedMax += Number(it.result?.max_score ?? it.maxScore ?? 0)
    } else {
      pendingCount++
    }
  }
  return { evaluatedRaw, evaluatedMax, pendingCount, totalCount: items.length }
})

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

function pillTone(item) {
  if (item.result.grading_status === 'AI_REVIEW_PENDING') return 'warn'
  if (item.result.grading_status === 'AI_REVIEW_FAILED') return 'danger'
  return item.result.passed ? 'success' : 'danger'
}

function score(value) {
  return value == null ? '—' : Number(value).toFixed(1)
}

function display(value) {
  if (value == null) return '—'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

async function requestExplanation(item) {
  if (!item.wrongQuestionId) return
  const key = item.itemId
  explainErrors.value[key] = ''
  explainErrorCodes.value[key] = ''
  explainBusy.value[key] = true
  clearTimeout(pollTimers[key])
  try {
    const job = await api('/api/review/ai/jobs', {
      method: 'POST',
      body: { scenario: 'EXPLANATION', wrongQuestionId: Number(item.wrongQuestionId) }
    })
    explainJobs.value[key] = job
    if (job.state === 'QUEUED' || job.state === 'RUNNING') {
      const tick = async () => {
        try {
          const res = await api(`/api/review/ai/jobs/${job.jobId}`)
          if (res.state === 'QUEUED' || res.state === 'RUNNING') {
            pollTimers[key] = setTimeout(tick, 1000)
          } else {
            explainJobs.value[key] = res
            explainBusy.value[key] = false
          }
        } catch (e) {
          explainBusy.value[key] = false
          explainErrors.value[key] = e.message || '获取 AI 讲解失败'
        }
      }
      pollTimers[key] = setTimeout(tick, 600)
    } else {
      explainBusy.value[key] = false
    }
  } catch (e) {
    explainBusy.value[key] = false
    explainErrorCodes.value[key] = e.code || ''
    explainErrors.value[key] = e.message || '获取 AI 讲解失败'
  }
}

onBeforeUnmount(() => Object.values(pollTimers).forEach(clearTimeout))
onMounted(load)
</script>

<template>
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">PRACTICE RESULT</span>
        <h1 class="ui-title">练习结果</h1>
      </div>
      <RouterLink to="/practice/history">练习历史</RouterLink>
    </header>

    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

    <template v-if="result">
      <BaseCard>
        <div class="result-summary">
          <div>
            <div class="ui-muted">总分</div>
            <div class="result-total ui-num">
              <template v-if="totals.pendingCount === totals.totalCount">—</template>
              <template v-else>
                {{ score(totals.evaluatedRaw) }} <span class="result-max">/ {{ score(totals.evaluatedMax) }}</span>
              </template>
              <StatusPill v-if="totals.pendingCount > 0" tone="warn" class="ui-pill-inline">
                另有 {{ totals.pendingCount }} 题未计入总分
              </StatusPill>
            </div>
          </div>
          <StatusPill tone="brand">共 {{ totals.totalCount }} 题</StatusPill>
        </div>

        <TableShell :empty="!result.items.length" empty-title="暂无分项明细" class="ui-mt-16">
          <template #head>
            <tr>
              <th>题号</th>
              <th>题型</th>
              <th>状态</th>
              <th>得分</th>
              <th>归一化</th>
            </tr>
          </template>
          <tr v-for="item in result.items" :key="`sum-${item.itemId}`">
            <td class="ui-num">第 {{ item.position + 1 }} 题</td>
            <td>{{ item.type }}</td>
            <td><StatusPill :tone="pillTone(item)">{{ label(item) }}</StatusPill></td>
            <td class="ui-num">{{ score(item.result.raw_score) }} / {{ score(item.result.max_score) }}</td>
            <td class="ui-num">{{ score(item.result.normalized_score) }}</td>
          </tr>
        </TableShell>
      </BaseCard>

      <BaseCard
        v-for="item in result.items"
        :key="item.itemId"
        tag="article"
        :class="{ 'ui-result-correct': item.result.passed === true, 'ui-result-wrong': item.result.passed === false }"
      >
        <div class="ui-actions ui-mb-12">
          <strong>第 {{ item.position + 1 }} 题</strong>
          <StatusPill tone="brand">{{ item.type }}</StatusPill>
          <StatusPill :tone="pillTone(item)">{{ label(item) }}</StatusPill>
        </div>

        <h2 class="item-stem">{{ item.content.stem }}</h2>

        <p v-if="item.result.grading_mode === 'LLM_SUGGESTED' && item.result.grading_status === 'AUTO_GRADED'" class="ui-num">
          建议分：{{ score(item.result.raw_score) }} / {{ score(item.result.max_score) }} · 归一化：{{ score(item.result.normalized_score) }} · 通过：{{ item.result.passed ? '是' : '否' }}
        </p>
        <p v-else class="ui-num">
          得分：{{ score(item.result.raw_score) }} / {{ score(item.result.max_score) }} · 归一化：{{ score(item.result.normalized_score) }} · 通过：{{ item.result.passed == null ? '—' : item.result.passed ? '是' : '否' }}
        </p>

        <p><strong>我的答案：</strong>{{ display(item.submittedAnswer) }}</p>
        <p><strong>参考答案：</strong>{{ display(item.correctAnswer) }}</p>
        <p v-if="item.explanation"><strong>解析：</strong>{{ item.explanation }}</p>

        <div v-if="item.rubric" class="ui-mt-12">
          <strong>评分要点：</strong>
          <pre class="ui-pre ui-mt-12">{{ JSON.stringify(item.rubric, null, 2) }}</pre>
        </div>

        <div v-if="item.result.missing_points?.length" class="ui-mt-12">
          <strong>缺失要点：</strong>
          <ul>
            <li v-for="(mp, idx) in item.result.missing_points" :key="idx">{{ mp }}</li>
          </ul>
        </div>

        <p v-if="item.result.reason" class="ui-muted"><strong>AI 评语：</strong>{{ item.result.reason }}</p>
        <p v-if="item.result.grading_status === 'AI_REVIEW_PENDING' && item.result.unavailable_reason" class="ui-muted">
          AI 暂不可用（{{ item.result.unavailable_reason }}），已保留待评分状态
        </p>
        <p v-else-if="item.result.grading_status === 'AI_REVIEW_FAILED'" class="ui-error">
          AI 评分未完成，本次不计入掌握度
          <BaseButton variant="secondary" size="sm" :disabled="retrying[item.itemId]" @click="retryReview(item.itemId)">
            {{ retrying[item.itemId] ? '重试中…' : '重试评分' }}
          </BaseButton>
        </p>
        <p v-else-if="item.result.provisional" class="ui-muted">{{ item.result.suggestion_disclaimer || 'AI 建议分不构成正式成绩' }}</p>

        <div v-if="item.result.passed === false && item.wrongQuestionId" class="ui-actions ui-mt-12">
          <BaseButton variant="secondary" :disabled="explainBusy[item.itemId]" @click="requestExplanation(item)">
            {{ explainBusy[item.itemId] ? '生成讲解中…' : 'AI 讲解' }}
          </BaseButton>
          <RouterLink :to="`/wrong/${item.wrongQuestionId}`">查看错题详情</RouterLink>
        </div>

        <AiTutoringPanel
          v-if="explainBusy[item.itemId] || explainErrors[item.itemId] || explainJobs[item.itemId]"
          :job="explainJobs[item.itemId]"
          :loading="Boolean(explainBusy[item.itemId])"
          :error="explainErrors[item.itemId] || ''"
          :error-code="explainErrorCodes[item.itemId] || ''"
          title="AI 错题讲解"
          @retry="requestExplanation(item)"
        />
      </BaseCard>

      <BaseCard class="ui-actions">
        <BaseButton to="/practice/new" variant="primary">再练一次</BaseButton>
        <RouterLink to="/">返回首页</RouterLink>
      </BaseCard>
    </template>
  </div>
</template>

<style scoped>
.result-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-16);
}

.result-total {
  font-size: var(--fs-28);
  font-weight: 700;
  line-height: var(--lh-tight);
  color: var(--ink-900);
}

.result-max {
  font-size: var(--fs-16);
  font-weight: 400;
  color: var(--ink-500);
}

.item-stem {
  margin: var(--sp-12) 0;
  font-size: var(--fs-18);
  line-height: var(--lh-loose);
}
</style>
