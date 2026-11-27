import { onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '../stores/auth.ts'

export function useVisibilityRefresh(refresh: () => void, blocked: () => boolean = () => false) {
  const auth = useAuthStore()
  let lastRefresh = Number.NEGATIVE_INFINITY
  let scheduled: number | undefined
  let active = true
  function refreshVisible() {
    if (document.hidden || scheduled !== undefined || performance.now() - lastRefresh < 5_000) return
    // 焦点事件也会恢复登录态。先等本次身份检查，避免写入中或身份未确认时读取。
    scheduled = window.setTimeout(async () => {
      await auth.restore()
      scheduled = undefined
      if (!active || document.hidden || blocked()) return
      lastRefresh = performance.now()
      refresh()
    }, 0)
  }
  onMounted(() => {
    document.addEventListener('visibilitychange', refreshVisible)
    window.addEventListener('focus', refreshVisible)
  })
  onUnmounted(() => {
    active = false
    window.clearTimeout(scheduled)
    document.removeEventListener('visibilitychange', refreshVisible)
    window.removeEventListener('focus', refreshVisible)
  })
}
