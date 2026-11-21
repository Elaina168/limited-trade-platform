<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { activity, products, myCoupons } from '../api/catalog.ts'
import { addresses, setCartQuantity } from '../api/checkout.ts'
import { errorMessage, RequestError, SessionChangedError } from '../api/http.ts'
import type { Activity, Product, UserCoupon } from '../types/catalog.ts'
import type { Address } from '../types/checkout.ts'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const checkout = useCheckoutStore()
const product = ref<Product>()
const event = ref<Activity | null>(null)
const addressList = ref<Address[]>([])
const owned = ref<UserCoupon[]>([])
const quantity = ref(1)
const addressId = ref('')
const userCouponId = ref('')
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
let sequence = 0
let operation = 0
const blocked = computed(() => busy.value || saving.value || auth.busy || checkout.busy || checkout.pending !== null)
const original = computed(() => Math.round(Number(product.value?.price ?? 0) * 100) * quantity.value / 100)
const usable = computed(() => owned.value.filter(item => item.status === 'AVAILABLE' && !item.expired
  && item.coupon.activityId === event.value?.id
  && now.value >= new Date(item.coupon.useStartsAt).getTime() && now.value < new Date(item.coupon.useEndsAt).getTime()
  && Math.round(original.value * 100) >= Math.round(Number(item.coupon.thresholdAmount) * 100)))
const discount = computed(() => Number(usable.value.find(item => String(item.id) === userCouponId.value)?.coupon.discountAmount ?? 0))
const activityOpen = computed(() => !event.value || (now.value >= new Date(event.value.startsAt).getTime()
  && now.value < new Date(event.value.endsAt).getTime() && event.value.remainingQuantity >= quantity.value))
watch(usable, list => {
  if (userCouponId.value && !list.some(item => String(item.id) === userCouponId.value)) {
    userCouponId.value = ''; notice.value = '所选优惠券不再符合当前条件，已取消选择，请确认金额。'
  }
})
function reset() {
  sequence++; operation++; product.value = undefined; event.value = null; owned.value = []; addressList.value = []
  quantity.value = 1; addressId.value = ''; userCouponId.value = ''; saving.value = false; notice.value = ''
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
    const [list, data, eventData, mine] = await Promise.all([
      products(), addresses(), activityId === null ? Promise.resolve(null) : activity(activityId),
      activityId === null ? Promise.resolve([]) : myCoupons(),
    ])
    if (current !== sequence) return
    const found = list.find(item => item.id === id)
    if (!found) throw new RequestError('商品不存在或当前已下架，请返回商品列表。')
    if (eventData && eventData.productId !== id) throw new RequestError('活动与商品不匹配，请从活动详情重新进入。')
    product.value = found; event.value = eventData; owned.value = mine; addressList.value = data
    if (!data.some(item => String(item.id) === addressId.value)) addressId.value = String(data.find(item => item.defaultAddress)?.id ?? data[0]?.id ?? '')
  } catch (cause) { if (current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
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
  const current = ++operation
  saving.value = true; error.value = ''; notice.value = ''
  try {
    await setCartQuantity(product.value.id, quantity.value)
    if (current === operation) notice.value = `购物车中该商品数量已设为 ${quantity.value}。`
  } catch (cause) { if (current === operation && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
  finally { if (current === operation) saving.value = false }
}
async function submit() {
  if (blocked.value || !product.value || !validQuantity()) return
  if (!addressId.value || !activityOpen.value || quantity.value > product.value.stock) {
    error.value = '请确认收货地址、库存和活动剩余名额。'; return
  }
  error.value = ''
  try {
    const order = await checkout.submit('/api/orders', {
      productId: product.value.id, quantity: quantity.value, addressId: Number(addressId.value),
      activityId: event.value?.id ?? null, userCouponId: userCouponId.value ? Number(userCouponId.value) : null,
    })
    await router.push(`/orders/${order.id}`)
  } catch (cause) { if (!(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
}
watch([() => route.params.productId, () => route.query.activityId, () => auth.user?.id], reset, { flush: 'sync' })
watch([() => route.params.productId, () => route.query.activityId, () => auth.user?.id, () => auth.status], () => {
  if (auth.status === 'authenticated') void load()
}, { immediate: true })
onUnmounted(() => { sequence++; operation++; window.clearInterval(timer) })
</script>

<template>
  <RouterLink class="text-link" :to="event ? `/activities/${event.id}` : '/products'">← 返回浏览</RouterLink>
  <h1 class="page-title">{{ event ? '参加活动下单' : '选购商品' }}</h1>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <el-button :disabled="saving || auth.busy || checkout.busy" @click="load">刷新购买信息</el-button>
  <p v-if="busy" role="status">正在加载购买信息…</p>
  <section v-else-if="product" class="market-card purchase-form">
    <h2>{{ product.name }}</h2><p class="price">¥{{ Number(product.price).toFixed(2) }}</p>
    <p class="muted">库存 {{ product.stock }}<template v-if="event"> · {{ event.name }}剩余 {{ event.remainingQuantity }} 份</template></p>
    <form @submit.prevent="submit" novalidate>
      <fieldset class="purchase-fields" :disabled="blocked">
        <div class="field"><label for="purchase-quantity">购买数量</label><input id="purchase-quantity" v-model.number="quantity" type="number" min="1" max="2147483647" step="1" /></div>
        <div class="field"><label for="purchase-address">收货地址</label><select id="purchase-address" v-model="addressId"><option value="">请选择地址</option><option v-for="item in addressList" :key="item.id" :value="String(item.id)">{{ item.defaultAddress ? '默认 · ' : '' }}{{ item.recipientName }} {{ item.province }} {{ item.city }} {{ item.district }} {{ item.detailAddress }}</option></select></div>
        <div v-if="event" class="field"><label for="purchase-coupon">活动优惠券</label><select id="purchase-coupon" v-model="userCouponId"><option value="">不使用优惠券</option><option v-for="item in usable" :key="item.id" :value="String(item.id)">{{ item.coupon.name }} · 满{{ item.coupon.thresholdAmount }}减{{ item.coupon.discountAmount }}</option></select><p class="muted">仅显示本人已领、当前可用且满足本次金额门槛的活动券。</p></div>
      </fieldset>
      <RouterLink class="text-link" to="/addresses">{{ addressList.length ? '管理收货地址' : '请先添加收货地址' }}</RouterLink>
      <p>预估原价 ¥{{ original.toFixed(2) }} · 优惠 ¥{{ discount.toFixed(2) }}</p><p class="price">预估应付 ¥{{ (original - discount).toFixed(2) }}</p>
      <p class="muted">金额和优惠资格以服务端提交结果为准。保存到购物车使用当前数量覆盖原数量，不预留库存；购物车结算不参与本活动。</p>
      <p v-if="!activityOpen" class="form-error">活动尚未开始、已经结束或剩余名额不足。</p>
      <div class="business-actions"><el-button type="primary" native-type="submit" :loading="checkout.busy" :disabled="blocked || !addressId || !activityOpen || quantity > product.stock">提交订单</el-button><el-button :disabled="blocked" :loading="saving" @click="saveCart">保存到购物车</el-button><RouterLink class="text-link" to="/cart">查看购物车</RouterLink></div>
    </form>
  </section>
</template>
