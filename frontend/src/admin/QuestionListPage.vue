<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

const result = ref({ items: [], total: 0 })
const chapters = ref([])
const points = ref([])
const page = ref(0)
const size = ref(20)
const chapterId = ref('')
const knowledgeId = ref('')
const origin = ref('')
const type = ref('')
const keyword = ref('')
const error = ref('')

const names = {
  CHOICE: '单选题',
  FILL: '填空题',
  SHORT_ANSWER: '简答 / 问答题',
  ALGORITHM: '算法设计题'
}

const visiblePoints = computed(() =>
  chapterId.value ? points.value.filter(p => p.chapterId === chapterId.value) : points.value
)
const totalPages = computed(() => Math.max(1, Math.ceil(result.value.total / size.value)))

async function load() {
  try {
    error.value = ''
    const query = new URLSearchParams({ page: String(page.value), size: String(size.value) })
    for (const [key, value] of Object.entries({
      chapterId: chapterId.value,
      knowledgeId: knowledgeId.value,
      origin: origin.value,
      type: type.value,
      keyword: keyword.value.trim()
    })) {
      if (value) query.set(key, value)
    }
    result.value = await api(`/api/admin/questions?${query}`)
  } catch (e) {
    error.value = e.message
  }
}

function applyFilters() {
  page.value = 0
  load()
}

function changeChapter() {
  if (knowledgeId.value && !visiblePoints.value.some(p => p.id === knowledgeId.value)) knowledgeId.value = ''
  applyFilters()
}

function reset() {
  chapterId.value = ''
  knowledgeId.value = ''
  origin.value = ''
  type.value = ''
  keyword.value = ''
  page.value = 0
  load()
}

function changePage(next) {
  if (next < 0 || next >= totalPages.value) return
  page.value = next
  load()
}

onMounted(async () => {
  try {
    ;[chapters.value, points.value] = await Promise.all([
      api('/api/admin/chapters'),
      api('/api/admin/knowledge-points')
    ])
    await load()
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <section>
    <div class="ui-page-head">
      <div>
        <span class="ui-eyebrow">QUESTION BANK</span>
        <h1>四类题库</h1>
        <p class="ui-subtitle">按课程结构缩小范围；列表不传输答案、解析或评分要点。</p>
      </div>
      <div class="ui-actions">
        <BaseButton to="/admin/questions/new" variant="primary">录入题目</BaseButton>
      </div>
    </div>

    <BaseCard tag="form" class="q-filters ui-mb-12" @submit.prevent="applyFilters">
      <BaseField v-slot="{ id }" label="章节">
        <select :id="id" v-model="chapterId" @change="changeChapter">
          <option value="">全部章节</option>
          <option v-for="chapter in chapters" :key="chapter.id" :value="chapter.id">
            {{ chapter.code }} · {{ chapter.name }}
          </option>
        </select>
      </BaseField>

      <BaseField v-slot="{ id }" label="知识点">
        <select :id="id" v-model="knowledgeId" @change="applyFilters">
          <option value="">全部知识点</option>
          <option v-for="point in visiblePoints" :key="point.id" :value="point.id">
            {{ point.code }} · {{ point.name }}
          </option>
        </select>
      </BaseField>

      <BaseField v-slot="{ id }" label="来源">
        <select :id="id" v-model="origin" @change="applyFilters">
          <option value="">全部来源</option>
          <option value="MANUAL">人工录入</option>
          <option value="AI_GENERATED">AI 生成</option>
        </select>
      </BaseField>

      <BaseField v-slot="{ id }" label="题型">
        <select :id="id" v-model="type" @change="applyFilters">
          <option value="">全部题型</option>
          <option v-for="(label, key) in names" :key="key" :value="key">{{ label }}</option>
        </select>
      </BaseField>

      <BaseField v-slot="{ id }" label="题号或题干">
        <input :id="id" v-model="keyword" placeholder="输入关键词" @keyup.esc="keyword = ''" />
      </BaseField>

      <BaseField v-slot="{ id }" label="每页">
        <select :id="id" v-model.number="size" @change="applyFilters">
          <option :value="20">20</option>
          <option :value="50">50</option>
          <option :value="100">100</option>
        </select>
      </BaseField>

      <div class="ui-actions q-filter-actions">
        <BaseButton type="submit" variant="primary">搜索</BaseButton>
        <BaseButton variant="secondary" @click="reset">清空</BaseButton>
      </div>
    </BaseCard>

    <div class="result-note ui-num">当前范围共 <strong>{{ result.total }}</strong> 道题</div>

    <BaseCard>
      <TableShell
        :empty="!result.items.length"
        empty-title="当前筛选范围没有题目"
        empty-description="可清空条件或录入新题。"
      >
        <template #head>
          <tr>
            <th>题号</th>
            <th>题型</th>
            <th>题干</th>
            <th>来源</th>
            <th>状态</th>
            <th>版本</th>
          </tr>
        </template>
        <tr v-for="q in result.items" :key="q.id">
          <td class="ui-code">
            <RouterLink :to="`/admin/questions/${q.id}`">{{ q.questionNo }}</RouterLink>
          </td>
          <td><StatusPill tone="brand">{{ names[q.type] }}</StatusPill></td>
          <td class="q-stem-cell">
            <RouterLink :to="`/admin/questions/${q.id}`" class="q-stem-link">
              {{ q.content.stem }}
            </RouterLink>
          </td>
          <td>{{ q.origin === 'MANUAL' ? '人工' : 'AI' }}</td>
          <td>
            <StatusPill :tone="q.status === 'ACTIVE' ? 'success' : 'neutral'">
              {{ q.status === 'ACTIVE' ? '可用' : '已下架' }}
            </StatusPill>
          </td>
          <td class="ui-muted ui-num">v{{ q.version }}</td>
        </tr>
      </TableShell>
    </BaseCard>

    <nav class="ui-pager" aria-label="题库分页">
      <BaseButton variant="secondary" size="sm" :disabled="page === 0" @click="changePage(page - 1)">上一页</BaseButton>
      <span>第 {{ page + 1 }} / {{ totalPages }} 页</span>
      <BaseButton variant="secondary" size="sm" :disabled="page + 1 >= totalPages" @click="changePage(page + 1)">下一页</BaseButton>
    </nav>

    <p v-if="error" class="ui-error ui-mt-12" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.q-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(140px, 1fr)) minmax(180px, 1.4fr) 90px auto;
  gap: var(--sp-12);
  align-items: end;
}

.q-filters :deep(.ui-field + .ui-field) {
  margin-top: 0;
}

.q-filter-actions {
  gap: var(--sp-8);
}

.result-note {
  margin: 0 var(--sp-4) var(--sp-12);
  color: var(--ink-500);
  font-size: var(--fs-13);
}

.result-note strong {
  color: var(--ink-900);
  font-family: var(--font-mono);
}

.q-stem-cell {
  max-width: 360px;
}

.q-stem-link {
  display: block;
  color: var(--ink-900);
  text-decoration: none;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.q-stem-link:hover {
  color: var(--brand-600);
}

@media (max-width: 1024px) {
  .q-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .q-filters {
    grid-template-columns: 1fr;
  }
}
</style>
