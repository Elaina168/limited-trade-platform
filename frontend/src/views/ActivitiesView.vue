<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { activities } from '../api/catalog.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import { activityStatus, formatActivityTime } from '../utils/activity-time.ts'
import type { Activity } from '../types/catalog.ts'
const auth = useAuthStore()
const list = ref<Activity[]>([])
const busy = ref(false)
const error = ref('')
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
let sequence = 0
async function load() {
  const current = ++sequence
  busy.value = true
  error.value = ''
  try { const data = await activities(); if (current === sequence) list.value = data }
  catch (cause) { if (current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
  finally { if (current === sequence) busy.value = false }
}
// 身份操作结束后，即使还是同一账号，也要补载已被作废的旧请求。
watch([() => auth.user?.id, () => auth.status], () => {
  if (auth.status === 'authenticated' || auth.status === 'anonymous') void load()
}, { immediate: true })
onUnmounted(() => { sequence++; window.clearInterval(timer) })
</script>

<template>
  <p class="section-kicker">LIMITED EVENTS</p><h1 class="page-title">限量活动</h1>
  <p class="page-description">活动时间均为北京时间。</p>
  <p v-if="busy" role="status">正在加载活动…</p>
  <div v-else-if="error" role="alert"><p class="form-error">{{ error }}</p><el-button @click="load">重试</el-button></div>
  <template v-else>
    <p v-if="!list.length" class="empty-state">暂时没有活动。</p>
    <div class="market-grid">
      <article v-for="item in list" :key="item.id" class="market-card">
        <span class="role-badge">{{ activityStatus(item, now) }}</span><h2>{{ item.name }}</h2>
        <p class="muted">开始 {{ formatActivityTime(item.startsAt) }}</p><p class="muted">结束 {{ formatActivityTime(item.endsAt) }}</p>
        <p>剩余名额 {{ item.remainingQuantity }}</p>
        <RouterLink class="text-link" :to="`/activities/${item.id}`">查看活动详情 →</RouterLink>
      </article>
    </div>
  </template>
</template>
