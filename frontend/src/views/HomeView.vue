<script setup lang="ts">
import { useAuthStore } from '../stores/auth.ts'
const auth = useAuthStore()
</script>

<template>
  <section>
    <p class="section-kicker">我的账号</p>
    <h1 class="page-title">你好，{{ auth.user?.username }}</h1>
    <p class="page-description">在这里查看你的账号与当前登录信息。</p>
    <div class="account-card">
      <div class="card-heading"><h2>账号信息</h2><span class="connection-badge"><span class="status-dot"></span>已登录</span></div>
      <dl class="account-details">
        <div><dt>用户名</dt><dd>{{ auth.user?.username }}</dd></div>
        <div><dt>用户编号</dt><dd>{{ auth.user?.id }}</dd></div>
        <div><dt>账号角色</dt><dd>{{ auth.isAdmin ? '管理员' : '普通用户' }}</dd></div>
      </dl>
      <div v-if="auth.isAdmin" class="card-action"><RouterLink class="text-link" to="/admin">进入管理中心 <span aria-hidden="true">↗</span></RouterLink></div>
    </div>
  </section>
</template>
