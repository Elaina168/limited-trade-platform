import { RequestError } from '../api/http.ts'
import type { AgentEvent, AgentResponse } from '../types/agent.ts'

export class AgentProtocolError extends RequestError {}
export class AgentStreamError extends RequestError {
  code: number
  constructor(code: number, message: string) { super(message); this.code = code }
}
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function response(value: Record<string, unknown>): AgentResponse {
  if (typeof value.answer !== 'string' || typeof value.queriedAt !== 'string'
      || !Array.isArray(value.toolsUsed) || !value.toolsUsed.every(item => typeof item === 'string')
      || !count(value.modelCalls) || !count(value.toolCalls) || !count(value.durationMs) || value.totalTokens !== null && !count(value.totalTokens)
      || !Array.isArray(value.sources) || !value.sources.every(item => object(item) && typeof item.id === 'string' && typeof item.text === 'string' && typeof item.source === 'string')
      || !Array.isArray(value.knowledgeSources) || !value.knowledgeSources.every(item => object(item)
        && ['id','documentId','title','path','text'].every(key => typeof item[key] === 'string')
        && count(item.startLine) && item.startLine >= 1 && count(item.endLine) && item.endLine >= item.startLine
        && typeof item.score === 'number' && Number.isFinite(item.score))) throw new AgentProtocolError('助手回答或来源格式不正确。')
  return value as unknown as AgentResponse
}

export async function readAgentStream(body: ReadableStream<Uint8Array>, onEvent: (event: AgentEvent) => void, signal?: AbortSignal): Promise<AgentResponse> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = '', name = '', lines: string[] = [], started = false, completed: AgentResponse | undefined
  let rejectAbort!: (reason: unknown) => void
  const aborted = new Promise<never>((_, reject) => { rejectAbort = reject })
  const abort = () => { rejectAbort(signal!.reason) }
  signal?.addEventListener('abort', abort, { once: true })
  function line(value: string) {
    if (value === '') {
      if (!lines.length) { name = ''; return }
      let payload: unknown
      try { payload = JSON.parse(lines.join('\n')) }
      catch (cause) { if (cause instanceof SyntaxError) throw new AgentProtocolError('助手返回的事件JSON无法读取。'); throw cause }
      if (!object(payload)) throw new AgentProtocolError('助手事件格式不正确。')
      if (name === 'start') {
        if (started) throw new AgentProtocolError('助手重复开始回答。')
        started = true; onEvent({ type: 'start' })
      } else {
        if (!started) throw new AgentProtocolError('助手尚未开始就返回了回答事件。')
        if (name === 'delta' || name === 'reset') {
          if (!count(payload.round) || payload.round < 1 || typeof payload.text !== 'string') throw new AgentProtocolError('助手增量格式不正确。')
          onEvent({ type: name, round: payload.round, text: payload.text })
        } else if (name === 'done') {
          completed = response(payload); onEvent({ type: 'done', result: completed })
        } else if (name === 'error') {
          if (!count(payload.code) || typeof payload.message !== 'string') throw new AgentProtocolError('助手错误事件格式不正确。')
          throw new AgentStreamError(payload.code, payload.message)
        } else throw new AgentProtocolError('收到无法识别的助手事件。')
      }
      name = ''; lines = []; return
    }
    if (value.startsWith(':')) return
    const colon = value.indexOf(':')
    const key = colon < 0 ? value : value.slice(0, colon)
    let content = colon < 0 ? '' : value.slice(colon + 1)
    if (content.startsWith(' ')) content = content.slice(1)
    if (key === 'event') name = content
    if (key === 'data') lines.push(content)
  }
  try {
    signal?.throwIfAborted()
    while (!completed) {
      let chunk: ReadableStreamReadResult<Uint8Array>
      try { chunk = await (signal ? Promise.race([reader.read(), aborted]) : reader.read()) }
      catch (cause) { if (cause instanceof TypeError) throw new RequestError('连接中断，回答是否保存尚未确认，请勿自动重复发送。'); throw cause }
      signal?.throwIfAborted()
      try { buffer += chunk.done ? decoder.decode() : decoder.decode(chunk.value, { stream: true }) }
      catch (cause) { if (cause instanceof TypeError) throw new AgentProtocolError('助手返回的文字编码无法读取。'); throw cause }
      let start = 0
      for (let i = 0; i < buffer.length; i++) {
        if (buffer[i] !== '\n' && buffer[i] !== '\r') continue
        if (buffer[i] === '\r' && i === buffer.length - 1 && !chunk.done) break
        line(buffer.slice(start, i))
        if (buffer[i] === '\r' && buffer[i + 1] === '\n') i++
        start = i + 1
        if (completed) return completed
      }
      buffer = buffer.slice(start)
      if (chunk.done) throw new AgentProtocolError('连接已结束，但尚未确认回答完成；结果可能已保存，请勿自动重复发送。')
    }
    return completed
  } finally {
    signal?.removeEventListener('abort', abort)
    try { await reader.cancel() } finally { reader.releaseLock() }
  }
}
