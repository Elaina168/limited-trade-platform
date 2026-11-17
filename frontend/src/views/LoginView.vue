<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../stores/auth.ts'

const auth = useAuthStore()
const username = ref('')
const password = ref('')
const validation = ref('')

async function submit() {
  validation.value = ''
  if (!username.value.trim() || !password.value) {
    validation.value = '请填写用户名和密码。'
    return
  }
  try { await auth.login({ username: username.value.trim(), password: password.value }) }
  finally { password.value = '' }
}
</script>

<template>
  <main class="login-page">
    <section class="login-story" aria-label="限量集市">
      <a class="wordmark" href="/"><span class="brand-symbol">集</span> 限量集市</a>
      <div class="story-content">
        <p class="eyebrow">LIMITED MARKET</p>
        <h1>好物有期，<br />生活有序。</h1>
        <p class="story-description">从发现心仪好物，到查看每一笔订单。<br />你的集市，由这里开始。</p>
      </div>
      <p class="story-footer">认真选择，安心拥有。</p>
    </section>
    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-form-wrap">
        <span class="section-kicker">你的集市账号</span>
        <h2 id="login-title">欢迎回来</h2>
        <p class="muted login-intro">登录后，继续你的集市之旅。</p>
        <form @submit.prevent="submit" novalidate>
          <div class="field">
            <label for="username">用户名</label>
            <el-input id="username" v-model="username" name="username" autocomplete="username" placeholder="请输入用户名" :disabled="auth.busy" size="large" />
          </div>
          <div class="field">
            <label for="password">密码</label>
            <el-input id="password" v-model="password" name="password" type="password" autocomplete="current-password" placeholder="请输入密码" show-password :disabled="auth.busy" size="large" />
          </div>
          <p v-if="validation || auth.error" class="form-error" role="alert">{{ validation || auth.error }}</p>
          <el-button class="login-submit" type="primary" native-type="submit" :loading="auth.busy" size="large">登录</el-button>
          <el-button v-if="auth.status === 'failed'" class="identity-retry" text @click="auth.restore(true)" :disabled="auth.busy">重新读取登录状态</el-button>
        </form>
      </div>
      <p class="login-footer">限量集市 · 让每一次选择都有回响</p>
    </section>
  </main>
</template>
