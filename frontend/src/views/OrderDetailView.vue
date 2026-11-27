<script setup lang="ts">
import { computed, h, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox, ElPopconfirm } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/popconfirm/style/css'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { readOrder } from '../api/checkout.ts'
import { submitOrderAction } from '../api/orders.ts'
import { errorMessage, HttpError, onSessionChange, SessionChangedError, sessionRevision } from '../api/http.ts'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import { availableOrderActions, orderActionLabels, orderPaymentCountdown, orderStatusLabels } from '../utils/orders.ts'
import type { Order, OrderAction } from '../types/checkout.ts'

const auth = useAuthStore()
const checkout = useCheckoutStore()
const route = useRoute()
const order = ref<Order | null>(null)
const busy = ref(false)
const acting = ref<OrderAction | null>(null)
const cancelOpen = ref(false)
let closeCancelBox: (() => void) | undefined
const refreshRequired = ref(false)
const error = ref('')
const notice = ref('')
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
let sequence = 0
let operation = 0
let nextExpiryRead = 0
const actions = computed(() => order.value ? availableOrderActions(order.value, now.value) : [])
const blocked = computed(() => acting.value !== null || auth.status !== 'authenticated' || refreshRequired.value)
function dismissCancelBox() {
  cancelOpen.value = false
  closeCancelBox?.()
  closeCancelBox = undefined
}
function reset() {
  sequence++; operation++; nextExpiryRead = 0; order.value = null; acting.value = null
  dismissCancelBox()
  refreshRequired.value = false; busy.value = false; error.value = ''; notice.value = ''
}
function applyOrder(data: Order) {
  order.value = data
  checkout.confirmKnownOrder(data)
  refreshRequired.value = false
}
async function load() {
  if (busy.value || acting.value || auth.status !== 'authenticated') return
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
  if (blocked.value || !order.value || !availableOrderActions(order.value, Date.now()).includes(action)) return
  const id = order.value.id
  const current = ++operation
  const revision = sessionRevision()
  const isCurrent = () => current === operation && revision === sessionRevision()
  // 开始写入时废弃进行中的读取，避免旧快照覆盖操作结果。
  sequence++; busy.value = false
  acting.value = action; error.value = ''; notice.value = ''
  try {
    const result = await submitOrderAction(id, action, isCurrent, () => { notice.value = '正在核对订单状态…' })
    if (!result || !isCurrent()) return
    if (result.snapshot) applyOrder(result.snapshot)
    else if (result.notice) refreshRequired.value = true
    error.value = result.error ?? ''
    notice.value = result.notice ?? ''
  } finally { if (current === operation) acting.value = null }
}
async function confirmCancel() {
  if (blocked.value || cancelOpen.value || !order.value || !actions.value.includes('cancel')) return
  const id = order.value.id
  const current = operation
  const revision = sessionRevision()
  cancelOpen.value = true
  try {
    await ElMessageBox.confirm(({ close }) => {
      // 只关闭当前订单的确认框，避免影响页面上的其他弹窗。
      if (current === operation) closeCancelBox = close
      else queueMicrotask(close)
      return h('span', '取消后将归还库存并释放占用的优惠券。')
    }, '取消订单', {
      type: 'warning',
      confirmButtonText: '确认取消订单',
      cancelButtonText: '保留订单',
      confirmButtonType: 'danger',
      distinguishCancelAndClose: true,
      closeOnHashChange: false,
    })
  } catch (outcome) {
    if (outcome === 'cancel' || outcome === 'close') return
    throw outcome
  } finally {
    if (current === operation) {
      cancelOpen.value = false
      closeCancelBox = undefined
    }
  }
  if (current === operation && revision === sessionRevision() && order.value?.id === id) await act('cancel')
}
const stopSessionListener = onSessionChange(reset)
useVisibilityRefresh(() => { void load() }, () => busy.value || acting.value !== null || auth.status !== 'authenticated')
watch([() => route.params.id, () => auth.user?.id], reset, { flush: 'sync' })
watch([() => route.params.id, () => auth.user?.id, () => auth.status], () => {
  if (auth.status === 'authenticated' && !order.value) void load()
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
onUnmounted(() => { sequence++; operation++; dismissCancelBox(); window.clearInterval(timer); stopSessionListener() })
</script>

<template>
  <RouterLink class="text-link" to="/orders">← 返回我的订单</RouterLink>
  <h1 class="page-title">订单详情</h1>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <p v-if="busy && !order" role="status">正在读取本人订单…</p>
  <template v-if="order">
    <section :key="order.id" class="market-card order-result">
      <div class="section-heading"><h2>订单 #{{ order.id }}</h2><span class="role-badge">{{ orderStatusLabels[order.status] }}</span></div>
      <RouterLink class="text-link" :to="{ name: 'agent', query: { orderId: order.id } }">问这笔订单</RouterLink>
      <p class="price">应付 ¥{{ Number(order.totalAmount).toFixed(2) }}</p>
      <p>原价 ¥{{ Number(order.originalAmount).toFixed(2) }} · 优惠 ¥{{ Number(order.discountAmount).toFixed(2) }}</p>
      <p v-if="order.status === 'PENDING_PAYMENT'" class="operation-notice">{{ orderPaymentCountdown(order, now) }}</p>
      <div class="business-actions">
        <template v-for="action in actions" :key="action">
          <ElPopconfirm v-if="action === 'receive'" title="请确认已经收到商品。" confirm-button-text="确认收到"
            cancel-button-text="暂不确认" :disabled="blocked" @confirm="act('receive')">
            <template #reference>
              <el-button :disabled="blocked" :loading="acting === 'receive'">{{ orderActionLabels.receive }}</el-button>
            </template>
          </ElPopconfirm>
          <el-button v-else :type="action === 'pay' ? 'primary' : 'default'" :disabled="blocked || cancelOpen"
            :loading="acting === action" @click="action === 'cancel' ? confirmCancel() : act(action)">{{ action === 'pay' && route.query.justCreated === '1' ? '立即模拟支付' : orderActionLabels[action] }}</el-button>
        </template>
      </div>
      <p v-if="order.status === 'PENDING_PAYMENT'" class="muted">模拟支付用于演示订单状态流转，不会产生真实扣款。</p>
    </section>
    <section class="market-card order-result"><h2>成交商品</h2><ul class="order-items"><li v-for="item in order.items" :key="item.productId"><strong>{{ item.productName }}</strong><br />单价 ¥{{ Number(item.unitPrice).toFixed(2) }} × {{ item.quantity }} · 小计 ¥{{ Number(item.lineAmount).toFixed(2) }}<span v-if="item.activityId"> · 活动 #{{ item.activityId }}</span></li></ul></section>
    <section class="market-card order-result"><h2>收货信息</h2><p v-if="order.address">{{ order.address.recipientName }} {{ order.address.phone }}<br />{{ order.address.province }} {{ order.address.city }} {{ order.address.district }} {{ order.address.detailAddress }}</p><p v-else class="muted">本订单没有收货地址记录。</p></section>
    <section v-if="order.coupon" class="market-card order-result"><h2>成交优惠</h2><p>{{ order.coupon.name }} · 满{{ order.coupon.thresholdAmount }}减{{ order.coupon.discountAmount }}</p><p class="muted">使用期限 {{ order.coupon.useStartsAt.replace('T', ' ') }} 至 {{ order.coupon.useEndsAt.replace('T', ' ') }}</p></section>
    <section v-if="order.shipment" class="market-card order-result"><h2>物流信息</h2><p>承运方：{{ order.shipment.carrier }}</p><p>运单号：{{ order.shipment.trackingNo }}</p><p class="muted">发货时间 {{ order.shipment.shippedAt.replace('T', ' ') }}<template v-if="order.shipment.receivedAt"><br />收货时间 {{ order.shipment.receivedAt.replace('T', ' ') }}</template></p></section>
    <section class="market-card order-result"><h2>订单时间</h2><p class="muted">创建 {{ order.createdAt.replace('T', ' ') }}<br />支付截止 {{ order.expiresAt.replace('T', ' ') }}<template v-if="order.paidAt"><br />支付 {{ order.paidAt.replace('T', ' ') }}</template><template v-if="order.endedAt"><br />结束 {{ order.endedAt.replace('T', ' ') }}</template></p></section>
  </template>
</template>
