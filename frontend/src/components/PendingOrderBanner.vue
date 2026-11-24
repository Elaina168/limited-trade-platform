<script setup lang="ts">
import { watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'

const checkout = useCheckoutStore()
const auth = useAuthStore()
watch(() => auth.status, status => {
  if (status === 'authenticated') void checkout.recoverPending()
}, { immediate: true })
let retriedIntent: string | null = null
watch([() => checkout.pending?.requestId, () => checkout.busy, () => checkout.recoveryError], ([id, busy, failure], _, onCleanup) => {
  if (!id || busy || !failure || id === retriedIntent) return
  const timer = window.setTimeout(() => {
    if (auth.status !== 'authenticated') return
    retriedIntent = id
    void checkout.recoverPending()
  }, 2000)
  onCleanup(() => window.clearTimeout(timer))
})
</script>

<template>
  <section v-if="checkout.pending || checkout.recoveredOrder || checkout.recoveryError"
    class="pending-purchase" aria-label="下单结果" role="status">
    <p v-if="checkout.recoveredOrder">
      已恢复订单 #{{ checkout.recoveredOrder.id }}。
      <RouterLink :to="{ name: 'order-detail', params: { id: checkout.recoveredOrder.id } }">查看订单</RouterLink>
    </p>
    <p v-else>{{ checkout.recoveryError || '正在处理下单结果…' }}</p>
  </section>
</template>
