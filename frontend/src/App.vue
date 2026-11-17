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
    if (route.name === 'login') void router.replace({ name: auth.isAdmin ? 'admin' : 'home' })
    else if (route.meta.requiresAdmin && !auth.isAdmin) void router.replace({ name: 'forbidden' })
  }
})

function refreshIdentity() {
  if (!auth.busy && document.visibilityState === 'visible') void auth.restore(true)
}
onMounted(() => window.addEventListener('focus', refreshIdentity))
onUnmounted(() => window.removeEventListener('focus', refreshIdentity))
</script>

<template>
  <RouterView />
</template>
