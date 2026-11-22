import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth.ts'
import { clearConversation, createConversation, streamMessage } from '../api/agent.ts'
import { errorMessage, HttpError, onSessionChange, RequestError, SessionChangedError } from '../api/http.ts'
import { AgentStreamError } from '../utils/agentStream.ts'
import type { ChatMessage } from '../types/agent.ts'

export const useAgentStore = defineStore('agent', () => {
  const auth = useAuthStore()
  const conversationId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  const busy = ref(false)
  const action = ref<'send' | 'new' | 'clear' | null>(null)
  const notice = ref('')
  const error = ref('')
  const canSend = computed(() => auth.status === 'authenticated' && !busy.value)
  const canStop = computed(() => busy.value && action.value === 'send' && messages.value.at(-1)?.state !== 'complete')
  let controller: AbortController | null = null
  let generation = 0
  let nextId = 0
  const key = (userId: number) => `limited-market:vue-agent:${userId}`
  function remember(id: string | null) {
    if (!auth.user) return
    try {
      if (id) sessionStorage.setItem(key(auth.user.id), id)
      else sessionStorage.removeItem(key(auth.user.id))
    } catch (cause) {
      if (cause instanceof DOMException) throw new RequestError('无法保存会话编号，请允许浏览器会话存储后再操作。')
      throw cause
    }
    conversationId.value = id
  }
  function restore() {
    conversationId.value = auth.user ? sessionStorage.getItem(key(auth.user.id)) : null
    notice.value = conversationId.value ? '已接续上次会话，旧消息未在当前页面重新加载；可以清空历史后重新开始。' : '发送问题后会自动建立会话。'
  }
  function interrupt() {
    generation++; controller?.abort(); controller = null; busy.value = false; action.value = null
    const last = messages.value.at(-1)
    if (last?.role === 'assistant' && last.state !== 'complete') { last.state = 'stopped'; last.result = undefined }
  }
  const unsubscribe = onSessionChange(() => {
    interrupt(); messages.value = []; conversationId.value = null; error.value = ''; notice.value = '身份状态已变化，旧请求已停止。'
  })
  watch([() => auth.user?.id, () => auth.user?.role], () => { interrupt(); messages.value = []; error.value = ''; restore() }, { immediate: true, flush: 'sync' })
  watch(() => auth.status, status => { if (status === 'authenticated' && !conversationId.value) restore() })
  function failure(cause: unknown, creating = false) {
    const code = cause instanceof HttpError ? cause.status : cause instanceof AgentStreamError ? cause.code : null
    if (code === 404) return creating ? '交易助手当前不可用，请稍后重试。' : '会话已过期或不可访问，请新建会话后继续。'
    if (code === 409) return creating ? '未过期会话数量已达上限，可清空当前会话继续使用，或稍后再试。' : '会话正在处理其他请求或已发生变化，请稍后手动操作。'
    if (code === 502 || code === 504 || cause instanceof DOMException && cause.name === 'TimeoutError') return '助手未能在等待时间内完成，回答是否保存尚未确认，请勿自动重复发送。'
    return errorMessage(cause)
  }
  function unavailable(cause: unknown) { return cause instanceof HttpError && cause.status === 404 || cause instanceof AgentStreamError && cause.code === 404 }
  async function send(input: string) {
    if (!canSend.value) return
    const question = input.trim()
    if (!question || question.length > 2000) { error.value = '请输入1至2000字符的问题。'; return }
    const current = ++generation
    controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(75_000)])
    busy.value = true; action.value = 'send'; error.value = ''; notice.value = '正在准备回答…'
    const user: ChatMessage = { id: ++nextId, role: 'user', text: question, state: 'complete' }
    const row: ChatMessage = { id: ++nextId, role: 'assistant', text: '', state: 'connecting' }
    messages.value = [...messages.value, user, row].slice(-12)
    // 从响应式数组取得代理，后续增量修改才能驱动视图更新。
    const assistant = messages.value.at(-1)!
    let creating = !conversationId.value
    try {
      if (creating) {
        const created = await createConversation(signal)
        if (current !== generation) return
        remember(created.conversationId); creating = false
      }
      await streamMessage(conversationId.value!, question, event => {
        if (current !== generation) return
        if (event.type === 'start') { assistant.state = 'streaming'; notice.value = '正在查询并生成回答…' }
        if (event.type === 'delta') { assistant.state = 'streaming'; assistant.text += event.text }
        if (event.type === 'reset') { assistant.text = ''; assistant.state = 'streaming' }
        if (event.type === 'done') { assistant.text = event.result.answer; assistant.result = event.result; assistant.state = 'complete'; notice.value = '回答已完成并确认保存。' }
      }, signal)
    } catch (cause) {
      if (current !== generation || cause instanceof SessionChangedError) return
      if (assistant.state !== 'complete') {
        assistant.state = cause instanceof DOMException && cause.name === 'AbortError' ? 'stopped' : 'incomplete'
        assistant.result = undefined
      }
      if (!creating && unavailable(cause)) remember(null)
      error.value = cause instanceof DOMException && cause.name === 'AbortError' ? '' : failure(cause, creating)
      notice.value = '本轮未确认完成，结果可能已保存；请勿自动重复发送，可以新建或清空会话后继续。'
    } finally { if (current === generation) { busy.value = false; action.value = null; controller = null } }
  }
  function stop() { if (canStop.value) { controller?.abort(); notice.value = '正在停止生成…' } }
  async function change(clearing: boolean) {
    if (!canSend.value || clearing && !conversationId.value) return
    const current = ++generation
    controller = new AbortController()
    busy.value = true; action.value = clearing ? 'clear' : 'new'; error.value = ''
    notice.value = clearing ? '正在清空历史…' : '正在创建会话…'
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30_000)])
    try {
      if (clearing) await clearConversation(conversationId.value!, signal)
      else {
        const created = await createConversation(signal)
        if (current !== generation) return
        remember(created.conversationId)
      }
      if (current !== generation) return
      messages.value = []; notice.value = clearing ? '历史已清空，可以重新提问。' : '新会话已就绪。'
    } catch (cause) {
      if (current !== generation || cause instanceof SessionChangedError) return
      if (clearing && unavailable(cause)) remember(null)
      error.value = failure(cause, !clearing)
      notice.value = '操作未确认成功，当前显示的消息保留。'
    } finally { if (current === generation) { busy.value = false; action.value = null; controller = null } }
  }
  function leave() {
    if (busy.value) { interrupt(); notice.value = '已离开并停止请求，未完成回答是否保存尚未确认。' }
  }
  onScopeDispose(() => { unsubscribe(); controller?.abort() })
  return { conversationId, messages, busy, action, notice, error, canSend, canStop, send, stop, clear: () => change(true), newConversation: () => change(false), leave }
})
