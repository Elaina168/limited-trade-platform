<script setup lang="ts">
import { ElDrawer } from 'element-plus'
import 'element-plus/es/components/drawer/style/css'
import PurchaseForm from './PurchaseForm.vue'

const open = defineModel<boolean>({ required: true })
defineProps<{ productId: number; activityId?: number; quantity?: number }>()
</script>

<template>
  <ElDrawer v-model="open" :title="activityId ? '参加活动下单' : '立即购买'" size="min(100%, 520px)" destroy-on-close>
    <PurchaseForm v-if="open" :product-id="productId" :activity-id="activityId" :initial-quantity="quantity" />
  </ElDrawer>
</template>
