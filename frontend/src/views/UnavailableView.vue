<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
watch(() => auth.status, status => {
  if (status !== 'authenticated' && status !== 'anonymous') return
  const target = route.query.returnTo
  const resolved = typeof target === 'string' && /^\/(?![\/\\])/.test(target) ? router.resolve(target) : null
  if (resolved?.name && resolved.name !== 'unavailable') void router.replace(resolved.fullPath)
  else void router.replace({ name: auth.user ? (auth.isAdmin ? 'admin' : 'home') : 'login' })
}, { immediate: true })
</script>

<template>
  <main class="standalone-message message-page">
    <p class="section-kicker">连接服务</p>
    <h1>暂时无法确认登录状态</h1>
    <p class="muted" role="status">{{ auth.busy ? '正在重新连接…' : '重新联网或返回页面时会自动连接。' }}</p>
  </main>
</template>
