<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElInputNumber, ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/input-number/style/css'
import { useAuthStore } from '../stores/auth.ts'
import { useCartStore } from '../stores/cart.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { productById } from '../api/catalog.ts'
import { errorMessage, HttpError, SessionChangedError } from '../api/http.ts'
import { useCartQuantitySave } from '../composables/useCartQuantitySave.ts'
import QuickPurchaseDrawer from '../components/QuickPurchaseDrawer.vue'
import type { Product } from '../types/catalog.ts'

const route = useRoute()
const auth = useAuthStore()
const checkout = useCheckoutStore()
const cart = useCartStore()
const { saving, error: cartError, notice: cartNotice, addQuantity, resetCartSave } = useCartQuantitySave()
const product = ref<Product>()
const quantity = ref(1)
const purchaseOpen = ref(false)
const busy = ref(false)
const error = ref('')
const quantityValid = computed(() => Number.isInteger(quantity.value) && quantity.value >= 1 && quantity.value <= (product.value?.stock ?? 0))
const cartBlocked = computed(() => saving.value || auth.busy || checkout.busy || checkout.pending !== null)
let sequence = 0

async function load() {
  const current = ++sequence
  busy.value = true; error.value = ''; product.value = undefined
  const id = Number(route.params.productId)
  if (!Number.isSafeInteger(id) || id <= 0) {
    error.value = '商品编号无效。'; busy.value = false; return
  }
  try {
    const found = await productById(id)
    if (current === sequence) product.value = found
  } catch (cause) {
    if (current !== sequence || cause instanceof SessionChangedError) return
    error.value = cause instanceof HttpError && cause.status === 404
      ? '商品不存在或当前已下架。' : errorMessage(cause)
  } finally { if (current === sequence) busy.value = false }
}

async function addToCart() {
  if (!auth.user || !product.value || !quantityValid.value || cartBlocked.value) return
  const result = await addQuantity(product.value.id, quantity.value)
  if (!result) return
  if ('snapshot' in result && result.snapshot) cart.replace(result.snapshot)
  else if (result.item) cart.updateItem(result.item)
  if (result.confirmed) {
    if ('snapshot' in result) ElMessage.success('已核对该商品的购物车数量。')
    else {
      ElMessage.success('已加入购物车。')
      void cart.refresh()
    }
  }
}

watch([() => route.params.productId, () => auth.user?.id], () => {
  sequence++; product.value = undefined; quantity.value = 1; purchaseOpen.value = false; resetCartSave()
}, { flush: 'sync' })
watch([() => route.params.productId, () => auth.status], () => {
  if (!product.value && (auth.status === 'authenticated' || auth.status === 'anonymous')) void load()
}, { immediate: true })
onUnmounted(() => { sequence++ })
</script>

<template>
  <RouterLink class="text-link" :to="{ name: 'products' }">← 返回商品浏览</RouterLink>
  <p v-if="busy" role="status">正在加载商品…</p>
  <p v-else-if="error" class="form-error" role="alert">{{ error }}</p>
  <section v-else-if="product" class="market-card purchase-form">
    <div class="product-mark" aria-hidden="true">{{ product.name.slice(0, 1) }}</div>
    <h1 class="page-title">{{ product.name }}</h1>
    <p class="price">¥{{ Number(product.price).toFixed(2) }}</p>
    <p v-if="product.stock === 0" class="muted">暂时售罄</p>
    <form @submit.prevent="addToCart">
      <fieldset class="purchase-fields" :disabled="cartBlocked">
        <div class="field">
          <label for="product-quantity">购买数量</label>
          <ElInputNumber id="product-quantity" v-model="quantity" :min="1" :max="Math.max(1, product.stock)" :step="1" :disabled="product.stock === 0" />
        </div>
      </fieldset>
      <p v-if="cartError" class="form-error" role="alert">{{ cartError }}</p>
      <p v-if="cartNotice" class="operation-notice" role="status">{{ cartNotice }}</p>
      <div class="business-actions">
        <el-button v-if="auth.user" type="primary" :disabled="!quantityValid || cartBlocked" @click="purchaseOpen = true">立即购买</el-button>
        <RouterLink v-else-if="quantityValid" class="button-link"
          :to="{ name: 'login', query: { redirect: route.fullPath } }">登录后购买</RouterLink>
        <el-button v-if="auth.user" native-type="submit" :disabled="cartBlocked || !quantityValid" :loading="saving">加入购物车</el-button>
        <RouterLink v-else-if="product.stock > 0" class="text-link" :to="{ name: 'login', query: { redirect: route.fullPath } }">登录后加入购物车</RouterLink>
      </div>
    </form>
    <QuickPurchaseDrawer v-model="purchaseOpen" :product-id="product.id" :quantity="quantity" />
  </section>
</template>
