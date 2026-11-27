<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PurchaseForm from '../components/PurchaseForm.vue'

const route = useRoute()
const activityId = computed(() => route.query.activityId === undefined ? undefined : Number(route.query.activityId))
const quantity = computed(() => {
  const value = typeof route.query.quantity === 'string' ? Number(route.query.quantity) : 1
  return Number.isInteger(value) && value >= 1 && value <= 2147483647 ? value : 1
})
</script>

<template>
  <RouterLink class="text-link" :to="activityId !== undefined && activityId > 0 ? { name: 'activity-detail', params: { id: activityId } } : { name: 'product-detail', params: { productId: route.params.productId } }">← 返回详情</RouterLink>
  <h1 class="page-title">{{ activityId !== undefined && activityId > 0 ? '参加活动下单' : '选购商品' }}</h1>
  <PurchaseForm :product-id="Number(route.params.productId)" :activity-id="activityId" :initial-quantity="quantity" />
</template>
