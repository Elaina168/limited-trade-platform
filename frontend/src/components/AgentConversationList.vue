<script setup lang="ts">
import { ElPopconfirm } from 'element-plus'
import 'element-plus/es/components/popconfirm/style/css'
import { useAgentStore } from '../stores/agent.ts'

const agent = useAgentStore()
const emit = defineEmits<{ selected: [] }>()

async function create() {
  const previous = agent.conversationId
  await agent.newConversation()
  if (agent.conversationId && agent.conversationId !== previous) emit('selected')
}

async function select(id: string) {
  await agent.select(id)
  if (agent.conversationId === id && !agent.error) emit('selected')
}
</script>

<template>
  <div class="conversations">
    <el-button class="new-conversation" type="primary" :disabled="!agent.canSend" @click="create">+ 新建对话</el-button>
    <div class="list-heading">
      <strong>我的会话</strong>
      <div v-if="agent.page > 1 || agent.hasNext" class="conversation-pages">
        <el-button text size="small" :disabled="!agent.canSend || agent.page === 1" @click="agent.pageTo(agent.page - 1)">上一页</el-button>
        <el-button text size="small" :disabled="!agent.canSend || !agent.hasNext" @click="agent.pageTo(agent.page + 1)">下一页</el-button>
      </div>
    </div>
    <p v-if="!agent.conversations.length" class="empty-conversations">{{ agent.action === 'history' ? '正在加载会话…' : '此页暂无会话' }}</p>
    <ul v-else class="conversation-list">
      <li v-for="item in agent.conversations" :key="item.conversationId">
        <button type="button" class="conversation-link" :disabled="!agent.canSend"
          :data-conversation-id="item.conversationId" :aria-pressed="item.conversationId === agent.conversationId"
          @click="select(item.conversationId)">
          <strong>{{ item.title }}</strong>
          <span class="conversation-detail">有效至 {{ item.expiresAt.slice(0, 16).replace('T', ' ') }}</span>
          <span class="conversation-detail">#{{ item.conversationId.slice(0, 8) }}</span>
        </button>
      </li>
    </ul>
    <div v-if="agent.conversationId" class="conversation-actions">
      <ElPopconfirm title="清空当前会话的问答与模型上下文？" :width="230" :disabled="!agent.canSend"
        confirm-button-text="确认清空" cancel-button-text="取消" @confirm="agent.clear">
        <template #reference><el-button text size="small" :disabled="!agent.canSend">清空历史</el-button></template>
      </ElPopconfirm>
      <ElPopconfirm title="删除当前会话及其历史？" :width="210" :disabled="!agent.canSend" confirm-button-type="danger"
        confirm-button-text="确认删除" cancel-button-text="取消" @confirm="agent.remove">
        <template #reference><el-button text size="small" type="danger" :disabled="!agent.canSend">删除会话</el-button></template>
      </ElPopconfirm>
    </div>
  </div>
</template>

<style scoped>
.conversations { padding: 20px 16px; min-width: 0; }
.new-conversation { width: 100%; }
.list-heading { display: flex; align-items: center; justify-content: space-between; gap: 4px; margin: 18px 0 8px; font-size: 13px; }
.conversation-pages { display: flex; }
.conversation-pages .el-button, .conversation-actions .el-button { margin: 0; padding-inline: 6px; }
.empty-conversations { color: #536478; font-size: 13px; }
.conversation-list { display: grid; gap: 7px; margin: 0; padding: 0; list-style: none; }
.conversation-link {
  display: grid; gap: 3px; width: 100%; padding: 9px 11px; border: 1px solid transparent; border-radius: 9px;
  background: #fff; color: #1b3550; font: inherit; text-align: left; cursor: pointer; overflow-wrap: anywhere;
}
.conversation-link:hover:enabled { border-color: #b4cee9; }
.conversation-link:disabled { cursor: default; }
.conversation-link[aria-pressed="true"] { border-color: #90b9e3; background: #e9f3ff; }
.conversation-link strong { font-size: 13px; }
.conversation-detail { color: #526781; font-size: 11px; }
.conversation-actions { display: flex; gap: 4px; margin-top: 12px; }
</style>
