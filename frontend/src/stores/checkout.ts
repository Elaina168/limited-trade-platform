import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth.ts'
import { readOrder } from '../api/checkout.ts'
import { errorMessage, HttpError, isUncertainWrite, postJson, readJson, RequestError, SessionChangedError, sessionRevision } from '../api/http.ts'
import type { CartCheckout, DirectPurchase, Order, OrderIntent, SeckillPurchase, SeckillStatusResponse } from '../types/checkout.ts'

const POLL_DELAYS_MS = [120, 200, 350, 500, 800, 1000, 1500, 2000, 2500, 3000]

export const useCheckoutStore = defineStore('checkout', () => {
  const auth = useAuthStore()
  const pending = ref<OrderIntent | null>(null)
  const busy = ref(false)
  const queuingMessage = ref('')
  const recoveredOrder = ref<Order | null>(null)
  const recoveryError = ref('')
  let generation = 0
  const key = (userId: number) => `limited-market:vue-order-intent:${userId}`
  watch(() => auth.user?.id, userId => {
    generation++
    busy.value = false
    queuingMessage.value = ''
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

  async function submit(path: '/api/orders' | '/api/orders/checkout', data: DirectPurchase | CartCheckout): Promise<Order> {
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
    queuingMessage.value = ''
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
      if (cause instanceof HttpError && cause.status >= 400 && cause.status < 500
          && (!replay || cause.path === intent.path && [404, 409].includes(cause.status))) persist(userId, null)
      if (current === generation && !(cause instanceof SessionChangedError) && pending.value) {
        recoveryError.value = '暂未收到下单结果，连接恢复后会继续处理。'
      }
      throw cause
    } finally { if (current === generation) busy.value = false }
  }

  async function pollSeckillOrder(userId: number, intent: Extract<OrderIntent, { path: `/api/activities/${number}/seckill` }>,
                                  expected: number, current: number, onQueued?: (msg: string) => void): Promise<Order> {
    const msg = '已锁定抢购资格，正在排队生成订单…'
    queuingMessage.value = msg
    onQueued?.(msg)
    for (const waitMs of POLL_DELAYS_MS) {
      await new Promise(resolve => setTimeout(resolve, waitMs))
      if (current !== generation) throw new SessionChangedError()
      let result: SeckillStatusResponse
      try {
        result = await readJson<SeckillStatusResponse>(`/api/activities/seckill/result/${encodeURIComponent(intent.requestId)}`, expected)
      } catch (cause) {
        if (cause instanceof SessionChangedError) throw cause
        if (isUncertainWrite(cause)) continue
        if (cause instanceof HttpError && cause.status === 404) continue
        throw cause
      }
      if (result.status === 'CREATED') {
        const order = result.order ?? (result.orderId !== null ? await readOrder(result.orderId) : null)
        if (!order) continue
        persist(userId, null)
        queuingMessage.value = ''
        return order
      }
      if (result.status === 'FAILED') {
        persist(userId, null)
        queuingMessage.value = ''
        throw new RequestError(result.message || '抢购未能完成建单。')
      }
    }
    recoveryError.value = '抢购请求仍在排队处理中，结果确认后将在顶部提示。'
    throw new RequestError('抢购请求仍在排队处理中，系统将继续自动跟进结果。')
  }

  async function submitSeckill(activityId: number, data: SeckillPurchase, onQueued?: (msg: string) => void): Promise<Order> {
    if (!auth.user) throw new RequestError('请先登录。')
    if (busy.value) throw new RequestError('订单正在提交，请等待本次结果。')
    const path = `/api/activities/${activityId}/seckill` as const
    const userId = auth.user.id
    const current = generation
    const resuming = pending.value !== null
    if (pending.value && (pending.value.path !== path || JSON.stringify(pending.value.data) !== JSON.stringify(data))) {
      throw new RequestError('上一笔下单结果尚未确认，系统会在连接恢复后继续核对。')
    }
    const intent = (pending.value ?? { path, data, requestId: crypto.randomUUID() }) as Extract<OrderIntent, { path: `/api/activities/${number}/seckill` }>
    const expected = sessionRevision()
    persist(userId, intent)
    busy.value = true
    queuingMessage.value = ''
    recoveredOrder.value = null; recoveryError.value = ''
    let replay = resuming
    try {
      if (intent.queued) {
        return await pollSeckillOrder(userId, intent, expected, current, onQueued)
      }
      const send = () => postJson<SeckillStatusResponse>(intent.path, { ...intent.data, requestId: intent.requestId }, expected)
      let response: SeckillStatusResponse
      try {
        response = await send()
      } catch (cause) {
        if (!isUncertainWrite(cause) || resuming) throw cause
        replay = true
        response = await send()
      }
      if (response.status === 'CREATED') {
        const order = response.order ?? (response.orderId !== null ? await readOrder(response.orderId) : null)
        if (!order) throw new RequestError('订单已生成，但读取详情失败。')
        persist(userId, null)
        return order
      }
      if (response.status === 'FAILED') {
        persist(userId, null)
        throw new RequestError(response.message || '抢购未能完成建单。')
      }
      const queuedIntent = { ...intent, queued: true }
      persist(userId, queuedIntent)
      onQueued?.(response.message || '已锁定抢购名额，正在生成订单…')
      return await pollSeckillOrder(userId, queuedIntent, expected, current, onQueued)
    } catch (cause) {
      if (cause instanceof HttpError && cause.status >= 400 && cause.status < 500
          && (!replay || cause.path === intent.path && [404, 409].includes(cause.status))) persist(userId, null)
      if (current === generation && !(cause instanceof SessionChangedError) && pending.value && !recoveryError.value) {
        recoveryError.value = '暂未收到下单结果，连接恢复后会继续处理。'
      }
      throw cause
    } finally {
      if (current === generation) {
        busy.value = false
        queuingMessage.value = ''
      }
    }
  }

  async function recoverPending() {
    if (!pending.value || busy.value || auth.status !== 'authenticated') return
    const current = generation
    try {
      let order: Order
      if (pending.value.path !== '/api/orders' && pending.value.path !== '/api/orders/checkout') {
        const match = /^\/api\/activities\/(\d+)\/seckill$/.exec(pending.value.path)
        if (!match) return
        order = await submitSeckill(Number(match[1]), pending.value.data)
      } else {
        order = await submit(pending.value.path, pending.value.data as DirectPurchase | CartCheckout)
      }
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
  return { pending, busy, queuingMessage, recoveredOrder, recoveryError, submit, submitSeckill, recoverPending, confirmKnownOrder }
})
