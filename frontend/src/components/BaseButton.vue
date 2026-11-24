<script setup>
defineProps({
  variant: { type: String, default: 'primary' },
  size: { type: String, default: 'md' },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  type: { type: String, default: 'button' },
  to: { type: [String, Object], default: null }
})
</script>

<template>
  <RouterLink
    v-if="to"
    :to="to"
    :class="['ui-btn', `ui-btn--${variant}`, `ui-btn--${size}`]"
    :aria-disabled="disabled || loading ? 'true' : undefined"
  >
    <slot />
  </RouterLink>
  <button
    v-else
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading ? 'true' : undefined"
    :class="['ui-btn', `ui-btn--${variant}`, `ui-btn--${size}`]"
  >
    <span v-if="loading" class="ui-btn__spinner" aria-hidden="true"></span>
    <slot />
  </button>
</template>

<style scoped>
.ui-btn__spinner {
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: var(--r-pill);
  animation: ui-spin 600ms linear infinite;
}

@keyframes ui-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
