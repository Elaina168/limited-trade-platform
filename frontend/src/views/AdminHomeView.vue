<script setup lang="ts">
import { useAuthStore } from '../stores/auth.ts'
const auth = useAuthStore()
</script>

<template>
  <section>
    <p class="section-kicker">管理空间</p>
    <h1 class="page-title">管理中心</h1>
    <p class="page-description">当前以管理员身份访问。</p>
    <div class="account-card">
      <div class="card-heading"><h2>管理账号</h2><span class="role-badge">ADMIN</span></div>
      <dl class="account-details">
        <div><dt>管理员</dt><dd>{{ auth.user?.username }}</dd></div>
        <div><dt>用户编号</dt><dd>{{ auth.user?.id }}</dd></div>
        <div><dt>角色</dt><dd>管理员</dd></div>
      </dl>
      <div class="card-action"><RouterLink class="text-link" to="/">查看账号信息 <span aria-hidden="true">↗</span></RouterLink></div>
    </div>
    <div class="market-grid admin-grid">
      <RouterLink class="market-card" to="/admin/categories"><h2>分类管理 →</h2><p class="muted">新增与重命名分类</p></RouterLink>
      <RouterLink class="market-card" to="/admin/products"><h2>商品管理 →</h2><p class="muted">商品资料与上下架</p></RouterLink>
      <RouterLink class="market-card" to="/admin/activities"><h2>活动管理 →</h2><p class="muted">创建限时限量活动</p></RouterLink>
      <RouterLink class="market-card" to="/admin/coupons"><h2>优惠券管理 →</h2><p class="muted">创建和查看活动券</p></RouterLink>
      <RouterLink class="market-card" to="/admin/orders"><h2>订单管理 →</h2><p class="muted">查询与登记发货</p></RouterLink>
    </div>
  </section>
</template>
