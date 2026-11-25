<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { addresses, cartItems, removeCartItem, setCartQuantity } from '../api/checkout.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import { canReconcileWrite, reconcileWrite } from '../utils/write-reconciliation.ts'
import type { Address, CartItem } from '../types/checkout.ts'

const auth = useAuthStore()
const checkout = useCheckoutStore()
const router = useRouter()
const items = ref<CartItem[]>([])
const addressList = ref<Address[]>([])
const addressId = ref('')
const selected = ref<number[]>([])
const quantities = ref<Record<number, number>>({})
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
let sequence = 0
let operation = 0
const blocked = computed(() => busy.value || saving.value || auth.busy || checkout.busy || checkout.pending !== null)
const estimate = computed(() => items.value.filter(item => selected.value.includes(item.productId)).reduce((sum, item) => sum + Math.round(Number(item.price) * 100) * item.quantity, 0) / 100)
const unsaved = computed(() => items.value.some(item => selected.value.includes(item.productId) && quantities.value[item.productId] !== item.quantity))
function applyCart(cart: CartItem[]) {
  // 更新服务端数据，但保留用户尚未保存的数量输入。
  const previous = items.value
  quantities.value = Object.fromEntries(cart.map(item => {
    const old = previous.find(value => value.productId === item.productId)
    const draft = quantities.value[item.productId]
    return [item.productId, old && draft !== old.quantity ? draft : item.quantity]
  }))
  items.value = cart
  selected.value = selected.value.filter(id => cart.some(item => item.productId === id && item.status === 'ON_SALE' && item.stock >= item.quantity))
}
async function load() {
  const current = ++sequence
  busy.value = true
  error.value = ''
  try {
    const [cart, data] = await Promise.all([cartItems(), addresses()])
    if (current !== sequence) return
    applyCart(cart)
    addressList.value = data
    if (!data.some(item => String(item.id) === addressId.value)) addressId.value = String(data.find(item => item.defaultAddress)?.id ?? data[0]?.id ?? '')
  } catch (cause) { if (current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
  finally { if (current === sequence) busy.value = false }
}
async function update(item: CartItem, remove = false) {
  if (blocked.value) return
  const quantity = quantities.value[item.productId]!
  if (!remove && (!Number.isInteger(quantity) || quantity < 1 || quantity > 2147483647)) { error.value = '数量须为1至2147483647的整数。'; return }
  const current = ++operation
  const success = remove ? '商品已移除。' : '购物车数量已保存。'
  saving.value = true; error.value = ''; notice.value = ''
  try {
    if (remove) await removeCartItem(item.productId)
    else await setCartQuantity(item.productId, quantity)
    if (current !== operation) return
    if (remove) notice.value = success
    else ElMessage.success(success)
    await load()
  } catch (cause) {
    if (current !== operation || cause instanceof SessionChangedError) return
    if (!canReconcileWrite(cause)) { error.value = errorMessage(cause); return }
    notice.value = '正在核对购物车状态…'
    const result = await reconcileWrite(cartItems, cart => {
      const actual = cart.find(row => row.productId === item.productId)
      return remove ? !actual : actual?.quantity === quantity
    }, () => current === operation)
    if (current !== operation) return
    if (result?.snapshot) applyCart(result.snapshot)
    if (result?.confirmed && !remove) {
      notice.value = ''
      ElMessage.success(success)
      return
    }
    notice.value = !result ? '' : result.confirmed ? success : result.snapshot
      ? '已更新购物车，暂未确认本次修改。' : '暂时无法核对购物车状态。'
  }
  finally { if (current === operation) saving.value = false }
}
async function submit() {
  if (blocked.value) return
  if (!selected.value.length || selected.value.length > 50 || !addressId.value || unsaved.value) {
    error.value = '请选择1至50种商品和收货地址，并先保存所选商品的数量修改。'; return
  }
  error.value = ''
  try {
    const order = await checkout.submit('/api/orders/checkout', { productIds: [...selected.value].sort((a, b) => a - b), addressId: Number(addressId.value) })
    await router.push(`/orders/${order.id}`)
  } catch (cause) { if (!checkout.pending && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
}
watch(() => auth.user?.id, () => {
  sequence++; operation++; items.value = []; selected.value = []; quantities.value = {}; addressList.value = []; addressId.value = ''; saving.value = false; notice.value = ''
}, { flush: 'sync' })
watch(() => auth.status, status => { if (status === 'authenticated') void load() }, { immediate: true })
onUnmounted(() => { sequence++; operation++ })
</script>

<template>
  <p class="section-kicker">SHOPPING CART</p><h1 class="page-title">购物车</h1>
  <p class="page-description">调整数量并选择要结算的商品。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <el-button :disabled="saving || auth.busy || checkout.busy" @click="load">刷新购物车</el-button>
  <p v-if="busy" role="status">正在加载购物车…</p>
  <template v-else>
    <p v-if="!items.length && !error" class="empty-state">购物车还是空的，<RouterLink class="text-link" to="/products">去选购商品</RouterLink>。</p>
    <article v-for="item in items" :key="item.productId" class="market-card cart-row">
      <input v-model="selected" type="checkbox" :value="item.productId" :aria-label="`选择${item.name}`" :disabled="blocked || item.status !== 'ON_SALE' || item.stock < item.quantity" />
      <div class="cart-item-info"><h2>{{ item.name }}</h2><p class="muted">¥{{ Number(item.price).toFixed(2) }} · {{ item.status !== 'ON_SALE' ? '已下架' : item.stock < item.quantity ? '当前数量不可购买' : '在售' }}</p></div>
      <input v-model.number="quantities[item.productId]" class="quantity-input" type="number" min="1" max="2147483647" step="1" :aria-label="`${item.name}数量`" :disabled="blocked || item.status !== 'ON_SALE'" />
      <div class="business-actions"><el-button :disabled="blocked || item.status !== 'ON_SALE'" @click="update(item)">保存数量</el-button><el-button :disabled="blocked" @click="update(item, true)">移除</el-button></div>
    </article>
    <section v-if="items.length" class="market-card checkout-summary">
      <template v-if="addressList.length">
        <label for="cart-address">收货地址</label>
        <select id="cart-address" v-model="addressId" :disabled="blocked"><option v-for="item in addressList" :key="item.id" :value="String(item.id)">{{ item.defaultAddress ? '默认 · ' : '' }}{{ item.recipientName }} {{ item.province }} {{ item.city }} {{ item.district }} {{ item.detailAddress }}</option></select>
      </template>
      <RouterLink v-else class="text-link" to="/addresses">请先添加收货地址</RouterLink>
      <p>已选 {{ selected.length }} 种商品 · 预估 ¥{{ estimate.toFixed(2) }}</p>
      <p v-if="unsaved" class="form-error">所选商品有未保存的数量，请先保存。</p>
      <el-button type="primary" :loading="checkout.busy" :disabled="blocked || !selected.length || !addressId || unsaved" @click="submit">提交购物车订单</el-button>
    </section>
  </template>
</template>
