<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'

const route = useRoute()
const router = useRouter()
const detail = ref(null)
const error = ref('')
const redoBusy = ref(false)
const fbCategory = ref('SUSPECTED_ERROR')
const fbComment = ref('')
const fbBusy = ref(false)
const fbMessage = ref('')
const fbError = ref('')

const TYPE_LABELS = {
  CHOICE: '单选题',
  FILL: '填空题',
  SHORT_ANSWER: '简答/问答',
  ALGORITHM: '算法设计'
}

const ATTEMPT_LABELS = {
  ORIGINAL_REDO: '原题重做',
  AI_VARIANT: 'AI 变式',
  ORDINARY: '普通练习/任务'
}

const CATEGORY_LABELS = {
  SUSPECTED_ERROR: '题目疑似有误',
  CONTENT_PROBLEM: '内容或解析问题',
  OTHER: '其他'
}

function formatJson(val) {
  if (val == null) return '—'
  if (typeof val === 'string') return val
  return JSON.stringify(val, null, 2)
}

function attemptStatus(a) {
  if (a.passed === true) return '通过'
  if (a.passed === false) return a.aiJudged ? '未通过（AI 判定）' : '未通过'
  return '待评/未判定'
}

async function load() {
  error.value = ''
  try {
    detail.value = await api(`/api/wrong-questions/${route.params.id}`)
  } catch (e) {
    error.value = e.message || '加载错题详情失败'
  }
}

async function redo() {
  error.value = ''
  redoBusy.value = true
  try {
    const made = await api(`/api/wrong-questions/${route.params.id}/redo`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: { mode: 'ORIGINAL' }
    })
    router.push(`/practice/${made.id}`)
  } catch (e) {
    if (e.code === 'QUESTION_SUSPENDED') {
      error.value = '该题目已下架（SUSPENDED），暂不支持原题重做。'
    } else if (e.activityId) {
      error.value = `已有${e.kind === 'PRACTICE' ? '普通练习' : '复习任务'}进行中（活动 ${e.activityId}），请先完成或取消当前活动后再重做。`
    } else {
      error.value = e.message || '发起重做失败'
    }
  } finally {
    redoBusy.value = false
  }
}

async function submitFeedback() {
  fbError.value = ''
  fbMessage.value = ''
  if (!fbComment.value.trim()) {
    fbError.value = '请填写反馈说明'
    return
  }
  fbBusy.value = true
  try {
    const saved = await api(`/api/wrong-questions/${route.params.id}/feedback`, {
      method: 'POST',
      body: { category: fbCategory.value, comment: fbComment.value.trim() }
    })
    detail.value = { ...detail.value, feedback: saved }
    fbMessage.value = '已提交反馈，错题状态保持不变。'
  } catch (e) {
    fbError.value = e.message || '提交反馈失败'
  } finally {
    fbBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="practice-shell">
    <header class="practice-top">
      <div>
        <span class="practice-kicker">M09 · WRONG QUESTION DETAIL</span>
        <h1 class="practice-title">错题详情</h1>
      </div>
      <div class="p-actions">
        <RouterLink to="/wrong">返回错题本</RouterLink>
        <RouterLink to="/">返回首页</RouterLink>
      </div>
    </header>

    <p v-if="error" class="p-error" role="alert">{{ error }}</p>

    <template v-if="detail">
      <div class="p-card">
        <div class="p-actions" style="margin-bottom: 12px">
          <span class="p-pill">{{ TYPE_LABELS[detail.questionType] || detail.questionType }}</span>
          <span class="p-pill">{{ detail.sourceType === 'AI_GENERATED' ? 'AI 生成' : '人工录入' }}</span>
          <span class="p-pill">{{ detail.status === 'MASTERED' ? '已掌握' : '待重做' }}</span>
          <span v-if="detail.aiJudged" class="p-pill">AI 判定</span>
          <span class="p-muted">知识点：{{ detail.knowledgeCode }} · {{ detail.knowledgeName }}</span>
        </div>

        <h2>{{ detail.content?.stem || '（无题干）' }}</h2>

        <ul v-if="detail.content?.options?.length" style="padding-left: 20px">
          <li v-for="opt in detail.content.options" :key="opt.key || opt.id">
            <strong>{{ opt.key || opt.id }}.</strong> {{ opt.text }}
          </li>
        </ul>

        <p class="p-muted">
          错误次数：{{ detail.wrongCount }} ·
          首次答错：{{ new Date(detail.firstWrongAt).toLocaleString() }} ·
          最近答错：{{ new Date(detail.lastWrongAt).toLocaleString() }}
          <template v-if="detail.masteredAt">
            · 已掌握：{{ new Date(detail.masteredAt).toLocaleString() }}
          </template>
        </p>

        <div class="p-actions" style="margin-top: 16px">
          <button
            class="p-primary"
            :disabled="redoBusy || detail.questionStatus !== 'ACTIVE'"
            @click="redo"
          >
            {{ detail.questionStatus !== 'ACTIVE' ? '题目已下架，无法重做' : redoBusy ? '正在创建重做练习…' : '原题重做' }}
          </button>
        </div>
      </div>

      <div class="p-card">
        <h3>最近作答与参考答案</h3>
        <p v-if="detail.latestAttempt">
          <strong>最近作答：</strong>
          <pre class="p-code" style="padding: 10px; border-radius: 8px; overflow-x: auto">{{ formatJson(detail.latestAttempt.answer) }}</pre>
        </p>
        <p>
          <strong>标准答案：</strong>
          <pre class="p-code" style="padding: 10px; border-radius: 8px; overflow-x: auto">{{ formatJson(detail.correctAnswer) }}</pre>
        </p>
        <div v-if="detail.rubric">
          <strong>评分要点：</strong>
          <pre class="p-code" style="padding: 10px; border-radius: 8px; overflow-x: auto">{{ formatJson(detail.rubric) }}</pre>
        </div>
        <p v-if="detail.explanation">
          <strong>题目解析：</strong>{{ detail.explanation }}
        </p>
      </div>

      <div class="p-card">
        <h3>历次尝试（{{ detail.attempts.length }}）</h3>
        <p v-if="!detail.attempts.length" class="p-muted">暂无尝试记录。</p>
        <table v-else class="p-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>类型</th>
              <th>结果</th>
              <th>作答快照</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in detail.attempts" :key="a.id">
              <td>{{ new Date(a.createdAt).toLocaleString() }}</td>
              <td>{{ ATTEMPT_LABELS[a.attemptType] || a.attemptType }}</td>
              <td>{{ attemptStatus(a) }}</td>
              <td><code>{{ JSON.stringify(a.answer) }}</code></td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="p-card">
        <h3>题目反馈</h3>
        <template v-if="detail.feedback">
          <p class="p-success">
            已提交反馈（{{ CATEGORY_LABELS[detail.feedback.category] || detail.feedback.category }} · 状态：{{ detail.feedback.status }}）
          </p>
          <p>{{ detail.feedback.comment }}</p>
          <small class="p-muted">提交时间：{{ new Date(detail.feedback.createdAt).toLocaleString() }}</small>
        </template>
        <form v-else @submit.prevent="submitFeedback">
          <label class="p-field">
            <span>反馈类型</span>
            <select v-model="fbCategory">
              <option value="SUSPECTED_ERROR">题目疑似有误</option>
              <option value="CONTENT_PROBLEM">内容或解析问题</option>
              <option value="OTHER">其他</option>
            </select>
          </label>
          <label class="p-field">
            <span>反馈说明（不超过 500 字）</span>
            <textarea v-model="fbComment" maxlength="500" placeholder="请描述题目或答案存在的问题…" required />
          </label>
          <p v-if="fbError" class="p-error" role="alert">{{ fbError }}</p>
          <p v-if="fbMessage" class="p-success" role="status">{{ fbMessage }}</p>
          <button class="p-secondary" :disabled="fbBusy">
            {{ fbBusy ? '提交中…' : '提交反馈' }}
          </button>
        </form>
      </div>
    </template>
  </div>
</template>
