<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElInputNumber, ElOption, ElPagination, ElSelect } from 'element-plus'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/pagination/style/css'
import 'element-plus/es/components/select/style/css'
import { categories } from '../api/catalog.ts'
import { adminProduct, adminProductsPage, createProduct, updateProduct, updateProductStatus } from '../api/admin.ts'
import { sessionRevision } from '../api/http.ts'
import { useAdminPage } from '../composables/useAdminPage.ts'
import { validMoney, validQuantity } from '../utils/admin.ts'
import type { AdminProduct, AdminProductStatus } from '../types/admin.ts'

const editing = ref<number | null>(null)
const form = reactive({ name: '', price: '', stock: 0 as number | undefined, categoryId: '' })
const keyword = ref('')
const filters = reactive({ page: 1, keyword: '', categoryId: '', status: '' as AdminProductStatus | '' })
let confirmationTarget: { id: number } | undefined
function resetDraft() { editing.value = null; Object.assign(form, { name: '', price: '', stock: 0, categoryId: '' }) }
function resetPage() {
  resetDraft()
  keyword.value = ''
  Object.assign(filters, { page: 1, keyword: '', categoryId: '', status: '' })
  confirmationTarget = undefined
}
const { data, busy, saving, blocked, error, notice, load, mutate } = useAdminPage(async () => {
  const { page, keyword, categoryId, status } = filters
  const target = confirmationTarget
  const expected = sessionRevision()
  let [products, list] = await Promise.all([adminProductsPage(page, keyword, categoryId, status, expected), categories()])
  const lastPage = Math.max(1, Math.ceil(products.total / products.size))
  if (page > lastPage) products = await adminProductsPage(lastPage, keyword, categoryId, status, expected)
  // 写入可能使商品不再符合当前筛选；对账仍可按编号查询。
  const confirmedProduct = target && (products.items.find(item => item.id === target.id) ?? await adminProduct(target.id, expected))
  return { products, categories: list, confirmedProduct }
}, resetPage)
watch(data, result => { if (result) filters.page = result.products.page })

function search() {
  filters.keyword = keyword.value.trim()
  goToPage(1)
}
function goToPage(page: number) {
  filters.page = page
  void load()
}
function saveExisting(id: number, action: () => Promise<AdminProduct>, message: string,
  matches: (item: AdminProduct) => boolean, onSuccess?: () => void) {
  if (blocked.value) return
  const target = { id }
  confirmationTarget = target
  void mutate(action, message, () => {
    confirmationTarget = undefined
    onSuccess?.()
  }, ({ confirmedProduct }) => confirmedProduct !== undefined && matches(confirmedProduct)).finally(() => {
    if (confirmationTarget === target) confirmationTarget = undefined
  })
}
function edit(item: AdminProduct) {
  editing.value = item.id
  Object.assign(form, { name: item.name, price: String(item.price), stock: item.stock, categoryId: item.categoryId === null ? '' : String(item.categoryId) })
  error.value = ''
}
function save() {
  const name = form.name.trim()
  const stock = form.stock
  if (!name || name.length > 100 || !validMoney(form.price)) {
    error.value = '请检查商品名称（1至100字）和价格（大于0，最多8位整数2位小数）。'; return
  }
  const payload = { name, price: Number(form.price), categoryId: form.categoryId ? Number(form.categoryId) : null }
  const id = editing.value
  if (id === null) {
    if (stock === undefined || !validQuantity(stock, true)) { error.value = '请填写非负整数初始库存。'; return }
    void mutate(() => createProduct({ ...payload, stock }), '商品已创建，初始为下架状态。', resetDraft)
    return
  }
  saveExisting(id, () => updateProduct(id, payload), '商品资料已更新。', item =>
    item.name === payload.name && Number(item.price) === payload.price && item.categoryId === payload.categoryId, resetDraft)
}
function toggle(item: AdminProduct) {
  const status = item.status === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE'
  saveExisting(item.id, () => updateProductStatus(item.id, status), status === 'ON_SALE' ? '商品已上架。' : '商品已下架。',
    product => product.status === status)
}
</script>
<template>
  <h1 class="page-title">商品管理</h1><p class="page-description">创建商品、维护名称价格分类，并控制上架与下架。编辑资料不修改库存。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <el-button :loading="busy" :disabled="saving" @click="load">刷新商品</el-button>
  <section class="market-card address-editor"><h2>{{ editing === null ? '新增商品' : `编辑商品 #${editing}` }}</h2>
    <form @submit.prevent="save" novalidate><fieldset class="product-fields product-draft" :disabled="blocked">
      <div class="field"><label for="product-name">商品名称</label><el-input id="product-name" v-model="form.name" maxlength="100" :disabled="blocked" /></div>
      <div class="field"><label for="product-price">商品价格</label><el-input id="product-price" v-model="form.price" inputmode="decimal" placeholder="例如19.90" :disabled="blocked" /></div>
      <div class="field"><label for="product-stock">初始库存</label><ElInputNumber id="product-stock" v-model="form.stock" :min="0" :max="2147483647" :step="1" :disabled="blocked || editing !== null" /></div>
      <div class="field">
        <label for="product-category">商品分类</label>
        <ElSelect id="product-category" v-model="form.categoryId" placeholder="未分类" :disabled="blocked">
          <ElOption label="未分类" value="" />
          <ElOption v-for="item in data?.categories" :key="item.id" :label="item.name" :value="String(item.id)" />
        </ElSelect>
      </div>
    </fieldset><div class="business-actions"><el-button type="primary" native-type="submit" :disabled="blocked" :loading="saving">保存商品</el-button><el-button v-if="editing !== null" :disabled="blocked" @click="resetDraft">取消编辑</el-button></div></form>
  </section>
  <form class="market-card product-fields" @submit.prevent="search">
    <div class="field">
      <label for="admin-product-keyword">按商品名称搜索</label>
      <el-input id="admin-product-keyword" v-model="keyword" maxlength="100" clearable :disabled="saving" @clear="search" />
    </div>
    <div class="field">
      <label for="admin-product-category">按分类筛选</label>
      <ElSelect id="admin-product-category" v-model="filters.categoryId" placeholder="全部分类" :disabled="saving" @change="goToPage(1)">
        <ElOption label="全部分类" value="" />
        <ElOption v-for="item in data?.categories" :key="item.id" :label="item.name" :value="String(item.id)" />
      </ElSelect>
    </div>
    <div class="field">
      <label for="admin-product-status">按状态筛选</label>
      <ElSelect id="admin-product-status" v-model="filters.status" placeholder="全部状态" :disabled="saving" @change="goToPage(1)">
        <ElOption label="全部状态" value="" />
        <ElOption label="上架中" value="ON_SALE" />
        <ElOption label="已下架" value="OFF_SALE" />
      </ElSelect>
    </div>
    <div class="product-search-action"><el-button type="primary" native-type="submit" :disabled="saving">搜索商品</el-button></div>
  </form>
  <p v-if="busy && !data" role="status">正在加载商品…</p><p v-else-if="data && !data.products.items.length" class="empty-state">没有符合条件的商品。</p>
  <div class="market-grid admin-grid"><article v-for="item in data?.products.items" :key="item.id" class="market-card">
    <span class="role-badge">{{ item.status === 'ON_SALE' ? '上架中' : '已下架' }}</span><h2>{{ item.name }}</h2><p class="price">¥{{ Number(item.price).toFixed(2) }}</p>
    <p class="muted">商品 #{{ item.id }} · 库存 {{ item.stock }} · {{ data?.categories.find(value => value.id === item.categoryId)?.name ?? '未分类' }}</p>
    <div class="business-actions"><el-button :disabled="blocked" @click="edit(item)">编辑商品</el-button><el-button :disabled="blocked" @click="toggle(item)">{{ item.status === 'ON_SALE' ? '下架' : '上架' }}</el-button></div>
  </article></div>
  <nav v-if="data && data.products.total > 0" aria-label="管理商品分页">
    <ElPagination :current-page="data.products.page" :page-size="data.products.size" :total="data.products.total"
      layout="total, prev, pager, next" :pager-count="5" :disabled="blocked" @update:current-page="goToPage" />
  </nav>
</template>

<style scoped>
.product-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.product-draft { border: 0; margin: 0; min-width: 0; padding: 0; }
.product-search-action { align-self: center; }
.product-fields :deep(.el-select__wrapper) { min-height: 48px; }
@media (max-width: 640px) { .product-fields { grid-template-columns: 1fr; } }
</style>
