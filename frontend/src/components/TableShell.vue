<script setup>
import EmptyState from './EmptyState.vue'

defineProps({
  empty: { type: Boolean, default: false },
  emptyTitle: { type: String, default: '暂无记录' },
  emptyDescription: { type: String, default: '' }
})
</script>

<template>
  <div v-if="empty">
    <slot name="empty">
      <EmptyState :title="emptyTitle" :description="emptyDescription" />
    </slot>
  </div>
  <div v-else class="ui-table-wrap">
    <table class="ui-table">
      <thead v-if="$slots.head">
        <slot name="head" />
      </thead>
      <tbody>
        <slot />
      </tbody>
    </table>
  </div>
</template>
