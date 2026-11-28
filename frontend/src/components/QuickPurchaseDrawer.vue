<script setup lang="ts">
import { ElDrawer } from 'element-plus'
import { useRoute } from 'vue-router'
import 'element-plus/es/components/drawer/style/css'
import PurchaseForm from './PurchaseForm.vue'
import { useCheckoutStore } from '../stores/checkout.ts'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ productId: number; activityId?: number; quantity?: number }>()
const route = useRoute()
const checkout = useCheckoutStore()
function closeDrawer(done: () => void) {
  if (props.activityId !== undefined) checkout.leaveSeckillPurchase(route.fullPath)
  done()
}
</script>

<template>
  <ElDrawer v-model="open" :title="activityId ? '参加活动下单' : '立即购买'" :before-close="closeDrawer" size="min(100%, 520px)" destroy-on-close>
    <PurchaseForm v-if="open" :product-id="productId" :activity-id="activityId" :initial-quantity="quantity" />
  </ElDrawer>
</template>
