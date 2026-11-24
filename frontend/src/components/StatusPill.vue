<script setup>
defineProps({
  tone: { type: String, default: 'neutral' }
})
</script>

<template>
  <span :class="['ui-pill', `ui-pill--${tone}`]">
    <slot />
  </span>
</template>
