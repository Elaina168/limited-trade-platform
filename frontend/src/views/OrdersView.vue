<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { listOrders } from '../api/orders.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import { orderStatusLabels } from '../utils/orders.ts'
import type { OrderPage, OrderStatus } from '../types/checkout.ts'

const auth = useAuthStore()
const checkout = useCheckoutStore()
const status = ref<OrderStatus | ''>('')
const appliedStatus = ref<OrderStatus | ''>('')
const page = ref(1)
const result = ref<OrderPage | null>(null)
const busy = ref(false)
const error = ref('')
let sequence = 0
async function load() {
  const current = ++sequence
  busy.value = true; error.value = ''; result.value = null
  try {
    const data = await listOrders(page.value, appliedStatus.value)
    if (current !== sequence) return
    result.value = data
    for (const order of data.items) checkout.confirmKnownOrder(order)
  } catch (cause) { if (current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
  finally { if (current === sequence) busy.value = false }
}
function search() { appliedStatus.value = status.value; page.value = 1; void load() }
function changePage(delta: number) { page.value += delta; void load() }
watch(() => auth.user?.id, () => {
  sequence++; result.value = null; page.value = 1; status.value = ''; appliedStatus.value = ''; error.value = ''
}, { flush: 'sync' })
watch(() => auth.status, value => { if (value === 'authenticated') void load() }, { immediate: true })
onUnmounted(() => { sequence++ })
</script>

<template>
  <p class="section-kicker">MY ORDERS</p><h1 class="page-title">我的订单</h1>
  <p class="page-description">查看本人订单与成交信息，进入详情进行模拟支付、取消或确认收货。</p>
  <form class="filter-bar" @submit.prevent="search">
    <select v-model="status" aria-label="订单状态"><option value="">全部状态</option><option v-for="(label, value) in orderStatusLabels" :key="value" :value="value">{{ label }}</option></select>
    <el-button type="primary" native-type="submit" :disabled="auth.busy">筛选订单</el-button>
    <el-button :disabled="auth.busy" @click="load">刷新订单列表</el-button>
  </form>
  <p v-if="busy" role="status">正在加载本人订单…</p>
  <div v-else-if="error" role="alert"><p class="form-error">{{ error }}</p><el-button @click="load">重试</el-button></div>
  <template v-else-if="result">
    <p class="muted">共 {{ result.total }} 笔订单</p>
    <p v-if="!result.items.length" class="empty-state">没有符合条件的订单。</p>
    <article v-for="order in result.items" :key="order.id" class="market-card order-list-card">
      <div class="section-heading"><h2>订单 #{{ order.id }}</h2><span class="role-badge">{{ orderStatusLabels[order.status] }}</span></div>
      <p class="muted">创建于 {{ order.createdAt.replace('T', ' ') }}</p>
      <p v-for="item in order.items" :key="item.productId">{{ item.productName }} × {{ item.quantity }}</p>
      <div class="order-list-bottom"><span class="price">¥{{ Number(order.totalAmount).toFixed(2) }}</span><RouterLink class="button-link" :to="`/orders/${order.id}`">查看订单</RouterLink></div>
    </article>
    <nav class="pagination" aria-label="订单分页">
      <el-button :disabled="page <= 1" @click="changePage(-1)">上一页</el-button>
      <span>第 {{ page }} 页 / 共 {{ Math.max(1, Math.ceil(result.total / result.size)) }} 页</span>
      <el-button :disabled="page * result.size >= result.total" @click="changePage(1)">下一页</el-button>
    </nav>
  </template>
</template>
