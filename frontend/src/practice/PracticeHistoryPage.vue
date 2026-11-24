<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'
import BaseCard from '../components/BaseCard.vue'
import StatusPill from '../components/StatusPill.vue'
import TableShell from '../components/TableShell.vue'

const data = ref({ items: [], page: 0, size: 20, total: 0 })
const error = ref('')

async function load(page = 0) {
  try {
    data.value = await api(`/api/practices/history?page=${page}&size=20`)
  } catch (e) {
    error.value = e.message
  }
}

onMounted(() => load())
</script>

<template>
  <div class="ui-shell">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">PRACTICE HISTORY</span>
        <h1 class="ui-title">练习历史</h1>
      </div>
      <RouterLink to="/">返回首页</RouterLink>
    </header>

    <BaseCard>
      <p v-if="error" class="ui-error" role="alert">{{ error }}</p>

      <TableShell :empty="!data.items.length" empty-title="还没有已提交练习。">
        <template #head>
          <tr>
            <th>提交时间</th>
            <th>题量</th>
            <th>状态</th>
            <th></th>
          </tr>
        </template>
        <tr v-for="p in data.items" :key="p.id">
          <td class="ui-num">{{ new Date(p.submittedAt).toLocaleString() }}</td>
          <td class="ui-num">{{ p.questionCount }}</td>
          <td><StatusPill tone="brand">{{ p.status }}</StatusPill></td>
          <td><RouterLink :to="`/practice/${p.id}/result`">查看结果</RouterLink></td>
        </tr>
      </TableShell>

      <div class="ui-pager">
        <BaseButton variant="secondary" size="sm" :disabled="data.page === 0" @click="load(data.page - 1)">上一页</BaseButton>
        <span>第 {{ data.page + 1 }} / {{ Math.max(1, Math.ceil(data.total / data.size)) }} 页</span>
        <BaseButton variant="secondary" size="sm" :disabled="(data.page + 1) * data.size >= data.total" @click="load(data.page + 1)">下一页</BaseButton>
      </div>
    </BaseCard>
  </div>
</template>
