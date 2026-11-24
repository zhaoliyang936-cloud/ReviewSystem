<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

const router = useRouter()
const data = ref({ items: [], page: 0, size: 20, total: 0 })
const knowledgePoints = ref([])
const status = ref('PENDING')
const knowledgeId = ref('')
const type = ref('')
const source = ref('')
const error = ref('')
const busyId = ref('')

const TYPE_LABELS = {
  CHOICE: '单选题',
  FILL: '填空题',
  SHORT_ANSWER: '简答/问答',
  ALGORITHM: '算法设计'
}

function typeLabel(t) {
  return TYPE_LABELS[t] || t
}

async function loadKnowledge() {
  try {
    knowledgePoints.value = await api('/api/knowledge-points')
  } catch {
    // ignore filter dropdown load error
  }
}

async function load(page = 0) {
  error.value = ''
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', '20')
  if (status.value) params.set('status', status.value)
  if (knowledgeId.value) params.set('knowledgeId', knowledgeId.value)
  if (type.value) params.set('type', type.value)
  if (source.value) params.set('source', source.value)
  try {
    data.value = await api(`/api/wrong-questions?${params.toString()}`)
  } catch (e) {
    error.value = e.message
  }
}

async function redo(item) {
  error.value = ''
  busyId.value = item.id
  try {
    const made = await api(`/api/wrong-questions/${item.id}/redo`, {
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
    busyId.value = ''
  }
}

onMounted(() => {
  loadKnowledge()
  load(0)
})
</script>

<template>
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">M09 · WRONG QUESTIONS</span>
        <h1 class="ui-title">错题本</h1>
      </div>
      <div class="ui-actions">
        <RouterLink to="/practice/history">练习历史</RouterLink>
        <RouterLink to="/">返回首页</RouterLink>
      </div>
    </header>

    <BaseCard>
      <div class="ui-actions ui-mb-16">
        <BaseButton :variant="status === 'PENDING' ? 'primary' : 'secondary'" size="sm" @click="status = 'PENDING'; load(0)">
          待重做
        </BaseButton>
        <BaseButton :variant="status === 'MASTERED' ? 'primary' : 'secondary'" size="sm" @click="status = 'MASTERED'; load(0)">
          已掌握
        </BaseButton>
        <BaseButton :variant="status === 'ALL' ? 'primary' : 'secondary'" size="sm" @click="status = 'ALL'; load(0)">
          全部
        </BaseButton>
      </div>

      <div class="ui-grid ui-grid--3 ui-mb-16">
        <BaseField v-slot="{ id }" label="知识点">
          <select :id="id" v-model="knowledgeId" @change="load(0)">
            <option value="">全部知识点</option>
            <option v-for="kp in knowledgePoints" :key="kp.id" :value="kp.id">
              {{ kp.code }} · {{ kp.name }}
            </option>
          </select>
        </BaseField>

        <BaseField v-slot="{ id }" label="题型">
          <select :id="id" v-model="type" @change="load(0)">
            <option value="">全部题型</option>
            <option value="CHOICE">单选题</option>
            <option value="FILL">填空题</option>
            <option value="SHORT_ANSWER">简答/问答</option>
            <option value="ALGORITHM">算法设计</option>
          </select>
        </BaseField>

        <BaseField v-slot="{ id }" label="题目来源">
          <select :id="id" v-model="source" @change="load(0)">
            <option value="">全部来源</option>
            <option value="MANUAL">人工录入</option>
            <option value="AI_GENERATED">AI 生成</option>
          </select>
        </BaseField>
      </div>

      <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

      <TableShell
        :empty="!data.items.length"
        empty-title="当前筛选条件下没有错题记录"
        empty-description="完成练习或复习任务后，答错的题目会自动收录到这里。"
      >
        <template #head>
          <tr>
            <th>题干 / 知识点</th>
            <th>题型与来源</th>
            <th>状态</th>
            <th>错误次数</th>
            <th>最近答错</th>
            <th>操作</th>
          </tr>
        </template>
        <tr v-for="item in data.items" :key="item.id">
          <td>
            <div><strong>{{ item.stem || '（无题干）' }}</strong></div>
            <small class="ui-muted">{{ item.knowledgeCode }} · {{ item.knowledgeName }}</small>
          </td>
          <td>
            <div class="ui-actions wrong-pills">
              <StatusPill tone="brand">{{ typeLabel(item.questionType) }}</StatusPill>
              <StatusPill tone="neutral">{{ item.sourceType === 'AI_GENERATED' ? 'AI 生成' : '人工录入' }}</StatusPill>
              <StatusPill v-if="item.aiJudged" tone="info">AI 判定</StatusPill>
            </div>
          </td>
          <td>
            <StatusPill :tone="item.status === 'MASTERED' ? 'success' : 'warn'">
              {{ item.status === 'MASTERED' ? '已掌握' : '待重做' }}
            </StatusPill>
          </td>
          <td class="ui-num">{{ item.wrongCount }}</td>
          <td class="ui-num">{{ new Date(item.lastWrongAt).toLocaleString() }}</td>
          <td>
            <div class="ui-actions">
              <RouterLink :to="`/wrong/${item.id}`">详情</RouterLink>
              <BaseButton
                variant="primary"
                size="sm"
                :disabled="busyId === item.id || item.questionStatus !== 'ACTIVE'"
                @click="redo(item)"
              >
                {{ item.questionStatus !== 'ACTIVE' ? '已下架' : busyId === item.id ? '创建中…' : '重做' }}
              </BaseButton>
            </div>
          </td>
        </tr>
      </TableShell>

      <div v-if="data.items.length" class="ui-pager">
        <BaseButton variant="secondary" size="sm" :disabled="data.page === 0" @click="load(data.page - 1)">上一页</BaseButton>
        <span>第 {{ data.page + 1 }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }} 页</span>
        <BaseButton variant="secondary" size="sm" :disabled="(data.page + 1) * data.size >= data.total" @click="load(data.page + 1)">下一页</BaseButton>
      </div>
    </BaseCard>
  </div>
</template>

<style scoped>
.wrong-pills {
  gap: var(--sp-4);
}
</style>
