<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import { useAuthStore } from '../stores/auth.ts'
import { categories, searchProducts, suggestProducts } from '../api/catalog.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import { parseHighlightSegments } from '../utils/search-highlight.ts'
import type { Category, ProductPage, ProductSearchFilters } from '../types/catalog.ts'

const auth = useAuthStore()
const keyword = ref('')
const categoryId = ref('')
const appliedKeyword = ref('')
const minPrice = ref('')
const maxPrice = ref('')
const inStockOnly = ref(false)
const sort = ref<ProductSearchFilters['sort']>('')
const suggestions = ref<string[]>([])
const suggestOpen = ref(false)
const categoryList = ref<Category[]>([])
const result = ref<ProductPage | null>(null)
const page = ref(1)
const busy = ref(false)
const error = ref('')
let sequence = 0
let refreshing = false
let suggestTimer: number | undefined
let suggestController: AbortController | null = null

const facetMap = computed(() => {
  const map = new Map<number | null, number>()
  for (const facet of result.value?.categoryFacets ?? []) {
    map.set(facet.categoryId, facet.count)
  }
  return map
})

const allFacetTotal = computed(() => {
  if (!result.value?.categoryFacets?.length) return undefined
  return result.value.categoryFacets.reduce((sum, item) => sum + item.count, 0)
})

function facetCount(id: number): number | undefined {
  if (!result.value?.categoryFacets) return undefined
  return facetMap.value.get(id) ?? 0
}

async function load(silent = false) {
  const current = ++sequence
  refreshing = true
  if (!silent) { busy.value = true; error.value = '' }
  try {
    const [list, data] = await Promise.all([
      categories(),
      searchProducts(page.value, appliedKeyword.value, categoryId.value, {
        minPrice: minPrice.value,
        maxPrice: maxPrice.value,
        inStockOnly: inStockOnly.value,
        sort: sort.value,
      }),
    ])
    if (current !== sequence) return
    categoryList.value = list
    result.value = data
    error.value = ''
  } catch (cause) {
    if (!silent && current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally { if (current === sequence) { busy.value = false; refreshing = false } }
}

function triggerSuggest() {
  window.clearTimeout(suggestTimer)
  suggestController?.abort()
  const clean = keyword.value.trim()
  if (!clean) {
    suggestions.value = []
    suggestOpen.value = false
    return
  }
  suggestTimer = window.setTimeout(async () => {
    const controller = new AbortController()
    suggestController = controller
    try {
      const items = await suggestProducts(clean, controller.signal)
      if (suggestController === controller) {
        suggestions.value = items.filter(name => name !== clean)
        suggestOpen.value = suggestions.value.length > 0
      }
    } catch {
      if (suggestController === controller) {
        suggestions.value = []
        suggestOpen.value = false
      }
    }
  }, 180)
}

function pickSuggestion(name: string) {
  keyword.value = name
  suggestions.value = []
  suggestOpen.value = false
  search()
}

function search() {
  suggestOpen.value = false
  if (minPrice.value.trim() && maxPrice.value.trim() && Number(minPrice.value) > Number(maxPrice.value)) {
    error.value = '最低价格不能高于最高价格。'
    return
  }
  appliedKeyword.value = keyword.value
  page.value = 1
  void load()
}

function clearKeyword() {
  suggestions.value = []
  suggestOpen.value = false
  search()
}

function chooseCategory(id: string) {
  categoryId.value = id
  search()
}

function changePage(delta: number) { page.value += delta; void load() }

watch([() => auth.user?.id, () => auth.status], () => {
  if (!result.value && !refreshing && (auth.status === 'authenticated' || auth.status === 'anonymous')) void load()
}, { immediate: true })
useVisibilityRefresh(() => { void load(!!result.value) }, () => refreshing || auth.busy)
onUnmounted(() => {
  sequence++
  window.clearTimeout(suggestTimer)
  suggestController?.abort()
})
</script>

<template>
  <p class="section-kicker">MARKET PICKS</p>
  <h1 class="page-title">发现好物</h1>
  <p class="page-description">按分类、价格区间与关键词查找当前在售商品。</p>
  <form class="filter-bar search-toolbar" @submit.prevent="search">
    <div class="search-box-wrap">
      <el-input
        v-model="keyword"
        aria-label="商品名称"
        placeholder="搜索商品名称（支持联想与高亮）"
        maxlength="100"
        clearable
        @input="triggerSuggest"
        @focus="suggestOpen = suggestions.length > 0"
        @blur="suggestOpen = false"
        @clear="clearKeyword"
      />
      <ul v-if="suggestOpen && suggestions.length" class="search-suggest-list" role="listbox" aria-label="搜索联想">
        <li v-for="item in suggestions" :key="item" role="option" @mousedown.prevent="pickSuggestion(item)">{{ item }}</li>
      </ul>
    </div>
    <el-input v-model="minPrice" class="price-filter-input" aria-label="最低价格" placeholder="最低价 ¥" inputmode="decimal" clearable @clear="search" />
    <el-input v-model="maxPrice" class="price-filter-input" aria-label="最高价格" placeholder="最高价 ¥" inputmode="decimal" clearable @clear="search" />
    <el-select v-model="sort" class="sort-filter-select" aria-label="商品排序" placeholder="默认排序" @change="search">
      <el-option value="" label="默认排序" />
      <el-option value="relevance" label="相关度优先" />
      <el-option value="price_asc" label="价格从低到高" />
      <el-option value="price_desc" label="价格从高到低" />
    </el-select>
    <el-checkbox v-model="inStockOnly" @change="search">仅看有货</el-checkbox>
    <el-button type="primary" native-type="submit">搜索</el-button>
  </form>
  <div class="catalog-categories" role="group" aria-label="商品分类">
    <el-button round :type="categoryId === '' ? 'primary' : 'default'" aria-label="全部分类" :aria-pressed="categoryId === ''" @click="chooseCategory('')">
      <span>全部分类</span><span v-if="allFacetTotal !== undefined" class="facet-badge" aria-hidden="true"> ({{ allFacetTotal }})</span>
    </el-button>
    <el-button v-for="item in categoryList" :key="item.id" round :aria-label="item.name"
      :type="categoryId === String(item.id) ? 'primary' : 'default'" :aria-pressed="categoryId === String(item.id)" @click="chooseCategory(String(item.id))">
      <span>{{ item.name }}</span><span v-if="facetCount(item.id) !== undefined" class="facet-badge" aria-hidden="true"> ({{ facetCount(item.id) }})</span>
    </el-button>
  </div>
  <p v-if="busy && !result" role="status">正在加载商品…</p>
  <div v-else-if="error" role="alert"><p class="form-error">{{ error }}</p><el-button @click="load()">重试</el-button></div>
  <template v-if="result">
    <p class="muted">共 {{ result.total }} 件商品<span v-if="result.degraded"> · 搜索服务已自动降级至基础检索</span></p>
    <p v-if="!result.items.length" class="empty-state">没有找到符合条件的商品，试试其他名称或分类。</p>
    <div class="market-grid">
      <RouterLink v-for="item in result.items" :key="item.id" class="market-card product-card-link"
        :to="{ name: 'product-detail', params: { productId: item.id } }" :aria-label="item.name">
        <div class="product-mark" aria-hidden="true">{{ item.name.slice(0, 1) }}</div>
        <p class="section-kicker">{{ item.categoryName ?? '未分类' }}</p>
        <h2>
          <template v-for="(seg, idx) in parseHighlightSegments(item.name, item.highlightedName)" :key="idx">
            <mark v-if="seg.hit" class="search-mark">{{ seg.text }}</mark>
            <template v-else>{{ seg.text }}</template>
          </template>
        </h2>
        <p class="price">¥{{ Number(item.price).toFixed(2) }}</p>
        <p v-if="item.stock === 0" class="muted">暂时售罄</p>
      </RouterLink>
    </div>
    <nav class="pagination" aria-label="商品分页">
      <el-button :disabled="page <= 1" @click="changePage(-1)">上一页</el-button>
      <span>第 {{ page }} 页 / 共 {{ Math.max(1, Math.ceil(result.total / result.size)) }} 页</span>
      <el-button :disabled="page * result.size >= result.total" @click="changePage(1)">下一页</el-button>
    </nav>
  </template>
</template>

<style scoped>
.search-toolbar { align-items: center; flex-wrap: wrap; gap: 10px; }
.search-box-wrap { position: relative; flex: 1 1 240px; min-width: 200px; }
.price-filter-input { width: 118px; }
.sort-filter-select { width: 150px; }
.search-suggest-list {
  position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 20;
  margin: 0; padding: 6px 0; list-style: none; background: #fff;
  border: 1px solid #dfe5ef; border-radius: 8px; box-shadow: 0 8px 24px rgba(23, 43, 70, 0.12);
}
.search-suggest-list li { padding: 8px 14px; font-size: 13px; cursor: pointer; color: #26374b; }
.search-suggest-list li:hover { background: #eef2fb; color: #345cbb; }
.facet-badge { opacity: 0.78; font-size: 12px; }
.search-mark { background: #fff1b8; color: inherit; padding: 0 2px; border-radius: 3px; }
</style>
