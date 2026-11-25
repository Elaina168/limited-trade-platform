<script setup lang="ts">
import { reactive } from 'vue'
import { activities } from '../api/catalog.ts'
import { adminProducts, createActivity } from '../api/admin.ts'
import { useAdminPage } from '../composables/useAdminPage.ts'
import { validQuantity } from '../utils/admin.ts'
const form = reactive({ productId: '', name: '', totalQuantity: 1, startsAt: '', endsAt: '' })
function resetDraft() { Object.assign(form, { productId: '', name: '', totalQuantity: 1, startsAt: '', endsAt: '' }) }
const { data, busy, saving, blocked, error, notice, load, mutate } = useAdminPage(async () => {
  const [list, products] = await Promise.all([activities(), adminProducts()])
  return { activities: list, products }
}, resetDraft)
function save() {
  const name = form.name.trim()
  if (!form.productId || !name || name.length > 100 || !validQuantity(form.totalQuantity)
      || !Number.isFinite(new Date(form.startsAt).getTime()) || !Number.isFinite(new Date(form.endsAt).getTime())
      || new Date(form.startsAt) >= new Date(form.endsAt)) {
    error.value = '请选择商品，填写1至100字活动名称、正整数名额和有效的起止时间。'; return
  }
  const payload = { productId: Number(form.productId), name, totalQuantity: form.totalQuantity, startsAt: form.startsAt, endsAt: form.endsAt }
  void mutate(() => createActivity(payload), '活动已创建。', resetDraft)
}
</script>
<template>
  <h1 class="page-title">活动管理</h1><p class="page-description">为已有商品创建限时限量活动。活动时间按北京时间填写。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <el-button :loading="busy" :disabled="saving" @click="load">刷新活动</el-button>
  <section class="market-card address-editor"><h2>新增活动</h2><form @submit.prevent="save" novalidate>
    <fieldset class="business-fields" :disabled="blocked">
      <div class="field"><label for="activity-product">活动商品</label><select id="activity-product" v-model="form.productId"><option value="">请选择商品</option><option v-for="item in data?.products" :key="item.id" :value="String(item.id)">{{ item.name }}{{ item.status === 'ON_SALE' ? '' : '（已下架）' }}</option></select></div>
      <div class="field"><label for="activity-name">活动名称</label><input id="activity-name" v-model="form.name" maxlength="100" /></div>
      <div class="field"><label for="activity-quantity">活动名额</label><input id="activity-quantity" v-model.number="form.totalQuantity" type="number" min="1" max="2147483647" step="1" /></div>
      <div class="field"><label for="activity-start">活动开始时间</label><input id="activity-start" v-model="form.startsAt" type="datetime-local" step="1" /></div>
      <div class="field"><label for="activity-end">活动结束时间</label><input id="activity-end" v-model="form.endsAt" type="datetime-local" step="1" /></div>
    </fieldset><el-button type="primary" native-type="submit" :disabled="blocked" :loading="saving">创建活动</el-button>
  </form></section>
  <p v-if="busy && !data" role="status">正在加载活动…</p><p v-else-if="data && !data.activities.length" class="empty-state">暂无活动。</p>
  <div class="market-grid admin-grid"><article v-for="item in data?.activities" :key="item.id" class="market-card"><h2>{{ item.name }}</h2><p>剩余名额 {{ item.remainingQuantity }} / {{ item.totalQuantity }}</p><p class="muted">活动 #{{ item.id }} · 商品 #{{ item.productId }}<br />{{ item.startsAt.replace('T', ' ') }} 至 {{ item.endsAt.replace('T', ' ') }}</p><RouterLink class="text-link" to="/admin/coupons">管理活动优惠券 →</RouterLink></article></div>
</template>
