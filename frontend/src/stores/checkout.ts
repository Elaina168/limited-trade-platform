import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth.ts'
import { errorMessage, HttpError, isUncertainWrite, RequestError, SessionChangedError, postJson, sessionRevision } from '../api/http.ts'
import type { CartCheckout, DirectPurchase, Order, OrderIntent } from '../types/checkout.ts'

export const useCheckoutStore = defineStore('checkout', () => {
  const auth = useAuthStore()
  const pending = ref<OrderIntent | null>(null)
  const busy = ref(false)
  const recoveredOrder = ref<Order | null>(null)
  const recoveryError = ref('')
  let generation = 0
  const key = (userId: number) => `limited-market:vue-order-intent:${userId}`
  watch(() => auth.user?.id, userId => {
    generation++
    busy.value = false
    recoveredOrder.value = null; recoveryError.value = ''
    const saved = userId ? sessionStorage.getItem(key(userId)) : null
    pending.value = saved ? JSON.parse(saved) as OrderIntent : null
  }, { immediate: true, flush: 'sync' })

  function persist(userId: number, intent: OrderIntent | null) {
    try {
      if (intent) sessionStorage.setItem(key(userId), JSON.stringify(intent))
      else sessionStorage.removeItem(key(userId))
    } catch (cause) {
      if (cause instanceof DOMException) throw new RequestError('无法保存购买标识，请允许浏览器会话存储后再操作，避免重复下单。')
      throw cause
    }
    if (auth.user?.id === userId) pending.value = intent
  }

  async function submit(path: OrderIntent['path'], data: DirectPurchase | CartCheckout): Promise<Order> {
    if (!auth.user) throw new RequestError('请先登录。')
    if (busy.value) throw new RequestError('订单正在提交，请等待本次结果。')
    const userId = auth.user.id
    const current = generation
    const resuming = pending.value !== null
    if (pending.value && (pending.value.path !== path || JSON.stringify(pending.value.data) !== JSON.stringify(data))) {
      throw new RequestError('上一笔下单结果尚未确认，系统会在连接恢复后继续核对。')
    }
    const intent = pending.value ?? { path, data, requestId: crypto.randomUUID() } as OrderIntent
    const expected = sessionRevision()
    persist(userId, intent)
    busy.value = true
    recoveredOrder.value = null; recoveryError.value = ''
    let replay = resuming
    try {
      const send = () => postJson<Order>(intent.path, { ...intent.data, requestId: intent.requestId }, expected)
      let order: Order
      try {
        order = await send()
      } catch (cause) {
        if (!isUncertainWrite(cause) || resuming) throw cause
        // 服务器按用户和请求标识去重，恢复必须使用原参数及原标识。
        replay = true
        order = await send()
      }
      persist(userId, null)
      return order
    } catch (cause) {
      // 旧未知结果遭遇认证、CSRF或限流拒绝时，还未查询订单幂等记录。
      if (cause instanceof HttpError && cause.status >= 400 && cause.status < 500
          && (!replay || cause.path === intent.path && [404, 409].includes(cause.status))) persist(userId, null)
      if (current === generation && !(cause instanceof SessionChangedError) && pending.value) {
        recoveryError.value = '暂未收到下单结果，连接恢复后会继续处理。'
      }
      throw cause
    } finally { if (current === generation) busy.value = false }
  }

  async function recoverPending() {
    if (!pending.value || busy.value || auth.status !== 'authenticated') return
    const current = generation
    try {
      const order = await submit(pending.value.path, pending.value.data)
      if (current === generation) recoveredOrder.value = order
    } catch (cause) {
      if (current === generation && !(cause instanceof SessionChangedError) && !pending.value) {
        recoveryError.value = '下单未完成。' + errorMessage(cause)
      }
    }
  }
  function confirmKnownOrder(order: Order) {
    // 仅使用本人接口确实返回的订单确认，不因空列表或其他订单清除未知购买。
    if (auth.user && pending.value?.requestId === order.requestId) {
      persist(auth.user.id, null)
      recoveredOrder.value = order; recoveryError.value = ''
    }
  }
  return { pending, busy, recoveredOrder, recoveryError, submit, recoverPending, confirmKnownOrder }
})
