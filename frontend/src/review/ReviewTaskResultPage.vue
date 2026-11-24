<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import AiTutoringPanel from '../ai/AiTutoringPanel.vue'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
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

async function load() {
  try {
    result.value = await api(`/api/review/tasks/${route.params.id}/result`)
  } catch (e) {
    error.value = e.message
  }
}

async function retryReview(itemId) {
  retrying.value[itemId] = true
  error.value = ''
  try {
    result.value = await api(`/api/review/tasks/${route.params.id}/items/${itemId}/retry-review`, { method: 'POST' })
  } catch (e) {
    error.value = e.message
  } finally {
    retrying.value[itemId] = false
  }
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

const summary = computed(() => {
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
  const failed = items.filter(it => it.result?.grading_status === 'AI_REVIEW_FAILED').length
  const pending = Math.max(0, pendingCount - failed)
  return {
    total: items.length,
    evaluatedRaw,
    evaluatedMax,
    pendingCount,
    correct: items.filter(i => i.result?.passed === true).length,
    wrong: items.filter(i => i.result?.passed === false).length,
    pending,
    failed
  }
})

function statusText(i) {
  const s = i.result?.grading_status
  if (s === 'AI_REVIEW_PENDING') return '待建议评分'
  if (s === 'AI_REVIEW_FAILED') return '评分失败，可重试'
  return i.result?.passed ? '正确' : '错误'
}

function statusTone(i) {
  const s = i.result?.grading_status
  if (s === 'AI_REVIEW_PENDING') return 'warn'
  if (s === 'AI_REVIEW_FAILED') return 'danger'
  return i.result?.passed ? 'success' : 'danger'
}

function statusClass(i) {
  if (i.result?.passed === true) return 'ui-result-correct'
  if (i.result?.passed === false) return 'ui-result-wrong'
  return 'ui-result-pending'
}

function score(v) {
  return v == null ? '—' : Number(v).toFixed(1)
}

function myAnswer(item) {
  const v = item.submittedAnswer
  if (v == null) return '未作答'
  if (item.type === 'CHOICE') return v.selectedOption || '未作答'
  if (item.type === 'FILL') {
    const b = v.blanks || []
    return b.length ? b.map((x, i) => `第 ${i + 1} 空：${x || '（空）'}`).join('；') : '未作答'
  }
  const parts = []
  if (v.text) parts.push(v.text)
  if (v.code) parts.push('代码：\n' + v.code)
  return parts.length ? parts.join('\n\n') : '未作答'
}

function referenceAnswer(item) {
  const v = item.correctAnswer
  if (v == null) return '—'
  if (item.type === 'CHOICE') return v.correctOption || '—'
  if (item.type === 'FILL') {
    const b = v.blanks || []
    return b.map((x, i) => `第 ${i + 1} 空：${(x.accepted || []).join(' / ') || '—'}`).join('；')
  }
  return v.referenceAnswer || JSON.stringify(v)
}
</script>

<template>
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">REVIEW RESULT</span>
        <h1 class="ui-title">复习任务结果</h1>
      </div>
      <RouterLink to="/review">返回推荐</RouterLink>
    </header>

    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

    <template v-if="result">
      <BaseCard>
        <div class="result-summary">
          <div>
            <div class="ui-muted">总分</div>
            <div class="result-total ui-num">
              <template v-if="summary.pendingCount === summary.total">—</template>
              <template v-else>
                {{ score(summary.evaluatedRaw) }} <span class="result-max">/ {{ score(summary.evaluatedMax) }}</span>
              </template>
              <StatusPill v-if="summary.pendingCount > 0" tone="warn" class="ui-pill-inline">
                另有 {{ summary.pendingCount }} 题未计入总分
              </StatusPill>
            </div>
          </div>
          <div class="ui-actions">
            <StatusPill tone="brand">共 {{ summary.total }} 题</StatusPill>
            <StatusPill tone="success">正确 {{ summary.correct }}</StatusPill>
            <StatusPill tone="danger">错误 {{ summary.wrong }}</StatusPill>
            <StatusPill v-if="summary.pending > 0" tone="warn" class="ui-pill-inline">待建议评分 {{ summary.pending }}</StatusPill>
            <StatusPill v-if="summary.failed > 0" tone="danger" class="ui-pill-inline">评分失败 {{ summary.failed }}</StatusPill>
          </div>
        </div>
        <p class="ui-muted r-note">主观题当前只进入建议评分流程，分数为参考值，不构成正式成绩；掌握度评估会在评分完成后更新。</p>

        <TableShell :empty="!result.items.length" empty-title="暂无分项明细" class="ui-mt-16">
          <template #head>
            <tr>
              <th>题号</th>
              <th>题型</th>
              <th>状态</th>
              <th>得分</th>
            </tr>
          </template>
          <tr v-for="item in result.items" :key="`sum-${item.itemId}`">
            <td class="ui-num">第 {{ item.position + 1 }} 题</td>
            <td>{{ item.type }}</td>
            <td><StatusPill :tone="statusTone(item)">{{ statusText(item) }}</StatusPill></td>
            <td class="ui-num">{{ score(item.result?.raw_score) }} / {{ score(item.maxScore) }}</td>
          </tr>
        </TableShell>
      </BaseCard>

      <BaseCard v-for="item in result.items" :key="item.itemId" tag="article" :class="statusClass(item)">
        <div class="ui-actions ui-mb-12">
          <strong>第 {{ item.position + 1 }} 题</strong>
          <StatusPill tone="brand">{{ item.type }}</StatusPill>
          <StatusPill :tone="statusTone(item)">{{ statusText(item) }}</StatusPill>
          <span v-if="item.result?.grading_mode === 'LLM_SUGGESTED' && item.result?.grading_status === 'AUTO_GRADED'" class="ui-muted ui-num">
            建议分 {{ score(item.result?.raw_score) }} / {{ score(item.maxScore) }} · 归一化 {{ score(item.result?.normalized_score) }}
          </span>
          <span v-else class="ui-muted ui-num">得分 {{ score(item.result?.raw_score) }} / {{ score(item.maxScore) }}</span>
        </div>

        <h2 class="r-stem">{{ item.content.stem }}</h2>

        <div class="ui-grid ui-grid--2 r-block">
          <div>
            <div class="r-label">我的答案</div>
            <pre class="ui-pre">{{ myAnswer(item) }}</pre>
          </div>
          <div>
            <div class="r-label">参考答案</div>
            <pre class="ui-pre">{{ referenceAnswer(item) }}</pre>
          </div>
        </div>

        <p v-if="item.explanation"><strong>解析：</strong>{{ item.explanation }}</p>

        <details v-if="item.rubric" class="r-details">
          <summary>评分要点</summary>
          <pre class="ui-pre">{{ JSON.stringify(item.rubric, null, 2) }}</pre>
        </details>

        <div v-if="item.assets?.length" class="ui-assets r-assets">
          <a v-for="a in item.assets" :key="a.id" :href="a.url" target="_blank">
            <img :src="a.url" width="96" height="72" alt="作答附件" />
          </a>
        </div>

        <div v-if="item.result?.missing_points?.length" class="r-block">
          <div class="r-label">缺失要点</div>
          <ul>
            <li v-for="(mp, idx) in item.result.missing_points" :key="idx">{{ mp }}</li>
          </ul>
        </div>
        <p v-if="item.result?.reason" class="ui-muted r-note"><strong>AI 评语：</strong>{{ item.result.reason }}</p>

        <p v-if="item.result?.grading_status === 'AI_REVIEW_PENDING' && item.result?.unavailable_reason" class="ui-muted r-note">
          AI 暂不可用（{{ item.result.unavailable_reason }}），已保留待评分状态
        </p>
        <p v-else-if="item.result?.grading_status === 'AI_REVIEW_PENDING'" class="ui-muted r-note">
          这道主观题已保存，等待 AI 给出建议评分；评分完成前不计入掌握度。
        </p>
        <p v-else-if="item.result?.grading_status === 'AI_REVIEW_FAILED'" class="ui-error">
          AI 评分未完成，本次不计入掌握度
          <BaseButton variant="secondary" size="sm" :disabled="retrying[item.itemId]" @click="retryReview(item.itemId)">
            {{ retrying[item.itemId] ? '重试中…' : '重试评分' }}
          </BaseButton>
        </p>
        <p v-else-if="item.result?.provisional" class="ui-muted r-note">
          {{ item.result.suggestion_disclaimer || 'AI 建议分不构成正式成绩' }}
        </p>

        <div v-if="item.result?.passed === false && item.wrongQuestionId" class="ui-actions ui-mt-12">
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
        <BaseButton to="/review" variant="primary">再来一组</BaseButton>
        <RouterLink to="/">返回首页</RouterLink>
      </BaseCard>
    </template>

    <BaseCard v-else-if="!error">
      <SkeletonBlock :lines="4" />
    </BaseCard>
  </div>
</template>

<style scoped>
.result-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-16);
  flex-wrap: wrap;
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

.r-stem {
  margin: var(--sp-12) 0;
  font-size: var(--fs-18);
  line-height: var(--lh-loose);
}

.r-block {
  margin: var(--sp-12) 0;
}

.r-label {
  color: var(--ink-500);
  font-size: var(--fs-13);
  margin-bottom: var(--sp-8);
}

.r-details {
  margin: var(--sp-12) 0;
}

.r-details summary {
  cursor: pointer;
  color: var(--brand-600);
  font-size: var(--fs-14);
}

.r-assets {
  margin-top: var(--sp-12);
}

.r-note {
  font-size: var(--fs-13);
  line-height: var(--lh-base);
}
</style>
