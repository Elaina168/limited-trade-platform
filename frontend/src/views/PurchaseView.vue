<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { activity, productById, myCoupons } from '../api/catalog.ts'
import { addresses } from '../api/checkout.ts'
import { errorMessage, HttpError, RequestError, SessionChangedError } from '../api/http.ts'
import { useCartQuantitySave } from '../composables/useCartQuantitySave.ts'
import { activityStatus, beijingTimestamp } from '../utils/activity-time.ts'
import type { Activity, Product, UserCoupon } from '../types/catalog.ts'
import type { Address } from '../types/checkout.ts'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const checkout = useCheckoutStore()
const { saving, error: cartError, notice: cartNotice, saveQuantity, resetCartSave } = useCartQuantitySave()
const product = ref<Product>()
const event = ref<Activity | null>(null)
const addressList = ref<Address[]>([])
const owned = ref<UserCoupon[]>([])
const quantity = ref(initialQuantity())
const addressId = ref('')
const userCouponId = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
let sequence = 0
const blocked = computed(() => busy.value || saving.value || auth.busy || checkout.busy || checkout.pending !== null)
const original = computed(() => Math.round(Number(product.value?.price ?? 0) * 100) * quantity.value / 100)
const usable = computed(() => owned.value.filter(item => item.status === 'AVAILABLE' && !item.expired
  && item.coupon.activityId === event.value?.id
  && now.value >= beijingTimestamp(item.coupon.useStartsAt) && now.value < beijingTimestamp(item.coupon.useEndsAt)
  && Math.round(original.value * 100) >= Math.round(Number(item.coupon.thresholdAmount) * 100)))
const discount = computed(() => Number(usable.value.find(item => String(item.id) === userCouponId.value)?.coupon.discountAmount ?? 0))
const activityProblem = computed(() => {
  if (!event.value) return ''
  const status = activityStatus(event.value, now.value)
  if (status === '已结束') return '活动已结束。'
  if (status === '即将开始') return '活动尚未开始。'
  return event.value.remainingQuantity >= quantity.value ? '' : '本活动剩余名额不足。'
})
watch(usable, list => {
  if (userCouponId.value && !list.some(item => String(item.id) === userCouponId.value)) {
    userCouponId.value = ''; notice.value = '所选优惠券不再符合当前条件，已取消选择，请确认金额。'
  }
})
function initialQuantity() {
  const value = typeof route.query.quantity === 'string' ? Number(route.query.quantity) : 1
  return Number.isInteger(value) && value >= 1 && value <= 2147483647 ? value : 1
}
function reset() {
  sequence++; resetCartSave(); product.value = undefined; event.value = null; owned.value = []; addressList.value = []
  quantity.value = initialQuantity(); addressId.value = ''; userCouponId.value = ''; notice.value = ''
}
async function load() {
  const current = ++sequence
  busy.value = true; error.value = ''
  const id = Number(route.params.productId)
  const activityId = route.query.activityId === undefined ? null : Number(route.query.activityId)
  if (!Number.isSafeInteger(id) || id <= 0 || activityId !== null && (!Number.isSafeInteger(activityId) || activityId <= 0)) {
    error.value = '商品或活动编号无效。'; busy.value = false; return
  }
  try {
    const [found, data, eventData, mine] = await Promise.all([
      productById(id), addresses(), activityId === null ? Promise.resolve(null) : activity(activityId),
      activityId === null ? Promise.resolve([]) : myCoupons(),
    ])
    if (current !== sequence) return
    if (eventData && eventData.productId !== id) throw new RequestError('活动与商品不匹配，请从活动详情重新进入。')
    product.value = found; event.value = eventData; owned.value = mine; addressList.value = data
    if (!data.some(item => String(item.id) === addressId.value)) addressId.value = String(data.find(item => item.defaultAddress)?.id ?? data[0]?.id ?? '')
  } catch (cause) {
    if (current === sequence && !(cause instanceof SessionChangedError)) {
      error.value = cause instanceof HttpError && cause.status === 404 && cause.path === `/api/products/${id}`
        ? '商品不存在或当前已下架，请返回商品列表。'
        : errorMessage(cause)
    }
  }
  finally { if (current === sequence) busy.value = false }
}
function validQuantity() {
  if (!Number.isInteger(quantity.value) || quantity.value < 1 || quantity.value > 2147483647) {
    error.value = '数量须为1至2147483647的整数。'; return false
  }
  return true
}
async function saveCart() {
  if (blocked.value || !product.value || !validQuantity()) return
  error.value = ''; notice.value = ''
  const message = await saveQuantity(product.value.id, quantity.value)
  if (message) ElMessage.success(message)
}
async function submit() {
  if (blocked.value || !product.value || !validQuantity()) return
  if (!addressId.value || activityProblem.value || quantity.value > product.value.stock) return
  resetCartSave()
  error.value = ''
  try {
    const order = await checkout.submit('/api/orders', {
      productId: product.value.id, quantity: quantity.value, addressId: Number(addressId.value),
      activityId: event.value?.id ?? null, userCouponId: userCouponId.value ? Number(userCouponId.value) : null,
    })
    await router.push(`/orders/${order.id}`)
  } catch (cause) { if (!checkout.pending && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
}
watch([() => route.params.productId, () => route.query.activityId, () => route.query.quantity, () => auth.user?.id], reset, { flush: 'sync' })
watch([() => route.params.productId, () => route.query.activityId, () => route.query.quantity, () => auth.user?.id, () => auth.status], () => {
  if (auth.status === 'authenticated') void load()
}, { immediate: true })
onUnmounted(() => { sequence++; window.clearInterval(timer) })
</script>

<template>
  <RouterLink class="text-link" :to="event ? { name: 'activity-detail', params: { id: event.id } } : { name: 'product-detail', params: { productId: route.params.productId } }">← 返回详情</RouterLink>
  <h1 class="page-title">{{ event ? '参加活动下单' : '选购商品' }}</h1>
  <p v-if="error || cartError" class="form-error" role="alert">{{ error || cartError }}</p>
  <p v-if="notice || cartNotice" class="operation-notice" role="status">{{ notice || cartNotice }}</p>
  <p v-if="busy" role="status">正在加载购买信息…</p>
  <section v-else-if="product" class="market-card purchase-form">
    <h2>{{ product.name }}</h2><p class="price">¥{{ Number(product.price).toFixed(2) }}</p>
    <form @submit.prevent="submit" novalidate>
      <fieldset class="purchase-fields" :disabled="blocked">
        <div class="field"><label for="purchase-quantity">购买数量</label><input id="purchase-quantity" v-model.number="quantity" type="number" min="1" max="2147483647" step="1" /></div>
        <div v-if="addressList.length" class="field"><label for="purchase-address">收货地址</label><select id="purchase-address" v-model="addressId"><option v-for="item in addressList" :key="item.id" :value="String(item.id)">{{ item.defaultAddress ? '默认 · ' : '' }}{{ item.recipientName }} {{ item.province }} {{ item.city }} {{ item.district }} {{ item.detailAddress }}</option></select></div>
        <div v-if="event" class="field"><label for="purchase-coupon">活动优惠券</label><select id="purchase-coupon" v-model="userCouponId"><option value="">不使用优惠券</option><option v-for="item in usable" :key="item.id" :value="String(item.id)">{{ item.coupon.name }} · 满{{ item.coupon.thresholdAmount }}减{{ item.coupon.discountAmount }}</option></select><p class="muted">仅显示本人已领、当前可用且满足本次金额门槛的活动券。</p></div>
      </fieldset>
      <RouterLink v-if="!addressList.length" class="text-link" to="/addresses">请先添加收货地址</RouterLink>
      <p>预估原价 ¥{{ original.toFixed(2) }} · 优惠 ¥{{ discount.toFixed(2) }}</p><p class="price">预估应付 ¥{{ (original - discount).toFixed(2) }}</p>
      <p v-if="activityProblem" class="form-error">{{ activityProblem }}</p>
      <p v-else-if="quantity > product.stock" class="form-error">当前商品无法按所选数量购买。</p>
      <div class="business-actions"><el-button type="primary" native-type="submit" :loading="checkout.busy" :disabled="blocked || !addressId || !!activityProblem || quantity > product.stock">提交订单</el-button><el-button v-if="event" :disabled="blocked" :loading="saving" @click="saveCart">保存到购物车</el-button><RouterLink class="text-link" to="/cart">查看购物车</RouterLink></div>
    </form>
  </section>
</template>
