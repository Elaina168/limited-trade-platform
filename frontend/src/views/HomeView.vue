<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { activities, searchProducts } from '../api/catalog.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import type { Activity, ProductSearchItem } from '../types/catalog.ts'
import { activityStatus, beijingTimestamp, formatActivityTime } from '../utils/activity-time.ts'

const events = ref<Activity[]>([])
const products = ref<ProductSearchItem[]>([])
const now = ref(Date.now())
const loaded = ref(false)
const error = ref('')
const picks = computed(() => events.value
  .filter(item => ['进行中', '即将开始'].includes(activityStatus(item, now.value)))
  .sort((a, b) => beijingTimestamp(a.startsAt) - beijingTimestamp(b.startsAt))
  .slice(0, 4))
let active = true
let loading = false

async function load() {
  loading = true
  try {
    const [list, catalog] = await Promise.all([activities(), searchProducts(1, '', '')])
    if (!active) return
    now.value = Date.now()
    events.value = list
    products.value = catalog.items.filter(item => item.stock > 0).slice(0, 8)
    loaded.value = true
    error.value = ''
  } catch (cause) {
    if (active && !loaded.value && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally { loading = false }
}

onMounted(load)
onUnmounted(() => { active = false })
useVisibilityRefresh(() => { void load() }, () => loading)
</script>

<template>
  <section class="market-intro">
    <div>
      <h1>限量集市</h1>
      <p>查看正在进行的限量活动，或按分类挑选在售商品。</p>
      <div class="business-actions">
        <RouterLink class="button-link" to="/products">浏览商品</RouterLink>
        <RouterLink class="market-intro-link" to="/activities">查看全部活动</RouterLink>
      </div>
    </div>
  </section>
  <p v-if="error" class="form-error" role="alert">{{ error }} <el-button @click="load">重试</el-button></p>
  <p v-else-if="!loaded" role="status">正在加载商城推荐…</p>
  <template v-else>
    <section class="market-preview" aria-labelledby="featured-events">
      <div class="section-heading"><h2 id="featured-events">限量活动</h2><RouterLink class="text-link" to="/activities">全部活动 →</RouterLink></div>
      <p v-if="!picks.length" class="empty-state">暂无进行中或即将开始的活动。</p>
      <div v-else class="market-grid">
        <RouterLink v-for="event in picks" :key="event.id" class="market-card product-card-link" :to="{ name: 'activity-detail', params: { id: event.id } }">
          <span class="role-badge">{{ activityStatus(event, now) }}</span>
          <h3>{{ event.name }}</h3><p class="muted">开始 {{ formatActivityTime(event.startsAt) }}</p>
        </RouterLink>
      </div>
    </section>
    <section class="market-preview" aria-labelledby="featured-products">
      <div class="section-heading"><h2 id="featured-products">在售好物</h2><RouterLink class="text-link" to="/products">全部商品 →</RouterLink></div>
      <p v-if="!products.length" class="empty-state">暂无可购买商品。</p>
      <div v-else class="market-grid">
        <RouterLink v-for="item in products" :key="item.id" class="market-card product-card-link" :to="{ name: 'product-detail', params: { productId: item.id } }">
          <div class="product-mark" aria-hidden="true">{{ item.name.slice(0, 1) }}</div>
          <p class="muted">{{ item.categoryName ?? '未分类' }}</p><h3>{{ item.name }}</h3>
          <p class="price">¥{{ Number(item.price).toFixed(2) }}</p>
        </RouterLink>
      </div>
    </section>
  </template>
</template>

<style scoped>
.market-intro { padding: clamp(28px, 7vw, 72px); border-radius: 20px; background: #193a33; color: #fff; }
.market-intro h1 { margin: 0; font-size: clamp(2em, 6vw, 4em); }
.market-intro p { margin: 12px 0 24px; line-height: 1.6; color: #e1eee8; }
.market-intro-link { color: #fff; padding: 8px 0; text-underline-offset: 4px; }
.market-preview { margin-top: 36px; }
.market-preview h3 { margin-block: 8px; font-size: 1.125rem; }
</style>
