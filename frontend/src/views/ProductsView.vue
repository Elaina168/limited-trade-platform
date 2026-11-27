<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import { useAuthStore } from '../stores/auth.ts'
import { categories, searchProducts } from '../api/catalog.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import type { Category, ProductPage } from '../types/catalog.ts'

const auth = useAuthStore()
const keyword = ref('')
const categoryId = ref('')
const appliedKeyword = ref('')
const categoryList = ref<Category[]>([])
const result = ref<ProductPage | null>(null)
const page = ref(1)
const busy = ref(false)
const error = ref('')
let sequence = 0
let refreshing = false
async function load(silent = false) {
  const current = ++sequence
  refreshing = true
  if (!silent) { busy.value = true; error.value = '' }
  try {
    const [list, data] = await Promise.all([categories(), searchProducts(page.value, appliedKeyword.value, categoryId.value)])
    if (current !== sequence) return
    categoryList.value = list
    result.value = data
    error.value = ''
  } catch (cause) {
    if (!silent && current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally { if (current === sequence) { busy.value = false; refreshing = false } }
}
function search() {
  appliedKeyword.value = keyword.value
  page.value = 1
  void load()
}
function chooseCategory(id: string) {
  categoryId.value = id
  search()
}
function changePage(delta: number) { page.value += delta; void load() }
// 身份操作结束后，即使还是同一账号，也要补载已被作废的旧请求。
watch([() => auth.user?.id, () => auth.status], () => {
  if (!result.value && !refreshing && (auth.status === 'authenticated' || auth.status === 'anonymous')) void load()
}, { immediate: true })
useVisibilityRefresh(() => { void load(!!result.value) }, () => refreshing || auth.busy)
onUnmounted(() => { sequence++ })
</script>

<template>
  <p class="section-kicker">MARKET PICKS</p>
  <h1 class="page-title">发现好物</h1>
  <p class="page-description">按分类和名称查找当前在售商品。</p>
  <form class="filter-bar" @submit.prevent="search">
    <el-input v-model="keyword" aria-label="商品名称" placeholder="搜索商品名称" maxlength="100" clearable @clear="search" />
    <el-button type="primary" native-type="submit">搜索</el-button>
  </form>
  <div class="catalog-categories" role="group" aria-label="商品分类">
    <el-button round :type="categoryId === '' ? 'primary' : 'default'" :aria-pressed="categoryId === ''" @click="chooseCategory('')">全部分类</el-button>
    <el-button v-for="item in categoryList" :key="item.id" round
      :type="categoryId === String(item.id) ? 'primary' : 'default'" :aria-pressed="categoryId === String(item.id)" @click="chooseCategory(String(item.id))">{{ item.name }}</el-button>
  </div>
  <p v-if="busy && !result" role="status">正在加载商品…</p>
  <div v-else-if="error" role="alert"><p class="form-error">{{ error }}</p><el-button @click="load()">重试</el-button></div>
  <template v-if="result">
    <p class="muted">共 {{ result.total }} 件商品</p>
    <p v-if="!result.items.length" class="empty-state">没有找到符合条件的商品，试试其他名称或分类。</p>
    <div class="market-grid">
      <RouterLink v-for="item in result.items" :key="item.id" class="market-card product-card-link"
        :to="{ name: 'product-detail', params: { productId: item.id } }" :aria-label="item.name">
        <div class="product-mark" aria-hidden="true">{{ item.name.slice(0, 1) }}</div>
        <p class="section-kicker">{{ item.categoryName ?? '未分类' }}</p>
        <h2>{{ item.name }}</h2>
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
