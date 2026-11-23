<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { useAgentStore } from '../stores/agent.ts'
const auth = useAuthStore()
const agent = useAgentStore()
const question = ref('')
const confirming = ref<'clear' | 'delete' | null>(null)
const log = ref<HTMLElement | null>(null)
const stateLabels = { connecting: '正在连接', streaming: '生成中', complete: '已完成', stopped: '已停止（未确认完成）', incomplete: '未完成' }
async function submit() {
  if (!agent.canSend) return
  const text = question.value.trim()
  if (!text || text.length > 2000) { agent.error = '请输入1至2000字符的问题。'; return }
  confirming.value = null
  const task = agent.send(text)
  question.value = ''
  await task
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); void submit() }
}
async function create() { confirming.value = null; await agent.newConversation() }
async function select(id: string) {
  confirming.value = null
  await agent.select(id)
}
async function confirmChange() {
  const kind = confirming.value
  confirming.value = null
  if (kind === 'clear') await agent.clear()
  if (kind === 'delete') await agent.remove()
}
watch([() => auth.user?.id, () => auth.user?.role], () => { question.value = ''; confirming.value = null }, { flush: 'sync' })
watch([() => agent.messages.length, () => agent.messages.at(-1)?.text, () => agent.messages.at(-1)?.state], async () => {
  const element = log.value
  const follow = element && element.scrollHeight - element.scrollTop - element.clientHeight < 90
  await nextTick()
  if (follow && log.value === element) element.scrollTop = element.scrollHeight
})
onMounted(() => agent.open())
onUnmounted(() => agent.leave())
</script>
<template>
  <div class="chat-heading"><div><p class="section-kicker">TRADE ASSISTANT</p><h1 class="page-title">交易助手</h1></div><div class="business-actions"><el-button :disabled="!agent.canSend" @click="create">新会话</el-button><el-button :disabled="!agent.canSend || !agent.conversationId" @click="confirming = 'clear'">清空历史</el-button><el-button :disabled="!agent.canSend || !agent.conversationId" @click="confirming = 'delete'">删除会话</el-button></div></div>
  <p class="page-description">查询本人订单，了解平台交易规则。助手只读，不代为支付、取消或退款。</p>
  <section class="chat-history" aria-label="我的会话">
    <div class="chat-history-header">
      <strong>我的会话</strong>
      <div class="business-actions">
        <el-button size="small" :disabled="!agent.canSend" @click="agent.open">刷新会话</el-button>
        <el-button size="small" :disabled="!agent.canSend || agent.page === 1" @click="agent.pageTo(agent.page - 1)">上一页</el-button>
        <el-button size="small" :disabled="!agent.canSend || !agent.hasNext" @click="agent.pageTo(agent.page + 1)">下一页</el-button>
      </div>
    </div>
    <p v-if="!agent.conversations.length" class="muted">{{ agent.action === 'history' ? '正在加载会话…' : '此页暂无会话' }}</p>
    <ul v-else class="chat-sessions">
      <li v-for="item in agent.conversations" :key="item.conversationId">
        <el-button :disabled="!agent.canSend" :type="item.conversationId === agent.conversationId ? 'primary' : 'default'"
          :aria-pressed="item.conversationId === agent.conversationId" @click="select(item.conversationId)">
          会话 {{ item.conversationId.slice(0, 8) }}
        </el-button>
        <span class="muted">有效至 {{ item.expiresAt.slice(0, 16).replace('T', ' ') }}</span>
      </li>
    </ul>
  </section>
  <p v-if="agent.error" class="form-error" role="alert">{{ agent.error }}</p>
  <p class="chat-notice" role="status" aria-live="polite">{{ agent.notice }}</p>
  <div v-if="confirming" class="chat-confirm" role="group" :aria-label="confirming === 'clear' ? '确认清空历史' : '确认删除会话'">
    <p>{{ confirming === 'clear' ? '清空已保存的问答和模型上下文，保留当前会话。' : '删除当前会话及其历史，并释放一个会话名额。' }}</p>
    <div class="business-actions">
      <el-button :disabled="!agent.canSend" @click="confirmChange">{{ confirming === 'clear' ? '确认清空' : '确认删除' }}</el-button>
      <el-button @click="confirming = null">取消</el-button>
    </div>
  </div>
  <div ref="log" class="chat-log" role="log" aria-label="聊天记录" tabindex="0" aria-live="off">
    <div v-if="!agent.messages.length" class="chat-empty"><h2>有什么交易问题想了解？</h2><p>例如：查一下我的待支付订单，并解释支付期限。</p></div>
    <article v-for="message in agent.messages" :key="message.id" class="chat-message" :class="message.role">
      <div class="chat-message-heading"><strong>{{ message.role === 'user' ? '你' : '交易助手' }}</strong><span v-if="message.role === 'assistant'" class="muted">{{ stateLabels[message.state] }}</span></div>
      <div class="chat-text">{{ message.text || (message.state === 'connecting' ? '正在连接助手…' : message.state === 'streaming' ? '正在查询并生成回答…' : '') }}</div>
      <details v-if="message.state === 'complete' && message.result && (message.result.sources.length || message.result.knowledgeSources.length)" class="chat-sources">
        <summary>查看规则来源（{{ message.result.sources.length + message.result.knowledgeSources.length }}）</summary>
        <article v-for="source in message.result.knowledgeSources" :key="`knowledge:${source.id}`" class="chat-source"><strong>{{ source.title }}</strong><p class="muted">第 {{ source.startLine }}—{{ source.endLine }} 行</p><p class="chat-text">{{ source.text }}</p></article>
        <article v-for="source in message.result.sources" :key="`rule:${source.id}`" class="chat-source"><strong>{{ source.id }}</strong><p class="chat-text">{{ source.text }}</p></article>
      </details>
    </article>
  </div>
  <form class="chat-composer" @submit.prevent="submit"><label for="agent-question">你的问题</label><textarea id="agent-question" v-model="question" rows="3" maxlength="2000" :disabled="!agent.canSend" placeholder="输入订单或交易规则问题" @keydown="keydown"></textarea><div class="chat-compose-actions"><span class="muted">Enter发送，Shift＋Enter换行 · {{ question.length }} / 2000</span><div class="business-actions"><el-button :disabled="!agent.canStop" @click="agent.stop">停止生成</el-button><el-button type="primary" native-type="submit" :disabled="!agent.canSend || !question.trim()">发送</el-button></div></div></form>
</template>

<style scoped>
.chat-history { margin: 1rem 0; }
.chat-history-header { display: flex; align-items: center; gap: .75rem; flex-wrap: wrap; }
.chat-sessions { display: flex; flex-wrap: wrap; gap: .75rem 1rem; padding: 0; list-style: none; }
.chat-sessions li { display: flex; align-items: center; gap: .5rem; flex-wrap: wrap; }
</style>
