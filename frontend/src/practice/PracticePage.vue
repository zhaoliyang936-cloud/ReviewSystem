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
const practice = ref(null)
const index = ref(0)
const answers = ref({})
const message = ref('')
const error = ref('')
const saving = ref(false)
const uploading = ref(false)
const preparing = ref(false)
const prepareTimedOut = ref(false)
const prepareFailed = ref(false)
const prepareFailureReason = ref('')
const confirmDialog = ref({ open: false, title: '', message: '', danger: false, onConfirm: null })
const submitKey = crypto.randomUUID()
const requestedCount = Number(window.history.state.requestedCount || 0)
let timer, pollTimer, pollStartedAt = 0

const item = computed(() => practice.value?.items?.[index.value])
const mix = computed(() => practice.value?.mixRatio || null)
const answer = computed({
  get: () => answers.value[item.value?.id] || {},
  set: v => { answers.value[item.value.id] = v }
})
const plannedAiCount = computed(() => practice.value?.mixRatio?.planned?.ai ?? 3)

function isAnswered(i) {
  const a = answers.value[i.id]
  if (!a) return false
  if (i.type === 'CHOICE') return Boolean(a.selectedOption)
  if (i.type === 'FILL') return Array.isArray(a.blanks) && a.blanks.some(v => v && v.trim())
  return Boolean(a.text?.trim() || a.code?.trim())
}

function initial(i) {
  if (i.draft) return structuredClone(i.draft)
  if (i.type === 'CHOICE') return { selectedOption: '' }
  if (i.type === 'FILL') return { blanks: Array.from({ length: (i.content.stem.match(/\{\{blank\}\}/g) || []).length }, () => '') }
  return { text: '', code: '' }
}

async function initReadyPractice() {
  preparing.value = false
  prepareTimedOut.value = false
  if (practice.value.status === 'PREPARATION_FAILED') {
    prepareFailed.value = true
    prepareFailureReason.value = practice.value.mixRatio?.preparationFailure?.reason || 'AI_WORKER_STALE'
    return
  }
  prepareFailed.value = false
  for (const i of practice.value.items) answers.value[i.id] = initial(i)
  if (practice.value.status === 'READY') {
    practice.value = await api(`/api/practices/${route.params.id}/start`, { method: 'POST' })
  }
  if (practice.value.status === 'COMPLETED') {
    router.replace(`/practice/${route.params.id}/result`)
  }
}

async function pollPreparing() {
  const POLL_MAX_MS = Math.max(120000, ((practice.value?.preparationTimeoutSeconds ?? 90) * 1000) + 30000)
  if (Date.now() - pollStartedAt >= POLL_MAX_MS) {
    try {
      practice.value = await api(`/api/practices/${route.params.id}`)
      if (practice.value.status !== 'PREPARING') {
        await initReadyPractice()
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
    practice.value = await api(`/api/practices/${route.params.id}`)
    if (practice.value.status !== 'PREPARING') {
      await initReadyPractice()
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
    practice.value = await api(`/api/practices/${route.params.id}`)
    if (practice.value.status === 'PREPARING') {
      preparing.value = true
      pollStartedAt = Date.now()
      pollTimer = setTimeout(pollPreparing, 1000)
      return
    }
    await initReadyPractice()
  } catch (e) {
    error.value = e.message
  }
}

async function save() {
  clearTimeout(timer)
  if (!item.value || practice.value.status !== 'IN_PROGRESS') return
  saving.value = true
  message.value = ''
  try {
    const saved = await api(`/api/practices/${practice.value.id}/items/${item.value.id}/draft`, {
      method: 'PUT',
      body: { answer: answers.value[item.value.id], draftVersion: item.value.draftVersion }
    })
    item.value.draftVersion = saved.draftVersion
    message.value = '已保存'
  } catch (e) {
    error.value = `未保存：${e.message}`
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
  try {
    const body = new FormData()
    body.append('file', file)
    const made = await api(`/api/practices/${practice.value.id}/items/${item.value.id}/assets`, { method: 'POST', body })
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
        await api(`/api/practices/${practice.value.id}/items/${item.value.id}/assets/${asset.id}`, { method: 'DELETE' })
        item.value.assets = item.value.assets.filter(a => a.id !== asset.id)
      } catch (e) {
        error.value = e.message
      }
    }
  }
}

async function submit() {
  await save()
  const unanswered = practice.value.items.filter(i => {
    const a = answers.value[i.id]
    return i.type === 'CHOICE' ? !a.selectedOption : i.type === 'FILL' ? a.blanks.every(v => !v.trim()) : !a.text?.trim() && !a.code?.trim()
  }).length
  confirmDialog.value = {
    open: true,
    title: '提交练习',
    message: unanswered ? `还有 ${unanswered} 题未作答，仍要提交吗？` : '确认提交后不能再修改，继续吗？',
    danger: false,
    onConfirm: async () => {
      confirmDialog.value.open = false
      try {
        await api(`/api/practices/${practice.value.id}/submit`, {
          method: 'POST',
          headers: { 'Idempotency-Key': submitKey },
          body: { answers: practice.value.items.map(i => ({ itemId: i.id, answer: answers.value[i.id], assetIds: i.assets.map(a => a.id) })) }
        })
        router.push(`/practice/${practice.value.id}/result`)
      } catch (e) {
        error.value = e.message
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
        <span class="ui-kicker">普通练习</span>
        <h1 class="ui-title ui-num">第 {{ index + 1 }} / {{ practice?.items?.length || 0 }} 题</h1>
      </div>
      <RouterLink to="/">返回首页</RouterLink>
    </header>

    <p v-if="!mix && practice && requestedCount > practice.questionCount" class="ui-muted">
      该知识点可用题目不足，本次共 {{ practice.questionCount }} 题
    </p>
    <p v-if="mix?.shortage" class="ui-muted">
      <span v-if="practice?.status === 'PREPARING' || (mix?.actual?.ai ?? 0) > 0">题库可用题不足 {{ mix.shortage.bankShortfall }} 题，已由 AI 补位</span>
      <span v-else>题库可用题不足 {{ mix.shortage.bankShortfall }} 题，本次共 {{ mix.shortage.actual }} 题</span>
    </p>
    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

    <BaseCard v-if="preparing">
      <p>正在准备题目（AI {{ plannedAiCount }} 题）…</p>
      <SkeletonBlock :lines="2" class="ui-mb-16" />
      <BaseButton variant="primary" disabled>开始作答</BaseButton>
    </BaseCard>

    <BaseCard v-else-if="prepareFailed">
      <p class="ui-error">题目准备失败（{{ prepareFailureReason }}），请返回重新创建。</p>
      <BaseButton to="/practice/new" variant="secondary">返回重新创建</BaseButton>
    </BaseCard>

    <BaseCard v-else-if="prepareTimedOut">
      <p class="ui-error">题目仍在准备中，可以稍后刷新；若长时间无变化，请返回重新创建。</p>
      <BaseButton variant="secondary" @click="load">刷新题目</BaseButton>
    </BaseCard>

    <template v-else-if="item">
      <BaseCard>
        <div class="ui-actions ui-mb-12">
          <StatusPill tone="brand">{{ item.type }}</StatusPill>
          <span class="ui-muted ui-num">{{ item.maxScore }} 分</span>
        </div>

        <h2 class="practice-stem">{{ item.content.stem }}</h2>

        <div v-if="item.questionAssets?.length" class="ui-assets ui-mb-16">
          <a v-for="a in item.questionAssets" :key="a.id" :href="a.url" target="_blank">
            <img :src="a.url" width="96" height="72" alt="题目图片" />
          </a>
        </div>

        <div v-if="item.type === 'CHOICE'" class="ui-options">
          <label v-for="o in item.content.options" :key="o.key" class="ui-option">
            <input v-model="answer.selectedOption" type="radio" :value="o.key" />
            <span>{{ o.key }}. {{ o.text }}</span>
          </label>
        </div>

        <div v-else-if="item.type === 'FILL'">
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

          <BaseField v-slot="{ id }" label="作答图片（最多 3 张）" class="ui-mt-12">
            <input :id="id" type="file" accept="image/png,image/jpeg" :disabled="uploading || item.assets.length >= 3" @change="upload" />
          </BaseField>
        </template>
      </BaseCard>

      <BaseCard>
        <div class="ui-progress-grid" aria-label="题目导航">
          <button
            v-for="(q, i) in practice.items"
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
          <BaseButton variant="secondary" :disabled="index === practice.items.length - 1" @click="move(index + 1)">下一题</BaseButton>
          <BaseButton variant="primary" @click="submit">提交练习</BaseButton>
        </div>
      </BaseCard>
    </template>

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
.practice-stem {
  margin: var(--sp-12) 0 var(--sp-16);
  font-size: var(--fs-18);
  line-height: var(--lh-loose);
  color: var(--ink-900);
}
</style>
