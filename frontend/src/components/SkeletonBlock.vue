<script setup>
defineProps({
  lines: { type: Number, default: 3 },
  height: { type: String, default: '16px' }
})
</script>

<template>
  <div class="ui-skeleton" aria-busy="true" aria-label="加载中">
    <span
      v-for="n in lines"
      :key="n"
      class="ui-skeleton__line"
      :style="{ height, width: n === lines && lines > 1 ? '68%' : '100%' }"
    ></span>
  </div>
</template>

<style scoped>
.ui-skeleton {
  display: grid;
  gap: var(--sp-8);
}

.ui-skeleton__line {
  display: block;
  border-radius: var(--r-sm);
  background: linear-gradient(90deg, var(--bg) 25%, var(--line) 50%, var(--bg) 75%);
  background-size: 200% 100%;
  animation: ui-shimmer 1.4s var(--ease) infinite;
}

@keyframes ui-shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
</style>
