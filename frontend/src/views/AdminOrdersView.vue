<script setup lang="ts">
import { ref } from 'vue'
import { adminOrders } from '../api/admin.ts'
import { useAdminPage } from '../composables/useAdminPage.ts'
import { orderStatusLabels } from '../utils/orders.ts'
import type { OrderStatus } from '../types/checkout.ts'
const status = ref<OrderStatus | ''>('')
const userId = ref('')
const appliedStatus = ref<OrderStatus | ''>('')
const appliedUser = ref('')
const page = ref(1)
function resetDraft() { status.value = ''; userId.value = ''; appliedStatus.value = ''; appliedUser.value = ''; page.value = 1 }
const { data, busy, saving, error, notice, load } = useAdminPage(() => adminOrders(page.value, appliedStatus.value, appliedUser.value), resetDraft)
function search() {
  const value = userId.value.trim()
  if (value && (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value)))) { error.value = '用户编号须为正的安全整数。'; return }
  appliedStatus.value = status.value; appliedUser.value = value; page.value = 1; void load()
}
function changePage(delta: number) { page.value += delta; void load() }
</script>
<template>
  <h1 class="page-title">订单管理</h1><p class="page-description">按状态和用户编号查询订单，进入详情核对地址并登记发货。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <form class="filter-bar" @submit.prevent="search"><el-select v-model="status" aria-label="管理订单状态" placeholder="全部状态"><el-option label="全部状态" value="" /><el-option v-for="(label, value) in orderStatusLabels" :key="value" :label="label" :value="value" /></el-select><el-input v-model="userId" class="admin-input" aria-label="下单用户编号" placeholder="用户编号（可选）" inputmode="numeric" /><el-button type="primary" native-type="submit">查询管理订单</el-button><el-button :disabled="saving" @click="load">刷新管理订单</el-button></form>
  <p v-if="busy" role="status">正在加载管理订单…</p>
  <template v-else-if="data">
    <p class="muted">共 {{ data.total }} 笔订单</p><p v-if="!data.items.length" class="empty-state">没有符合条件的管理订单。</p>
    <article v-for="item in data.items" :key="item.id" class="market-card order-list-card"><div class="section-heading"><h2>订单 #{{ item.id }}</h2><span class="role-badge">{{ orderStatusLabels[item.status] }}</span></div><p>下单用户 #{{ item.userId }}</p><p v-for="product in item.items" :key="product.productId">{{ product.productName }} × {{ product.quantity }}</p><div class="order-list-bottom"><span class="price">¥{{ Number(item.totalAmount).toFixed(2) }}</span><RouterLink class="button-link" :to="`/admin/orders/${item.id}`">管理订单详情</RouterLink></div></article>
    <nav class="pagination" aria-label="管理订单分页"><el-button :disabled="page <= 1" @click="changePage(-1)">上一页</el-button><span>第 {{ page }} 页 / 共 {{ Math.max(1, Math.ceil(data.total / data.size)) }} 页</span><el-button :disabled="page * data.size >= data.total" @click="changePage(1)">下一页</el-button></nav>
  </template>
</template>
