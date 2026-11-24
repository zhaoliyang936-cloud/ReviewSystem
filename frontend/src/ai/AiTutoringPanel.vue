<script setup>
import { ref } from 'vue'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import StatusPill from '../components/StatusPill.vue'

const props = defineProps({
  job: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  errorCode: { type: String, default: '' },
  title: { type: String, default: 'AI 辅导结果' }
})

const emit = defineEmits(['retry'])

const fbCategory = ref('HELPFUL')
const fbComment = ref('')
const fbBusy = ref(false)
const fbSaved = ref(null)
const fbError = ref('')

function formatDate(v) {
  return v ? new Date(v).toLocaleString() : '—'
}

function errorText(code, msg) {
  if (code === 'AI_BLOCKED_BY_TASK') return '完成当前练习后可用'
  if (code === 'AI_QUOTA_EXCEEDED') return '今日辅导额度已耗尽，请明日再试'
  if (code === 'AI_DISABLED') return 'AI 服务未开启，暂不可用'
  if (code === 'INSUFFICIENT_SOURCE') return msg || '资料不足，已提供规则摘要'
  if (code === 'AI_SOURCE_CONFLICT') return msg || '课程资料存在冲突，已提供规则摘要'
  if (code === 'TUTORING_BLOCKED') return '因新练习/任务开始，本次辅导结果已作废，请重新请求'
  return msg || (code ? `请求失败（${code}）` : '请求失败')
}

async function submitFeedback() {
  if (!props.job?.jobId) return
  fbError.value = ''
  fbBusy.value = true
  try {
    fbSaved.value = await api(`/api/review/ai/jobs/${props.job.jobId}/feedback`, {
      method: 'POST',
      body: { category: fbCategory.value, comment: fbComment.value.trim() }
    })
  } catch (e) {
    fbError.value = e.message || '提交反馈失败'
  } finally {
    fbBusy.value = false
  }
}
</script>

<template>
  <BaseCard class="ai-panel">
    <div class="ai-panel__head">
      <strong>{{ title }}</strong>
      <StatusPill v-if="job?.templateVersion" tone="brand">{{ job.templateVersion }}</StatusPill>
    </div>

    <p v-if="loading" class="ui-muted">正在生成 {{ title }}，请稍候…</p>

    <div v-else-if="error">
      <p class="ui-error" role="alert">{{ errorText(errorCode, error) }}</p>
      <BaseButton v-if="errorCode !== 'AI_BLOCKED_BY_TASK' && errorCode !== 'AI_DISABLED'" variant="secondary" size="sm" @click="emit('retry')">
        重试
      </BaseButton>
    </div>

    <template v-else-if="job">
      <div v-if="job.ruleFacts" class="ui-muted ai-facts ui-num">
        规则事实：证据数 n={{ job.ruleFacts.n }} ·
        规则分 M_rule={{ job.ruleFacts.ruleScore == null ? '—' : Number(job.ruleFacts.ruleScore).toFixed(1) }} ·
        状态={{ job.ruleFacts.status }} ·
        最近练习={{ formatDate(job.ruleFacts.latestPracticeAt) }}
      </div>

      <p v-if="job.state === 'QUEUED' || job.state === 'RUNNING'" class="ui-muted">
        作业处理中（{{ job.state }}）…
      </p>

      <div v-else-if="job.status === 'INSUFFICIENT_SOURCE'">
        <p class="ui-muted">{{ job.message || '资料不足，已提供规则摘要' }}</p>
        <p v-if="job.errorCode === 'AI_SOURCE_CONFLICT'" class="ui-error">检测到同知识点资料存在冲突（AI_SOURCE_CONFLICT），已停止生成。</p>
      </div>

      <div v-else-if="job.expiredCleaned">
        <p class="ui-muted">{{ job.message || '该结果已过期清理，可重新请求' }}</p>
        <BaseButton variant="secondary" size="sm" @click="emit('retry')">重新请求</BaseButton>
      </div>

      <div v-else-if="job.state === 'FAILED' || job.state === 'EXPIRED'">
        <p class="ui-error" role="alert">{{ errorText(job.errorCode, job.message) }}</p>
        <BaseButton variant="secondary" size="sm" @click="emit('retry')">重新请求</BaseButton>
      </div>

      <div v-else-if="job.state === 'SUCCEEDED' && job.output">
        <div v-if="job.output.keyConcepts?.length" class="ai-section">
          <strong>关键概念：</strong>
          <ul>
            <li v-for="(kc, idx) in job.output.keyConcepts" :key="idx">{{ kc }}</li>
          </ul>
        </div>

        <div v-if="job.output.steps?.length" class="ai-section">
          <strong>推导与复习步骤：</strong>
          <ol>
            <li v-for="(st, idx) in job.output.steps" :key="idx" class="ai-step">
              <div><strong>{{ st.goal }}</strong></div>
              <div>{{ st.action }}</div>
              <small v-if="st.citationIds?.length" class="ui-muted">引用：{{ st.citationIds.join(', ') }}</small>
            </li>
          </ol>
        </div>

        <div v-if="job.output.possibleConfusions?.length" class="ai-section">
          <strong>可能混淆点：</strong>
          <ul>
            <li v-for="(cf, idx) in job.output.possibleConfusions" :key="idx">
              <StatusPill v-if="cf.speculative !== false" tone="warn" class="ai-spec-pill">推测</StatusPill>
              <span>{{ cf.text }}</span>
              <small v-if="cf.citationIds?.length" class="ui-muted">（引用：{{ cf.citationIds.join(', ') }}）</small>
            </li>
          </ul>
        </div>

        <div v-if="job.output.selfCheck?.length" class="ai-section">
          <strong>自查建议：</strong>
          <ul>
            <li v-for="(sc, idx) in job.output.selfCheck" :key="idx">{{ sc }}</li>
          </ul>
        </div>

        <details v-if="job.citations?.length" class="ai-citations">
          <summary class="ai-citations__summary">展开课程资料引用（{{ job.citations.length }}）</summary>
          <ul>
            <li v-for="c in job.citations" :key="c.id">
              <strong>[{{ c.id }}]</strong> {{ c.title }}（版本 #{{ c.materialVersionId }}，片段 #{{ c.chunkId }}）
            </li>
          </ul>
        </details>

        <div class="ai-feedback">
          <div v-if="fbSaved" class="ui-success">
            已提交辅导反馈（{{ fbSaved.category }}）{{ fbSaved.comment ? '：' + fbSaved.comment : '' }}
          </div>
          <form v-else class="ui-actions" @submit.prevent="submitFeedback">
            <select v-model="fbCategory" class="ui-select ai-fb-select" aria-label="辅导反馈类型">
              <option value="HELPFUL">有帮助</option>
              <option value="UNCLEAR">讲解不清楚</option>
              <option value="SUSPECTED_ERROR">内容可能有误</option>
            </select>
            <input v-model="fbComment" maxlength="500" placeholder="补充备注（可选）" class="ui-input ai-fb-input" aria-label="辅导反馈备注" />
            <BaseButton type="submit" variant="secondary" size="sm" :loading="fbBusy">
              {{ fbBusy ? '提交中…' : '提交辅导反馈' }}
            </BaseButton>
          </form>
          <p v-if="fbError" class="ui-error">{{ fbError }}</p>
        </div>
      </div>
    </template>
  </BaseCard>
</template>

<style scoped>
.ai-panel {
  margin-top: var(--sp-12);
  border-left: 4px solid var(--brand-600);
}

.ai-panel__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-12);
  margin-bottom: var(--sp-8);
}

.ai-facts {
  margin-bottom: var(--sp-12);
  font-size: var(--fs-13);
}

.ai-section {
  margin-bottom: var(--sp-12);
}

.ai-step {
  margin-bottom: var(--sp-8);
}

.ai-spec-pill {
  margin-right: var(--sp-8);
}

.ai-citations {
  margin: var(--sp-12) 0;
}

.ai-citations__summary {
  cursor: pointer;
  color: var(--brand-600);
}

.ai-feedback {
  margin-top: var(--sp-12);
  padding-top: var(--sp-12);
  border-top: 1px solid var(--line);
}

.ai-fb-select {
  width: auto;
}

.ai-fb-input {
  flex: 1;
  min-width: 180px;
}
</style>
