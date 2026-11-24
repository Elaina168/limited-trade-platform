<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminOrder, shipOrder } from '../api/admin.ts'
import { RequestError } from '../api/http.ts'
import { useAdminPage } from '../composables/useAdminPage.ts'
import { orderStatusLabels } from '../utils/orders.ts'
const route = useRoute()
const form = reactive({ carrier: '', trackingNo: '' })
function resetDraft() { form.carrier = ''; form.trackingNo = '' }
const { data, busy, saving, blocked, error, notice, load, mutate, reset } = useAdminPage(() => {
  const id = Number(route.params.id)
  if (!Number.isSafeInteger(id) || id <= 0) throw new RequestError('订单编号无效。')
  return adminOrder(id)
}, resetDraft)
const canShip = computed(() => data.value?.status === 'PAID' && data.value.address !== null)
function save() {
  if (!data.value || !canShip.value || blocked.value) return
  const payload = { carrier: form.carrier.trim(), trackingNo: form.trackingNo.trim() }
  if (!payload.carrier || payload.carrier.length > 50 || !payload.trackingNo || payload.trackingNo.length > 64) { error.value = '请填写承运方（最多50字）和运单号（最多64字）。'; return }
  if (!window.confirm(`确认登记发货？承运方：${payload.carrier}，运单号：${payload.trackingNo}`)) return
  const id = data.value.id
  void mutate(() => shipOrder(id, payload), '发货已登记。', resetDraft, order =>
    order.id === id && order.shipment?.carrier === payload.carrier && order.shipment.trackingNo === payload.trackingNo)
}
watch(() => route.params.id, () => { reset(); void load() }, { flush: 'sync' })
</script>
<template>
  <RouterLink class="text-link" to="/admin/orders">← 返回订单管理</RouterLink><h1 class="page-title">管理订单详情</h1>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <el-button :loading="busy" :disabled="saving" @click="load">刷新管理详情</el-button>
  <p v-if="busy && !data" role="status">正在读取订单…</p>
  <template v-if="data">
    <section class="market-card order-result"><div class="section-heading"><h2>订单 #{{ data.id }}</h2><span class="role-badge">{{ orderStatusLabels[data.status] }}</span></div><p class="price">应付 ¥{{ Number(data.totalAmount).toFixed(2) }}</p><p>原价 ¥{{ Number(data.originalAmount).toFixed(2) }} · 优惠 ¥{{ Number(data.discountAmount).toFixed(2) }}</p></section>
    <section class="market-card order-result"><h2>成交商品</h2><ul class="order-items"><li v-for="item in data.items" :key="item.productId">{{ item.productName }} × {{ item.quantity }} · 单价 ¥{{ Number(item.unitPrice).toFixed(2) }} · 小计 ¥{{ Number(item.lineAmount).toFixed(2) }}</li></ul></section>
    <section class="market-card order-result"><h2>收货地址</h2><p v-if="data.address">{{ data.address.recipientName }} {{ data.address.phone }}<br />{{ data.address.province }} {{ data.address.city }} {{ data.address.district }} {{ data.address.detailAddress }}</p><p v-else class="muted">本订单没有地址快照，不能登记发货。</p></section>
    <section v-if="data.coupon" class="market-card order-result"><h2>成交优惠</h2><p>{{ data.coupon.name }} · 满{{ data.coupon.thresholdAmount }}减{{ data.coupon.discountAmount }}</p></section>
    <section v-if="canShip" class="market-card order-result"><h2>登记发货</h2><p class="muted">核对收货信息后人工填写物流，不会向外部物流公司下单。</p><form @submit.prevent="save" novalidate><fieldset class="business-fields" :disabled="blocked"><div class="field"><label for="ship-carrier">承运方</label><input id="ship-carrier" v-model="form.carrier" maxlength="50" /></div><div class="field"><label for="ship-tracking">运单号</label><input id="ship-tracking" v-model="form.trackingNo" maxlength="64" /></div></fieldset><el-button type="primary" native-type="submit" :disabled="blocked" :loading="saving">登记发货</el-button></form></section>
    <section v-if="data.shipment" class="market-card order-result"><h2>已登记物流</h2><p>承运方：{{ data.shipment.carrier }}</p><p>运单号：{{ data.shipment.trackingNo }}</p><p class="muted">发货 {{ data.shipment.shippedAt.replace('T', ' ') }}<template v-if="data.shipment.receivedAt"><br />收货 {{ data.shipment.receivedAt.replace('T', ' ') }}</template></p></section>
    <section class="market-card order-result"><h2>订单时间</h2><p class="muted">创建 {{ data.createdAt.replace('T', ' ') }}<br />支付截止 {{ data.expiresAt.replace('T', ' ') }}<template v-if="data.paidAt"><br />支付 {{ data.paidAt.replace('T', ' ') }}</template><template v-if="data.endedAt"><br />结束 {{ data.endedAt.replace('T', ' ') }}</template></p></section>
  </template>
</template>
