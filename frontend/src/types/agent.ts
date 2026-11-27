import type { Order } from './checkout.ts'

export interface RuleSource { id: string; text: string; source: string }
export interface KnowledgeSource {
  id: string; documentId: string; title: string; path: string; startLine: number; endLine: number; text: string; score: number
}
export type AgentOrderSummary = Pick<Order, 'id' | 'status' | 'totalAmount' | 'expiresAt'>
export interface AgentResponse {
  answer: string; queriedAt: string; toolsUsed: string[]; sources: RuleSource[]; knowledgeSources: KnowledgeSource[]
  orders: AgentOrderSummary[]
  modelCalls: number; toolCalls: number; totalTokens: number | null; durationMs: number
}
interface ConversationIdentity { conversationId: string; expiresAt: string }
export interface CreatedConversation extends ConversationIdentity { title: string }
export interface ConversationPage { page: number; size: number; hasNext: boolean; items: CreatedConversation[] }
export type VisibleResult = Pick<AgentResponse, 'sources' | 'knowledgeSources' | 'orders'>
export interface VisibleTurn extends VisibleResult { question: string; answer: string; clientTurnId?: string | null }
export interface ConversationHistory extends ConversationIdentity { generating: boolean; turns: VisibleTurn[] }
export type AgentEvent = { type: 'start' } | { type: 'delta' | 'reset'; round: number; text: string } | { type: 'done'; result: AgentResponse }
export interface ChatMessage {
  id: number; role: 'user' | 'assistant'; text: string; state: 'connecting' | 'streaming' | 'complete' | 'stopped' | 'incomplete'; result?: VisibleResult
}
