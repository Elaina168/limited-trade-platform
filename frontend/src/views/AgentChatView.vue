<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElCollapse, ElCollapseItem, ElDrawer } from 'element-plus'
import type { InputInstance } from 'element-plus'
import 'element-plus/es/components/collapse/style/css'
import 'element-plus/es/components/collapse-item/style/css'
import 'element-plus/es/components/drawer/style/css'
import { useRoute } from 'vue-router'
import AgentConversationList from '../components/AgentConversationList.vue'
import { useVisibilityRefresh } from '../composables/useVisibilityRefresh.ts'
import { useAuthStore } from '../stores/auth.ts'
import { useAgentStore } from '../stores/agent.ts'
import { orderStatusLabels } from '../utils/orders.ts'

const route = useRoute()
const auth = useAuthStore()
const agent = useAgentStore()
const question = ref('')
const composer = ref<InputInstance>()
const orderFocusPending = ref(false)
const log = ref<HTMLElement | null>(null)
const compact = window.matchMedia('(max-width: 960px)')
const mobile = ref(compact.matches)
const sessionsOpen = ref(false)
const stateLabels = { connecting: '正在连接', streaming: '正在检索规则与订单…', complete: '已完成', stopped: '已停止（未确认完成）', incomplete: '未完成' }
const prompts = [
  '查一下我最近的待支付订单',
  '限时活动的满减券怎么领取和使用？',
  '下单后多久不付款会自动关单？',
  '取消订单后优惠券和库存会退回吗？',
]
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })

async function send(input: string) {
  if (!agent.canSend) return
  const text = input.trim()
  if (!text || text.length > 2000) {
    agent.error = '请输入1至2000字符的问题。'
    return
  }
  question.value = ''
  await agent.send(text)
}

function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    if (question.value.trim()) void send(question.value)
  }
}

function changeLayout(event: MediaQueryListEvent) {
  mobile.value = event.matches
  if (!event.matches) sessionsOpen.value = false
}

watch([() => auth.user?.id, () => auth.user?.role], () => {
  question.value = ''
  orderFocusPending.value = false
  sessionsOpen.value = false
}, { flush: 'sync' })
watch([() => route.query.orderId, () => auth.user?.id], ([value]) => {
  orderFocusPending.value = false
  if (auth.user && typeof value === 'string' && /^[1-9][0-9]*$/.test(value) && Number.isSafeInteger(Number(value))) {
    question.value = '请查询订单 #' + value + ' 的当前状态。'
    orderFocusPending.value = true
  }
}, { immediate: true })
watch([orderFocusPending, () => agent.canSend, composer], ([pending, ready, input]) => {
  if (pending && ready && input) {
    input.focus()
    orderFocusPending.value = false
  }
}, { flush: 'post' })
watch([() => agent.messages.length, () => agent.messages.at(-1)?.text, () => agent.messages.at(-1)?.state], async () => {
  const element = log.value
  const follow = element && element.scrollHeight - element.scrollTop - element.clientHeight < 90
  await nextTick()
  if (follow && log.value === element) element.scrollTop = element.scrollHeight
})
onMounted(() => {
  compact.addEventListener('change', changeLayout)
  void agent.open()
})
useVisibilityRefresh(() => { void agent.pageTo(agent.page) }, () => !agent.canSend || sessionsOpen.value)
onUnmounted(() => {
  compact.removeEventListener('change', changeLayout)
  agent.leave()
})
</script>

<template>
  <div class="assistant-page">
    <header class="assistant-header">
      <h1 class="page-title">交易助手</h1>
      <el-button v-if="mobile" @click="sessionsOpen = true">我的会话</el-button>
    </header>
    <div class="assistant-workspace">
      <aside v-if="!mobile" class="assistant-sidebar" aria-label="我的会话">
        <AgentConversationList />
      </aside>
      <ElDrawer v-else v-model="sessionsOpen" title="我的会话" direction="ltr" size="min(88vw, 320px)" append-to-body>
        <AgentConversationList @selected="sessionsOpen = false" />
      </ElDrawer>

      <div class="assistant-panel">
        <div ref="log" class="transcript" role="log" aria-label="聊天记录" tabindex="0" aria-live="off">
          <section v-if="!agent.messages.length" class="assistant-welcome">
            <span class="welcome-mark" aria-hidden="true">✦</span>
            <h2>有什么交易问题想了解？</h2>
            <p>可以查询你的订单和解答平台规则。助手只读，支付、取消和退款由你处理。</p>
            <div class="prompt-list">
              <button v-for="prompt in prompts" :key="prompt" type="button" :disabled="!agent.canSend" @click="send(prompt)">
                {{ prompt }}
              </button>
            </div>
          </section>
          <article v-for="message in agent.messages" :key="message.id" class="message-row" :class="message.role">
            <div class="message-bubble">
              <div class="message-heading">
                <strong>{{ message.role === 'user' ? '你' : '交易助手' }}</strong>
                <span v-if="message.role === 'assistant'" class="message-state"
                  :class="{ 'message-progress': message.state === 'connecting' || message.state === 'streaming' }">
                  {{ stateLabels[message.state] }}
                </span>
              </div>
              <div v-if="message.text" class="message-text" :class="{ typing: message.state === 'streaming' }">{{ message.text }}</div>
              <template v-if="message.state === 'complete' && message.result">
                <section v-if="message.result.orders.length" class="linked-orders" aria-label="关联订单">
                  <h3>关联订单</h3>
                  <article v-for="order in message.result.orders" :key="order.id" class="linked-order">
                    <div class="linked-order-data">
                      <strong>订单 #{{ order.id }}</strong>
                      <span class="order-status">{{ orderStatusLabels[order.status] }}</span>
                      <span class="order-amount">应付 {{ money.format(order.totalAmount) }}</span>
                    </div>
                    <RouterLink class="text-link" :to="{ name: 'order-detail', params: { id: order.id } }">
                      {{ order.status === 'PENDING_PAYMENT' && Date.parse(order.expiresAt) > Date.now() ? '去支付' : '查看订单' }}
                    </RouterLink>
                  </article>
                </section>
                <ElCollapse v-if="message.result.sources.length || message.result.knowledgeSources.length" class="message-sources">
                  <ElCollapseItem :title="'查看规则来源（' + (message.result.sources.length + message.result.knowledgeSources.length) + '）'">
                    <article v-for="source in message.result.knowledgeSources" :key="'knowledge:' + source.id" class="rule-source">
                      <div><strong>{{ source.title }}</strong><span class="line-label">第 {{ source.startLine }}—{{ source.endLine }} 行</span></div>
                      <blockquote>{{ source.text }}</blockquote>
                    </article>
                    <article v-for="source in message.result.sources" :key="'rule:' + source.id" class="rule-source">
                      <div><strong>{{ source.id }}</strong></div>
                      <blockquote>{{ source.text }}</blockquote>
                    </article>
                  </ElCollapseItem>
                </ElCollapse>
              </template>
            </div>
          </article>
        </div>
        <form class="assistant-composer" @submit.prevent="send(question)">
          <p v-if="agent.error" class="form-error" role="alert">{{ agent.error }}</p>
          <p class="assistant-notice" role="status" aria-live="polite">{{ agent.notice }}</p>
          <div class="composer-box">
            <label for="agent-question">你的问题</label>
            <el-input id="agent-question" ref="composer" v-model="question" type="textarea" :rows="2" resize="none"
              maxlength="2000" :disabled="!agent.canSend" placeholder="输入订单或交易规则问题" @keydown="keydown" />
            <div class="composer-footer">
              <span class="input-count"><span class="keyboard-hint">Enter 发送 · Shift＋Enter 换行 · </span>{{ question.length }} / 2000</span>
              <el-button v-if="agent.canStop" type="danger" plain @click="agent.stop">停止生成</el-button>
              <el-button v-else type="primary" native-type="submit" :disabled="!agent.canSend || !question.trim()">发送</el-button>
            </div>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.assistant-page { color: #193551; }
.assistant-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.assistant-header .page-title { margin: 0; }
.assistant-workspace {
  display: grid; grid-template-columns: 240px minmax(0, 1fr); overflow: hidden;
  border: 1px solid #dce7f1; border-radius: 16px; background: #f3f7fc;
}
.assistant-sidebar { min-width: 0; border-right: 1px solid #dce7f1; background: #f8fbff; }
.assistant-panel { display: grid; min-width: 0; background: linear-gradient(145deg, #eff6fd, #f8faff 70%); }
.transcript { height: clamp(320px, 55svh, 670px); min-width: 0; overflow-y: auto; padding: 22px; }
.transcript:focus-visible { outline-offset: -3px; }
.assistant-welcome { padding: 25px 4px 12px; text-align: center; }
.assistant-welcome h2 { margin: 12px 0 5px; font-size: clamp(20px, 3vw, 26px); }
.assistant-welcome p { margin: 0 auto; max-width: 34rem; color: #4c637a; font-size: 14px; line-height: 1.7; }
.welcome-mark {
  display: inline-grid; place-items: center; width: 45px; height: 45px; border-radius: 14px;
  background: #193f66; color: #fff; font-size: 23px; box-shadow: 0 6px 12px #1f4e7133;
}
.prompt-list { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; max-width: 480px; margin: 18px auto 0; }
.prompt-list button {
  border: 1px solid #c3d7ec; border-radius: 999px; padding: 8px 13px; color: #234e75; background: #fff;
  font: inherit; font-size: 13px; text-align: left; cursor: pointer;
}
.prompt-list button:hover:enabled { border-color: #6e9bcc; background: #edf6ff; }
.prompt-list button:disabled { cursor: default; }
.message-row { display: flex; margin-bottom: 15px; }
.message-row.user { justify-content: flex-end; }
.message-bubble {
  max-width: min(92%, 760px); padding: 12px 16px; border: 1px solid #dce7f1; border-radius: 5px 15px 15px 15px;
  background: #fff; box-shadow: 0 2px 5px #17395e0f; overflow-wrap: anywhere;
}
.user .message-bubble { border: 0; border-radius: 15px 5px 15px 15px; background: #183e64; color: #fff; }
.message-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 12px; }
.message-heading strong { padding: 2px 6px; border-radius: 5px; color: #22496e; background: #edf4fd; }
.user .message-heading strong { background: #ffffff26; color: #fff; }
.message-state { color: #526780; }
.message-progress::before, .typing::after {
  display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: #237ba7;
  content: ""; animation: agent-pulse 1.4s ease-in-out infinite;
}
.message-progress::before { margin-right: 6px; vertical-align: 2px; }
.typing::after { width: 2px; height: 1em; border-radius: 1px; margin-left: 3px; vertical-align: -.12em; }
@keyframes agent-pulse { 50% { opacity: .25; } }
@media (prefers-reduced-motion: reduce) { .message-progress::before, .typing::after { animation: none; } }
.message-text { margin-top: 7px; color: inherit; font-size: 14px; line-height: 1.75; white-space: pre-wrap; }
.linked-orders { margin-top: 12px; }
.linked-orders h3 { margin: 0 0 5px; font-size: 12px; }
.linked-order {
  display: flex; justify-content: space-between; flex-wrap: wrap; align-items: center; gap: 6px 12px;
  padding: 8px 9px; margin-top: 5px; border: 1px solid #dbe7f3; border-radius: 8px; background: #f6faff; font-size: 12px;
}
.linked-order-data { display: flex; align-items: center; flex-wrap: wrap; gap: 5px 8px; }
.order-status, .line-label { border-radius: 5px; padding: 2px 5px; background: #e7f0fb; color: #2b577f; }
.order-amount { color: #405a76; }
.message-sources { margin-top: 12px; --el-collapse-border-color: #dce7f1; --el-collapse-header-height: 37px; }
.rule-source { padding: 5px 0; }
.rule-source > div { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; font-size: 12px; }
.rule-source blockquote {
  margin: 5px 0 0; padding: 6px 9px; border-left: 2px solid #8eb6dc;
  border-radius: 3px; background: #f4f8fc; color: #405972; white-space: pre-wrap;
}
.assistant-composer { min-width: 0; padding: 0 18px 16px; }
.assistant-notice { margin: 0 0 5px; color: #526781; font-size: 12px; }
.composer-box { padding: 10px 12px 8px; border: 1px solid #c3d5e8; border-radius: 13px; background: #fff; }
.composer-box:focus-within { border-color: #5590c8; }
.composer-box label { display: block; font-size: 12px; font-weight: 600; }
.composer-box :deep(.el-textarea__inner) { padding-inline: 0; box-shadow: none; }
.composer-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-top: 5px; }
.input-count { color: #526781; font-size: 12px; }
@media (max-width: 960px) {
  .assistant-workspace { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 520px) {
  .transcript { height: clamp(310px, 51svh, 450px); padding: 12px; }
  .assistant-welcome { padding-top: 12px; }
  .assistant-composer { padding: 0 10px 10px; }
  .message-bubble { padding: 9px 10px; }
  .keyboard-hint { display: none; }
}
</style>
