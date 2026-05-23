<template>
  <div class="chat-panel">
    <div class="chat-header">AI 助手</div>
    <div class="chat-messages" ref="msgContainer">
      <ChatMessage v-for="(m, i) in messages" :key="i" :role="m.role" :text="m.text" />
      <div v-if="thinking" class="thinking">思考中...</div>
    </div>
    <div class="chat-input">
      <input v-model="input" @keyup.enter="send" placeholder="比如：我的余额是多少？" :disabled="thinking" />
      <button @click="send" :disabled="thinking">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import ChatMessage from './ChatMessage.vue'

const AGENT_BASE = 'http://localhost:8081'
const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgContainer = ref(null)

async function send() {
  if (!input.value.trim() || thinking.value) return
  const text = input.value
  input.value = ''
  messages.value.push({ role: 'user', text })
  thinking.value = true
  try {
    const res = await fetch(`${AGENT_BASE}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text })
    })
    const data = await res.json()
    messages.value.push({ role: 'assistant', text: data.reply })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '抱歉，服务暂时不可用' })
  } finally {
    thinking.value = false
    await nextTick()
    msgContainer.value?.scrollTo({ top: msgContainer.value.scrollHeight, behavior: 'smooth' })
  }
}
</script>

<style scoped>
.chat-panel {
  display: flex; flex-direction: column; height: 100%;
  border-left: 1px solid #eee; background: #fafafa;
}
.chat-header { padding: 12px 16px; font-weight: 600; border-bottom: 1px solid #eee; }
.chat-messages { flex: 1; overflow-y: auto; padding: 12px; }
.chat-input { display: flex; padding: 12px; gap: 8px; border-top: 1px solid #eee; }
.chat-input input { flex: 1; padding: 8px; border: 1px solid #ddd; border-radius: 4px; }
.chat-input button { padding: 8px 16px; background: #3498db; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.thinking { color: #888; font-size: 0.85rem; font-style: italic; }
</style>
