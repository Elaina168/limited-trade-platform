import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { errorMessage, HttpError, isUncertainWrite, onSessionChange, RequestError, SessionChangedError } from '../api/http.ts'

export function useAdminPage<T>(read: () => Promise<T>, resetDraft: () => void, describeError = errorMessage) {
  const auth = useAuthStore()
  const data = shallowRef<T | null>(null)
  const busy = ref(false)
  const saving = ref(false)
  const error = ref('')
  const notice = ref('')
  let sequence = 0
  let operation = 0
  const blocked = computed(() => busy.value || saving.value || auth.status !== 'authenticated' || !auth.isAdmin)
  function reset() {
    sequence++; operation++; data.value = null; busy.value = false; saving.value = false
    error.value = ''; notice.value = ''; resetDraft()
  }
  async function load() {
    if (!auth.isAdmin || auth.status !== 'authenticated') return
    const current = ++sequence
    busy.value = true; error.value = ''
    try {
      const result = await read()
      if (current !== sequence) return
      data.value = result
    } catch (cause) {
      if (current === sequence && !(cause instanceof SessionChangedError)) error.value = describeError(cause)
    } finally { if (current === sequence) busy.value = false }
  }
  async function mutate(action: () => Promise<unknown>, message: string, success?: () => void, confirms?: (result: T) => boolean) {
    if (blocked.value) return
    const current = ++operation
    saving.value = true; error.value = ''; notice.value = ''
    try {
      await action()
      if (current !== operation) return
      sequence++; busy.value = false
      success?.()
      notice.value = message
      await load()
    } catch (cause) {
      if (current !== operation || cause instanceof SessionChangedError) return
      sequence++; busy.value = false
      if (!isUncertainWrite(cause)) {
        error.value = describeError(cause)
        return
      }

      notice.value = '正在自动查询操作结果…'
      let refreshed = false
      // 写入可能已生效，只重试读取。短暂等待给服务端事务留出完成时间。
      for (const delay of [0, 300, 900]) {
        if (delay) await new Promise(resolve => setTimeout(resolve, delay))
        if (current !== operation || auth.status !== 'authenticated' || !auth.isAdmin) return
        let result: T
        try {
          result = await read()
        } catch (readError) {
          if (current !== operation || readError instanceof SessionChangedError) return
          if (readError instanceof RequestError || readError instanceof HttpError && readError.status >= 500) continue
          error.value = describeError(readError)
          break
        }
        if (current !== operation) return
        data.value = result
        refreshed = true
        if (confirms?.(result)) {
          success?.()
          notice.value = message
          return
        }
        if (!confirms) break
      }
      notice.value = refreshed
        ? '已自动更新页面数据，但仍无法确认本次操作结果。请勿重复提交同一项操作。'
        : '自动查询操作结果失败，暂时无法确认本次操作结果。请勿重复提交同一项操作。'
    } finally { if (current === operation) saving.value = false }
  }
  watch([() => auth.user?.id, () => auth.user?.role], reset, { immediate: true, flush: 'sync' })
  watch(() => auth.status, status => { if (status === 'authenticated' && auth.isAdmin) void load() }, { immediate: true })
  const stopSessionListener = onSessionChange(reset)
  onScopeDispose(() => { sequence++; operation++; stopSessionListener() })
  return { data, busy, saving, error, notice, blocked, load, mutate, reset }
}
