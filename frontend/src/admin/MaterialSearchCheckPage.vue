<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'

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
    <div class="page-head">
      <div>
        <h2>资料检索预览</h2>
        <p class="muted">仅检索 subject=1 的已发布资料；按关键词命中数降序、同分按 chunkId 升序，最多返回 3 段（c1..c3）。</p>
      </div>
      <RouterLink class="secondary link-btn" to="/admin/materials">返回资料列表</RouterLink>
    </div>

    <form class="card filters" @submit.prevent="runSearch">
      <label class="field">
        知识点（可选）
        <select v-model="knowledgeId">
          <option value="">全部知识点</option>
          <option v-for="p in points" :key="p.id" :value="p.id">{{ p.code }} · {{ p.name }}</option>
        </select>
      </label>
      <label class="field">
        检索关键词（中文按 ≥2 字二元组，英文/数字按完整词，大小写不敏感；留空仅按知识点过滤）
        <input v-model="query" placeholder="例如：二叉树 前序遍历 DFS" />
      </label>
      <button class="primary" type="submit">检索预览</button>
    </form>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="searched" class="card">
      <h3>命中结果（{{ hits.length }} / 3）</h3>
      <p v-if="!hits.length" class="muted">未命中任何已发布片段。</p>
      <ol v-else class="hit-list">
        <li v-for="(hit, idx) in hits" :key="hit.chunkId">
          <div class="hit-head">
            <span class="badge active">c{{ idx + 1 }}</span>
            <strong>{{ hit.title }}</strong>
            <span class="code">来源: {{ hit.sourceLabel || '—' }}</span>
            <span class="code">versionId={{ hit.materialVersionId }}</span>
            <span class="code">chunkId={{ hit.chunkId }}</span>
            <span class="badge">命中词数: {{ hit.hitCount }}</span>
          </div>
          <pre>{{ hit.text }}</pre>
        </li>
      </ol>
    </div>
  </section>
</template>

<style scoped>
.filters {
  display: grid;
  grid-template-columns: 260px 1fr auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 14px;
}
.link-btn {
  display: inline-block;
  text-decoration: none;
}
.hit-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 12px;
}
.hit-list li {
  border: 1px solid #dfe4ec;
  border-radius: 8px;
  padding: 12px;
  background: #fafbfc;
}
.hit-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.hit-list pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}
@media (max-width: 900px) {
  .filters {
    grid-template-columns: 1fr;
  }
}
</style>
