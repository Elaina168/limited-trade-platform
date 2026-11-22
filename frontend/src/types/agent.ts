export interface RuleSource { id: string; text: string; source: string }
export interface KnowledgeSource {
  id: string; documentId: string; title: string; path: string; startLine: number; endLine: number; text: string; score: number
}
export interface AgentResponse {
  answer: string; queriedAt: string; toolsUsed: string[]; sources: RuleSource[]; knowledgeSources: KnowledgeSource[]
  modelCalls: number; toolCalls: number; totalTokens: number | null; durationMs: number
}
export interface CreatedConversation { conversationId: string; expiresAt: string }
export type AgentEvent = { type: 'start' } | { type: 'delta' | 'reset'; round: number; text: string } | { type: 'done'; result: AgentResponse }
export interface ChatMessage {
  id: number; role: 'user' | 'assistant'; text: string; state: 'connecting' | 'streaming' | 'complete' | 'stopped' | 'incomplete'; result?: AgentResponse
}
