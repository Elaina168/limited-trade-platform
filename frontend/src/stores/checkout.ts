import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth.ts'
import { readOrder, readSeckillResult } from '../api/checkout.ts'
import { errorMessage, HttpError, isUncertainWrite, onSessionChange, postJson, RequestError, SessionChangedError, sessionRevision } from '../api/http.ts'
import type { CartCheckout, DirectPurchase, Order, OrderIntent, SeckillPurchase, SeckillStatusResponse } from '../types/checkout.ts'

const POLL_DELAYS_MS = [120, 200, 350, 500, 800, 1000, 1500, 2000, 2500, 3000, 5000, 10_000]
type SeckillIntent = Extract<OrderIntent, { path: `/api/activities/${number}/seckill` }>

export const useCheckoutStore = defineStore('checkout', () => {
  const auth = useAuthStore()
  const pending = ref<OrderIntent | null>(null)
  const busy = ref(false)
  const queuingMessage = ref('')
  const recoveredOrder = ref<Order | null>(null)
  const recoveryError = ref('')
  const seckillPayment = ref<{ route: string; orderId: number } | null>(null)
  const seckillPurchaseRoute = computed(() => seckillIntent()?.purchaseRoute ?? seckillPayment.value?.route)
  let seckillPolling: AbortController | undefined
  let generation = 0
  const key = (userId: number) => `limited-market:vue-order-intent:${userId}`
  watch(() => auth.user?.id, userId => {
    generation++
    seckillPolling?.abort()
    busy.value = false
    queuingMessage.value = ''
    recoveredOrder.value = null; recoveryError.value = ''
    seckillPayment.value = null
    const saved = userId ? sessionStorage.getItem(key(userId)) : null
    pending.value = saved ? JSON.parse(saved) as OrderIntent : null
  }, { immediate: true, flush: 'sync' })
  const unsubscribe = onSessionChange(() => { seckillPolling?.abort(); seckillPayment.value = null })
  onScopeDispose(() => { unsubscribe(); seckillPolling?.abort() })

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

  function seckillIntent(): SeckillIntent | undefined {
    const intent = pending.value
    if (intent && intent.path !== '/api/orders' && intent.path !== '/api/orders/checkout') return intent
  }

  function completeSeckill(userId: number, order: Order, intent: SeckillIntent) {
    persist(userId, null)
    queuingMessage.value = ''; recoveryError.value = ''
    recoveredOrder.value = order
    if (intent.purchaseRoute) seckillPayment.value = { route: intent.purchaseRoute, orderId: order.id }
  }

  function leaveSeckillPurchase(route: string) {
    const intent = seckillIntent()
    if (auth.user && intent?.purchaseRoute === route) persist(auth.user.id, { ...intent, purchaseRoute: undefined })
    if (seckillPayment.value?.route === route) seckillPayment.value = null
  }

  function takeSeckillPayment(route: string) {
    if (seckillPayment.value?.route !== route) return
    const id = seckillPayment.value.orderId
    seckillPayment.value = null
    return id
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

  async function pollSeckillOrder(userId: number, requestId: string, expected: number, signal: AbortSignal,
                                 onQueued?: (msg: string) => void): Promise<Order> {
    const msg = '已锁定抢购资格，正在排队生成订单…'
    queuingMessage.value = msg
    onQueued?.(msg)
    for (let step = 0; ; step = Math.min(step + 1, POLL_DELAYS_MS.length - 1)) {
      // 每次请求完成后才排下一次，长队列的间隔封顶为十秒。
      await new Promise<void>(resolve => {
        const timer = setTimeout(() => { signal.removeEventListener('abort', stop); resolve() }, POLL_DELAYS_MS[step])
        function stop() { clearTimeout(timer); resolve() }
        signal.addEventListener('abort', stop, { once: true })
      })
      if (signal.aborted || expected !== sessionRevision()) throw new SessionChangedError()
      if (auth.status !== 'authenticated') continue
      const readSignal = AbortSignal.any([signal, AbortSignal.timeout(15_000)])
      let result: SeckillStatusResponse
      let order: Order | null = null
      try {
        result = await readSeckillResult(requestId, expected, readSignal)
        if (result.status === 'CREATED') {
          order = result.order ?? (result.orderId !== null ? await readOrder(result.orderId, expected, readSignal) : null)
        }
      } catch (cause) {
        if (signal.aborted || expected !== sessionRevision() || cause instanceof SessionChangedError) throw new SessionChangedError()
        if (!(isUncertainWrite(cause) || cause instanceof HttpError)) throw cause
        recoveryError.value = '暂未读到抢购结果，正在继续核对。'
        continue
      }
      const intent = seckillIntent()
      if (signal.aborted || intent?.requestId !== requestId) throw new SessionChangedError()
      if (order) {
        completeSeckill(userId, order, intent)
        return order
      }
      if (result.status === 'FAILED') {
        persist(userId, null)
        queuingMessage.value = ''
        throw new RequestError(result.message || '抢购未能完成建单。')
      }
      recoveryError.value = ''
    }
  }

  async function submitSeckill(activityId: number, data: SeckillPurchase, onQueued?: (msg: string) => void, purchaseRoute?: string): Promise<Order> {
    if (!auth.user) throw new RequestError('请先登录。')
    if (busy.value) throw new RequestError('订单正在提交，请等待本次结果。')
    const path = `/api/activities/${activityId}/seckill` as const
    const userId = auth.user.id
    const current = generation
    const resuming = pending.value !== null
    if (pending.value && (pending.value.path !== path || JSON.stringify(pending.value.data) !== JSON.stringify(data))) {
      throw new RequestError('上一笔下单结果尚未确认，系统会在连接恢复后继续核对。')
    }
    const intent = (pending.value ?? { path, data, requestId: crypto.randomUUID(), purchaseRoute }) as SeckillIntent
    const expected = sessionRevision()
    persist(userId, intent)
    busy.value = true
    queuingMessage.value = ''
    recoveredOrder.value = null; recoveryError.value = ''
    seckillPayment.value = null
    const controller = new AbortController()
    seckillPolling = controller
    let replay = resuming
    try {
      if (intent.queued) {
        return await pollSeckillOrder(userId, intent.requestId, expected, controller.signal, onQueued)
      }
      const requestSignal = () => AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)])
      const send = () => postJson<SeckillStatusResponse>(
        intent.path, { ...intent.data, requestId: intent.requestId }, expected, requestSignal(),
      )
      async function readKnown() {
        try { return await readSeckillResult(intent.requestId, expected, requestSignal()) }
        catch (cause) {
          if (!(cause instanceof HttpError && cause.status === 404)) throw cause
        }
      }
      // 回执丢失或重启时先按原标识查询。查不到才沿用原标识恢复提交。
      let response = resuming ? await readKnown() : undefined
      if (!response) {
        try { response = await send() }
        catch (cause) {
          if (!isUncertainWrite(cause) || resuming) throw cause
          replay = true
          response = await readKnown() ?? await send()
        }
      }
      const active = seckillIntent()
      if (controller.signal.aborted || current !== generation || active?.requestId !== intent.requestId) throw new SessionChangedError()
      if (response.status === 'FAILED') {
        persist(userId, null)
        throw new RequestError(response.message || '抢购未能完成建单。')
      }
      if (response.status === 'CREATED' && response.order) {
        completeSeckill(userId, response.order, active)
        return response.order
      }
      // 只要服务端已受理，就持久化为仅查询。成单回执缺少明细时也从结果接口补读。
      persist(userId, { ...active, queued: true })
      return await pollSeckillOrder(userId, intent.requestId, expected, controller.signal, onQueued)
    } catch (cause) {
      if (controller.signal.aborted || current !== generation) throw new SessionChangedError()
      if (cause instanceof HttpError && cause.status >= 400 && cause.status < 500 && !seckillIntent()?.queued
          && (!replay || cause.path === intent.path && [404, 409].includes(cause.status))) persist(userId, null)
      if (current === generation && !(cause instanceof SessionChangedError) && pending.value && !recoveryError.value) {
        recoveryError.value = '暂未收到下单结果，连接恢复后会继续处理。'
      }
      throw cause
    } finally {
      if (seckillPolling === controller) seckillPolling = undefined
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
      if (pending.value.path !== '/api/orders' && pending.value.path !== '/api/orders/checkout') {
        const match = /^\/api\/activities\/(\d+)\/seckill$/.exec(pending.value.path)
        if (!match) return
        await submitSeckill(Number(match[1]), pending.value.data)
        return
      }
      const order = await submit(pending.value.path, pending.value.data as DirectPurchase | CartCheckout)
      if (current === generation) recoveredOrder.value = order
    } catch (cause) {
      if (current === generation && !(cause instanceof SessionChangedError) && !pending.value) {
        recoveryError.value = '下单未完成。' + errorMessage(cause)
      }
    }
  }
  function confirmKnownOrder(order: Order) {
    // 仅使用本人接口确实返回的订单确认，不因空列表或其他订单清除未知购买。
    // 秒杀结果只认预约接口。普通订单可能占用同一个请求标识。
    if (auth.user && !seckillIntent() && pending.value?.requestId === order.requestId) {
      persist(auth.user.id, null)
      recoveredOrder.value = order; recoveryError.value = ''
    }
  }
  return { pending, busy, queuingMessage, recoveredOrder, recoveryError, seckillPurchaseRoute, submit, submitSeckill,
    recoverPending, confirmKnownOrder, leaveSeckillPurchase, takeSeckillPayment }
})
