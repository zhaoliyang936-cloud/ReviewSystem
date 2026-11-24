<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import EmptyState from '../components/EmptyState.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'

const points = ref([])
const selected = ref([])
const count = ref(10)
const error = ref('')
const busy = ref(false)
const loading = ref(true)
const router = useRouter()

const enabled = computed(() => points.value.filter(p => p.enabled))

onMounted(async () => {
  try {
    points.value = await api('/api/knowledge-points')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})

async function create() {
  error.value = ''
  if (!selected.value.length) {
    error.value = '请至少选择一个知识点'
    return
  }
  busy.value = true
  try {
    const made = await api('/api/practices', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: { knowledgeIds: selected.value, questionCount: count.value }
    })
    router.push({ path: `/practice/${made.id}`, state: { requestedCount: made.requestedCount } })
  } catch (e) {
    error.value = e.activityId
      ? `已有${e.kind === 'PRACTICE' ? '普通练习' : '学习任务'}进行中（活动 ${e.activityId}），请先完成。`
      : e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">M04 · PRACTICE</span>
        <h1 class="ui-title">设置一次普通练习</h1>
      </div>
      <RouterLink to="/">返回首页</RouterLink>
    </header>

    <BaseCard tag="form" @submit.prevent="create">
      <h2 class="section-title">选择知识点</h2>
      <p class="ui-muted">系统按选择顺序轮转出题；题量不足时按实际可用题数组卷，不会重复取题。</p>

      <SkeletonBlock v-if="loading" :lines="4" />
      <EmptyState v-else-if="!enabled.length" title="暂无可用知识点" />
      <div v-else class="ui-grid ui-grid--2 ui-mb-16">
        <label v-for="p in enabled" :key="p.id" class="ui-option">
          <input v-model="selected" type="checkbox" :value="p.id" />
          <span>
            <strong>{{ p.name }}</strong><br />
            <small class="ui-muted">{{ p.code }}</small>
          </span>
        </label>
      </div>

      <BaseField v-slot="{ id }" label="题目数量（1—20）">
        <input :id="id" v-model.number="count" type="number" min="1" max="20" required />
      </BaseField>

      <p v-if="error" class="ui-error ui-mt-12" role="alert">{{ error }}</p>

      <div class="ui-actions ui-mt-20">
        <BaseButton type="submit" variant="primary" :loading="busy">
          {{ busy ? '正在组卷…' : '创建练习' }}
        </BaseButton>
        <RouterLink to="/practice/history">查看历史</RouterLink>
      </div>
    </BaseCard>
  </div>
</template>

<style scoped>
.section-title {
  margin: 0 0 var(--sp-8);
  font-size: var(--fs-18);
  line-height: var(--lh-tight);
}
</style>
