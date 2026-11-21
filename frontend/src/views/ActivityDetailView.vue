<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
import { activity, activityCoupons, products, myCoupons, claimCoupon } from '../api/catalog.ts'
import { errorMessage, HttpError, SessionChangedError } from '../api/http.ts'
import type { Activity, ActivityCoupon, Product, UserCoupon } from '../types/catalog.ts'

const route = useRoute()
const auth = useAuthStore()
const detail = ref<Activity | null>(null)
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
const phase = computed(() => {
  if (!detail.value) return ''
  if (now.value < new Date(detail.value.startsAt).getTime()) return '即将开始'
  if (now.value >= new Date(detail.value.endsAt).getTime()) return '已结束'
  return detail.value.remainingQuantity > 0 ? '进行中' : '名额已用完'
})
function claimLabel(coupon: ActivityCoupon) {
  if (owned.value.some(item => item.coupon.id === coupon.id)) return '已领取'
  if (now.value < new Date(coupon.claimStartsAt).getTime()) return '尚未开领'
  if (now.value >= new Date(coupon.claimEndsAt).getTime()) return '领取已结束'
  return coupon.remainingQuantity > 0 ? '领取优惠券' : '已领完'
}
async function load() {
  const current = ++sequence
  busy.value = true
  error.value = ''
  notice.value = ''
  owned.value = []
  detail.value = null
  claiming.value = null
  const id = Number(route.params.id)
  if (!Number.isSafeInteger(id) || id <= 0) { error.value = '活动编号无效。'; busy.value = false; return }
  try {
    const [event, templates, productList, mine] = await Promise.all([
      activity(id), activityCoupons(id), products(), auth.user ? myCoupons() : Promise.resolve([]),
    ])
    if (current !== sequence) return
    detail.value = event
    coupons.value = templates
    product.value = productList.find(item => item.id === event.productId)
    owned.value = mine
  } catch (cause) {
    if (current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally { if (current === sequence) busy.value = false }
}
async function claim(coupon: ActivityCoupon) {
  if (claiming.value !== null || auth.busy || !auth.user) return
  const current = sequence
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
    notice.value = cause instanceof HttpError && cause.status === 409
      ? '当前不在领取时间内或优惠券已领完，请刷新活动。' : errorMessage(cause)
  } finally { if (current === sequence) claiming.value = null }
}
// 身份操作结束后，即使还是同一账号，也要重新确认活动与领券状态。
watch([() => route.params.id, () => auth.user?.id, () => auth.status], () => {
  if (auth.status === 'authenticated' || auth.status === 'anonymous') void load()
}, { immediate: true })
onUnmounted(() => { sequence++; window.clearInterval(timer) })
</script>

<template>
  <RouterLink class="text-link" to="/activities">← 返回活动列表</RouterLink>
  <p v-if="busy" role="status">正在加载活动详情…</p>
  <div v-else-if="error" role="alert"><p class="form-error">{{ error }}</p><el-button @click="load">重试</el-button></div>
  <template v-else-if="detail">
    <h1 class="page-title">{{ detail.name }}</h1>
    <p><span class="role-badge">{{ phase }}</span></p>
    <section class="market-card event-summary">
      <h2>{{ product?.name ?? '商品暂不可展示' }}</h2>
      <p v-if="product" class="price">¥{{ Number(product.price).toFixed(2) }}</p>
      <p>活动剩余 {{ detail.remainingQuantity }} / {{ detail.totalQuantity }} 份</p>
      <p class="muted">{{ detail.startsAt.replace('T', ' ') }} 至 {{ detail.endsAt.replace('T', ' ') }}</p>
      <RouterLink v-if="product && phase === '进行中'" class="button-link" :to="`/purchase/${product.id}?activityId=${detail.id}`">参加活动下单</RouterLink>
    </section>
    <div class="section-heading"><h2>活动优惠券</h2><el-button :disabled="claiming !== null" @click="load">刷新活动</el-button></div>
    <p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
    <p v-if="!coupons.length" class="empty-state">本活动暂无优惠券。</p>
    <div class="market-grid">
      <article v-for="coupon in coupons" :key="coupon.id" class="market-card">
        <h2>{{ coupon.name }}</h2>
        <p class="price">满 {{ coupon.thresholdAmount }} 减 {{ coupon.discountAmount }}</p>
        <p class="muted">剩余 {{ coupon.remainingQuantity }} 张</p>
        <p class="muted">领取：{{ coupon.claimStartsAt.replace('T', ' ') }} 至 {{ coupon.claimEndsAt.replace('T', ' ') }}</p>
        <p class="muted">使用：{{ coupon.useStartsAt.replace('T', ' ') }} 至 {{ coupon.useEndsAt.replace('T', ' ') }}</p>
        <RouterLink v-if="!auth.user" class="button-link" to="/login">登录后领取</RouterLink>
        <el-button v-else type="primary" :disabled="claimLabel(coupon) !== '领取优惠券' || claiming !== null || auth.busy" :loading="claiming === coupon.id" @click="claim(coupon)">{{ claimLabel(coupon) }}</el-button>
      </article>
    </div>
  </template>
</template>
