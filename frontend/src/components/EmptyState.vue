<script setup>
defineProps({
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' }
})
</script>

<template>
  <div class="ui-empty">
    <p class="ui-empty__title">{{ title }}</p>
    <p v-if="description" class="ui-empty__desc">{{ description }}</p>
    <div v-if="$slots.action" class="ui-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.ui-empty {
  padding: var(--sp-24) var(--sp-16);
  text-align: center;
  color: var(--ink-500);
}

.ui-empty__title {
  margin: 0;
  font-size: var(--fs-14);
  font-weight: 600;
  color: var(--ink-700);
}

.ui-empty__desc {
  margin: var(--sp-8) auto 0;
  max-width: 42ch;
  font-size: var(--fs-13);
  color: var(--ink-500);
}

.ui-empty__action {
  margin-top: var(--sp-16);
}
</style>
