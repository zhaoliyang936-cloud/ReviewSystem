<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import AiTutoringPanel from '../ai/AiTutoringPanel.vue'

const route = useRoute()
const data = ref({ items: [], page: 0, size: 20, total: 0 })
const error = ref('')
const guidanceQuery = ref('')
const guidanceJob = ref(null)
const guidanceBusy = ref(false)
const guidanceError = ref('')
const guidanceErrorCode = ref('')
let pollTimer = null

const score = v => (v == null ? '—' : Number(v).toFixed(1))
const date = v => (v ? new Date(v).toLocaleString() : '—')

async function load(page = 0) {
  try {
    error.value = ''
    data.value = await api(`/api/review/knowledge/${route.params.knowledgeId}/history?page=${page}&size=20`)
  } catch (e) {
    error.value = e.message
  }
}

async function requestGuidance() {
  guidanceError.value = ''
  guidanceErrorCode.value = ''
  guidanceBusy.value = true
  clearTimeout(pollTimer)
  try {
    const job = await api('/api/review/ai/jobs', {
      method: 'POST',
      body: {
        scenario: 'GUIDANCE',
        knowledgeId: Number(route.params.knowledgeId),
        query: guidanceQuery.value.trim()
      }
    })
    guidanceJob.value = job
    if (job.state === 'QUEUED' || job.state === 'RUNNING') {
      const tick = async () => {
        try {
          const res = await api(`/api/review/ai/jobs/${job.jobId}`)
          if (res.state === 'QUEUED' || res.state === 'RUNNING') {
            pollTimer = setTimeout(tick, 1000)
          } else {
            guidanceJob.value = res
            guidanceBusy.value = false
          }
        } catch (e) {
          guidanceBusy.value = false
          guidanceError.value = e.message || '查询指导结果失败'
        }
      }
      pollTimer = setTimeout(tick, 600)
    } else {
      guidanceBusy.value = false
    }
  } catch (e) {
    guidanceBusy.value = false
    guidanceErrorCode.value = e.code || ''
    guidanceError.value = e.message || '请求复习指导失败'
  }
}

onBeforeUnmount(() => clearTimeout(pollTimer))
onMounted(() => load())
</script>

<template>
  <div class="practice-shell">
    <header class="practice-top">
      <div>
        <span class="practice-kicker">MASTERY HISTORY</span>
        <h1 class="practice-title">掌握度快照</h1>
      </div>
      <RouterLink to="/mastery">返回概览</RouterLink>
    </header>

    <div class="p-card">
      <div class="p-actions">
        <input v-model="guidanceQuery" placeholder="输入希望重点复习的内容（可选）" style="flex: 1; padding: 8px" />
        <button class="p-primary" :disabled="guidanceBusy" @click="requestGuidance">
          {{ guidanceBusy ? '生成中…' : '生成复习指导' }}
        </button>
      </div>
    </div>

    <AiTutoringPanel
      v-if="guidanceBusy || guidanceError || guidanceJob"
      :job="guidanceJob"
      :loading="guidanceBusy"
      :error="guidanceError"
      :error-code="guidanceErrorCode"
      title="AI 复习指导"
      @retry="requestGuidance"
    />

    <p v-if="error" class="p-error">{{ error }}</p>
    <p v-if="!data.items.length" class="p-card">暂无快照。</p>
    <article v-for="(item, index) in data.items" :key="index" class="p-card">
      <div class="p-actions">
        <div><strong>最终分 {{ score(item.finalScore) }}</strong></div>
        <div><strong>规则分 {{ score(item.ruleScore) }}</strong></div>
        <div>证据 n={{ item.evidence.length }}</div>
      </div>
      <p>
        {{ date(item.createdAt) }} · {{ item.ruleVersion }} · 版本 {{ item.evidenceRevision }}
        <span v-if="item.clamped"> · 已限幅</span>
        <span v-if="!item.aiReasonJson?.aiApplied"> · AI 综合判断未生效</span>
      </p>
      <table class="p-table">
        <thead>
          <tr><th>题号</th><th>题型</th><th>权重</th><th>结果</th><th>提交时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="e in item.evidence" :key="e.questionNo">
            <td>{{ e.questionNo }}</td>
            <td>{{ e.questionType }}</td>
            <td>{{ e.weight }}</td>
            <td>{{ e.resultY }}</td>
            <td>{{ date(e.submittedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </article>

    <div class="p-actions">
      <button class="p-secondary" :disabled="data.page === 0" @click="load(data.page - 1)">上一页</button>
      <span>第 {{ data.page + 1 }} 页</span>
      <button class="p-secondary" :disabled="(data.page + 1) * data.size >= data.total" @click="load(data.page + 1)">下一页</button>
    </div>
  </div>
</template>
