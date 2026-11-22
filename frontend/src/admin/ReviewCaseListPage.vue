<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'

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
  <div class="card">
    <div class="page-head">
      <div>
        <h2>题目人工复核案件</h2>
        <p class="muted">当同一道题被 3 名以上不同学生标记为“疑似有误”时，系统自动建案并暂停其进入新推荐与练习。</p>
      </div>
      <div class="actions">
        <select v-model="status" @change="load(0)">
          <option value="OPEN">待复核 (OPEN)</option>
          <option value="RESOLVED">已处理 (RESOLVED)</option>
          <option value="ALL">全部</option>
        </select>
        <button class="secondary" @click="load(data.page)">刷新</button>
      </div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <p v-if="!data.items.length" class="muted">当前筛选下暂无复核案件。</p>
    <table v-else>
      <thead>
        <tr>
          <th>题号 / 题干摘要</th>
          <th>触发人数</th>
          <th>原因 / 状态</th>
          <th>学生反馈明细</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in data.items" :key="item.id">
          <td>
            <div><strong>{{ item.questionNo }}</strong></div>
            <div class="muted">{{ item.stemSummary }}</div>
          </td>
          <td>{{ item.triggerFeedbackCount }}</td>
          <td>
            <span class="badge" :class="{ active: item.status === 'RESOLVED' }">{{ item.status }}</span>
            <div class="muted">{{ item.reason }}</div>
            <small class="muted">{{ new Date(item.createdAt).toLocaleString() }}</small>
          </td>
          <td>
            <ul style="margin: 0; padding-left: 16px">
              <li v-for="fb in item.feedbackList" :key="fb.id">
                <strong>{{ fb.username }}</strong>（{{ fb.category }}）：{{ fb.comment }}
              </li>
            </ul>
          </td>
          <td>
            <button
              v-if="item.status === 'OPEN'"
              class="primary"
              :disabled="busyId === item.id"
              @click="resolveCase(item.id)"
            >
              {{ busyId === item.id ? '处理中…' : '标记已复核' }}
            </button>
            <span v-else class="muted">
              已于 {{ item.resolvedAt ? new Date(item.resolvedAt).toLocaleString() : '—' }} 处理
            </span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
