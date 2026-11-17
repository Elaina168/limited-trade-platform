<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
const auth = useAuthStore()
const router = useRouter()
async function retry() {
  await auth.restore(true)
  if (auth.status !== 'failed') await router.replace({ name: auth.user ? (auth.isAdmin ? 'admin' : 'home') : 'login' })
}
</script>

<template>
  <main class="standalone-message message-page">
    <p class="section-kicker">连接服务</p>
    <h1>暂时无法确认登录状态</h1>
    <p class="muted" role="alert">{{ auth.error || '请重新连接后继续。' }}</p>
    <el-button type="primary" :loading="auth.busy" size="large" @click="retry">重新连接</el-button>
  </main>
</template>
