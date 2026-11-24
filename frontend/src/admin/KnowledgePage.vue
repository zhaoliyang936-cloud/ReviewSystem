<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import StatusPill from '../components/StatusPill.vue'
import { pushToast } from '../components/useToast'

const chapters = ref([])
const points = ref([])
const error = ref('')
const chapterForm = reactive({ code: 'CH10', name: '', description: '', sortOrder: 10, enabled: true })
const pointForm = reactive({ chapterId: '', code: '', name: '', description: '', sortOrder: 1, enabled: true })

const grouped = computed(() => chapters.value.map(c => ({ ...c, points: points.value.filter(p => p.chapterId === c.id) })))

async function load() {
  try {
    ;[chapters.value, points.value] = await Promise.all([
      api('/api/admin/chapters'),
      api('/api/admin/knowledge-points')
    ])
    if (!pointForm.chapterId && chapters.value[0]) pointForm.chapterId = chapters.value[0].id
  } catch (e) {
    error.value = e.message
  }
}

async function addChapter() {
  await run(async () => {
    await api('/api/admin/chapters', { method: 'POST', body: chapterForm })
    chapterForm.name = ''
    await load()
  }, '章节已保存')
}

async function addPoint() {
  await run(async () => {
    await api('/api/admin/knowledge-points', { method: 'POST', body: pointForm })
    pointForm.name = ''
    pointForm.code = ''
    await load()
  }, '知识点已保存')
}

async function editChapter(c) {
  const name = prompt('章节名称', c.name)
  if (name === null) return
  const description = prompt('章节说明', c.description)
  if (description === null) return
  const sortOrder = Number(prompt('排序', String(c.sortOrder)))
  await run(
    () => api(`/api/admin/chapters/${c.id}`, {
      method: 'PUT',
      body: { ...c, name, description, sortOrder, expectedVersion: c.version }
    }).then(load),
    '章节已更新'
  )
}

async function toggleChapter(c) {
  await run(
    () => api(`/api/admin/chapters/${c.id}`, {
      method: 'PUT',
      body: { ...c, expectedVersion: c.version, enabled: !c.enabled }
    }).then(load),
    c.enabled ? '章节已停用' : '章节已启用'
  )
}

async function editPoint(p) {
  const name = prompt('知识点名称', p.name)
  if (name === null) return
  const description = prompt('知识点说明', p.description)
  if (description === null) return
  const sortOrder = Number(prompt('排序', String(p.sortOrder)))
  await run(
    () => api(`/api/admin/knowledge-points/${p.id}`, {
      method: 'PUT',
      body: { ...p, name, description, sortOrder, expectedVersion: p.version }
    }).then(load),
    '知识点已更新'
  )
}

async function togglePoint(p) {
  await run(
    () => api(`/api/admin/knowledge-points/${p.id}`, {
      method: 'PUT',
      body: { ...p, expectedVersion: p.version, enabled: !p.enabled }
    }).then(load),
    p.enabled ? '知识点已停用' : '知识点已启用'
  )
}

async function run(fn, text) {
  error.value = ''
  try {
    await fn()
    pushToast(text, 'success')
  } catch (e) {
    error.value = e.message
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div class="ui-page-head">
      <div>
        <span class="ui-eyebrow">CURRICULUM · DIRECTORY</span>
        <h1>章节与知识点</h1>
        <p class="ui-subtitle">维护固定课程目录；停用代替删除，历史引用继续保留。</p>
      </div>
    </div>

    <div class="ui-grid ui-grid--2">
      <BaseCard tag="form" class="ui-grid" @submit.prevent="addChapter">
        <h2 class="form-title">新增章节</h2>
        <BaseField v-slot="{ id }" label="章节编码">
          <input :id="id" v-model="chapterForm.code" required pattern="CH[0-9]{2}" />
        </BaseField>
        <BaseField v-slot="{ id }" label="名称">
          <input :id="id" v-model="chapterForm.name" required />
        </BaseField>
        <BaseField v-slot="{ id }" label="说明">
          <textarea :id="id" v-model="chapterForm.description"></textarea>
        </BaseField>
        <BaseField v-slot="{ id }" label="排序">
          <input :id="id" v-model.number="chapterForm.sortOrder" type="number" min="0" />
        </BaseField>
        <div>
          <BaseButton type="submit" variant="primary">保存章节</BaseButton>
        </div>
      </BaseCard>

      <BaseCard tag="form" class="ui-grid" @submit.prevent="addPoint">
        <h2 class="form-title">新增知识点</h2>
        <BaseField v-slot="{ id }" label="所属章节">
          <select :id="id" v-model="pointForm.chapterId">
            <option v-for="c in chapters" :key="c.id" :value="c.id">{{ c.code }} · {{ c.name }}</option>
          </select>
        </BaseField>
        <BaseField v-slot="{ id }" label="知识点编码">
          <input :id="id" v-model="pointForm.code" required placeholder="CH03-KP02" pattern="CH[0-9]{2}-KP[0-9]{2}" />
        </BaseField>
        <BaseField v-slot="{ id }" label="名称">
          <input :id="id" v-model="pointForm.name" required />
        </BaseField>
        <BaseField v-slot="{ id }" label="说明">
          <textarea :id="id" v-model="pointForm.description"></textarea>
        </BaseField>
        <BaseField v-slot="{ id }" label="排序">
          <input :id="id" v-model.number="pointForm.sortOrder" type="number" min="0" />
        </BaseField>
        <div>
          <BaseButton type="submit" variant="primary">保存知识点</BaseButton>
        </div>
      </BaseCard>
    </div>

    <p v-if="error" class="ui-error ui-mt-12" role="alert">{{ error }}</p>

    <BaseCard class="directory">
      <article v-for="chapter in grouped" :key="chapter.id" class="directory__chapter">
        <header class="directory__header">
          <span class="ui-code">{{ chapter.code }}</span>
          <h3 class="directory__title">{{ chapter.name }}</h3>
          <small class="ui-muted ui-num">v{{ chapter.version }}</small>
          <StatusPill :tone="chapter.enabled ? 'success' : 'neutral'">
            {{ chapter.enabled ? '启用' : '停用' }}
          </StatusPill>
          <span class="chapter-actions">
            <BaseButton variant="secondary" size="sm" @click="editChapter(chapter)">编辑</BaseButton>
            <BaseButton variant="secondary" size="sm" @click="toggleChapter(chapter)">
              {{ chapter.enabled ? '停用' : '启用' }}
            </BaseButton>
          </span>
        </header>

        <div v-for="p in chapter.points" :key="p.id" class="point">
          <div class="point__info">
            <span class="ui-code">{{ p.code }}</span>
            <strong>{{ p.name }}</strong>
            <small class="ui-muted ui-num">v{{ p.version }}</small>
          </div>
          <span class="ui-actions">
            <BaseButton variant="secondary" size="sm" @click="editPoint(p)">编辑</BaseButton>
            <BaseButton variant="secondary" size="sm" @click="togglePoint(p)">
              {{ p.enabled ? '停用' : '启用' }}
            </BaseButton>
          </span>
        </div>
      </article>
    </BaseCard>
  </section>
</template>

<style scoped>
.form-title {
  margin: 0;
  font-size: var(--fs-18);
  line-height: var(--lh-tight);
}

.directory {
  margin-top: var(--sp-20);
}

.directory__chapter {
  border-left: 2px solid var(--brand-200);
  padding: var(--sp-4) 0 var(--sp-20) var(--sp-20);
}

.directory__header {
  display: flex;
  align-items: center;
  gap: var(--sp-12);
  flex-wrap: wrap;
}

.directory__title {
  margin: 0;
  font-size: var(--fs-16);
}

.chapter-actions {
  display: flex;
  gap: var(--sp-8);
  margin-left: auto;
}

.point {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-12);
  flex-wrap: wrap;
  margin-top: var(--sp-12);
  padding: var(--sp-12);
  background: var(--bg-sub);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.point__info {
  display: flex;
  gap: var(--sp-12);
  align-items: center;
  flex-wrap: wrap;
}
</style>
