<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { ElPopconfirm, ElTabPane, ElTabs } from 'element-plus'
import 'element-plus/es/components/popconfirm/style/css'
import 'element-plus/es/components/tabs/style/css'
import 'element-plus/es/components/tab-pane/style/css'
import { useAuthStore } from '../stores/auth.ts'
import { useCheckoutStore } from '../stores/checkout.ts'
import { listOrders, submitOrderAction } from '../api/orders.ts'
import { errorMessage, onSessionChange, SessionChangedError, sessionRevision } from '../api/http.ts'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import { availableOrderActions, orderActionLabels, orderPaymentCountdown, orderStatusLabels } from '../utils/orders.ts'
import type { Order, OrderAction, OrderPage } from '../types/checkout.ts'

const tabs = [
  { name: 'ALL', label: '全部', filter: '' },
  { name: 'PENDING_PAYMENT', label: '待支付', filter: 'PENDING_PAYMENT' },
  { name: 'PAID', label: '已支付', filter: 'PAID' },
  { name: 'SHIPPED', label: '已发货', filter: 'SHIPPED' },
  { name: 'COMPLETED', label: '已完成', filter: 'COMPLETED' },
  { name: 'CANCELED_OR_CLOSED', label: '已取消或关闭', filter: 'CANCELED_OR_CLOSED' },
] as const

const auth = useAuthStore()
const checkout = useCheckoutStore()
const tab = ref<(typeof tabs)[number]>(tabs[0])
const page = ref(1)
const result = ref<OrderPage | null>(null)
const reading = ref(false)
const acting = ref<{ id: number; action: OrderAction } | null>(null)
const uncertain = ref(new Set<number>())
const error = ref('')
const notice = ref('')
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
let sequence = 0
let operation = 0
let nextExpiryRead = 0

function reset() {
  sequence++; operation++; nextExpiryRead = 0
  page.value = 1; tab.value = tabs[0]; result.value = null; reading.value = false; acting.value = null
  uncertain.value.clear(); error.value = ''; notice.value = ''
}

async function load() {
  if (reading.value || acting.value || auth.status !== 'authenticated') return
  const current = ++sequence
  const revision = sessionRevision()
  const isCurrent = () => current === sequence && revision === sessionRevision()
  reading.value = true
  try {
    const data = await listOrders(page.value, tab.value.filter)
    if (!isCurrent()) return
    const last = Math.max(1, Math.ceil(data.total / data.size))
    if (page.value > last) {
      // 后台关单或取消使末页消失时，回到最后一个仍存在的页面。
      page.value = last
      reading.value = false
      void load()
      return
    }
    result.value = data
    error.value = ''
    for (const order of data.items) {
      uncertain.value.delete(order.id)
      checkout.confirmKnownOrder(order)
    }
  } catch (cause) {
    if (isCurrent() && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally { if (isCurrent()) reading.value = false }
}

function query(nextPage: number) {
  sequence++; reading.value = false
  page.value = nextPage; result.value = null; error.value = ''; notice.value = ''
  void load()
}

function changeTab(name: string | number) {
  const next = tabs.find(entry => entry.name === name)
  if (!next || next.name === tab.value.name || acting.value) return
  tab.value = next
  nextExpiryRead = 0
  query(1)
}

function actionBlocked(order: Order) {
  return acting.value !== null || auth.status !== 'authenticated' || uncertain.value.has(order.id)
}

async function act(order: Order, action: OrderAction) {
  if (actionBlocked(order) || !result.value?.items.includes(order)
      || !availableOrderActions(order, Date.now()).includes(action)) return
  const current = ++operation
  const revision = sessionRevision()
  const isCurrent = () => current === operation && revision === sessionRevision()
  // 写入前作废旧列表请求，避免迟到读取覆盖单卡回执。
  sequence++; reading.value = false
  acting.value = { id: order.id, action }; error.value = ''; notice.value = ''
  let refill = false
  try {
    const outcome = await submitOrderAction(order.id, action, isCurrent, () => { notice.value = '正在核对订单状态…' })
    if (!outcome || !isCurrent()) return
    notice.value = outcome.notice ?? ''
    error.value = outcome.error ?? ''
    if (!outcome.snapshot) {
      if (outcome.notice) uncertain.value.add(order.id)
      return
    }
    const snapshot = outcome.snapshot
    checkout.confirmKnownOrder(snapshot)
    const filter = tab.value.filter
    const remains = !filter || snapshot.status === filter
      || filter === 'CANCELED_OR_CLOSED' && (snapshot.status === 'CANCELED' || snapshot.status === 'CLOSED')
    const data = result.value!
    result.value = remains
      ? { ...data, items: data.items.map(item => item.id === order.id ? snapshot : item) }
      : { ...data, total: data.total - 1, items: data.items.filter(item => item.id !== order.id) }
    // 离开当前筛选的订单会腾出位置，静默同步补齐本页和总数。
    refill = !remains
  } finally {
    if (isCurrent()) {
      acting.value = null
      if (refill) void load()
    }
  }
}

const stopSessionListener = onSessionChange(reset)
watch(() => auth.user?.id, reset, { flush: 'sync' })
watch([() => auth.user?.id, () => auth.status], () => {
  if (auth.status === 'authenticated' && !result.value) void load()
}, { immediate: true })
useVisibilityRefresh(() => { void load() }, () => reading.value || acting.value !== null || auth.status !== 'authenticated')
watch(now, time => {
  if (document.hidden || auth.status !== 'authenticated' || reading.value || acting.value || time < nextExpiryRead) return
  const expired = result.value?.items.filter(order => order.status === 'PENDING_PAYMENT'
    && new Date(order.expiresAt).getTime() <= time)
  if (!expired?.length) return
  // 同一页有多个待关单订单时，共用一次列表读取。
  nextExpiryRead = time + (expired.some(order => time - new Date(order.expiresAt).getTime() < 60_000) ? 5_000 : 30_000)
  void load()
})
onUnmounted(() => { sequence++; operation++; window.clearInterval(timer); stopSessionListener() })
</script>

<template>
  <h1 class="page-title">我的订单</h1>
  <ElTabs :model-value="tab.name" @tab-change="changeTab">
    <ElTabPane v-for="entry in tabs" :key="entry.name" :name="entry.name" :label="entry.label" :disabled="acting !== null">
      <template v-if="entry.name === tab.name">
        <p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p v-if="reading && !result" role="status">正在加载本人订单…</p>
        <template v-if="result">
          <p class="muted">共 {{ result.total }} 笔订单</p>
          <p v-if="!result.items.length" class="empty-state">没有符合条件的订单。</p>
          <article v-for="order in result.items" :key="order.id" class="market-card order-list-card">
            <div class="section-heading"><h2>订单 #{{ order.id }}</h2><span class="role-badge">{{ orderStatusLabels[order.status] }}</span></div>
            <p class="muted">创建于 {{ order.createdAt.replace('T', ' ') }}</p>
            <p v-for="item in order.items" :key="item.productId">{{ item.productName }} × {{ item.quantity }}</p>
            <p v-if="order.status === 'PENDING_PAYMENT'" class="operation-notice">{{ orderPaymentCountdown(order, now) }}</p>
            <div class="order-list-bottom">
              <span class="price">¥{{ Number(order.totalAmount).toFixed(2) }}</span>
              <div class="business-actions">
                <template v-for="action in availableOrderActions(order, now)" :key="action">
                  <ElPopconfirm v-if="action !== 'pay'" :disabled="actionBlocked(order)"
                    :title="action === 'cancel' ? '取消后将归还库存并释放占用的优惠券。' : '请确认已经收到商品。'"
                    :confirm-button-text="action === 'cancel' ? '确认取消订单' : '确认收到'"
                    :cancel-button-text="action === 'cancel' ? '保留订单' : '暂不确认'" @confirm="act(order, action)">
                    <template #reference>
                      <el-button :disabled="actionBlocked(order)" :loading="acting?.id === order.id && acting.action === action">{{ orderActionLabels[action] }}</el-button>
                    </template>
                  </ElPopconfirm>
                  <el-button v-else type="primary" :disabled="actionBlocked(order)" :loading="acting?.id === order.id && acting.action === action" @click="act(order, action)">立即支付</el-button>
                </template>
                <RouterLink class="button-link" :to="`/orders/${order.id}`">查看订单</RouterLink>
              </div>
            </div>
          </article>
          <nav class="pagination" aria-label="订单分页">
            <el-button :disabled="acting !== null || page <= 1" @click="query(page - 1)">上一页</el-button>
            <span>第 {{ page }} 页 / 共 {{ Math.max(1, Math.ceil(result.total / result.size)) }} 页</span>
            <el-button :disabled="acting !== null || page * result.size >= result.total" @click="query(page + 1)">下一页</el-button>
          </nav>
        </template>
      </template>
    </ElTabPane>
  </ElTabs>
</template>
