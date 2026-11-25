<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { postJson, errorMessage, HttpError } from '../api/http.ts'
import type { CurrentUser } from '../types/auth.ts'

const router = useRouter()
const username = ref('')
const password = ref('')
const confirmation = ref('')
const busy = ref(false)
const error = ref('')
async function submit() {
  if (busy.value) return
  error.value = ''
  const name = username.value.trim()
  if (!/^[A-Za-z0-9_]{3,50}$/.test(name)) { error.value = '用户名为3至50位字母、数字或下划线。'; return }
  if (!password.value.trim() || password.value.length < 8) {
    error.value = '密码至少8个字符，不能全是空白。'; return
  }
  if (new TextEncoder().encode(password.value).length > 72) {
    error.value = '密码太长，请缩短后重试。'; return
  }
  if (password.value !== confirmation.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    await postJson<CurrentUser>('/api/auth/register', { username: name, password: password.value })
    await router.replace({ name: 'login' })
    ElMessage.success('注册成功，请使用新账号登录。')
  } catch (cause) {
    error.value = cause instanceof HttpError && cause.status === 409
      ? '用户名已存在，请换一个用户名。' : errorMessage(cause)
  } finally { busy.value = false; password.value = ''; confirmation.value = '' }
}
</script>

<template>
  <main class="standalone-message">
    <RouterLink class="wordmark" to="/products"><span class="brand-symbol">集</span> 限量集市</RouterLink>
    <section class="register-card">
      <h1>创建集市账号</h1>
      <form @submit.prevent="submit" novalidate>
        <div class="field"><label for="register-username">用户名</label><el-input id="register-username" v-model="username" autocomplete="username" :disabled="busy" placeholder="3至50位字母、数字或下划线" /></div>
        <div class="field"><label for="register-password">密码</label><el-input id="register-password" v-model="password" type="password" autocomplete="new-password" :disabled="busy" show-password /></div>
        <div class="field"><label for="register-confirmation">确认密码</label><el-input id="register-confirmation" v-model="confirmation" type="password" autocomplete="new-password" :disabled="busy" /></div>
        <p class="muted">密码至少8个字符。</p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <el-button type="primary" native-type="submit" :loading="busy">注册</el-button>
        <RouterLink class="text-link" to="/login">已有账号，前往登录</RouterLink>
      </form>
    </section>
  </main>
</template>
