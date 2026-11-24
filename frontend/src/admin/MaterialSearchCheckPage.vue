<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusPill from '../components/StatusPill.vue'

const points = ref([])
const knowledgeId = ref('')
const query = ref('')
const hits = ref([])
const searched = ref(false)
const error = ref('')

async function runSearch() {
  error.value = ''
  try {
    const params = new URLSearchParams()
    if (knowledgeId.value) params.set('knowledgeId', knowledgeId.value)
    if (query.value.trim()) params.set('query', query.value.trim())
    hits.value = await api(`/api/admin/materials/search-check?${params}`)
    searched.value = true
  } catch (e) {
    error.value = e.message
  }
}

onMounted(async () => {
  try {
    points.value = await api('/api/admin/knowledge-points')
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <section>
    <div class="ui-page-head">
      <div>
        <span class="ui-eyebrow">SEARCH CHECK</span>
        <h1>资料检索预览</h1>
        <p class="ui-subtitle">仅检索 subject=1 的已发布资料；按关键词命中数降序、同分按 chunkId 升序，最多返回 3 段（c1..c3）。</p>
      </div>
      <BaseButton to="/admin/materials" variant="secondary">返回资料列表</BaseButton>
    </div>

    <BaseCard tag="form" class="s-filters ui-mb-16" @submit.prevent="runSearch">
      <BaseField v-slot="{ id }" label="知识点（可选）">
        <select :id="id" v-model="knowledgeId">
          <option value="">全部知识点</option>
          <option v-for="p in points" :key="p.id" :value="p.id">{{ p.code }} · {{ p.name }}</option>
        </select>
      </BaseField>

      <BaseField v-slot="{ id }" label="检索关键词（中文按 ≥2 字二元组，英文/数字按完整词，大小写不敏感；留空仅按知识点过滤）">
        <input :id="id" v-model="query" placeholder="例如：二叉树 前序遍历 DFS" />
      </BaseField>

      <BaseButton type="submit" variant="primary">检索预览</BaseButton>
    </BaseCard>

    <p v-if="error" class="ui-error ui-mb-12" role="alert">{{ error }}</p>

    <BaseCard v-if="searched">
      <h2 class="hit-title ui-num">命中结果（{{ hits.length }} / 3）</h2>
      <EmptyState v-if="!hits.length" title="未命中任何已发布片段。" />
      <ol v-else class="hit-list">
        <li v-for="(hit, idx) in hits" :key="hit.chunkId">
          <div class="hit-head">
            <StatusPill tone="success">c{{ idx + 1 }}</StatusPill>
            <strong>{{ hit.title }}</strong>
            <span class="ui-code">来源: {{ hit.sourceLabel || '—' }}</span>
            <span class="ui-code">versionId={{ hit.materialVersionId }}</span>
            <span class="ui-code">chunkId={{ hit.chunkId }}</span>
            <StatusPill tone="neutral" class="ui-num">命中词数: {{ hit.hitCount }}</StatusPill>
          </div>
          <pre>{{ hit.text }}</pre>
        </li>
      </ol>
    </BaseCard>
  </section>
</template>

<style scoped>
.s-filters {
  display: grid;
  grid-template-columns: 260px 1fr auto;
  gap: var(--sp-12);
  align-items: end;
}

.s-filters :deep(.ui-field + .ui-field) {
  margin-top: 0;
}

.hit-title {
  margin: 0 0 var(--sp-12);
  font-size: var(--fs-16);
}

.hit-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: var(--sp-12);
}

.hit-list li {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: var(--sp-12);
  background: var(--bg-sub);
}

.hit-head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-8);
  align-items: center;
  margin-bottom: var(--sp-8);
}

.hit-list pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}

@media (max-width: 1024px) {
  .s-filters {
    grid-template-columns: 1fr;
  }
}
</style>
