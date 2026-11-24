<script setup>
import { nextTick, onBeforeUnmount, useId, watch, ref } from 'vue'
import BaseButton from './BaseButton.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  cancelText: { type: String, default: '取消' },
  danger: { type: Boolean, default: false },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'cancel', 'update:open'])
const titleId = `dialog-title-${useId()}`
const cancelBtn = ref(null)
const dialogRef = ref(null)
let previousFocus = null

function onCancel() {
  if (props.busy) return
  emit('cancel')
  emit('update:open', false)
}

function onKeydown(e) {
  if (!props.open) return
  if (e.key === 'Escape') {
    onCancel()
    return
  }
  if (e.key === 'Tab' && dialogRef.value) {
    const focusables = [...dialogRef.value.querySelectorAll('button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])')]
    if (!focusables.length) return
    const first = focusables[0]
    const last = focusables[focusables.length - 1]
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault()
      first.focus()
    }
  }
}

watch(
  () => props.open,
  async (val) => {
    if (val) {
      previousFocus = document.activeElement
      window.addEventListener('keydown', onKeydown)
      await nextTick()
      cancelBtn.value?.$el?.focus?.()
    } else {
      window.removeEventListener('keydown', onKeydown)
      previousFocus?.focus?.()
      previousFocus = null
    }
  }
)

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="ui-dialog-backdrop" @click.self="onCancel">
      <div ref="dialogRef" class="ui-dialog" role="dialog" aria-modal="true" :aria-labelledby="titleId">
        <h2 :id="titleId" class="ui-dialog__title">{{ title }}</h2>
        <p v-if="message" class="ui-dialog__message">{{ message }}</p>
        <slot />
        <div class="ui-dialog__actions">
          <BaseButton ref="cancelBtn" variant="secondary" :disabled="busy" @click="onCancel">
            {{ cancelText }}
          </BaseButton>
          <BaseButton :variant="danger ? 'danger' : 'primary'" :loading="busy" @click="emit('confirm')">
            {{ confirmText }}
          </BaseButton>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ui-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  background: var(--overlay);
  display: grid;
  place-items: center;
  padding: var(--sp-16);
}

.ui-dialog {
  width: min(440px, 100%);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  padding: var(--sp-24);
  box-shadow: var(--sh-3);
}

.ui-dialog__title {
  margin: 0 0 var(--sp-8);
  font-size: var(--fs-18);
  line-height: var(--lh-tight);
  color: var(--ink-900);
}

.ui-dialog__message {
  margin: 0 0 var(--sp-20);
  color: var(--ink-700);
  font-size: var(--fs-14);
  line-height: var(--lh-base);
}

.ui-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-12);
  margin-top: var(--sp-20);
}
</style>
