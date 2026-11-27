<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { activities, activityCoupons } from '../api/catalog.ts'
import { createCoupon } from '../api/admin.ts'
import { useAdminPage } from '../composables/useAdminPage.ts'
import { validMoney, validQuantity, validCouponWindow } from '../utils/admin.ts'
const activityId = ref('')
const form = reactive({ name: '', threshold: '', discount: '', totalQuantity: 1, claimStartsAt: '', claimEndsAt: '', useStartsAt: '', useEndsAt: '' })
function resetDraft() { Object.assign(form, { name: '', threshold: '', discount: '', totalQuantity: 1, claimStartsAt: '', claimEndsAt: '', useStartsAt: '', useEndsAt: '' }) }
function resetScope() { activityId.value = ''; resetDraft() }
const { data, busy, saving, blocked, error, notice, load, mutate } = useAdminPage(async () => {
  const [list, coupons] = await Promise.all([activities(), activityId.value ? activityCoupons(Number(activityId.value)) : Promise.resolve([])])
  return { activities: list, coupons }
}, resetScope)
const selected = computed(() => data.value?.activities.find(item => String(item.id) === activityId.value))
watch(activityId, () => { resetDraft(); void load() })
function save() {
  const name = form.name.trim()
  if (!selected.value || !name || name.length > 100 || !validMoney(form.threshold) || !validMoney(form.discount)
      || Number(form.discount) >= Number(form.threshold) || !validQuantity(form.totalQuantity)) {
    error.value = '请选择活动，填写1至100字券名称、正整数数量，金额最多8位整数2位小数，优惠须小于门槛。'; return
  }
  const payload = { activityId: Number(activityId.value), name, thresholdAmount: Number(form.threshold), discountAmount: Number(form.discount), totalQuantity: form.totalQuantity,
    claimStartsAt: form.claimStartsAt, claimEndsAt: form.claimEndsAt, useStartsAt: form.useStartsAt, useEndsAt: form.useEndsAt }
  if (!validCouponWindow(payload, selected.value)) {
    error.value = '领取、使用结束须晚于各自开始；领取结束不晚于使用结束；使用区间须在活动时间内。'; return
  }
  void mutate(() => createCoupon(payload), '优惠券已创建。', resetDraft)
}
</script>
<template>
  <h1 class="page-title">优惠券管理</h1><p class="page-description">选择活动，查看已发布的规则并创建新的满减券。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <div class="field"><label for="coupon-activity">所属活动</label><el-select id="coupon-activity" v-model="activityId" aria-label="所属活动" placeholder="请选择活动" class="admin-input" :disabled="saving || blocked" filterable><el-option v-for="item in data?.activities" :key="item.id" :value="String(item.id)" :label="`${item.name} (#${item.id})`" /></el-select></div>
  <el-button :loading="busy" :disabled="saving" @click="load">刷新优惠券</el-button>
  <p v-if="selected" class="muted">活动时间：{{ selected.startsAt.replace('T', ' ') }} 至 {{ selected.endsAt.replace('T', ' ') }}</p>
  <section v-if="selected" class="market-card address-editor"><h2>新增优惠券</h2><form @submit.prevent="save" novalidate><fieldset class="business-fields" :disabled="blocked">
    <div class="field"><label for="coupon-name">优惠券名称</label><el-input id="coupon-name" v-model="form.name" maxlength="100" :disabled="blocked" /></div>
    <div class="field"><label for="coupon-threshold">使用门槛</label><el-input id="coupon-threshold" v-model="form.threshold" inputmode="decimal" :disabled="blocked" /></div>
    <div class="field"><label for="coupon-discount">优惠金额</label><el-input id="coupon-discount" v-model="form.discount" inputmode="decimal" :disabled="blocked" /></div>
    <div class="field"><label for="coupon-quantity">发放数量</label><el-input-number id="coupon-quantity" v-model="form.totalQuantity" aria-label="发放数量" :min="1" :max="2147483647" :step="1" :disabled="blocked" /></div>
    <div class="field"><label for="claim-start">领取开始时间</label><el-date-picker id="claim-start" v-model="form.claimStartsAt" aria-label="领取开始时间" type="datetime" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="blocked" /></div>
    <div class="field"><label for="claim-end">领取结束时间</label><el-date-picker id="claim-end" v-model="form.claimEndsAt" aria-label="领取结束时间" type="datetime" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="blocked" /></div>
    <div class="field"><label for="use-start">使用开始时间</label><el-date-picker id="use-start" v-model="form.useStartsAt" aria-label="使用开始时间" type="datetime" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="blocked" /></div>
    <div class="field"><label for="use-end">使用结束时间</label><el-date-picker id="use-end" v-model="form.useEndsAt" aria-label="使用结束时间" type="datetime" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="blocked" /></div>
  </fieldset><el-button type="primary" native-type="submit" :disabled="blocked" :loading="saving">创建优惠券</el-button></form></section>
  <p v-if="busy && !data" role="status">正在加载活动与券…</p><p v-if="selected && data && !busy && !data.coupons.length" class="empty-state">本活动暂无优惠券。</p>
  <div class="market-grid admin-grid"><article v-for="item in data?.coupons" :key="item.id" class="market-card"><h2>{{ item.name }}</h2><p class="price">满{{ item.thresholdAmount }}减{{ item.discountAmount }}</p><p>剩余 {{ item.remainingQuantity }} / {{ item.totalQuantity }} 张</p><p class="muted">领取 {{ item.claimStartsAt.replace('T', ' ') }} 至 {{ item.claimEndsAt.replace('T', ' ') }}<br />使用 {{ item.useStartsAt.replace('T', ' ') }} 至 {{ item.useEndsAt.replace('T', ' ') }}</p></article></div>
</template>
