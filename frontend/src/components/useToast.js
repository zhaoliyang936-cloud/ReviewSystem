import { reactive } from 'vue'

export const toastState = reactive({ items: [] })
let nextId = 1

export function pushToast(message, tone = 'info', duration = 3200) {
  if (!message) return
  const id = nextId++
  toastState.items.push({ id, message, tone })
  if (duration > 0) {
    setTimeout(() => dismissToast(id), duration)
  }
  return id
}

export function dismissToast(id) {
  const idx = toastState.items.findIndex(item => item.id === id)
  if (idx !== -1) toastState.items.splice(idx, 1)
}
