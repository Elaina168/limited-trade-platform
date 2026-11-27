import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { cartItems } from '../api/checkout.ts'
import { errorMessage, onSessionChange, SessionChangedError, sessionRevision } from '../api/http.ts'
import type { CartItem } from '../types/checkout.ts'
import { useAuthStore } from './auth.ts'

export const useCartStore = defineStore('cart', () => {
  const auth = useAuthStore()
  const items = ref<CartItem[]>([])
  const error = ref('')
  const totalCount = computed(() => items.value.reduce((sum, item) => sum + item.quantity, 0))
  let sequence = 0
  let loaded = false

  function replace(next: CartItem[]) {
    sequence++
    items.value = next
    error.value = ''
  }

  async function refresh() {
    if (!auth.user) return
    const current = ++sequence
    const expected = sessionRevision()
    try {
      const next = await cartItems()
      if (auth.user && expected === sessionRevision() && current === sequence) {
        replace(next)
        loaded = true
      }
      if (expected === sessionRevision()) return next
    } catch (cause) {
      if (current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
    }
  }

  function updateItem(item: CartItem) {
    replace([...items.value.filter(row => row.productId !== item.productId), item]
      .sort((left, right) => left.productId - right.productId))
  }

  function reset() {
    loaded = false
    replace([])
  }
  const unsubscribe = onSessionChange(reset)
  watch(() => auth.user?.id, reset, { flush: 'sync' })
  watch(() => auth.status, status => {
    if (status === 'authenticated' && !loaded) void refresh()
  }, { immediate: true })
  onScopeDispose(unsubscribe)
  return { items, totalCount, error, refresh, replace, updateItem }
})
