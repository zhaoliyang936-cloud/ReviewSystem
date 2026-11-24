<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import AiTutoringPanel from '../ai/AiTutoringPanel.vue'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import EmptyState from '../components/EmptyState.vue'
import TableShell from '../components/TableShell.vue'

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
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">MASTERY HISTORY</span>
        <h1 class="ui-title">掌握度快照</h1>
      </div>
      <RouterLink to="/mastery">返回概览</RouterLink>
    </header>

    <BaseCard>
      <div class="ui-actions detail-guidance">
        <BaseField v-slot="{ id }" label="复习指导关注点" class="detail-guidance__field">
          <input :id="id" v-model="guidanceQuery" placeholder="输入希望重点复习的内容（可选）" />
        </BaseField>
        <BaseButton variant="primary" :loading="guidanceBusy" @click="requestGuidance">
          {{ guidanceBusy ? '生成中…' : '生成复习指导' }}
        </BaseButton>
      </div>
    </BaseCard>

    <AiTutoringPanel
      v-if="guidanceBusy || guidanceError || guidanceJob"
      :job="guidanceJob"
      :loading="guidanceBusy"
      :error="guidanceError"
      :error-code="guidanceErrorCode"
      title="AI 复习指导"
      @retry="requestGuidance"
    />

    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

    <BaseCard v-if="!data.items.length">
      <EmptyState title="暂无快照。" />
    </BaseCard>

    <BaseCard v-for="(item, index) in data.items" :key="index" tag="article">
      <div class="ui-actions ui-num ui-mb-12">
        <div><strong>最终分 {{ score(item.finalScore) }}</strong></div>
        <div><strong>规则分 {{ score(item.ruleScore) }}</strong></div>
        <div>证据 n={{ item.evidence.length }}</div>
      </div>
      <p class="ui-muted ui-num">
        {{ date(item.createdAt) }} · {{ item.ruleVersion }} · 版本 {{ item.evidenceRevision }}
        <span v-if="item.clamped"> · 已限幅</span>
        <span v-if="!item.aiReasonJson?.aiApplied"> · AI 综合判断未生效</span>
      </p>
      <TableShell :empty="!item.evidence.length" empty-title="暂无证据">
        <template #head>
          <tr>
            <th>题号</th>
            <th>题型</th>
            <th>权重</th>
            <th>结果</th>
            <th>提交时间</th>
          </tr>
        </template>
        <tr v-for="e in item.evidence" :key="e.questionNo">
          <td class="ui-code">{{ e.questionNo }}</td>
          <td>{{ e.questionType }}</td>
          <td class="ui-num">{{ e.weight }}</td>
          <td class="ui-num">{{ e.resultY }}</td>
          <td class="ui-num">{{ date(e.submittedAt) }}</td>
        </tr>
      </TableShell>
    </BaseCard>

    <div class="ui-pager">
      <BaseButton variant="secondary" size="sm" :disabled="data.page === 0" @click="load(data.page - 1)">上一页</BaseButton>
      <span>第 {{ data.page + 1 }} 页</span>
      <BaseButton variant="secondary" size="sm" :disabled="(data.page + 1) * data.size >= data.total" @click="load(data.page + 1)">下一页</BaseButton>
    </div>
  </div>
</template>

<style scoped>
.detail-guidance {
  align-items: flex-end;
}

.detail-guidance__field {
  flex: 1;
  min-width: 220px;
}
</style>
