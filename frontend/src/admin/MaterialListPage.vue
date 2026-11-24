<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

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

function statusTone(status) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'WITHDRAWN') return 'neutral'
  return 'warn'
}

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
    <div class="ui-page-head">
      <div>
        <span class="ui-eyebrow">COURSE MATERIALS</span>
        <h1>课程资料</h1>
        <p class="ui-subtitle">仅已发布资料进入检索；编辑将生成新版本，旧版本不可变；撤回后立即停止检索。</p>
      </div>
      <div class="ui-actions">
        <BaseButton to="/admin/materials/search" variant="secondary">检索预览</BaseButton>
        <BaseButton to="/admin/materials/new" variant="primary">新建资料</BaseButton>
      </div>
    </div>

    <BaseCard tag="form" class="m-filters ui-mb-16" @submit.prevent="applyFilters">
      <BaseField v-slot="{ id }" label="知识点">
        <select :id="id" v-model="knowledgeId" @change="applyFilters">
          <option value="">全部知识点</option>
          <option v-for="p in points" :key="p.id" :value="p.id">{{ p.code }} · {{ p.name }}</option>
        </select>
      </BaseField>

      <BaseField v-slot="{ id }" label="发布状态">
        <select :id="id" v-model="statusFilter" @change="applyFilters">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿 (DRAFT)</option>
          <option value="PUBLISHED">已发布 (PUBLISHED)</option>
          <option value="WITHDRAWN">已撤回 (WITHDRAWN)</option>
        </select>
      </BaseField>

      <div class="ui-actions">
        <BaseButton type="submit" variant="primary">筛选</BaseButton>
        <BaseButton variant="secondary" @click="resetFilters">清空</BaseButton>
      </div>
    </BaseCard>

    <p v-if="notice" class="ui-success ui-mb-12" role="status">{{ notice }}</p>
    <p v-if="error" class="ui-error ui-mb-12" role="alert">{{ error }}</p>

    <BaseCard>
      <TableShell :empty="!result.items.length" empty-title="暂无符合条件的课程资料。">
        <template #head>
          <tr>
            <th>标题</th>
            <th>知识点</th>
            <th>状态</th>
            <th>版本</th>
            <th>来源位置</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </template>
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
            <StatusPill :tone="statusTone(item.publicationStatus)">
              {{ statusLabels[item.publicationStatus] || item.publicationStatus }}
            </StatusPill>
          </td>
          <td class="ui-code ui-num">
            v{{ item.latestVersion }}（共 {{ item.versionCount }} 版）
            <div v-if="item.publishedVersion && item.publishedVersion !== item.latestVersion" class="live-note">
              生效中 v{{ item.publishedVersion }}
            </div>
          </td>
          <td class="ui-code">{{ item.sourceLabel || '未填写' }}</td>
          <td class="ui-num">{{ formatTime(item.updatedAt) }}</td>
          <td>
            <div class="ui-actions">
              <BaseButton :to="`/admin/materials/${item.materialId}`" variant="secondary" size="sm">编辑 / 详情</BaseButton>
              <BaseButton
                v-if="item.publicationStatus === 'DRAFT'"
                variant="primary"
                size="sm"
                @click="publishItem(item)"
              >
                发布
              </BaseButton>
              <BaseButton
                v-if="item.publishedVersion != null"
                variant="danger"
                size="sm"
                @click="withdrawItem(item)"
              >
                撤回
              </BaseButton>
            </div>
          </td>
        </tr>
      </TableShell>
    </BaseCard>

    <nav class="ui-pager" aria-label="资料分页">
      <BaseButton variant="secondary" size="sm" :disabled="page === 0" @click="changePage(page - 1)">上一页</BaseButton>
      <span>第 {{ page + 1 }} / {{ totalPages }} 页（共 {{ result.total }} 条）</span>
      <BaseButton variant="secondary" size="sm" :disabled="page + 1 >= totalPages" @click="changePage(page + 1)">下一页</BaseButton>
    </nav>
  </section>
</template>

<style scoped>
.m-filters {
  display: grid;
  grid-template-columns: 1fr 220px auto;
  gap: var(--sp-12);
  align-items: end;
}

.m-filters :deep(.ui-field + .ui-field) {
  margin-top: 0;
}

tr.withdrawn {
  opacity: 0.65;
  background: var(--bg-sub);
}

.withdraw-note {
  margin-top: var(--sp-4);
  font-size: var(--fs-12);
  color: var(--danger);
}

.live-note {
  margin-top: var(--sp-4);
  font-size: var(--fs-12);
  color: var(--ink-700);
}

@media (max-width: 1024px) {
  .m-filters {
    grid-template-columns: 1fr;
  }
}
</style>
