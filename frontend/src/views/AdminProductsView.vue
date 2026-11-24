<script setup lang="ts">
import { reactive, ref } from 'vue'
import { categories } from '../api/catalog.ts'
import { adminProducts, createProduct, updateProduct, updateProductStatus } from '../api/admin.ts'
import { useAdminPage } from '../composables/useAdminPage.ts'
import { validMoney, validQuantity } from '../utils/admin.ts'
import type { AdminProduct } from '../types/admin.ts'
const editing = ref<number | null>(null)
const form = reactive({ name: '', price: '', stock: 0, categoryId: '' })
function resetDraft() { editing.value = null; Object.assign(form, { name: '', price: '', stock: 0, categoryId: '' }) }
const { data, busy, saving, blocked, error, notice, load, mutate } = useAdminPage(async () => {
  const [products, list] = await Promise.all([adminProducts(), categories()])
  return { products, categories: list }
}, resetDraft)
function edit(item: AdminProduct) {
  editing.value = item.id
  Object.assign(form, { name: item.name, price: String(item.price), stock: item.stock, categoryId: item.categoryId === null ? '' : String(item.categoryId) })
  error.value = ''
}
function save() {
  const name = form.name.trim()
  if (!name || name.length > 100 || !validMoney(form.price) || editing.value === null && !validQuantity(form.stock, true)) {
    error.value = '请检查商品名称（1至100字）、价格（大于0，最多8位整数2位小数）和非负整数初始库存。'; return
  }
  const payload = { name, price: Number(form.price), categoryId: form.categoryId ? Number(form.categoryId) : null }
  const id = editing.value
  if (id === null) {
    void mutate(() => createProduct({ ...payload, stock: form.stock }), '商品已创建，初始为下架状态。', resetDraft)
    return
  }
  void mutate(() => updateProduct(id, payload), '商品资料已更新。', resetDraft, ({ products }) =>
    products.some(item => item.id === id && item.name === payload.name && Number(item.price) === payload.price && item.categoryId === payload.categoryId))
}
function toggle(item: AdminProduct) {
  const status = item.status === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE'
  void mutate(() => updateProductStatus(item.id, status), status === 'ON_SALE' ? '商品已上架。' : '商品已下架。', undefined,
    ({ products }) => products.some(product => product.id === item.id && product.status === status))
}
</script>
<template>
  <h1 class="page-title">商品管理</h1><p class="page-description">创建商品、维护名称价格分类，并控制上架与下架。编辑资料不修改库存。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <el-button :loading="busy" :disabled="saving" @click="load">刷新商品</el-button>
  <section class="market-card address-editor"><h2>{{ editing === null ? '新增商品' : `编辑商品 #${editing}` }}</h2>
    <form @submit.prevent="save" novalidate><fieldset class="business-fields" :disabled="blocked">
      <div class="field"><label for="product-name">商品名称</label><input id="product-name" v-model="form.name" maxlength="100" /></div>
      <div class="field"><label for="product-price">商品价格</label><input id="product-price" v-model="form.price" inputmode="decimal" placeholder="例如19.90" /></div>
      <div class="field"><label for="product-stock">初始库存</label><input id="product-stock" v-model.number="form.stock" type="number" min="0" max="2147483647" step="1" :disabled="editing !== null" /></div>
      <div class="field"><label for="product-category">商品分类</label><select id="product-category" v-model="form.categoryId"><option value="">未分类</option><option v-for="item in data?.categories" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></div>
    </fieldset><div class="business-actions"><el-button type="primary" native-type="submit" :disabled="blocked" :loading="saving">保存商品</el-button><el-button v-if="editing !== null" :disabled="blocked" @click="resetDraft">取消编辑</el-button></div></form>
  </section>
  <p v-if="busy && !data" role="status">正在加载商品…</p><p v-else-if="data && !data.products.length" class="empty-state">暂无商品。</p>
  <div class="market-grid admin-grid"><article v-for="item in data?.products" :key="item.id" class="market-card">
    <span class="role-badge">{{ item.status === 'ON_SALE' ? '上架中' : '已下架' }}</span><h2>{{ item.name }}</h2><p class="price">¥{{ Number(item.price).toFixed(2) }}</p>
    <p class="muted">商品 #{{ item.id }} · 库存 {{ item.stock }} · {{ data?.categories.find(value => value.id === item.categoryId)?.name ?? '未分类' }}</p>
    <div class="business-actions"><el-button :disabled="blocked" @click="edit(item)">编辑商品</el-button><el-button :disabled="blocked" @click="toggle(item)">{{ item.status === 'ON_SALE' ? '下架' : '上架' }}</el-button></div>
  </article></div>
</template>
