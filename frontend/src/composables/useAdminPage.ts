import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { errorMessage, HttpError, RequestError, SessionChangedError } from '../api/http.ts'

export function useAdminPage<T>(read: () => Promise<T>, resetDraft: () => void, describeError = errorMessage) {
  const auth = useAuthStore()
  const data = shallowRef<T | null>(null)
  const busy = ref(false)
  const saving = ref(false)
  const error = ref('')
  const notice = ref('')
  const needsReview = ref(false)
  let sequence = 0
  let operation = 0
  const blocked = computed(() => busy.value || saving.value || auth.busy || !auth.isAdmin || needsReview.value)
  function reset() {
    sequence++; operation++; data.value = null; busy.value = false; saving.value = false
    error.value = ''; notice.value = ''; needsReview.value = false; resetDraft()
  }
  async function load() {
    if (!auth.isAdmin || auth.status !== 'authenticated') return
    const current = ++sequence
    busy.value = true; error.value = ''
    try {
      const result = await read()
      if (current !== sequence) return
      data.value = result
      if (needsReview.value) notice.value = '已刷新，请核对列表中的实际结果后再操作。'
      needsReview.value = false
    } catch (cause) {
      if (current === sequence && !(cause instanceof SessionChangedError)) error.value = describeError(cause)
    } finally { if (current === sequence) busy.value = false }
  }
  async function mutate<R>(action: () => Promise<R>, message: string, success?: (result: R) => void) {
    if (blocked.value) return
    const current = ++operation
    saving.value = true; error.value = ''; notice.value = ''
    try {
      const result = await action()
      if (current !== operation) return
      sequence++; busy.value = false
      success?.(result)
      notice.value = message
      needsReview.value = false
      await load()
    } catch (cause) {
      if (current !== operation) return
      sequence++; busy.value = false
      error.value = describeError(cause)
      if (cause instanceof RequestError || cause instanceof HttpError && cause.status >= 500) {
        needsReview.value = true; notice.value = '操作结果尚未确认，请先刷新核对，避免重复提交。'
      }
    } finally { if (current === operation) saving.value = false }
  }
  watch([() => auth.user?.id, () => auth.user?.role], reset, { immediate: true, flush: 'sync' })
  watch(() => auth.status, status => { if (status === 'authenticated' && auth.isAdmin) void load() }, { immediate: true })
  onUnmounted(() => { sequence++; operation++ })
  return { data, busy, saving, error, notice, blocked, needsReview, load, mutate, reset }
}
