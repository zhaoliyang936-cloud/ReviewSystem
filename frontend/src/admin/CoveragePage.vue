<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
import BaseCard from '../components/BaseCard.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

const report = ref(null)
const error = ref('')
const types = { CHOICE: '单选', FILL: '填空', SHORT_ANSWER: '简答', ALGORITHM: '算法' }

onMounted(async () => {
  try {
    report.value = await api('/api/admin/question-coverage')
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <section>
    <div class="ui-page-head">
      <div>
        <span class="ui-eyebrow">COVERAGE REPORT</span>
        <h1>覆盖统计</h1>
        <p class="ui-subtitle">可用题按题型与来源计数；“无题”和“有题但不可用”分别判断。</p>
      </div>
    </div>

    <template v-if="report">
      <div class="ui-grid ui-grid--4 summary ui-mb-16">
        <BaseCard class="summary__card">
          <strong class="ui-num">{{ report.zeroQuestionKnowledgePoints.length }}</strong>
          <span class="ui-muted">完全无题</span>
        </BaseCard>
        <BaseCard class="summary__card warning-card">
          <strong class="ui-num">{{ report.unusableOnlyQuestionKnowledgePoints.length }}</strong>
          <span class="ui-muted">有题但不可用</span>
        </BaseCard>
        <BaseCard class="summary__card">
          <strong class="ui-num">{{ report.missingBindingCount }}</strong>
          <span class="ui-muted">缺少主知识点</span>
        </BaseCard>
        <BaseCard class="summary__card">
          <strong class="ui-num">{{ report.incompleteFieldCount }}</strong>
          <span class="ui-muted">字段不完整题</span>
        </BaseCard>
      </div>

      <BaseCard>
        <TableShell :empty="!report.knowledgePoints.length" empty-title="暂无覆盖统计数据" class="cov-table">
          <template #head>
            <tr>
              <th rowspan="2">知识点</th>
              <th rowspan="2">题库状态</th>
              <th v-for="(_, key) in types" :key="key" colspan="2">{{ types[key] }}</th>
            </tr>
            <tr>
              <template v-for="key in Object.keys(types)" :key="key">
                <th>人工</th>
                <th>AI</th>
              </template>
            </tr>
          </template>
          <tr v-for="p in report.knowledgePoints" :key="p.knowledgePointId">
            <td>
              {{ p.name }}<br />
              <small class="ui-muted ui-code">{{ p.code }}</small>
            </td>
            <td>
              <StatusPill v-if="p.zeroQuestion" tone="neutral">无题</StatusPill>
              <StatusPill v-else-if="p.unusableOnlyQuestion" tone="warn">有题但不可用</StatusPill>
              <StatusPill v-else tone="success">有可用题</StatusPill>
            </td>
            <template v-for="count in p.counts" :key="count.type">
              <td class="ui-num">{{ count.manual }}</td>
              <td class="ui-num ai-col">{{ count.aiGenerated }}</td>
            </template>
          </tr>
        </TableShell>
      </BaseCard>

      <div class="ui-grid ui-grid--2 ui-mt-16">
        <BaseCard>
          <h2 class="ui-h3">完全无题</h2>
          <p v-if="!report.zeroQuestionKnowledgePoints.length" class="ui-success">每个知识点都至少关联过一道题。</p>
          <div class="ui-actions">
            <StatusPill v-for="p in report.zeroQuestionKnowledgePoints" :key="p.knowledgePointId" tone="neutral">
              {{ p.name }} · {{ p.code }}
            </StatusPill>
          </div>
        </BaseCard>

        <BaseCard>
          <h2 class="ui-h3">有题但不可用</h2>
          <p v-if="!report.unusableOnlyQuestionKnowledgePoints.length" class="ui-success">没有仅含坏题或停用题的知识点。</p>
          <div class="ui-actions">
            <StatusPill v-for="p in report.unusableOnlyQuestionKnowledgePoints" :key="p.knowledgePointId" tone="warn">
              {{ p.name }} · {{ p.code }}
            </StatusPill>
          </div>
        </BaseCard>
      </div>

      <BaseCard class="ui-mt-16">
        <h2 class="ui-h3">字段不完整题（最多 3 条）</h2>
        <p v-if="!report.incompleteQuestions.length" class="ui-success">未发现字段不完整题。</p>
        <p v-for="q in report.incompleteQuestions" :key="q.questionId">
          <span class="ui-code">{{ q.questionNo }}</span> · {{ q.message }}
        </p>
      </BaseCard>
    </template>

    <p v-if="error" class="ui-error ui-mt-12" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.summary__card {
  display: grid;
  gap: var(--sp-4);
}

.summary__card strong {
  font-family: var(--font-mono);
  font-size: var(--fs-28);
  line-height: var(--lh-tight);
}

.warning-card {
  border-left: 3px solid var(--warn);
}

.cov-table :deep(.ui-table) {
  min-width: 820px;
}

.cov-table :deep(th),
.cov-table :deep(td) {
  text-align: center;
}

.cov-table :deep(td:first-child),
.cov-table :deep(th:first-child) {
  text-align: left;
}

.ai-col {
  background: var(--brand-50);
}
</style>
