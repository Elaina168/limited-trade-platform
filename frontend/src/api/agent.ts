import { readJson, postJson, writeJson, postEventStream, RequestError, SessionChangedError, sessionRevision } from './http.ts'
import { readAgentStream } from '../utils/agentStream.ts'
import type { AgentEvent, ConversationHistory, ConversationPage, CreatedConversation } from '../types/agent.ts'

export async function createConversation(signal: AbortSignal) {
  const value = await postJson<unknown>('/api/agent/conversations', undefined, sessionRevision(), signal)
  if (value === null || typeof value !== 'object' || Array.isArray(value) || !('conversationId' in value) || !('expiresAt' in value) || !('title' in value)
      || typeof value.conversationId !== 'string' || !value.conversationId || typeof value.expiresAt !== 'string' || typeof value.title !== 'string') {
    throw new RequestError('助手返回的会话信息无法读取。')
  }
  return { conversationId: value.conversationId, expiresAt: value.expiresAt, title: value.title } satisfies CreatedConversation
}
export async function listConversations(page: number, signal: AbortSignal) {
  const result = await readJson<ConversationPage>(`/api/agent/conversations?page=${page}`, sessionRevision(), signal)
  if (!result || !Array.isArray(result.items)) throw new RequestError('助手返回的会话列表无法读取。')
  return result
}

export async function readConversation(id: string, signal: AbortSignal) {
  const result = await readJson<ConversationHistory>(`/api/agent/conversations/${encodeURIComponent(id)}/messages`, sessionRevision(), signal)
  if (!result || result.conversationId !== id || !Array.isArray(result.turns) || typeof result.generating !== 'boolean') {
    throw new RequestError('助手返回的会话历史无法读取。')
  }
  return result
}

export const deleteConversation = (id: string, signal: AbortSignal) =>
  writeJson(`/api/agent/conversations/${encodeURIComponent(id)}`, 'DELETE', undefined, sessionRevision(), signal)

export const clearConversation = (id: string, signal: AbortSignal) => writeJson(`/api/agent/conversations/${encodeURIComponent(id)}/messages`, 'DELETE', undefined, sessionRevision(), signal)
export async function streamMessage(id: string, message: string, onEvent: (event: AgentEvent) => void, signal: AbortSignal, clientTurnId?: string) {
  const expected = sessionRevision()
  const response = await postEventStream(`/api/agent/conversations/${encodeURIComponent(id)}/messages/stream`, { message, clientTurnId }, signal, expected)
  if (response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'text/event-stream' || !response.body) throw new RequestError('助手当前未返回可读取的流式回答。')
  return readAgentStream(response.body, event => {
    if (expected !== sessionRevision()) throw new SessionChangedError()
    onEvent(event)
  }, signal)
}
