<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth.ts'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

watch(() => auth.status, status => {
  if (status === 'anonymous' && route.meta.requiresAuth) void router.replace({ name: 'login' })
  if (status === 'authenticated') {
    if (route.name === 'login' || route.name === 'register') void router.replace({ name: auth.isAdmin ? 'admin' : 'products' })
    else if (route.meta.requiresAdmin && !auth.isAdmin) void router.replace({ name: 'forbidden' })
  }
})

const reconnectDelays = [1000, 3000, 7000]
let reconnectAttempts = 0
watch(() => auth.status, (status, _, onCleanup) => {
  if (status === 'authenticated' || status === 'anonymous') reconnectAttempts = 0
  if (status !== 'failed' || reconnectAttempts >= reconnectDelays.length) return
  const timer = window.setTimeout(() => {
    reconnectAttempts++
    void auth.restore(true)
  }, reconnectDelays[reconnectAttempts])
  onCleanup(() => window.clearTimeout(timer))
}, { immediate: true })

function refreshIdentity() {
  if (!auth.busy && document.visibilityState === 'visible') void auth.restore(true)
}
onMounted(() => {
  window.addEventListener('focus', refreshIdentity)
  window.addEventListener('online', refreshIdentity)
})
onUnmounted(() => {
  window.removeEventListener('focus', refreshIdentity)
  window.removeEventListener('online', refreshIdentity)
})
</script>

<template>
  <RouterView />
</template>
