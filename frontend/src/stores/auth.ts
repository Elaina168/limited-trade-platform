import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AuthStatus, CurrentUser, LoginCredentials } from '../types/auth.ts'
import * as api from '../api/auth.ts'
import { advanceSession, errorMessage, HttpError, isUncertainWrite, onUnauthorized, sessionRevision } from '../api/http.ts'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const status = ref<AuthStatus>('unloaded')
  const error = ref('')
  const busy = computed(() => status.value === 'loading')
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  let pending: Promise<void> | null = null

  onUnauthorized(() => {
    advanceSession()
    user.value = null
    status.value = 'anonymous'
    error.value = '登录已过期，请重新登录。'
  })

  function restore(force = false, notice = ''): Promise<void> {
    if (pending) return pending
    if (!force && status.value !== 'unloaded') return Promise.resolve()
    const expected = sessionRevision()
    status.value = 'loading'
    error.value = notice
    const task = api.currentUser(expected).then(current => {
      if (expected !== sessionRevision()) return
      if (user.value && (user.value.id !== current.id || user.value.role !== current.role)) advanceSession()
      user.value = current
      status.value = 'authenticated'
    }).catch(cause => {
      if (expected !== sessionRevision()) return
      const hadUser = user.value !== null
      user.value = null
      if (cause instanceof HttpError && cause.status === 401) {
        if (hadUser) advanceSession()
        status.value = 'anonymous'
      } else {
        error.value = errorMessage(cause)
        status.value = 'failed'
      }
    }).finally(() => {
      if (pending === task) pending = null
    })
    pending = task
    return task
  }

  async function login(credentials: LoginCredentials): Promise<void> {
    const expected = advanceSession()
    pending = null
    user.value = null
    status.value = 'loading'
    error.value = ''
    try {
      let loginFailure: unknown
      try { await api.login(credentials, expected) }
      catch (cause) {
        if (!isUncertainWrite(cause)) throw cause
        loginFailure = cause
      }
      let current: CurrentUser
      try { current = await api.currentUser(expected) }
      catch (cause) {
        if (!isUncertainWrite(cause)) throw cause
        current = await api.currentUser(expected)
      }
      if (loginFailure && current.username !== credentials.username) throw loginFailure
      if (expected !== sessionRevision()) return
      user.value = current
      status.value = 'authenticated'
    } catch (cause) {
      if (expected !== sessionRevision()) return
      error.value = errorMessage(cause)
      status.value = cause instanceof HttpError && cause.status === 401 ? 'anonymous' : 'failed'
    }
  }

  async function logout(): Promise<void> {
    const previous = user.value
    const expected = advanceSession()
    pending = null
    status.value = 'loading'
    error.value = ''
    try {
      await api.logout(expected)
      if (expected !== sessionRevision()) return
      user.value = null
      status.value = 'anonymous'
    } catch (cause) {
      if (expected !== sessionRevision()) return
      error.value = errorMessage(cause)
      // 退出结果不确定时重新读取服务端身份，由服务端决定页面状态。
      user.value = previous
      await restore(true, errorMessage(cause))
    }
  }

  return { user, status, error, busy, isAdmin, restore, login, logout }
})
