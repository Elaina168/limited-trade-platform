<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { useRouter } from 'vue-router'
import { useCheckoutStore } from '../stores/checkout.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
const checkout = useCheckoutStore()
const auth = useAuthStore()
const router = useRouter()
const error = ref('')
watch(() => auth.user?.id, () => { error.value = '' })
async function retry() {
  if (auth.busy) return
  error.value = ''
  try {
    const order = await checkout.retry()
    await router.push(`/orders/${order.id}`)
  } catch (cause) {
    if (!(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  }
}
</script>
<template>
  <section v-if="checkout.pending || error" class="pending-purchase" aria-label="待确认购买">
    <template v-if="checkout.pending">
    <strong>上一笔下单结果尚未确认</strong>
    <p>请使用原参数确认结果。刷新页面也会保留原购买标识，确认前不要重复购买。</p>
    <p class="muted">购买标识：{{ checkout.pending.requestId }}</p>
    </template>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <el-button v-if="checkout.pending" :disabled="auth.busy" :loading="checkout.busy" type="primary" @click="retry">使用原参数确认结果</el-button>
  </section>
</template>
