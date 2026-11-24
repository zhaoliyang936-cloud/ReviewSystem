<script setup>
import { dismissToast, toastState } from './useToast'
</script>

<template>
  <div class="ui-toast-host" aria-live="polite" aria-atomic="true">
    <div
      v-for="item in toastState.items"
      :key="item.id"
      :class="['ui-toast', `ui-toast--${item.tone}`]"
      role="status"
    >
      <span>{{ item.message }}</span>
      <button type="button" class="ui-toast__close" aria-label="关闭提示" @click="dismissToast(item.id)">×</button>
    </div>
  </div>
</template>

<style scoped>
.ui-toast-host {
  position: fixed;
  right: var(--sp-20);
  bottom: var(--sp-20);
  z-index: 60;
  display: grid;
  gap: var(--sp-8);
  max-width: min(360px, calc(100vw - 32px));
}

.ui-toast {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-12);
  padding: var(--sp-12) var(--sp-16);
  border-radius: var(--r-md);
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--sh-2);
  font-size: var(--fs-13);
  color: var(--ink-900);
}

.ui-toast--success {
  border-left: 4px solid var(--success);
}

.ui-toast--danger {
  border-left: 4px solid var(--danger);
}

.ui-toast--warn {
  border-left: 4px solid var(--warn);
}

.ui-toast--info {
  border-left: 4px solid var(--brand-600);
}

.ui-toast__close {
  border: 0;
  background: transparent;
  color: var(--ink-500);
  cursor: pointer;
  font-size: var(--fs-16);
  line-height: 1;
  padding: 0 var(--sp-4);
}
</style>
