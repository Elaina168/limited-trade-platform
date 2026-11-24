<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { readOrder } from '../api/checkout.ts'
import { changeOrder } from '../api/orders.ts'
import { errorMessage, HttpError, SessionChangedError } from '../api/http.ts'
import { availableOrderActions, orderActionLabels, orderStatusLabels } from '../utils/orders.ts'
import { canReconcileWrite, reconcileWrite } from '../utils/write-reconciliation.ts'
import type { Order, OrderAction } from '../types/checkout.ts'

const auth = useAuthStore()
const checkout = useCheckoutStore()
const route = useRoute()
const order = ref<Order | null>(null)
const busy = ref(false)
const acting = ref<OrderAction | null>(null)
const refreshRequired = ref(false)
const error = ref('')
const notice = ref('')
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
let sequence = 0
let operation = 0
let nextExpiryRead = 0
const actions = computed(() => order.value ? availableOrderActions(order.value, now.value) : [])
const blocked = computed(() => busy.value || acting.value !== null || auth.busy || refreshRequired.value)
const secondsLeft = computed(() => order.value ? Math.max(0, Math.ceil((new Date(order.value.expiresAt).getTime() - now.value) / 1000)) : 0)
const countdown = computed(() => `${Math.floor(secondsLeft.value / 60)}分${secondsLeft.value % 60}秒`)
function reset() {
  sequence++; operation++; nextExpiryRead = 0; order.value = null; acting.value = null; refreshRequired.value = false; error.value = ''; notice.value = ''
}
function applyOrder(data: Order) {
  order.value = data
  checkout.confirmKnownOrder(data)
  refreshRequired.value = false
}
async function load() {
  const current = ++sequence
  busy.value = true; error.value = ''
  const id = Number(route.params.id)
  if (!Number.isSafeInteger(id) || id <= 0) { error.value = '订单编号无效。'; busy.value = false; return }
  try {
    const data = await readOrder(id)
    if (current !== sequence) return
    applyOrder(data)
  } catch (cause) {
    if (current === sequence && !(cause instanceof SessionChangedError)) {
      if (cause instanceof HttpError && cause.status === 404) order.value = null
      error.value = errorMessage(cause); refreshRequired.value = true
    }
  } finally { if (current === sequence) busy.value = false }
}
async function act(action: OrderAction) {
  if (blocked.value || !order.value || !actions.value.includes(action)) return
  if (action === 'cancel' && !window.confirm('确认取消这笔订单？取消后将归还库存并释放占用的优惠券。')) return
  if (action === 'receive' && !window.confirm('请确认已收到商品，再确认收货。')) return
  const id = order.value.id
  const success = action === 'pay' ? '模拟支付成功。' : action === 'cancel' ? '订单已取消，库存与优惠券占用已释放。' : '已确认收货。'
  const current = ++operation
  acting.value = action; error.value = ''; notice.value = ''
  try {
    const data = await changeOrder(id, action)
    if (current !== operation) return
    // 同时结束已作废的焦点读取状态，避免迟到GET覆盖操作结果或让页面一直忙碌。
    sequence++; busy.value = false
    applyOrder(data)
    error.value = ''
    notice.value = success
  } catch (cause) {
    if (current !== operation || cause instanceof SessionChangedError) return
    sequence++; busy.value = false
    if (!canReconcileWrite(cause)) { error.value = errorMessage(cause); return }
    notice.value = '正在核对订单状态…'
    const result = await reconcileWrite(() => readOrder(id), data =>
      action === 'pay' ? ['PAID', 'SHIPPED', 'COMPLETED'].includes(data.status)
        : data.status === (action === 'cancel' ? 'CANCELED' : 'COMPLETED'),
    () => current === operation)
    if (!result || current !== operation) {
      if (current === operation) notice.value = ''
      return
    }
    if (result.snapshot) {
      applyOrder(result.snapshot)
      notice.value = result.confirmed ? success : `已更新订单状态为「${orderStatusLabels[result.snapshot.status]}」，未确认本次操作完成。`
    } else {
      refreshRequired.value = true
      notice.value = '暂时无法核对订单状态。'
    }
  } finally { if (current === operation) acting.value = null }
}
watch([() => route.params.id, () => auth.user?.id], reset, { flush: 'sync' })
watch([() => route.params.id, () => auth.user?.id, () => auth.status], () => {
  if (auth.status === 'authenticated' && !acting.value) void load()
}, { immediate: true })
watch(now, time => {
  if (document.hidden || auth.status !== 'authenticated' || busy.value || acting.value || order.value?.status !== 'PENDING_PAYMENT'
      || time < nextExpiryRead) return
  const expiry = new Date(order.value.expiresAt).getTime()
  if (time < expiry) return
  // 支付到期后由后端异步关单，页面定期读取直至订单离开待支付状态。
  nextExpiryRead = time + (time - expiry < 60_000 ? 5_000 : 30_000)
  void load()
})
onUnmounted(() => { sequence++; operation++; window.clearInterval(timer) })
</script>

<template>
  <RouterLink class="text-link" to="/orders">← 返回我的订单</RouterLink>
  <h1 class="page-title">订单详情</h1>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <div class="business-actions"><el-button :loading="busy" :disabled="acting !== null || auth.busy" @click="load">刷新订单</el-button></div>
  <p v-if="busy && !order" role="status">正在读取本人订单…</p>
  <template v-if="order">
    <section class="market-card order-result">
      <div class="section-heading"><h2>订单 #{{ order.id }}</h2><span class="role-badge">{{ orderStatusLabels[order.status] }}</span></div>
      <RouterLink class="text-link" :to="{ name: 'agent', query: { orderId: order.id } }">问这笔订单</RouterLink>
      <p class="price">应付 ¥{{ Number(order.totalAmount).toFixed(2) }}</p>
      <p>原价 ¥{{ Number(order.originalAmount).toFixed(2) }} · 优惠 ¥{{ Number(order.discountAmount).toFixed(2) }}</p>
      <p v-if="order.status === 'PENDING_PAYMENT'" class="operation-notice">{{ secondsLeft > 0 ? `支付剩余 ${countdown}` : '已过支付截止时间，正在自动核对订单状态。' }}</p>
      <div class="business-actions"><el-button v-for="action in actions" :key="action" :type="action === 'pay' ? 'primary' : 'default'" :disabled="blocked" :loading="acting === action" @click="act(action)">{{ orderActionLabels[action] }}</el-button></div>
      <p v-if="order.status === 'PENDING_PAYMENT'" class="muted">模拟支付用于演示订单状态流转，不会产生真实扣款。</p>
    </section>
    <section class="market-card order-result"><h2>成交商品</h2><ul class="order-items"><li v-for="item in order.items" :key="item.productId"><strong>{{ item.productName }}</strong><br />单价 ¥{{ Number(item.unitPrice).toFixed(2) }} × {{ item.quantity }} · 小计 ¥{{ Number(item.lineAmount).toFixed(2) }}<span v-if="item.activityId"> · 活动 #{{ item.activityId }}</span></li></ul></section>
    <section class="market-card order-result"><h2>收货信息</h2><p v-if="order.address">{{ order.address.recipientName }} {{ order.address.phone }}<br />{{ order.address.province }} {{ order.address.city }} {{ order.address.district }} {{ order.address.detailAddress }}</p><p v-else class="muted">本订单没有收货地址记录。</p></section>
    <section v-if="order.coupon" class="market-card order-result"><h2>成交优惠</h2><p>{{ order.coupon.name }} · 满{{ order.coupon.thresholdAmount }}减{{ order.coupon.discountAmount }}</p><p class="muted">使用期限 {{ order.coupon.useStartsAt.replace('T', ' ') }} 至 {{ order.coupon.useEndsAt.replace('T', ' ') }}</p></section>
    <section v-if="order.shipment" class="market-card order-result"><h2>物流信息</h2><p>承运方：{{ order.shipment.carrier }}</p><p>运单号：{{ order.shipment.trackingNo }}</p><p class="muted">发货时间 {{ order.shipment.shippedAt.replace('T', ' ') }}<template v-if="order.shipment.receivedAt"><br />收货时间 {{ order.shipment.receivedAt.replace('T', ' ') }}</template></p></section>
    <section class="market-card order-result"><h2>订单时间</h2><p class="muted">创建 {{ order.createdAt.replace('T', ' ') }}<br />支付截止 {{ order.expiresAt.replace('T', ' ') }}<template v-if="order.paidAt"><br />支付 {{ order.paidAt.replace('T', ' ') }}</template><template v-if="order.endedAt"><br />结束 {{ order.endedAt.replace('T', ' ') }}</template></p></section>
  </template>
</template>
