<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseDialog from '../components/BaseDialog.vue'
import BaseField from '../components/BaseField.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import StatusPill from '../components/StatusPill.vue'

const route = useRoute()
const router = useRouter()
const task = ref(null)
const index = ref(0)
const answers = ref({})
const message = ref('')
const error = ref('')
const saving = ref(false)
const uploading = ref(false)
const submitting = ref(false)
const preparing = ref(false)
const prepareTimedOut = ref(false)
const prepareFailed = ref(false)
const prepareFailureReason = ref('')
const confirmDialog = ref({ open: false, title: '', message: '', danger: false, onConfirm: null })
const submitKey = crypto.randomUUID()
let timer, pollTimer, pollStartedAt = 0

const item = computed(() => task.value?.items[index.value])
const answer = computed({
  get: () => ensureAnswer(item.value) || {},
  set: v => {
    if (item.value) answers.value[item.value.id] = isPlainObject(v) ? v : emptyAnswer(item.value)
  }
})
const mix = computed(() => task.value?.mixRatio || null)
const degradedCount = computed(() => mix.value?.degraded?.length || 0)

function blankCount(content) {
  const stem = content?.stem || ''
  return (stem.match(/\{\{blank\}\}/g) || []).length || 1
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v)
}

function emptyAnswer(i) {
  if (i.questionType === 'CHOICE') return { selectedOption: '' }
  if (i.questionType === 'FILL') return { blanks: Array.from({ length: blankCount(i.content) }, () => '') }
  return { text: '', code: '' }
}

function ensureAnswer(i) {
  if (!i) return null
  if (!isPlainObject(answers.value[i.id])) answers.value[i.id] = emptyAnswer(i)
  return answers.value[i.id]
}

function isAnswered(i) {
  const a = answers.value[i.id]
  if (!a) return false
  if (i.questionType === 'CHOICE') return Boolean(a.selectedOption)
  if (i.questionType === 'FILL') return Array.isArray(a.blanks) && a.blanks.some(v => v && v.trim())
  return Boolean(a.text?.trim() || a.code?.trim())
}

function initial(i) {
  // i.draft 是 Vue 响应式 Proxy，structuredClone 会抛 DataCloneError 并中断 load() 的初始化循环，必须用 JSON 深拷贝
  if (isPlainObject(i.draft)) return JSON.parse(JSON.stringify(i.draft))
  return emptyAnswer(i)
}

async function initReadyTask() {
  preparing.value = false
  prepareTimedOut.value = false
  if (task.value.status === 'PREPARATION_FAILED') {
    prepareFailed.value = true
    prepareFailureReason.value = task.value.mixRatio?.preparationFailure?.reason || 'AI_WORKER_STALE'
    return
  }
  prepareFailed.value = false
  for (const i of task.value.items) answers.value[i.id] = initial(i)
  if (task.value.status === 'READY') task.value = await api(`/api/review/tasks/${route.params.id}/start`, { method: 'POST' })
  else if (task.value.status === 'COMPLETED') router.replace(`/review/tasks/${route.params.id}/result`)
  else if (task.value.status === 'CANCELLED') error.value = '这个任务已取消，无法继续作答。'
}

async function pollPreparing() {
  const POLL_MAX_MS = Math.max(120000, ((task.value?.preparationTimeoutSeconds ?? 90) * 1000) + 30000)
  if (Date.now() - pollStartedAt >= POLL_MAX_MS) {
    try {
      task.value = await api(`/api/review/tasks/${route.params.id}`)
      if (task.value.status !== 'PREPARING') {
        await initReadyTask()
        return
      }
    } catch (e) {
      error.value = e.message
    }
    preparing.value = false
    prepareTimedOut.value = true
    return
  }
  try {
    task.value = await api(`/api/review/tasks/${route.params.id}`)
    if (task.value.status !== 'PREPARING') {
      await initReadyTask()
      return
    }
    pollTimer = setTimeout(pollPreparing, 1000)
  } catch (e) {
    preparing.value = false
    error.value = e.message
  }
}

async function load() {
  error.value = ''
  prepareTimedOut.value = false
  prepareFailed.value = false
  try {
    task.value = await api(`/api/review/tasks/${route.params.id}`)
    if (task.value.status === 'PREPARING') {
      preparing.value = true
      pollStartedAt = Date.now()
      pollTimer = setTimeout(pollPreparing, 1000)
      return
    }
    await initReadyTask()
  } catch (e) {
    error.value = e.message
  }
}

async function save() {
  clearTimeout(timer)
  if (!item.value || task.value.status !== 'IN_PROGRESS') return
  const payload = ensureAnswer(item.value)
  saving.value = true
  message.value = ''
  try {
    const saved = await api(`/api/review/tasks/${task.value.id}/items/${item.value.id}/draft`, {
      method: 'PUT',
      body: { answer: payload, draftVersion: item.value.draftVersion }
    })
    item.value.draftVersion = saved.draftVersion
    message.value = '已保存'
  } catch (e) {
    if (e.code === 'STATE_CONFLICT') message.value = '草稿版本冲突，已重新加载'
    else error.value = `未保存：${e.message}`
    if (e.code === 'STATE_CONFLICT') await load()
  } finally {
    saving.value = false
  }
}

async function move(next) {
  await save()
  index.value = next
  error.value = ''
}

watch(answers, () => {
  clearTimeout(timer)
  message.value = ''
  timer = setTimeout(save, 5000)
}, { deep: true })

async function upload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  uploading.value = true
  error.value = ''
  try {
    const body = new FormData()
    body.append('file', file)
    const made = await api(`/api/review/tasks/${task.value.id}/items/${item.value.id}/assets`, { method: 'POST', body })
    item.value.assets.push(made)
  } catch (e) {
    error.value = e.message
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

function removeAsset(asset) {
  confirmDialog.value = {
    open: true,
    title: '删除作答图片',
    message: '删除这张作答图片？',
    danger: true,
    onConfirm: async () => {
      confirmDialog.value.open = false
      try {
        await api(`/api/review/tasks/${task.value.id}/items/${item.value.id}/assets/${asset.id}`, { method: 'DELETE' })
        item.value.assets = item.value.assets.filter(a => a.id !== asset.id)
      } catch (e) {
        error.value = e.message
      }
    }
  }
}

function unanswered() {
  return task.value.items.filter(i => {
    const a = answers.value[i.id] || {}
    if (i.questionType === 'CHOICE') return !a.selectedOption
    if (i.questionType === 'FILL') return !(a.blanks || []).some(v => v && v.trim())
    return !a.text?.trim() && !a.code?.trim()
  }).length
}

async function submit() {
  await save()
  const left = unanswered()
  confirmDialog.value = {
    open: true,
    title: '提交复习任务',
    message: left ? `还有 ${left} 题未作答，仍要提交吗？` : '提交后不能再修改，继续吗？',
    danger: false,
    onConfirm: async () => {
      confirmDialog.value.open = false
      submitting.value = true
      error.value = ''
      try {
        await api(`/api/review/tasks/${task.value.id}/submit`, {
          method: 'POST',
          headers: { 'Idempotency-Key': submitKey },
          body: { answers: task.value.items.map(i => ({ itemId: i.id, answer: ensureAnswer(i), assetIds: (i.assets || []).map(a => a.id) })) }
        })
        router.push(`/review/tasks/${task.value.id}/result`)
      } catch (e) {
        error.value = e.message
      } finally {
        submitting.value = false
      }
    }
  }
}

onMounted(load)
onBeforeUnmount(() => {
  clearTimeout(timer)
  clearTimeout(pollTimer)
})
</script>

<template>
  <div class="ui-shell">
    <div
      class="ui-save-bar"
      :class="{ 'is-saving': saving, 'is-saved': !saving && message === '已保存' }"
      role="status"
      aria-live="polite"
      :aria-label="saving ? '正在保存草稿' : message"
    ></div>

    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">复习任务</span>
        <h1 class="ui-title ui-num">第 {{ index + 1 }} / {{ task?.items.length || 0 }} 题</h1>
      </div>
      <RouterLink to="/review">返回推荐</RouterLink>
    </header>

    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

    <BaseCard v-if="mix" class="task-mix">
      <StatusPill tone="brand">共 {{ task.questionCount }} 题</StatusPill>
      <span class="ui-num">计划 题库 {{ mix.planned?.bank ?? 0 }} + AI {{ mix.planned?.ai ?? 0 }}</span>
      <span class="ui-muted ui-num">实际 题库 {{ mix.actual?.bank ?? 0 }} + AI {{ mix.actual?.ai ?? 0 }}</span>
      <span v-if="mix?.shortage && (task?.status === 'PREPARING' || (mix?.actual?.ai ?? 0) > 0)" class="ui-muted">
        题库可用题不足 {{ mix.shortage.bankShortfall }} 题，已由 AI 补位
      </span>
      <span v-else-if="mix?.shortage" class="ui-muted">
        题库可用题不足 {{ mix.shortage.bankShortfall }} 题，本次共 {{ mix.shortage.actual }} 题
      </span>
      <span v-if="degradedCount" class="ui-muted">{{ degradedCount }} 个 AI 槽位已由题库题补位</span>
    </BaseCard>

    <BaseCard v-if="preparing">
      <p>正在准备题目（AI {{ mix?.planned?.ai ?? 3 }} 题）…</p>
      <SkeletonBlock :lines="2" class="ui-mb-16" />
      <BaseButton variant="primary" disabled>开始作答</BaseButton>
    </BaseCard>

    <BaseCard v-else-if="prepareFailed">
      <p class="ui-error">题目准备失败（{{ prepareFailureReason }}），请返回重新创建。</p>
      <BaseButton to="/review" variant="secondary">返回重新创建</BaseButton>
    </BaseCard>

    <BaseCard v-else-if="prepareTimedOut">
      <p class="ui-error">题目仍在准备中，可以稍后刷新；若长时间无变化，请返回重新创建。</p>
      <BaseButton variant="secondary" @click="load">刷新题目</BaseButton>
    </BaseCard>

    <template v-else-if="item">
      <BaseCard>
        <div class="ui-actions ui-mb-12">
          <StatusPill tone="brand">{{ item.questionType }}</StatusPill>
          <span class="ui-muted ui-num">{{ item.maxScore }} 分</span>
        </div>

        <h2 class="task-stem">{{ item.content.stem }}</h2>
        <p v-if="item.reason" class="ui-muted task-reason ui-num">
          推荐理由：{{ item.reason.pool === 'REVIEW' ? '复习池' : '诊断池' }} · 优先级 {{ Number(item.reason.priority ?? 0).toFixed(3) }} · 薄弱 {{ Number(item.reason.weakness ?? 0).toFixed(2) }} · 距上次 {{ Number(item.reason.daysSincePractice ?? 0).toFixed(1) }} 天
        </p>

        <div v-if="item.questionType === 'CHOICE'" class="ui-options">
          <label v-for="o in item.content.options" :key="o.key" class="ui-option">
            <input v-model="answer.selectedOption" type="radio" :value="o.key" />
            <span>{{ o.key }}. {{ o.text }}</span>
          </label>
        </div>

        <div v-else-if="item.questionType === 'FILL'">
          <BaseField v-for="(_, i) in answer.blanks" :key="i" v-slot="{ id }" :label="`第 ${i + 1} 空`">
            <input :id="id" v-model="answer.blanks[i]" />
          </BaseField>
        </div>

        <template v-else>
          <BaseField v-slot="{ id }" label="文字作答">
            <textarea :id="id" v-model="answer.text" placeholder="写下思路与说明"></textarea>
          </BaseField>
          <BaseField v-slot="{ id }" label="代码（可选）">
            <textarea :id="id" v-model="answer.code" class="ui-code" placeholder="输入伪代码或程序代码"></textarea>
          </BaseField>
          <div v-if="item.assets?.length" class="ui-assets ui-mt-12">
            <div v-for="a in item.assets" :key="a.id" class="ui-asset-item">
              <img :src="a.url" width="96" height="72" alt="作答附件" />
              <BaseButton variant="secondary" size="sm" @click="removeAsset(a)">删除</BaseButton>
            </div>
          </div>
          <BaseField v-slot="{ id }" label="作答图片（最多 3 张，PNG/JPEG）" class="ui-mt-12">
            <input :id="id" type="file" accept="image/png,image/jpeg" :disabled="uploading || item.assets.length >= 3" @change="upload" />
          </BaseField>
        </template>
      </BaseCard>

      <BaseCard>
        <div class="ui-progress-grid" aria-label="题目导航">
          <button
            v-for="(q, i) in task.items"
            :key="q.id || i"
            type="button"
            class="ui-progress-btn"
            :class="{ 'is-answered': isAnswered(q), 'is-current': i === index }"
            @click="move(i)"
          >
            {{ i + 1 }}
          </button>
        </div>
        <div class="ui-actions ui-mt-20">
          <BaseButton variant="secondary" :disabled="index === 0" @click="move(index - 1)">上一题</BaseButton>
          <BaseButton variant="secondary" :disabled="index === task.items.length - 1" @click="move(index + 1)">下一题</BaseButton>
          <BaseButton variant="primary" :loading="submitting" @click="submit">
            {{ submitting ? '提交中…' : '提交任务' }}
          </BaseButton>
        </div>
        <p class="ui-muted task-reason">任务开始后不能取消；停止输入满 5 秒将自动保存草稿。</p>
      </BaseCard>
    </template>

    <BaseCard v-else-if="!error">
      <SkeletonBlock :lines="4" />
    </BaseCard>

    <BaseDialog
      v-model:open="confirmDialog.open"
      :title="confirmDialog.title"
      :message="confirmDialog.message"
      :danger="confirmDialog.danger"
      @confirm="confirmDialog.onConfirm && confirmDialog.onConfirm()"
    />
  </div>
</template>

<style scoped>
.task-mix {
  display: flex;
  gap: var(--sp-12);
  flex-wrap: wrap;
  align-items: center;
  font-size: var(--fs-14);
  padding: var(--sp-16) var(--sp-20);
}

.task-stem {
  margin: var(--sp-12) 0 var(--sp-8);
  font-size: var(--fs-18);
  line-height: var(--lh-loose);
}

.task-reason {
  font-size: var(--fs-13);
  margin: var(--sp-8) 0 var(--sp-16);
}
</style>
