import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth.ts'
import { clearConversation, createConversation, deleteConversation, listConversations, readConversation, streamMessage } from '../api/agent.ts'
import { errorMessage, HttpError, onSessionChange, isUncertainWrite, RequestError, SessionChangedError } from '../api/http.ts'
import { AgentStreamError } from '../utils/agentStream.ts'
import type { ChatMessage, ConversationHistory, CreatedConversation } from '../types/agent.ts'

export const useAgentStore = defineStore('agent', () => {
  const auth = useAuthStore()
  const conversationId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  const conversations = ref<CreatedConversation[]>([])
  const page = ref(1)
  const hasNext = ref(false)
  const busy = ref(false)
  const action = ref<'send' | 'history' | 'new' | 'clear' | 'delete' | null>(null)
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
  function interrupt() {
    generation++; controller?.abort(); controller = null; busy.value = false; action.value = null
    const last = messages.value.at(-1)
    if (last?.role === 'assistant' && last.state !== 'complete') { last.state = 'stopped'; last.result = undefined }
  }
  let needsInitialLoad = true
  function resetIdentity() {
    needsInitialLoad = true
    interrupt()
    messages.value = []; conversations.value = []; conversationId.value = null
    page.value = 1; hasNext.value = false; error.value = ''; notice.value = ''
  }
  const unsubscribe = onSessionChange(resetIdentity)
  watch([() => auth.user?.id, () => auth.user?.role], resetIdentity, { flush: 'sync' })
  let active = false
  watch(() => auth.status, status => {
    if (active && needsInitialLoad && status === 'authenticated') void open()
  })
  function failure(cause: unknown, creating = false) {
    const code = cause instanceof HttpError ? cause.status : cause instanceof AgentStreamError ? cause.code : null
    if (code === 404) return creating ? '交易助手当前不可用，请稍后重试。' : '会话已过期或不可访问，请新建会话后继续。'
    if (code === 409) return creating ? '未过期会话数量已达上限，请继续现有会话或删除一个会话。' : '会话正在处理其他请求或已发生变化，请稍后再试。'
    if (code === 413) return '问题和当轮查询结果超出助手上下文额度，请缩小问题范围。'
    if (code === 429) return '助手提问、并发或本月费用额度已满，请稍后查看。'
    if (code === 502 || code === 504 || cause instanceof DOMException && cause.name === 'TimeoutError') return '助手未能在等待时间内完成。'
    return errorMessage(cause)
  }
  function unavailable(cause: unknown) { return cause instanceof HttpError && cause.status === 404 || cause instanceof AgentStreamError && cause.code === 404 }
  async function refreshPage(number: number, signal: AbortSignal, current: number) {
    const result = await listConversations(number, signal)
    if (current === generation) {
      conversations.value = result.items; page.value = result.page; hasNext.value = result.hasNext
    }
    return result.items
  }

  function showHistory(history: ConversationHistory) {
    remember(history.conversationId)
    messages.value = history.turns.flatMap<ChatMessage>(turn => [
      { id: ++nextId, role: 'user', text: turn.question, state: 'complete' },
      { id: ++nextId, role: 'assistant', text: turn.answer, state: 'complete', result: turn },
    ])
    notice.value = history.generating ? '此会话正在生成，当前显示已保存的问答。' : ''
  }

  async function browse(requestedPage?: number, selected?: string) {
    if (!canSend.value) return
    const current = ++generation
    controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)])
    busy.value = true; action.value = 'history'; error.value = ''
    try {
      if (requestedPage !== undefined) {
        await refreshPage(requestedPage, signal, current)
        return
      }
      if (selected) {
        const history = await readConversation(selected, signal)
        if (current === generation) showHistory(history)
        return
      }

      const items = await refreshPage(1, signal, current)
      if (current !== generation) return
      let previous: string | null
      try { previous = sessionStorage.getItem(key(auth.user!.id)) }
      catch (cause) {
        if (cause instanceof DOMException) throw new RequestError('无法读取浏览器保存的会话编号。')
        throw cause
      }
      if (previous) {
        try {
          const history = await readConversation(previous, signal)
          if (current === generation) showHistory(history)
          return
        } catch (cause) {
          if (current !== generation || !unavailable(cause)) throw cause
          remember(null)
        }
      }
      if (items.length) {
        const history = await readConversation(items[0]!.conversationId, signal)
        if (current === generation) showHistory(history)
      } else {
        remember(null); messages.value = []; notice.value = '发送问题后会自动建立会话。'
      }
    } catch (cause) {
      if (current === generation && !(cause instanceof SessionChangedError)) error.value = failure(cause)
    } finally {
      if (current === generation) { busy.value = false; action.value = null; controller = null }
    }
  }

  async function send(input: string) {
    if (!canSend.value) return
    const question = input.trim()
    if (!question || question.length > 2000) { error.value = '请输入1至2000字符的问题。'; return }
    const clientTurnId = crypto.randomUUID()
    const current = ++generation
    controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(75_000)])
    busy.value = true; action.value = 'send'; error.value = ''; notice.value = '正在准备回答…'
    const user: ChatMessage = { id: ++nextId, role: 'user', text: question, state: 'complete' }
    const row: ChatMessage = { id: ++nextId, role: 'assistant', text: '', state: 'connecting' }
    const last = messages.value.at(-1)
    const saved = last?.state === 'stopped' || last?.state === 'incomplete' ? messages.value.slice(0, -2) : messages.value
    messages.value = [...saved, user, row]
    // 从响应式数组取得代理，后续增量修改才能驱动视图更新。
    const assistant = messages.value.at(-1)!
    let submitted = false
    let creating = !conversationId.value
    try {
      if (creating) {
        const created = await createConversation(signal)
        if (current !== generation) return
        remember(created.conversationId); creating = false
        // 首轮未完成时，新建的空会话也应出现在列表。
        if (page.value === 1) {
          hasNext.value = hasNext.value || conversations.value.length === 5
          conversations.value = [created, ...conversations.value].slice(0, 5)
        }
      }
      const id = conversationId.value!
      submitted = true
      await streamMessage(id, question, event => {
        if (current !== generation) return
        if (event.type === 'start') { assistant.state = 'streaming'; notice.value = '正在查询并生成回答…' }
        if (event.type === 'delta') { assistant.state = 'streaming'; assistant.text += event.text }
        if (event.type === 'reset') { assistant.text = ''; assistant.state = 'streaming' }
        if (event.type === 'done') {
          assistant.text = event.result.answer; assistant.result = event.result; assistant.state = 'complete'
          messages.value = messages.value.slice(-100)
          notice.value = '回答已完成并确认保存。'
        }
      }, signal, clientTurnId)
      if (current === generation) await refreshPage(1, signal, current)
    } catch (cause) {
      if (current !== generation || cause instanceof SessionChangedError) return
      if (assistant.state === 'complete') {
        error.value = '回答已保存，会话列表刷新失败。'
        return
      }
      assistant.state = cause instanceof DOMException && cause.name === 'AbortError' ? 'stopped' : 'incomplete'
      assistant.result = undefined
      if (!creating && unavailable(cause)) {
        const missing = conversationId.value
        remember(null)
        messages.value = []
        conversations.value = conversations.value.filter(item => item.conversationId !== missing)
      }
      const stopped = assistant.state === 'stopped'
      error.value = stopped ? '' : failure(cause, creating)
      notice.value = stopped ? '已停止生成。' : '本轮未完成。'
      if (submitted && conversationId.value && (stopped || !(cause instanceof AgentStreamError) && isUncertainWrite(cause))) {
        await recoverTurn(conversationId.value, clientTurnId, current)
      } else if (creating && isUncertainWrite(cause)) {
        await recoverChange('new', null, current)
      }
    } finally { if (current === generation) { busy.value = false; action.value = null; controller = null } }
  }
  async function recoverTurn(id: string, clientTurnId: string, current: number) {
    controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(12_000)])
    action.value = 'history'; error.value = ''; notice.value = '正在恢复本轮回答…'
    let idle = false
    for (const delay of [0, 250, 500, 1000, 2000, 3000]) {
      if (delay) await new Promise(resolve => setTimeout(resolve, delay))
      if (current !== generation) return
      if (signal.aborted) break
      try {
        const history = await readConversation(id, signal)
        if (current !== generation) return
        if (history.turns.some(turn => turn.clientTurnId === clientTurnId)) {
          showHistory(history)
          notice.value = '已恢复本轮保存的问答。'
          return
        }
        if (!history.generating && idle) {
          notice.value = '本轮未保存回答。'
          return
        }
        idle = !history.generating
      } catch (cause) {
        if (current !== generation || cause instanceof SessionChangedError) return
        if (!isUncertainWrite(cause)) {
          error.value = failure(cause)
          break
        }
        idle = false
      }
    }
    if (current === generation) notice.value = '暂时无法确认本轮结果，已保存的回答会在会话历史中显示。'
  }

  async function recoverChange(kind: 'new' | 'clear' | 'delete', id: string | null, current: number) {
    controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)])
    notice.value = '正在重新读取会话状态…'
    let confirmed = false
    try {
      if (id) {
        try {
          const history = await readConversation(id, signal)
          if (current !== generation) return
          confirmed = kind === 'clear' && history.turns.length === 0 && !history.generating
          showHistory(history)
        } catch (cause) {
          if (current !== generation || cause instanceof SessionChangedError) return
          if (!unavailable(cause)) throw cause
          remember(null); messages.value = []
          confirmed = kind === 'delete'
        }
      }
      await refreshPage(1, signal, current)
      if (current !== generation) return
      if (confirmed) error.value = ''
      notice.value = confirmed ? (kind === 'clear' ? '历史已清空。' : '会话已删除。') : '已更新会话列表，暂时无法确认本次操作。'
    } catch (cause) {
      if (current !== generation || cause instanceof SessionChangedError) return
      error.value ||= failure(cause)
      notice.value = confirmed ? '操作结果已核对，会话列表暂时无法更新。' : '暂时无法确认本次操作。'
    }
  }
  function stop() { if (canStop.value) { controller?.abort(); notice.value = '正在停止生成…' } }
  async function change(kind: 'new' | 'clear' | 'delete') {
    if (!canSend.value || kind !== 'new' && !conversationId.value) return
    const current = ++generation
    controller = new AbortController()
    busy.value = true; action.value = kind; error.value = ''
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30_000)])
    const targetId = kind === 'new' ? null : conversationId.value
    let applied = false
    try {
      if (kind === 'new') {
        const created = await createConversation(signal)
        if (current !== generation) return
        remember(created.conversationId)
      } else if (kind === 'clear') {
        await clearConversation(conversationId.value!, signal)
      } else {
        await deleteConversation(conversationId.value!, signal)
        if (current !== generation) return
        remember(null)
      }
      if (current !== generation) return
      messages.value = []
      notice.value = { clear: '历史已清空，可以重新提问。', new: '新会话已就绪。', delete: '会话已删除。请选择会话或直接提问。' }[kind]
      applied = true
      await refreshPage(1, signal, current)
    } catch (cause) {
      if (current !== generation || cause instanceof SessionChangedError) return
      if (applied) {
        error.value = '操作已保存，会话列表刷新失败。'
      } else {
        if (kind !== 'new' && unavailable(cause)) { remember(null); messages.value = [] }
        error.value = failure(cause, kind === 'new')
        if (isUncertainWrite(cause)) await recoverChange(kind, targetId, current)
      }
    } finally { if (current === generation) { busy.value = false; action.value = null; controller = null } }
  }
  function open() {
    active = true
    if (!canSend.value) return
    needsInitialLoad = false
    return browse()
  }
  function leave() {
    active = false
    if (busy.value) interrupt()
  }
  onScopeDispose(() => { unsubscribe(); controller?.abort() })
  return {
    conversationId, conversations, page, hasNext, messages, busy, action, notice, error, canSend, canStop,
    open, leave, pageTo: (number: number) => browse(number), select: (id: string) => browse(undefined, id),
    send, stop, clear: () => change('clear'), newConversation: () => change('new'), remove: () => change('delete'),
  }
})
