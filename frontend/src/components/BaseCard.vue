<script setup>
defineProps({
  inset: { type: Boolean, default: false },
  tag: { type: String, default: 'div' }
})
</script>

<template>
  <component :is="tag" :class="['ui-card', inset ? 'ui-card--inset' : '']">
    <slot />
  </component>
</template>
