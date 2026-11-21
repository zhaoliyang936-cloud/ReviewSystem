<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'

const result = ref({ items: [], total: 0 })
const points = ref([])
const knowledgeId = ref('')
const statusFilter = ref('')
const page = ref(0)
const size = ref(20)
const error = ref('')
const notice = ref('')
const withdrawReasons = ref({})

const statusLabels = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  WITHDRAWN: '已撤回'
}

const totalPages = computed(() => Math.max(1, Math.ceil(result.value.total / size.value)))

async function load() {
  try {
    error.value = ''
    const query = new URLSearchParams({ page: String(page.value), size: String(size.value) })
    if (knowledgeId.value) query.set('knowledgeId', knowledgeId.value)
    if (statusFilter.value) query.set('status', statusFilter.value)
    result.value = await api(`/api/admin/materials?${query}`)
  } catch (e) {
    error.value = e.message
  }
}

function applyFilters() {
  page.value = 0
  load()
}

function resetFilters() {
  knowledgeId.value = ''
  statusFilter.value = ''
  page.value = 0
  load()
}

function changePage(next) {
  if (next < 0 || next >= totalPages.value) return
  page.value = next
  load()
}

async function publishItem(item) {
  try {
    error.value = ''
    notice.value = ''
    await api(`/api/admin/materials/${item.materialId}/publish`, {
      method: 'POST',
      body: { expectedVersion: item.latestVersion }
    })
    notice.value = `资料《${item.title}》v${item.latestVersion} 已发布`
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function withdrawItem(item) {
  const reason = window.prompt(`请输入撤回《${item.title}》的原因：`, '内容待修订')
  if (reason === null) return
  if (!reason.trim()) {
    error.value = '撤回原因不能为空'
    return
  }
  try {
    error.value = ''
    notice.value = ''
    const updated = await api(`/api/admin/materials/${item.materialId}/withdraw`, {
      method: 'POST',
      body: { reason: reason.trim(), expectedVersion: item.latestVersion }
    })
    withdrawReasons.value = {
      ...withdrawReasons.value,
      [item.materialId]: updated.withdrawReason || reason.trim()
    }
    notice.value = `资料《${item.title}》已撤回，检索将不再命中`
    await load()
  } catch (e) {
    error.value = e.message
  }
}

function formatTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(async () => {
  try {
    points.value = await api('/api/admin/knowledge-points')
    await load()
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>课程资料</h2>
        <p class="muted">仅已发布资料进入检索；编辑将生成新版本，旧版本不可变；撤回后立即停止检索。</p>
      </div>
      <div class="actions">
        <RouterLink class="secondary link-btn" to="/admin/materials/search">检索预览</RouterLink>
        <RouterLink class="primary link-btn" to="/admin/materials/new">新建资料</RouterLink>
      </div>
    </div>

    <form class="card filters" @submit.prevent="applyFilters">
      <label class="field">
        知识点
        <select v-model="knowledgeId" @change="applyFilters">
          <option value="">全部知识点</option>
          <option v-for="p in points" :key="p.id" :value="p.id">{{ p.code }} · {{ p.name }}</option>
        </select>
      </label>
      <label class="field">
        发布状态
        <select v-model="statusFilter" @change="applyFilters">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿 (DRAFT)</option>
          <option value="PUBLISHED">已发布 (PUBLISHED)</option>
          <option value="WITHDRAWN">已撤回 (WITHDRAWN)</option>
        </select>
      </label>
      <div class="actions">
        <button class="primary" type="submit">筛选</button>
        <button class="secondary" type="button" @click="resetFilters">清空</button>
      </div>
    </form>

    <p v-if="notice" class="success">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div class="card">
      <p v-if="!result.items.length" class="muted">暂无符合条件的课程资料。</p>
      <table v-else>
        <thead>
          <tr>
            <th>标题</th>
            <th>知识点</th>
            <th>状态</th>
            <th>版本</th>
            <th>来源位置</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in result.items"
            :key="item.materialId"
            :class="{ withdrawn: item.publicationStatus === 'WITHDRAWN' }"
          >
            <td>
              <strong>{{ item.title }}</strong>
              <div v-if="item.publicationStatus === 'WITHDRAWN'" class="withdraw-note">
                已撤回{{ withdrawReasons[item.materialId] ? `：${withdrawReasons[item.materialId]}` : '（已停止检索）' }}
              </div>
            </td>
            <td>{{ item.knowledgeName }}</td>
            <td>
              <span :class="['badge', statusClass(item.publicationStatus)]">
                {{ statusLabels[item.publicationStatus] || item.publicationStatus }}
              </span>
            </td>
            <td class="code">
              v{{ item.latestVersion }}（共 {{ item.versionCount }} 版）
              <div v-if="item.publishedVersion && item.publishedVersion !== item.latestVersion" class="live-note">
                生效中 v{{ item.publishedVersion }}
              </div>
            </td>
            <td class="code">{{ item.sourceLabel || '未填写' }}</td>
            <td>{{ formatTime(item.updatedAt) }}</td>
            <td>
              <div class="actions">
                <RouterLink class="secondary link-btn" :to="`/admin/materials/${item.materialId}`">编辑 / 详情</RouterLink>
                <button
                  v-if="item.publicationStatus === 'DRAFT'"
                  type="button"
                  class="primary"
                  @click="publishItem(item)"
                >
                  发布
                </button>
                <button
                  v-if="item.publishedVersion != null"
                  type="button"
                  class="danger"
                  @click="withdrawItem(item)"
                >
                  撤回
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <nav class="pager" aria-label="资料分页">
      <button class="secondary" :disabled="page === 0" @click="changePage(page - 1)">上一页</button>
      <span>第 {{ page + 1 }} / {{ totalPages }} 页（共 {{ result.total }} 条）</span>
      <button class="secondary" :disabled="page + 1 >= totalPages" @click="changePage(page + 1)">下一页</button>
    </nav>
  </section>
</template>

<script>
function statusClass(status) {
  if (status === 'PUBLISHED') return 'active'
  if (status === 'WITHDRAWN') return 'withdrawn-badge'
  return 'draft-badge'
}
</script>

<style scoped>
.filters {
  display: grid;
  grid-template-columns: 1fr 220px auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 14px;
}
.link-btn {
  display: inline-block;
  text-decoration: none;
}
tr.withdrawn {
  opacity: 0.62;
  background: #f8f9fb;
}
.withdraw-note {
  margin-top: 4px;
  font-size: 12px;
  color: #8a3b36;
}

.live-note {
  margin-top: 4px;
  font-size: 12px;
  color: #4a4a4a;
}
.badge.draft-badge {
  background: #fff4e5;
  color: #8a5300;
}
.badge.withdrawn-badge {
  background: #eceff4;
  color: #5c6678;
}
.pager {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 14px;
  margin-top: 16px;
  color: #526071;
}
.pager button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
@media (max-width: 900px) {
  .filters {
    grid-template-columns: 1fr;
  }
}
</style>
