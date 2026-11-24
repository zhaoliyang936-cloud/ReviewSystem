<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseDialog from '../components/BaseDialog.vue'
import BaseField from '../components/BaseField.vue'
import StatusPill from '../components/StatusPill.vue'
import { pushToast } from '../components/useToast'

const route = useRoute()
const router = useRouter()
const id = computed(() => route.params.id)
const points = ref([])
const historyVersions = ref([])
const selectedHistoryVersion = ref('')
const error = ref('')
const file = ref(null)
const confirmDialog = ref({ open: false, title: '', message: '', onConfirm: null })

const blankToken = '{{blank}}'
const form = reactive({
  knowledgePointId: '',
  type: 'CHOICE',
  origin: 'MANUAL',
  maxScore: 2,
  stem: '',
  options: [
    { key: 'A', text: '' },
    { key: 'B', text: '' },
    { key: 'C', text: '' }
  ],
  correctOption: 'A',
  fillAnswers: '',
  referenceAnswer: '',
  rubric: [{ criterion: '', score: 1 }],
  explanation: '',
  sourceRef: '',
  version: null,
  status: 'ACTIVE',
  suspensionReason: '',
  assets: []
})

const typeNames = {
  CHOICE: '单选题',
  FILL: '填空题',
  SHORT_ANSWER: '简答 / 问答题',
  ALGORITHM: '算法设计题'
}

const selectedHistory = computed(() =>
  historyVersions.value.find(item => item.version === Number(selectedHistoryVersion.value))
)

function addOption() {
  form.options.push({ key: String.fromCharCode(65 + form.options.length), text: '' })
}

function addRubric() {
  form.rubric.push({ criterion: '', score: 1 })
}

function request() {
  const content = { stem: form.stem }
  let answer
  let rubric = null
  if (form.type === 'CHOICE') {
    content.options = form.options
    answer = { correctOption: form.correctOption }
  } else if (form.type === 'FILL') {
    answer = {
      blanks: form.fillAnswers.split('\n').map(line => ({
        accepted: line.split('|').map(x => x.trim()).filter(Boolean)
      }))
    }
  } else {
    answer = { referenceAnswer: form.referenceAnswer }
    rubric = { items: form.rubric }
  }
  return {
    knowledgePointId: form.knowledgePointId,
    type: form.type,
    maxScore: form.maxScore,
    content,
    answer,
    rubric,
    explanation: form.explanation,
    sourceRef: form.sourceRef,
    expectedVersion: form.version
  }
}

async function loadHistory() {
  if (!id.value) return
  historyVersions.value = await api(`/api/admin/questions/${id.value}/versions`)
  if (!historyVersions.value.some(item => item.version === Number(selectedHistoryVersion.value))) {
    selectedHistoryVersion.value = ''
  }
}

async function load() {
  try {
    points.value = await api('/api/admin/knowledge-points')
    if (!id.value) {
      form.knowledgePointId = points.value.find(p => p.enabled)?.id || ''
      return
    }
    const q = await api(`/api/admin/questions/${id.value}`)
    Object.assign(form, {
      knowledgePointId: q.knowledgePointId,
      type: q.type,
      origin: q.origin,
      maxScore: q.maxScore,
      stem: q.content.stem,
      explanation: q.explanation || '',
      sourceRef: q.sourceRef || '',
      version: q.version,
      status: q.status,
      suspensionReason: q.suspensionReason || '',
      assets: q.assets
    })
    if (q.type === 'CHOICE') {
      form.options = q.content.options
      form.correctOption = q.answer.correctOption
    } else if (q.type === 'FILL') {
      form.fillAnswers = q.answer.blanks.map(b => b.accepted.join(' | ')).join('\n')
    } else {
      form.referenceAnswer = q.answer.referenceAnswer
      form.rubric = q.rubric.items
    }
    await loadHistory()
  } catch (e) {
    error.value = e.message
  }
}

async function save() {
  error.value = ''
  try {
    const previous = form.version
    const q = id.value
      ? await api(`/api/admin/questions/${id.value}`, { method: 'PUT', body: request() })
      : await api('/api/admin/questions', { method: 'POST', body: request() })
    pushToast(
      id.value
        ? q.version === previous
          ? '内容无变化，未新增版本'
          : `已保存为版本 ${q.version}`
        : `已创建 ${q.questionNo}`,
      'success'
    )
    form.version = q.version
    form.assets = q.assets || []
    if (!id.value) router.replace(`/admin/questions/${q.id}`)
    else await loadHistory()
  } catch (e) {
    error.value = e.message
  }
}

async function changeStatus() {
  try {
    const path = form.status === 'ACTIVE' ? 'suspend' : 'restore'
    const reason = form.status === 'ACTIVE' ? prompt('填写下架原因（必填）') : null
    if (form.status === 'ACTIVE' && !reason) return
    const q = await api(`/api/admin/questions/${id.value}/${path}`, {
      method: 'POST',
      body: { reason, expectedVersion: form.version }
    })
    form.status = q.status
    form.suspensionReason = q.suspensionReason || ''
  } catch (e) {
    error.value = e.message
  }
}

async function upload() {
  if (!file.value) return
  const data = new FormData()
  data.append('file', file.value)
  try {
    const asset = await api(`/api/admin/questions/${id.value}/assets`, { method: 'POST', body: data })
    form.assets = [...form.assets, asset].sort((a, b) => a.position - b.position)
    file.value = null
    await loadHistory()
  } catch (e) {
    error.value = e.message
  }
}

function deleteHistoryAsset(asset) {
  if (!selectedHistory.value) return
  confirmDialog.value = {
    open: true,
    title: '删除历史题图',
    message: `确认删除版本 ${selectedHistory.value.version} 的这张历史题图？删除后文件不可恢复。`,
    onConfirm: async () => {
      confirmDialog.value.open = false
      try {
        await api(`/api/admin/question-assets/${asset.id}`, { method: 'DELETE' })
        selectedHistory.value.assets = selectedHistory.value.assets.filter(item => item.id !== asset.id)
        pushToast(`已删除版本 ${selectedHistory.value.version} 的历史题图`, 'success')
      } catch (e) {
        error.value = e.message
      }
    }
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div class="ui-page-head">
      <div>
        <span class="ui-eyebrow">QUESTION EDITOR</span>
        <h1>{{ id ? '编辑题目' : '录入题目' }}</h1>
        <p class="ui-subtitle">每次实质修改生成不可变新版本；切换题型会启用对应答案控件。</p>
      </div>
      <div v-if="id" class="ui-actions">
        <StatusPill :tone="form.status === 'ACTIVE' ? 'success' : 'neutral'">
          {{ form.status === 'ACTIVE' ? '可用' : '已下架' }}
        </StatusPill>
        <BaseButton variant="danger" size="sm" @click="changeStatus">
          {{ form.status === 'ACTIVE' ? '下架' : '恢复' }}
        </BaseButton>
      </div>
    </div>

    <BaseCard tag="form" class="ui-grid" @submit.prevent="save">
      <div class="ui-grid ui-grid--2">
        <BaseField v-slot="{ id: fieldId }" label="主知识点">
          <select :id="fieldId" v-model="form.knowledgePointId" required>
            <option v-for="p in points" :key="p.id" :value="p.id" :disabled="!p.enabled">
              {{ p.code }} · {{ p.name }}{{ p.enabled ? '' : '（停用）' }}
            </option>
          </select>
        </BaseField>

        <BaseField v-slot="{ id: fieldId }" label="题型">
          <select :id="fieldId" v-model="form.type">
            <option v-for="(label, key) in typeNames" :key="key" :value="key">{{ label }}</option>
          </select>
        </BaseField>

        <BaseField label="来源">
          <span class="readonly-source">
            {{ id && form.origin === 'AI_GENERATED' ? 'AI 生成（只读）' : '人工录入（服务端固定）' }}
          </span>
        </BaseField>

        <BaseField v-slot="{ id: fieldId }" label="满分">
          <input :id="fieldId" v-model.number="form.maxScore" type="number" min="0.01" step="0.01" required />
        </BaseField>
      </div>

      <BaseField v-slot="{ id: fieldId }" label="题干">
        <textarea
          :id="fieldId"
          v-model="form.stem"
          required
          :placeholder="form.type === 'FILL' ? `用 ${blankToken} 标记每个空格` : '输入完整题干'"
        ></textarea>
      </BaseField>

      <section v-if="form.type === 'CHOICE'" class="answer-box">
        <header>
          <h3>单选项</h3>
          <BaseButton variant="secondary" size="sm" @click="addOption">增加选项</BaseButton>
        </header>
        <div v-for="(option, index) in form.options" :key="index" class="option-row">
          <input
            v-model="form.correctOption"
            type="radio"
            :value="option.key"
            :aria-label="`选 ${option.key} 为正确答案`"
          />
          <input v-model="option.key" class="ui-input" required maxlength="8" :aria-label="`选项 ${index + 1} 编号`" />
          <input v-model="option.text" class="ui-input" required :placeholder="`选项 ${option.key}`" :aria-label="`选项 ${option.key} 内容`" />
        </div>
      </section>

      <section v-else-if="form.type === 'FILL'" class="answer-box">
        <h3>等价答案集合</h3>
        <p class="ui-muted">每个 <span class="ui-code" v-text="blankToken"></span> 对应一行；同一空的等价答案用竖线分隔。</p>
        <textarea v-model="form.fillAnswers" class="ui-textarea" required placeholder="栈 | stack&#10;队列 | queue" aria-label="等价答案集合"></textarea>
      </section>

      <section v-else class="answer-box ui-grid">
        <BaseField v-slot="{ id: fieldId }" label="参考答案">
          <textarea :id="fieldId" v-model="form.referenceAnswer" required></textarea>
        </BaseField>
        <header>
          <h3>评分要点</h3>
          <BaseButton variant="secondary" size="sm" @click="addRubric">增加要点</BaseButton>
        </header>
        <div v-for="(item, index) in form.rubric" :key="index" class="rubric-row">
          <input v-model="item.criterion" class="ui-input" required placeholder="评分标准" :aria-label="`评分标准 ${index + 1}`" />
          <input v-model.number="item.score" class="ui-input ui-num" type="number" min="0.01" step="0.01" required aria-label="分值" />
        </div>
        <p class="ui-muted ui-num">
          当前合计 {{ form.rubric.reduce((sum, x) => sum + Number(x.score || 0), 0) }} / {{ form.maxScore }} 分
        </p>
      </section>

      <div class="ui-grid ui-grid--2">
        <BaseField v-slot="{ id: fieldId }" label="解析">
          <textarea :id="fieldId" v-model="form.explanation"></textarea>
        </BaseField>
        <BaseField v-slot="{ id: fieldId }" label="来源备注">
          <textarea :id="fieldId" v-model="form.sourceRef"></textarea>
        </BaseField>
      </div>

      <div class="ui-actions">
        <BaseButton type="submit" variant="primary">保存题目</BaseButton>
        <BaseButton to="/admin/questions" variant="secondary">返回列表</BaseButton>
      </div>
    </BaseCard>

    <BaseCard v-if="id" class="assets-card">
      <div>
        <h3 class="ui-h3 ui-mb-0">当前版本题图</h3>
        <p class="ui-muted">仅 PNG/JPEG，实际解码后才会保存；每个版本最多 5 张、每张最多 5 MB。</p>
      </div>
      <div class="ui-actions">
        <input type="file" accept="image/png,image/jpeg" aria-label="选择题目图片" @change="file = $event.target.files[0]" />
        <BaseButton variant="secondary" @click="upload">上传</BaseButton>
      </div>
      <div class="asset-list">
        <a v-for="a in form.assets" :key="a.id" :href="`/api/question-assets/${a.id}`" target="_blank" class="ui-num">
          图 {{ a.position + 1 }} · {{ a.width }}×{{ a.height }} · {{ Math.ceil(a.sizeBytes / 1024) }} KB
        </a>
      </div>
    </BaseCard>

    <BaseCard v-if="id && historyVersions.length" class="assets-card">
      <div>
        <h3 class="ui-h3 ui-mb-0">历史版本题图</h3>
        <p class="ui-muted">先选择历史版本；删除只影响该历史版本，确认后物理文件不可恢复。</p>
      </div>
      <BaseField v-slot="{ id: fieldId }" label="查看版本" class="history-select">
        <select :id="fieldId" v-model="selectedHistoryVersion">
          <option value="">请选择</option>
          <option v-for="item in historyVersions" :key="item.version" :value="item.version">
            版本 {{ item.version }} · {{ item.content.stem }}
          </option>
        </select>
      </BaseField>
      <div v-if="selectedHistory" class="history-view">
        <p class="history-stem"><span class="ui-code">v{{ selectedHistory.version }}</span> {{ selectedHistory.content.stem }}</p>
        <p v-if="!selectedHistory.assets.length" class="ui-muted">该历史版本没有题图。</p>
        <div v-for="a in selectedHistory.assets" :key="a.id" class="history-asset">
          <a :href="`/api/question-assets/${a.id}`" target="_blank" class="ui-num">
            图 {{ a.position + 1 }} · {{ a.width }}×{{ a.height }} · {{ Math.ceil(a.sizeBytes / 1024) }} KB
          </a>
          <BaseButton variant="danger" size="sm" @click="deleteHistoryAsset(a)">删除历史题图</BaseButton>
        </div>
      </div>
    </BaseCard>

    <p v-if="error" class="ui-error ui-mt-12" role="alert">{{ error }}</p>

    <BaseDialog
      v-model:open="confirmDialog.open"
      :title="confirmDialog.title"
      :message="confirmDialog.message"
      danger
      @confirm="confirmDialog.onConfirm && confirmDialog.onConfirm()"
    />
  </section>
</template>

<style scoped>
.answer-box {
  border: 1px solid var(--line);
  border-left: 4px solid var(--brand-600);
  border-radius: var(--r-md);
  padding: var(--sp-16);
  background: var(--bg-sub);
}

.answer-box header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.answer-box h3 {
  margin: 0 0 var(--sp-12);
  font-size: var(--fs-16);
}

.readonly-source {
  display: block;
  padding: var(--sp-8) var(--sp-12);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg);
  color: var(--ink-500);
}

.option-row,
.rubric-row {
  display: grid;
  grid-template-columns: 28px 64px 1fr;
  gap: var(--sp-8);
  align-items: center;
  margin-top: var(--sp-8);
}

.rubric-row {
  grid-template-columns: 1fr 110px;
}

.assets-card {
  margin-top: var(--sp-16);
  display: flex;
  justify-content: space-between;
  gap: var(--sp-20);
  flex-wrap: wrap;
}

.asset-list {
  flex-basis: 100%;
  display: flex;
  gap: var(--sp-12);
  flex-wrap: wrap;
}

.history-select {
  min-width: 280px;
}

.history-view {
  flex-basis: 100%;
  border-top: 1px solid var(--line);
  padding-top: var(--sp-16);
}

.history-stem {
  color: var(--ink-700);
}

.history-asset {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-16);
  padding: var(--sp-12) 0;
  border-top: 1px solid var(--line);
}
</style>
