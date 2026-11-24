<script setup>
import { computed, useId } from 'vue'

const props = defineProps({
  label: { type: String, default: '' },
  hint: { type: String, default: '' },
  error: { type: String, default: '' },
  id: { type: String, default: '' }
})

const autoId = useId()
const fieldId = computed(() => props.id || `field-${autoId}`)
const hintId = computed(() => `${fieldId.value}-hint`)
const errorId = computed(() => `${fieldId.value}-error`)
const describedBy = computed(() => {
  const ids = []
  if (props.hint) ids.push(hintId.value)
  if (props.error) ids.push(errorId.value)
  return ids.length ? ids.join(' ') : undefined
})
</script>

<template>
  <div class="ui-field">
    <label v-if="label" class="ui-field__label" :for="fieldId">{{ label }}</label>
    <slot :id="fieldId" :described-by="describedBy" />
    <span v-if="hint" :id="hintId" class="ui-field__hint">{{ hint }}</span>
    <span v-if="error" :id="errorId" class="ui-field__error" role="alert">{{ error }}</span>
  </div>
</template>
