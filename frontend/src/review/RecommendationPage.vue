<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import BaseField from '../components/BaseField.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

const router = useRouter()
const data = ref(null)
const count = ref(10)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const key = crypto.randomUUID()

async function load() {
  error.value = ''
  try {
    data.value = await api(`/api/review/recommendations?count=${count.value}`)
  } catch (e) {
    error.value = e.message
  }
}

async function generate() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await api('/api/review/tasks', {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
      body: { questionCount: count.value }
    })
    router.push(`/review/tasks/${created.id}`)
  } catch (e) {
    if (e.code === 'ASSESSMENT_UPDATING') notice.value = '掌握度评估仍在更新，请等几秒后再试。'
    else if (e.code === 'STATE_CONFLICT' && e.activityId) notice.value = `还有进行中的${e.kind === 'TASK' ? '复习任务' : '普通练习'}，请先完成它。`
    else if (e.code === 'NO_AVAILABLE_QUESTION') notice.value = '当前没有可用的推荐题目，先完成一次练习再回来。'
    else error.value = e.message
  } finally {
    busy.value = false
  }
}

function num(v, d = 2) {
  return v == null ? '—' : Number(v).toFixed(d)
}

onMounted(load)
</script>

<template>
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">REVIEW</span>
        <h1 class="ui-title">复习任务</h1>
      </div>
      <RouterLink to="/">返回首页</RouterLink>
    </header>

    <p v-if="error" class="ui-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="ui-notice" role="status">{{ notice }}</p>

    <BaseCard>
      <div class="ui-actions rec-controls">
        <BaseField v-slot="{ id }" label="题目数量" class="rec-count-field">
          <select :id="id" v-model.number="count" @change="load">
            <option v-for="n in 10" :key="n" :value="n">{{ n }}</option>
          </select>
        </BaseField>
        <BaseButton variant="primary" :loading="busy" @click="generate">
          {{ busy ? '正在生成…' : '生成复习任务' }}
        </BaseButton>
        <BaseButton variant="secondary" :disabled="busy" @click="load">重新计算推荐</BaseButton>
      </div>

      <p v-if="data" class="ui-muted rec-note ui-num">
        规则版本 {{ data.ruleVersion }}；优先级 = {{ num(data.parameters.weaknessWeight) }}×薄弱 + {{ num(data.parameters.timeWeight) }}×间隔 + {{ num(data.parameters.errorWeight) }}×错误率。
        复习池 {{ num(data.parameters.reviewSlots, 0) }} 个、诊断池 {{ num(data.parameters.diagnosticSlots, 0) }} 个名额，n≥{{ data.parameters.reviewMinimumN }} 进入复习池。
      </p>
      <p v-if="data?.updating" class="ui-notice">掌握度评估仍在更新中，下面的分数可能稍有滞后。</p>
    </BaseCard>

    <template v-if="data">
      <BaseCard>
        <h2 class="rec-h2">知识点优先级</h2>
        <TableShell :empty="!data.plannedKnowledge.length" empty-title="暂无可用知识点">
          <template #head>
            <tr>
              <th>知识点</th>
              <th>池</th>
              <th>优先级</th>
              <th>已答证据数 n</th>
            </tr>
          </template>
          <tr v-for="k in data.plannedKnowledge" :key="k.knowledgeId">
            <td>{{ k.knowledgeCode }}</td>
            <td>
              <StatusPill :tone="k.pool === 'REVIEW' ? 'brand' : 'info'">
                {{ k.pool === 'REVIEW' ? '复习' : '诊断' }}
              </StatusPill>
            </td>
            <td class="ui-num">{{ num(k.priority, 3) }}</td>
            <td class="ui-num">{{ k.n }}</td>
          </tr>
        </TableShell>
      </BaseCard>

      <BaseCard>
        <h2 class="rec-h2">选题明细（{{ data.items.length }} 题）</h2>
        <TableShell :empty="!data.items.length" empty-title="没有可推荐的题目">
          <template #head>
            <tr>
              <th>#</th>
              <th>知识点</th>
              <th>题型</th>
              <th>池</th>
              <th>M_final</th>
              <th>薄弱</th>
              <th>间隔</th>
              <th>错误率</th>
              <th>说明</th>
            </tr>
          </template>
          <tr v-for="(it, i) in data.items" :key="it.questionId + '-' + i">
            <td class="ui-num">{{ i + 1 }}</td>
            <td>{{ it.knowledgeCode }}</td>
            <td>{{ it.questionType }}</td>
            <td>
              <StatusPill :tone="it.reason.pool === 'REVIEW' ? 'brand' : 'info'">
                {{ it.reason.pool === 'REVIEW' ? '复习' : '诊断' }}
              </StatusPill>
            </td>
            <td class="ui-num">{{ num(it.reason.finalScore) }}</td>
            <td class="ui-num">{{ num(it.reason.weakness) }}</td>
            <td class="ui-num">{{ num(it.reason.daysSincePractice, 1) }}</td>
            <td class="ui-num">{{ it.reason.errorDenominator ? `${it.reason.errorNumerator}/${it.reason.errorDenominator}` : '—' }}</td>
            <td class="ui-muted">
              {{ it.reason.selectionCategory }}<template v-if="it.reason.cooldownRelaxed">（放宽冷却）</template>
            </td>
          </tr>
        </TableShell>
        <p v-for="s in data.shortfall" :key="s.pool" class="ui-muted rec-note">
          {{ s.pool }}池缺少 {{ s.missing }} 题（{{ s.reason }}）。
        </p>
      </BaseCard>
    </template>

    <BaseCard v-else-if="!error">
      <SkeletonBlock :lines="5" />
    </BaseCard>
  </div>
</template>

<style scoped>
.rec-controls {
  align-items: flex-end;
}

.rec-count-field {
  min-width: 120px;
}

.rec-note {
  margin: var(--sp-12) 0 0;
  font-size: var(--fs-13);
  line-height: var(--lh-base);
}

.rec-h2 {
  margin: 0 0 var(--sp-16);
  font-size: var(--fs-18);
  line-height: var(--lh-tight);
}
</style>
