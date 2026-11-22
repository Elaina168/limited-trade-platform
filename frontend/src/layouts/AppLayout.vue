<script setup lang="ts">
import { ref } from 'vue'
import PendingOrderBanner from '../components/PendingOrderBanner.vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'

const auth = useAuthStore()
const route = useRoute()
const menuOpen = ref(false)
</script>

<template>
  <div class="workspace" @keydown.esc="menuOpen = false">
    <aside class="sidebar" :class="{ 'is-open': menuOpen }">
      <RouterLink class="wordmark" to="/" @click="menuOpen = false"><span class="brand-symbol">集</span> 限量集市</RouterLink>
      <span class="sidebar-label">我的集市</span>
      <nav aria-label="主导航" @click="menuOpen = false">
        <RouterLink to="/products" active-class="is-active"><span class="nav-dot"></span>商品浏览</RouterLink>
        <RouterLink to="/activities" active-class="is-active"><span class="nav-dot"></span>限量活动</RouterLink>
        <RouterLink v-if="auth.user" to="/" exact-active-class="is-active"><span class="nav-dot"></span>账号概览</RouterLink>
        <RouterLink v-if="auth.user" to="/agent" active-class="is-active"><span class="nav-dot"></span>交易助手</RouterLink>
        <RouterLink v-if="auth.user" to="/orders" active-class="is-active"><span class="nav-dot"></span>我的订单</RouterLink>
        <RouterLink v-if="auth.user" to="/cart" active-class="is-active"><span class="nav-dot"></span>购物车</RouterLink>
        <RouterLink v-if="auth.user" to="/addresses" active-class="is-active"><span class="nav-dot"></span>收货地址</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin" exact-active-class="is-active"><span class="nav-dot"></span>管理中心</RouterLink>
        <template v-if="auth.isAdmin">
          <RouterLink to="/admin/products" active-class="is-active"><span class="nav-dot"></span>商品管理</RouterLink>
          <RouterLink to="/admin/categories" active-class="is-active"><span class="nav-dot"></span>分类管理</RouterLink>
          <RouterLink to="/admin/activities" active-class="is-active"><span class="nav-dot"></span>活动管理</RouterLink>
          <RouterLink to="/admin/coupons" active-class="is-active"><span class="nav-dot"></span>优惠券管理</RouterLink>
          <RouterLink to="/admin/orders" active-class="is-active"><span class="nav-dot"></span>订单管理</RouterLink>
        </template>
      </nav>
      <div class="sidebar-bottom"><span class="status-dot"></span> {{ auth.isAdmin ? '管理员空间' : auth.user ? '个人空间' : '访客空间' }}</div>
    </aside>
    <button v-if="menuOpen" class="menu-scrim" aria-label="关闭导航" @click="menuOpen = false"></button>
    <div class="workspace-main">
      <header class="topbar">
        <button class="menu-toggle" @click="menuOpen = !menuOpen" :aria-expanded="menuOpen" aria-label="切换导航">☰</button>
        <span class="breadcrumb">我的集市 <span>/</span> {{ route.meta.title }}</span>
        <div v-if="auth.user" class="account-actions">
          <span class="avatar">{{ auth.user?.username.slice(0, 1).toUpperCase() }}</span>
          <span class="account-name">{{ auth.user?.username }}</span>
          <el-button text :loading="auth.busy" @click="auth.logout()">退出登录</el-button>
        </div>
        <div v-else class="account-actions"><RouterLink class="text-link" to="/login">登录</RouterLink><RouterLink class="text-link" to="/register">注册</RouterLink></div>
      </header>
      <main id="main" class="page-content">
        <el-alert v-if="auth.error" :title="auth.error" type="error" :closable="false" show-icon />
        <div v-if="auth.status === 'failed'" class="connection-inline" role="alert">
          <p>当前身份尚未确认，请重新连接。</p>
          <el-button :loading="auth.busy" @click="auth.restore(true)">重新连接</el-button>
        </div>
        <template v-else-if="!route.meta.requiresAuth || auth.status === 'authenticated' || auth.status === 'loading' && auth.user !== null">
          <PendingOrderBanner v-if="auth.user" />
          <RouterView />
        </template>
        <p v-else class="loading-copy" role="status">正在确认登录状态…</p>
      </main>
      <footer class="workspace-footer">限量集市 <span>·</span> 认真选择，安心拥有。</footer>
    </div>
  </div>
</template>
