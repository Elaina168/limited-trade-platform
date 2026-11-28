<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'

const checkout = useCheckoutStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
watch(() => auth.status, status => {
  if (status === 'authenticated') void checkout.recoverPending()
}, { immediate: true })
let retriedIntent: string | null = null
watch([() => checkout.pending?.requestId, () => checkout.busy, () => checkout.recoveryError], ([id, busy, failure], _, onCleanup) => {
  const intent = checkout.pending
  if (intent && 'queued' in intent && intent.queued) {
    if (!busy && !failure && auth.status === 'authenticated') void checkout.recoverPending()
    return
  }
  if (!id || busy || !failure || id === retriedIntent) return
  const timer = window.setTimeout(() => {
    if (auth.status !== 'authenticated') return
    retriedIntent = id
    void checkout.recoverPending()
  }, 2000)
  onCleanup(() => window.clearTimeout(timer))
})
watch([() => checkout.seckillPurchaseRoute, () => checkout.recoveredOrder?.id, () => route.fullPath, () => auth.status],
  ([purchaseRoute, , currentPath, status]) => {
    if (!purchaseRoute || status !== 'authenticated') return
    if (purchaseRoute !== currentPath) {
      checkout.leaveSeckillPurchase(purchaseRoute)
      return
    }
    // 只消费一次导航资格。离开购买上下文后仍保留横幅中的订单结果。
    const id = checkout.takeSeckillPayment(currentPath)
    if (id !== undefined) void router.push({ name: 'order-detail', params: { id }, query: { justCreated: '1' } })
  }, { immediate: true, flush: 'post' })
</script>

<template>
  <section v-if="checkout.pending || checkout.recoveredOrder || checkout.recoveryError"
    class="pending-purchase" aria-label="下单结果" role="status">
    <p v-if="checkout.recoveredOrder">
      已恢复订单 #{{ checkout.recoveredOrder.id }}。
      <RouterLink :to="{ name: 'order-detail', params: { id: checkout.recoveredOrder.id } }">查看订单</RouterLink>
    </p>
    <p v-else>{{ checkout.recoveryError || checkout.queuingMessage || '正在处理下单结果…' }}</p>
  </section>
</template>
