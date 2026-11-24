<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

const data = ref({ items: [], page: 0, size: 20, total: 0 })
const status = ref('OPEN')
const error = ref('')
const busyId = ref(null)

async function load(page = 0) {
  error.value = ''
  try {
    data.value = await api(`/api/admin/review-cases?status=${status.value}&page=${page}&size=20`)
  } catch (e) {
    error.value = e.message || '加载复核案件失败'
  }
}

async function resolveCase(id) {
  busyId.value = id
  error.value = ''
  try {
    await api(`/api/admin/review-cases/${id}/resolve`, { method: 'POST' })
    await load(data.value.page)
  } catch (e) {
    error.value = e.message || '处理复核案件失败'
  } finally {
    busyId.value = null
  }
}

onMounted(() => load(0))
</script>

<template>
  <section>
    <div class="ui-page-head">
      <div>
        <span class="ui-eyebrow">REVIEW CASES</span>
        <h1>题目人工复核案件</h1>
        <p class="ui-subtitle">当同一道题被 3 名以上不同学生标记为“疑似有误”时，系统自动建案并暂停其进入新推荐与练习。</p>
      </div>
      <div class="ui-actions">
        <select v-model="status" class="ui-select case-status-select" aria-label="案件状态筛选" @change="load(0)">
          <option value="OPEN">待复核 (OPEN)</option>
          <option value="RESOLVED">已处理 (RESOLVED)</option>
          <option value="ALL">全部</option>
        </select>
        <BaseButton variant="secondary" @click="load(data.page)">刷新</BaseButton>
      </div>
    </div>

    <BaseCard>
      <p v-if="error" class="ui-error ui-mb-12" role="alert">{{ error }}</p>

      <TableShell :empty="!data.items.length" empty-title="当前筛选下暂无复核案件。">
        <template #head>
          <tr>
            <th>题号 / 题干摘要</th>
            <th>触发人数</th>
            <th>原因 / 状态</th>
            <th>学生反馈明细</th>
            <th>操作</th>
          </tr>
        </template>
        <tr v-for="item in data.items" :key="item.id">
          <td>
            <div><strong>{{ item.questionNo }}</strong></div>
            <div class="ui-muted">{{ item.stemSummary }}</div>
          </td>
          <td class="ui-num">{{ item.triggerFeedbackCount }}</td>
          <td>
            <StatusPill :tone="item.status === 'RESOLVED' ? 'success' : 'warn'">{{ item.status }}</StatusPill>
            <div class="ui-muted">{{ item.reason }}</div>
            <small class="ui-muted ui-num">{{ new Date(item.createdAt).toLocaleString() }}</small>
          </td>
          <td>
            <ul class="feedback-list">
              <li v-for="fb in item.feedbackList" :key="fb.id">
                <strong>{{ fb.username }}</strong>（{{ fb.category }}）：{{ fb.comment }}
              </li>
            </ul>
          </td>
          <td>
            <BaseButton
              v-if="item.status === 'OPEN'"
              variant="primary"
              size="sm"
              :loading="busyId === item.id"
              @click="resolveCase(item.id)"
            >
              {{ busyId === item.id ? '处理中…' : '标记已复核' }}
            </BaseButton>
            <span v-else class="ui-muted ui-num">
              已于 {{ item.resolvedAt ? new Date(item.resolvedAt).toLocaleString() : '—' }} 处理
            </span>
          </td>
        </tr>
      </TableShell>
    </BaseCard>
  </section>
</template>

<style scoped>
.case-status-select {
  width: auto;
}

.feedback-list {
  margin: 0;
  padding-left: var(--sp-16);
}
</style>
