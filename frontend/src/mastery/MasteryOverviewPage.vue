<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import AiTutoringPanel from '../ai/AiTutoringPanel.vue'

const data = ref({ items: [], page: 0, size: 20, total: 0 })
const status = ref('')
const error = ref('')
const selectedKnowledgeId = ref(null)
const selectedKnowledgeName = ref('')
const guidanceQuery = ref('')
const guidanceJob = ref(null)
const guidanceBusy = ref(false)
const guidanceError = ref('')
const guidanceErrorCode = ref('')
let pollTimer = null

const labels = {
  UNASSESSED: '未评估',
  INSUFFICIENT: '证据不足',
  WEAK: '待加强',
  CONSOLIDATE: '巩固',
  STABLE: '稳定'
}
const score = v => (v == null ? '—' : Number(v).toFixed(1))
const date = v => (v ? new Date(v).toLocaleString() : '—')

async function load(page = 0) {
  try {
    error.value = ''
    data.value = await api(`/api/review/mastery?page=${page}&size=20${status.value ? '&status=' + status.value : ''}`)
  } catch (e) {
    error.value = e.message
  }
}

async function requestGuidance(item) {
  if (item) {
    selectedKnowledgeId.value = Number(item.knowledgeId)
    selectedKnowledgeName.value = `${item.knowledgeCode} ${item.knowledgeName}`
  }
  if (!selectedKnowledgeId.value) return
  guidanceError.value = ''
  guidanceErrorCode.value = ''
  guidanceBusy.value = true
  clearTimeout(pollTimer)
  try {
    const job = await api('/api/review/ai/jobs', {
      method: 'POST',
      body: {
        scenario: 'GUIDANCE',
        knowledgeId: selectedKnowledgeId.value,
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
        <span class="practice-kicker">MASTERY</span>
        <h1 class="practice-title">知识点掌握度</h1>
      </div>
      <RouterLink to="/">返回首页</RouterLink>
    </header>

    <div class="p-card">
      <div class="p-actions">
        <label>状态
          <select v-model="status" @change="load(0)">
            <option value="">全部</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
        <input v-model="guidanceQuery" placeholder="复习指导关注点（可选）" style="padding: 8px; min-width: 200px" />
        <button class="p-secondary" @click="load(data.page)">刷新</button>
      </div>
      <p v-if="error" class="p-error">{{ error }}</p>
      <table class="p-table">
        <thead>
          <tr>
            <th>知识点</th>
            <th>最终分</th>
            <th>规则分</th>
            <th>n</th>
            <th>状态</th>
            <th>最近练习</th>
            <th>更新状态</th>
            <th>复习指导</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in data.items" :key="item.knowledgeId">
            <td>
              <RouterLink :to="`/mastery/${item.knowledgeId}`">{{ item.knowledgeCode }} {{ item.knowledgeName }}</RouterLink>
              <small>{{ item.chapterTitle }}</small>
            </td>
            <td>{{ score(item.finalScore) }}</td>
            <td>{{ score(item.ruleScore) }}</td>
            <td>{{ item.n }}</td>
            <td>{{ labels[item.status] }}</td>
            <td>{{ date(item.latestPracticeAt) }}</td>
            <td>{{ item.updating ? '更新中' : '已更新' }}</td>
            <td>
              <button class="p-secondary" :disabled="guidanceBusy" @click="requestGuidance(item)">
                获取指导
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="p-actions">
        <button class="p-secondary" :disabled="data.page === 0" @click="load(data.page - 1)">上一页</button>
        <span>第 {{ data.page + 1 }} 页</span>
        <button class="p-secondary" :disabled="(data.page + 1) * data.size >= data.total" @click="load(data.page + 1)">下一页</button>
      </div>
    </div>

    <AiTutoringPanel
      v-if="guidanceBusy || guidanceError || guidanceJob"
      :job="guidanceJob"
      :loading="guidanceBusy"
      :error="guidanceError"
      :error-code="guidanceErrorCode"
      :title="`复习指导 · ${selectedKnowledgeName}`"
      @retry="requestGuidance(null)"
    />
  </div>
</template>

<style scoped>
small { display: block; color: #666 }
select { padding: 8px; background: white; border: 1px solid #aaa }
table { margin: 16px 0 }
</style>
