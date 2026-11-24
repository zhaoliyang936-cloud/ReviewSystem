<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import AiTutoringPanel from '../ai/AiTutoringPanel.vue'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

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

const tones = {
  UNASSESSED: 'neutral',
  INSUFFICIENT: 'info',
  WEAK: 'danger',
  CONSOLIDATE: 'warn',
  STABLE: 'success'
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
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">MASTERY</span>
        <h1 class="ui-title">知识点掌握度</h1>
      </div>
      <RouterLink to="/">返回首页</RouterLink>
    </header>

    <BaseCard>
      <div class="ui-actions mastery-filters ui-mb-16">
        <BaseField v-slot="{ id }" label="状态" class="mastery-filter-item">
          <select :id="id" v-model="status" @change="load(0)">
            <option value="">全部</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option>
          </select>
        </BaseField>
        <BaseField v-slot="{ id }" label="复习指导关注点" class="mastery-filter-grow">
          <input :id="id" v-model="guidanceQuery" placeholder="复习指导关注点（可选）" />
        </BaseField>
        <BaseButton variant="secondary" @click="load(data.page)">刷新</BaseButton>
      </div>

      <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

      <TableShell :empty="!data.items.length" empty-title="暂无掌握度记录">
        <template #head>
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
        </template>
        <tr v-for="item in data.items" :key="item.knowledgeId">
          <td :title="item.knowledgeCode">
            <RouterLink :to="`/mastery/${item.knowledgeId}`" :title="item.knowledgeCode">{{ item.knowledgeName }}</RouterLink>
            <small class="mastery-chapter">{{ item.chapterTitle }}</small>
          </td>
          <td class="ui-num">{{ score(item.finalScore) }}</td>
          <td class="ui-num">{{ score(item.ruleScore) }}</td>
          <td class="ui-num">{{ item.n }}</td>
          <td>
            <StatusPill :tone="tones[item.status] || 'neutral'">{{ labels[item.status] }}</StatusPill>
          </td>
          <td class="ui-num">{{ date(item.latestPracticeAt) }}</td>
          <td>{{ item.updating ? '更新中' : '已更新' }}</td>
          <td>
            <BaseButton variant="secondary" size="sm" :disabled="guidanceBusy" @click="requestGuidance(item)">
              获取指导
            </BaseButton>
          </td>
        </tr>
      </TableShell>

      <div class="ui-pager">
        <BaseButton variant="secondary" size="sm" :disabled="data.page === 0" @click="load(data.page - 1)">上一页</BaseButton>
        <span>第 {{ data.page + 1 }} 页</span>
        <BaseButton variant="secondary" size="sm" :disabled="(data.page + 1) * data.size >= data.total" @click="load(data.page + 1)">下一页</BaseButton>
      </div>
    </BaseCard>

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
.mastery-filters {
  align-items: flex-end;
}

.mastery-filter-item {
  min-width: 150px;
}

.mastery-filter-grow {
  flex: 1;
  min-width: 200px;
  margin-top: 0;
}

.mastery-chapter {
  display: block;
  color: var(--ink-500);
  font-size: var(--fs-12);
}
</style>
