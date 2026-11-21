<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'

const route = useRoute()
const router = useRouter()

const points = ref([])
const detail = ref(null)
const form = ref({
  knowledgeId: '',
  title: '',
  sourceLabel: '',
  content: ''
})
const withdrawReason = ref('')
const error = ref('')
const notice = ref('')
const loading = ref(false)

const isNew = computed(() => !route.params.id)
const statusLabels = { DRAFT: '草稿', PUBLISHED: '已发布', WITHDRAWN: '已撤回' }

// Client-side preview matching backend MaterialChunkSplitter rules
const previewChunks = computed(() => {
  const raw = (form.value.content || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim()
  if (!raw) return []
  const paragraphs = raw.split(/\n\s*\n+/)
  const out = []
  for (const para of paragraphs) {
    const trimmed = para.trim()
    if (!trimmed) continue
    if (trimmed.length <= 1000) {
      out.push(trimmed)
    } else {
      const sentences = trimmed.split(/(?<=[。；;！？!?\n])/)
      let current = ''
      for (const sRaw of sentences) {
        const s = sRaw.trim()
        if (!s) continue
        if (s.length > 1000) {
          if (current) {
            out.push(current.trim())
            current = ''
          }
          for (let i = 0; i < s.length; i += 1000) {
            const slice = s.slice(i, i + 1000).trim()
            if (slice) out.push(slice)
          }
          continue
        }
        if (!current) {
          current = s
        } else if (current.length + 1 + s.length <= 1000) {
          current += ' ' + s
        } else {
          out.push(current.trim())
          current = s
        }
      }
      if (current.trim()) out.push(current.trim())
    }
  }
  return out
})

async function loadDetail() {
  if (isNew.value) {
    detail.value = null
    form.value = { knowledgeId: points.value[0]?.id || '', title: '', sourceLabel: '', content: '' }
    return
  }
  loading.value = true
  try {
    error.value = ''
    const data = await api(`/api/admin/materials/${route.params.id}`)
    detail.value = data
    form.value = {
      knowledgeId: data.knowledgeId,
      title: data.title,
      sourceLabel: data.sourceLabel || '',
      content: data.content
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function save() {
  error.value = ''
  notice.value = ''
  try {
    if (isNew.value) {
      const created = await api('/api/admin/materials', {
        method: 'POST',
        body: {
          knowledgeId: form.value.knowledgeId,
          title: form.value.title,
          sourceLabel: form.value.sourceLabel || null,
          content: form.value.content
        }
      })
      notice.value = '草稿已创建'
      await router.replace(`/admin/materials/${created.materialId}`)
    } else {
      const updated = await api(`/api/admin/materials/${detail.value.materialId}`, {
        method: 'PUT',
        body: {
          knowledgeId: form.value.knowledgeId,
          title: form.value.title,
          sourceLabel: form.value.sourceLabel || null,
          content: form.value.content,
          expectedVersion: detail.value.latestVersion
        }
      })
      detail.value = updated
      notice.value = `已生成新版本 v${updated.latestVersion}（草稿）`
    }
  } catch (e) {
    error.value = e.message
  }
}

async function publish() {
  if (!detail.value) return
  error.value = ''
  notice.value = ''
  try {
    const updated = await api(`/api/admin/materials/${detail.value.materialId}/publish`, {
      method: 'POST',
      body: { expectedVersion: detail.value.latestVersion }
    })
    detail.value = updated
    notice.value = `版本 v${updated.latestVersion} 已发布并完成拆段`
  } catch (e) {
    error.value = e.message
  }
}

async function withdraw() {
  if (!detail.value) return
  if (!withdrawReason.value.trim()) {
    error.value = '请填写撤回原因'
    return
  }
  error.value = ''
  notice.value = ''
  try {
    const updated = await api(`/api/admin/materials/${detail.value.materialId}/withdraw`, {
      method: 'POST',
      body: { reason: withdrawReason.value.trim(), expectedVersion: detail.value.latestVersion }
    })
    detail.value = updated
    notice.value = `线上版本已撤回（原因：${updated.withdrawReason || withdrawReason.value.trim()}）`
    withdrawReason.value = ''
  } catch (e) {
    error.value = e.message
  }
}

function formatTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

watch(() => route.params.id, loadDetail)

onMounted(async () => {
  try {
    points.value = await api('/api/admin/knowledge-points')
    await loadDetail()
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>{{ isNew ? '新建课程资料' : `资料详情 #${detail?.materialId || ''}` }}</h2>
        <p class="muted">
          {{ isNew ? '创建后为草稿状态，需填写来源位置并发布后才进入检索。' : '编辑保存将生成新的草稿版本，旧版本不可变。' }}
        </p>
      </div>
      <RouterLink class="secondary link-btn" to="/admin/materials">返回列表</RouterLink>
    </div>

    <p v-if="notice" class="success">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="detail?.publicationStatus === 'WITHDRAWN'" class="card withdrawn-banner">
      <strong>该资料当前处于已撤回状态</strong>
      <span>{{ detail.withdrawReason ? `撤回原因：${detail.withdrawReason}` : '检索已立即停止命中；如需重新启用，请编辑生成新版本并发布。' }}</span>
    </div>

    <div class="editor-grid">
      <form class="card grid" @submit.prevent="save">
        <div v-if="detail" class="status-bar">
          <span class="badge">当前 v{{ detail.latestVersion }}</span>
          <span :class="['badge', detail.publicationStatus === 'PUBLISHED' ? 'active' : '']">
            {{ statusLabels[detail.publicationStatus] || detail.publicationStatus }}
          </span>
          <span
            v-if="detail.publishedVersion && detail.publishedVersion !== detail.latestVersion"
            class="badge live"
          >
            线上生效 v{{ detail.publishedVersion }}
          </span>
        </div>

        <label class="field">
          标题
          <input v-model="form.title" maxlength="200" required placeholder="例如：二叉树的遍历性质" />
        </label>

        <label class="field">
          所属知识点
          <select v-model="form.knowledgeId" required>
            <option value="" disabled>请选择知识点</option>
            <option v-for="p in points" :key="p.id" :value="p.id">{{ p.code }} · {{ p.name }}</option>
          </select>
        </label>

        <label class="field">
          来源位置（发布前必填，如 19A.pdf#p12）
          <input v-model="form.sourceLabel" maxlength="300" placeholder="例如：教材第6章#p132 或 19A.pdf#p12" />
        </label>

        <label class="field">
          正文（空行分段，每段最多 1000 字符）
          <textarea v-model="form.content" rows="12" required placeholder="输入知识点讲解正文..."></textarea>
        </label>

        <div class="actions">
          <button class="primary" type="submit">{{ isNew ? '保存草稿' : '编辑生成新版本' }}</button>
          <button
            v-if="detail && detail.publicationStatus === 'DRAFT'"
            type="button"
            class="primary"
            @click="publish"
          >
            审核发布
          </button>
        </div>

        <div v-if="detail && detail.publishedVersion != null" class="withdraw-box">
          <label class="field">
            撤回原因
            <input v-model="withdrawReason" placeholder="输入撤回原因后点击撤回" />
          </label>
          <p class="muted note">
            撤回作用于线上生效版本 v{{ detail.publishedVersion }}；若存在更新的草稿，草稿会保留但不会上线。
          </p>
          <button type="button" class="danger" @click="withdraw">撤回资料</button>
        </div>
      </form>

      <div class="grid">
        <div class="card">
          <h3>右侧分段预览（{{ previewChunks.length }} 段）</h3>
          <p class="muted note">前端实时预览按空行与 1000 字规则拆分，发布时以服务端实际拆段结果为准。</p>
          <p v-if="!previewChunks.length" class="muted">输入正文后在此显示分段预览。</p>
          <ol v-else class="chunk-list">
            <li v-for="(chunk, idx) in previewChunks" :key="idx">
              <div class="chunk-meta">
                <span class="code">position={{ idx }}</span>
                <span class="muted">{{ chunk.length }} 字符</span>
              </div>
              <pre>{{ chunk }}</pre>
            </li>
          </ol>
        </div>

        <div v-if="detail && detail.chunks?.length" class="card">
          <h3>服务端已落库分段（{{ detail.chunks.length }} 段）</h3>
          <ol class="chunk-list">
            <li v-for="c in detail.chunks" :key="c.id">
              <div class="chunk-meta">
                <span class="code">chunkId={{ c.id }} · position={{ c.position }}</span>
              </div>
              <pre>{{ c.text }}</pre>
            </li>
          </ol>
        </div>

        <div v-if="detail && detail.versions?.length" class="card">
          <h3>版本历史（不可变）</h3>
          <table>
            <thead>
              <tr>
                <th>版本</th>
                <th>状态</th>
                <th>来源</th>
                <th>创建时间</th>
                <th>审核时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in detail.versions" :key="v.id" :class="{ withdrawn: v.publicationStatus === 'WITHDRAWN' }">
                <td class="code">v{{ v.version }}</td>
                <td>{{ statusLabels[v.publicationStatus] || v.publicationStatus }}</td>
                <td class="code">{{ v.sourceLabel || '—' }}</td>
                <td>{{ formatTime(v.createdAt) }}</td>
                <td>{{ formatTime(v.approvedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.editor-grid {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 18px;
  align-items: start;
}
.link-btn {
  display: inline-block;
  text-decoration: none;
}
.status-bar {
  display: flex;
  gap: 8px;
}

.badge.live {
  border-color: #4a4a4a;
  color: #1a1a1a;
  background: #f2f2f2;
}
.withdrawn-banner {
  margin-bottom: 14px;
  background: #f5f6f8;
  border-color: #d5dae2;
  color: #5c6678;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.withdraw-box {
  margin-top: 8px;
  padding-top: 14px;
  border-top: 1px solid #e8ebf0;
  display: grid;
  gap: 10px;
}
.note {
  font-size: 13px;
  margin-top: 0;
}
.chunk-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 10px;
}
.chunk-list li {
  border: 1px solid #e5e9f0;
  border-radius: 8px;
  padding: 10px;
  background: #fafbfc;
}
.chunk-meta {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 12px;
}
.chunk-list pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
}
tr.withdrawn {
  opacity: 0.6;
}
@media (max-width: 1000px) {
  .editor-grid {
    grid-template-columns: 1fr;
  }
}
</style>
