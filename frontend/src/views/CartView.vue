<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElCheckbox, ElInputNumber } from 'element-plus'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/checkbox/style/css'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import { useAuthStore } from '../stores/auth.ts'
import { useCartStore } from '../stores/cart.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { addresses, cartItems, removeCartItem, setCartQuantity } from '../api/checkout.ts'
import { errorMessage, onSessionChange, SessionChangedError } from '../api/http.ts'
import { canReconcileWrite, reconcileWrite } from '../utils/write-reconciliation.ts'
import AddressFormDialog from '../components/AddressFormDialog.vue'
import type { Address, CartItem } from '../types/checkout.ts'

const auth = useAuthStore()
const cart = useCartStore()
const checkout = useCheckoutStore()
const router = useRouter()
const items = computed(() => cart.items)
const addressList = ref<Address[]>([])
const addressId = ref('')
const addressDialog = ref(false)
const selected = ref<number[]>([])
const quantities = ref<Record<number, number>>({})
const busy = ref(false)
const deleting = ref(false)
const submitting = ref(false)
const savingProductId = ref<number | null>(null)
const error = ref('')
const notice = ref('')
const pending = new Map<number, number>()
let draining: Promise<boolean> | undefined
let sequence = 0
let refreshing = false
let loaded = false
let generation = 0
const blocked = computed(() => busy.value || deleting.value || submitting.value || auth.busy || checkout.busy || checkout.pending !== null)
const estimate = computed(() => items.value.filter(item => selected.value.includes(item.productId))
  .reduce((sum, item) => sum + Math.round(Number(item.price) * 100) * (quantities.value[item.productId] ?? item.quantity), 0) / 100)

watch(items, rows => {
  quantities.value = Object.fromEntries(rows.map(item => [item.productId,
    pending.has(item.productId) || savingProductId.value === item.productId
      ? (quantities.value[item.productId] ?? item.quantity) : item.quantity]))
  selected.value = selected.value.filter(id => rows.some(item => item.productId === id && item.status === 'ON_SALE'))
}, { immediate: true, flush: 'sync' })

function selectNewAddress(address: Address, list: Address[]) {
  addressList.value = list
  addressId.value = String(address.id)
}

async function load(silent = false) {
  const current = ++sequence
  refreshing = true
  if (!silent) { busy.value = true; error.value = '' }
  try {
    const [, data] = await Promise.all([cart.refresh(), addresses()])
    if (current !== sequence) return
    addressList.value = data
    loaded = true
    if (!data.some(item => String(item.id) === addressId.value)) addressId.value = String(data.find(item => item.defaultAddress)?.id ?? data[0]?.id ?? '')
  } catch (cause) { if (!silent && current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
  finally { if (current === sequence) { busy.value = false; refreshing = false } }
}

function changeQuantity(item: CartItem, value: number | undefined) {
  if (blocked.value || item.status !== 'ON_SALE') return
  if (value === undefined || !Number.isInteger(value) || value < 1) {
    quantities.value[item.productId] = pending.get(item.productId) ?? item.quantity
    return
  }
  quantities.value[item.productId] = value
  pending.set(item.productId, value)
  error.value = ''; notice.value = ''
  void flushPendingQuantityUpdates()
}

function flushPendingQuantityUpdates(): Promise<boolean> {
  if (draining) return draining
  if (!pending.size) return Promise.resolve(true)
  const current = generation
  // 同一时刻只发送一笔数量写入，连续操作以最后一次输入为准。
  draining = (async () => {
    let confirmed = true
    while (current === generation && pending.size) {
      const [id, quantity] = pending.entries().next().value!
      pending.delete(id)
      const item = cart.items.find(row => row.productId === id)
      if (!item || item.quantity === quantity) continue
      savingProductId.value = id
      try {
        await setCartQuantity(id, quantity)
        if (current !== generation) return false
        cart.updateItem({ ...item, quantity })
      } catch (cause) {
        if (current !== generation || cause instanceof SessionChangedError) return false
        if (canReconcileWrite(cause)) {
          const result = await reconcileWrite(cartItems, rows => rows.some(row => row.productId === id && row.quantity === quantity),
            () => current === generation)
          if (current !== generation) return false
          if (result?.snapshot) cart.replace(result.snapshot)
          if (result?.confirmed) continue
        }
        confirmed = false
        error.value = errorMessage(cause)
      } finally {
        if (current === generation) {
          savingProductId.value = null
          const actual = cart.items.find(row => row.productId === id)
          if (actual && !pending.has(id)) quantities.value[id] = actual.quantity
        }
      }
    }
    return current === generation && confirmed
  })().finally(() => { if (current === generation) draining = undefined })
  return draining
}

async function remove(item: CartItem) {
  if (blocked.value || deleting.value) return
  const current = generation
  deleting.value = true; error.value = ''; notice.value = ''
  try {
    await flushPendingQuantityUpdates()
    if (current !== generation) return
    await removeCartItem(item.productId)
    if (current !== generation) return
    cart.replace(cart.items.filter(row => row.productId !== item.productId))
    notice.value = '商品已移除。'
  } catch (cause) {
    if (current !== generation || cause instanceof SessionChangedError) return
    if (!canReconcileWrite(cause)) { error.value = errorMessage(cause); return }
    notice.value = '正在核对购物车状态…'
    const result = await reconcileWrite(cartItems, rows => !rows.some(row => row.productId === item.productId),
      () => current === generation)
    if (current !== generation) return
    if (result?.snapshot) cart.replace(result.snapshot)
    notice.value = result?.confirmed ? '商品已移除。' : '暂未确认移除结果。'
  } finally { if (current === generation) deleting.value = false }
}

async function submit() {
  if (blocked.value || !selected.value.length || !addressId.value) return
  const productIds = [...selected.value].sort((a, b) => a - b)
  const deliveryAddressId = Number(addressId.value)
  if (productIds.length > 50) { error.value = '一次最多结算50种商品。'; return }
  const current = generation
  submitting.value = true; error.value = ''
  try {
    if (!(await flushPendingQuantityUpdates()) || current !== generation) return
    if (!productIds.every(id => cart.items.some(row => row.productId === id && row.status === 'ON_SALE' && row.stock >= row.quantity))) {
      error.value = '所选商品的可购数量已变化，请调整后结算。'
      return
    }
    const order = await checkout.submit('/api/orders/checkout', { productIds, addressId: deliveryAddressId })
    if (current !== generation) return
    cart.replace(cart.items.filter(row => !order.items.some(item => item.productId === row.productId)))
    void cart.refresh()
    await router.push(`/orders/${order.id}`)
  } catch (cause) { if (!checkout.pending && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
  finally { if (current === generation) submitting.value = false }
}

function reset() {
  sequence++; generation++; pending.clear(); draining = undefined; refreshing = false; loaded = false
  selected.value = []; quantities.value = {}; addressList.value = []; addressId.value = ''; addressDialog.value = false
  savingProductId.value = null; busy.value = false; deleting.value = false; submitting.value = false; error.value = ''; notice.value = ''
}
const unsubscribe = onSessionChange(reset)
watch(() => auth.user?.id, reset, { flush: 'sync' })
watch(() => auth.status, status => {
  if (status === 'authenticated' && !loaded && !refreshing && !draining && !submitting.value && !deleting.value) void load(items.value.length > 0)
}, { immediate: true })
useVisibilityRefresh(() => { void load(true) }, () => blocked.value || refreshing || !!draining || addressDialog.value)
onBeforeRouteLeave(async () => { if (auth.status === 'authenticated') await flushPendingQuantityUpdates() })
onUnmounted(() => { reset(); unsubscribe() })
</script>

<template>
  <p class="section-kicker">SHOPPING CART</p><h1 class="page-title">购物车</h1>
  <p class="page-description">调整数量并选择要结算的商品。</p>
  <p v-if="error || cart.error" class="form-error" role="alert">{{ error || cart.error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <p v-if="busy" role="status">正在加载购物车…</p>
  <template v-else>
    <p v-if="!items.length && !error" class="empty-state">购物车还是空的，<RouterLink class="text-link" to="/products">去选购商品</RouterLink>。</p>
    <article v-for="item in items" :key="item.productId" class="market-card cart-row">
      <ElCheckbox :model-value="selected.includes(item.productId)" :aria-label="`选择${item.name}`"
        :disabled="blocked || item.status !== 'ON_SALE' || item.stock < (quantities[item.productId] ?? item.quantity)"
        @change="checked => selected = checked ? [...selected, item.productId] : selected.filter(id => id !== item.productId)" />
      <div class="cart-item-info"><h2>{{ item.name }}</h2><p class="muted">¥{{ Number(item.price).toFixed(2) }} · {{ item.status !== 'ON_SALE' ? '已下架' : item.stock < item.quantity ? '当前数量不可购买' : '在售' }}</p></div>
      <ElInputNumber :model-value="quantities[item.productId]" :min="1" :max="2147483647" :step="1"
        :aria-label="item.name + '数量'" :disabled="blocked || item.status !== 'ON_SALE'" @change="changeQuantity(item, $event)" />
      <div class="business-actions">
        <span v-if="savingProductId === item.productId" class="muted" role="status">正在同步…</span>
        <el-button :disabled="blocked" @click="remove(item)">移除</el-button>
      </div>
    </article>
    <section v-if="items.length" class="market-card checkout-summary">
      <template v-if="addressList.length">
        <label for="cart-address">收货地址</label>
        <el-select id="cart-address" v-model="addressId" aria-label="收货地址" :disabled="blocked">
          <el-option v-for="item in addressList" :key="item.id" :value="String(item.id)"
            :label="`${item.defaultAddress ? '默认 · ' : ''}${item.recipientName} ${item.province} ${item.city} ${item.district} ${item.detailAddress}`" />
        </el-select>
      </template>
      <el-button :disabled="blocked || addressDialog" @click="addressDialog = true">+ 新增地址</el-button>
      <p>已选 {{ selected.length }} 种商品 · 预估 ¥{{ estimate.toFixed(2) }}</p>
      <el-button type="primary" :loading="submitting || checkout.busy" :disabled="blocked || addressDialog || !selected.length || !addressId" @click="submit">提交购物车订单</el-button>
    </section>
  </template>
  <AddressFormDialog v-model="addressDialog" @saved="selectNewAddress" />
</template>
