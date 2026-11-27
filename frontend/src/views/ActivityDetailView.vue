<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
import { activity, activityCoupons, productById, myCoupons, claimCoupon } from '../api/catalog.ts'
import { errorMessage, HttpError, onSessionChange, SessionChangedError } from '../api/http.ts'
import { canReconcileWrite, reconcileWrite } from '../utils/write-reconciliation.ts'
import { activityStatus, beijingTimestamp, formatActivityTime } from '../utils/activity-time.ts'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import QuickPurchaseDrawer from '../components/QuickPurchaseDrawer.vue'
import type { Activity, ActivityCoupon, Product, UserCoupon } from '../types/catalog.ts'

const route = useRoute()
const auth = useAuthStore()
const detail = ref<Activity | null>(null)
const purchaseOpen = ref(false)
const product = ref<Product>()
const coupons = ref<ActivityCoupon[]>([])
const owned = ref<UserCoupon[]>([])
const busy = ref(false)
const claiming = ref<number | null>(null)
const error = ref('')
const notice = ref('')
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
let sequence = 0
let refreshing = false
let boundaryPending = false
let nextRefreshAt = 0
const phase = computed(() => detail.value ? activityStatus(detail.value, now.value) : '')
function claimLabel(coupon: ActivityCoupon) {
  if (owned.value.some(item => item.coupon.id === coupon.id)) return '已领取'
  if (now.value < beijingTimestamp(coupon.claimStartsAt)) return '尚未开领'
  if (now.value >= beijingTimestamp(coupon.claimEndsAt)) return '领取已结束'
  return coupon.remainingQuantity > 0 ? '领取优惠券' : '已领完'
}
function reset() {
  sequence++
  refreshing = false
  boundaryPending = false
  detail.value = null
  product.value = undefined
  coupons.value = []
  owned.value = []
  error.value = ''
  notice.value = ''
  claiming.value = null
  purchaseOpen.value = false
}
function refreshBlocked() {
  return refreshing || auth.busy || claiming.value !== null
}
async function load(silent = false) {
  const current = ++sequence
  refreshing = true
  boundaryPending = false
  nextRefreshAt = Date.now() + 12_000
  if (!silent) { busy.value = true; error.value = '' }
  const id = Number(route.params.id)
  if (!Number.isSafeInteger(id) || id <= 0) { error.value = '活动编号无效。'; busy.value = false; refreshing = false; return }
  try {
    const event = await activity(id)
    if (current !== sequence) return
    const [templates, found, mine] = await Promise.all([
      activityCoupons(id),
      productById(event.productId).catch(cause => {
        if (cause instanceof HttpError && cause.status === 404) return undefined
        throw cause
      }),
      auth.user ? myCoupons() : Promise.resolve([]),
    ])
    if (current !== sequence) return
    detail.value = event
    coupons.value = templates
    product.value = found
    error.value = ''
    owned.value = mine
  } catch (cause) {
    if (!silent && current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally { if (current === sequence) { busy.value = false; refreshing = false } }
}
async function claim(coupon: ActivityCoupon) {
  if (claiming.value !== null || auth.busy || !auth.user) return
  const current = ++sequence
  refreshing = false
  const activityId = detail.value!.id
  claiming.value = coupon.id
  notice.value = ''
  try {
    const result = await claimCoupon(coupon.id)
    if (current !== sequence) return
    owned.value = [...owned.value.filter(item => item.coupon.id !== coupon.id), result]
    coupons.value = coupons.value.map(item => item.id === coupon.id ? result.coupon : item)
    notice.value = `已领取「${result.coupon.name}」。`
  } catch (cause) {
    if (current !== sequence || cause instanceof SessionChangedError) return
    if (!canReconcileWrite(cause)) { notice.value = errorMessage(cause); return }
    notice.value = '正在核对领券结果…'
    const result = await reconcileWrite(async () => {
      const [mine, templates] = await Promise.all([myCoupons(), activityCoupons(activityId)])
      return { mine, templates }
    }, data => data.mine.some(item => item.coupon.id === coupon.id), () => current === sequence)
    if (!result || current !== sequence) {
      if (current === sequence) notice.value = ''
      return
    }
    if (result.snapshot) {
      owned.value = result.snapshot.mine
      coupons.value = result.snapshot.templates
      notice.value = result.confirmed ? `已领取「${coupon.name}」。` : '已更新优惠券状态，暂未查到本人领取记录。'
    } else notice.value = '暂时无法核对领券结果。'
  } finally { if (current === sequence) claiming.value = null }
}
watch([() => route.params.id, () => auth.user?.id], reset, { flush: 'sync' })
watch([() => route.params.id, () => auth.user?.id, () => auth.status], () => {
  if (!detail.value && !refreshBlocked() && (auth.status === 'authenticated' || auth.status === 'anonymous')) void load()
}, { immediate: true })
useVisibilityRefresh(() => { void load(!!detail.value) }, refreshBlocked)
watch(now, (time, previous) => {
  const event = detail.value
  if (!event) return
  const start = beijingTimestamp(event.startsAt)
  const end = beijingTimestamp(event.endsAt)
  if ((previous < start && time >= start) || (previous < end && time >= end)) boundaryPending = true
  if (!document.hidden && !refreshBlocked() && (boundaryPending || time >= nextRefreshAt && time >= start && time < end)) {
    void load(true)
  }
})
const unsubscribe = onSessionChange(reset)
onUnmounted(() => { sequence++; unsubscribe(); window.clearInterval(timer) })
</script>

<template>
  <RouterLink class="text-link" to="/activities">← 返回活动列表</RouterLink>
  <p v-if="busy" role="status">正在加载活动详情…</p>
  <div v-else-if="error" role="alert"><p class="form-error">{{ error }}</p><el-button @click="load()">重试</el-button></div>
  <template v-else-if="detail">
    <h1 class="page-title">{{ detail.name }}</h1>
    <p><span class="role-badge">{{ phase }}</span></p>
    <section class="market-card event-summary">
      <h2>{{ product?.name ?? '商品暂不可展示' }}</h2>
      <p v-if="product" class="price">¥{{ Number(product.price).toFixed(2) }}</p>
      <p>活动剩余 {{ detail.remainingQuantity }} / {{ detail.totalQuantity }} 份</p>
      <p class="muted">北京时间 {{ formatActivityTime(detail.startsAt) }} 至 {{ formatActivityTime(detail.endsAt) }}</p>
      <template v-if="product && phase === '进行中'">
        <el-button v-if="auth.user" type="primary" @click="purchaseOpen = true">参加活动下单</el-button>
        <RouterLink v-else class="button-link" :to="{ name: 'login', query: { redirect: route.fullPath } }">登录后参加活动</RouterLink>
      </template>
      <QuickPurchaseDrawer v-if="product" v-model="purchaseOpen" :product-id="product.id" :activity-id="detail.id" />
    </section>
    <div class="section-heading"><h2>活动优惠券</h2></div>
    <p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
    <p v-if="!coupons.length" class="empty-state">本活动暂无优惠券。</p>
    <div class="market-grid">
      <article v-for="coupon in coupons" :key="coupon.id" class="market-card">
        <h2>{{ coupon.name }}</h2>
        <p class="price">满 {{ coupon.thresholdAmount }} 减 {{ coupon.discountAmount }}</p>
        <p class="muted">剩余 {{ coupon.remainingQuantity }} 张</p>
        <p class="muted">领取：{{ formatActivityTime(coupon.claimStartsAt) }} 至 {{ formatActivityTime(coupon.claimEndsAt) }}</p>
        <p class="muted">使用：{{ formatActivityTime(coupon.useStartsAt) }} 至 {{ formatActivityTime(coupon.useEndsAt) }}</p>
        <RouterLink v-if="!auth.user && claimLabel(coupon) === '领取优惠券'" class="button-link" :to="{ name: 'login', query: { redirect: route.fullPath } }">登录后领取</RouterLink>
        <el-button v-else-if="auth.user" type="primary" :disabled="claimLabel(coupon) !== '领取优惠券' || claiming !== null || auth.busy" :loading="claiming === coupon.id" @click="claim(coupon)">{{ claimLabel(coupon) }}</el-button>
        <p v-else class="muted">{{ claimLabel(coupon) }}</p>
      </article>
    </div>
  </template>
</template>
