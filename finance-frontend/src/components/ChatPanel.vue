<template>
  <div class="chat-panel">
    <div class="chat-header">
      AI 助手
      <span class="memory-info" v-if="memoryCount > 0">记忆: {{ memoryCount }}/20 条</span>
    </div>
    <div class="chat-messages" ref="msgContainer">
      <ChatMessage
        v-for="m in messages"
        :key="m.id"
        :role="m.role"
        :text="m.text"
        :id="m.id"
        :streaming="m.streaming"
      />
      <div v-if="thinking && !messages.length" class="thinking">思考中...</div>
      <div
        v-if="thinking && messages.length && messages[messages.length-1].role === 'assistant' && messages[messages.length-1].streaming"
        class="streaming-dot"
      ></div>
    </div>
    <div class="chat-input">
      <el-input
        v-model="input"
        @keyup.enter="send"
        placeholder="比如：我的余额是多少？"
        :disabled="thinking"
        clearable
      />
      <el-button type="primary" @click="send" :disabled="thinking" style="margin-left: 8px">发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onUnmounted } from 'vue'
import ChatMessage from './ChatMessage.vue'
import { userStore } from '../stores/userStore.js'
import { createStreamBuffer } from '../utils/streamParser.js'

const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgContainer = ref(null)
const memoryCount = ref(0)

let scrollFrame = null
function scheduleScrollToBottom() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = null
    if (msgContainer.value) {
      msgContainer.value.scrollTop = msgContainer.value.scrollHeight
    }
  })
}

watch(
  () => userStore.currentUser,
  () => {
    messages.value = []
    memoryCount.value = 0
    thinking.value = false
  },
)

watch(() => messages.value.length, scheduleScrollToBottom)

onUnmounted(() => {
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
})

let nextMsgId = 1
function newId(role) {
  return `${role}-${Date.now()}-${nextMsgId++}`
}

async function send() {
  if (!input.value.trim() || thinking.value) return
  const text = input.value
  console.time('[Agent] 总耗时')
  input.value = ''

  messages.value.push({ id: newId('user'), role: 'user', text, streaming: false })
  const assistantMsg = { id: newId('assistant'), role: 'assistant', text: '', streaming: true }
  messages.value.push(assistantMsg)
  const assistantIdx = messages.value.length - 1

  thinking.value = true
  const requestStart = performance.now()
  let firstToken = false
  const buf = createStreamBuffer()

  try {
    const res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text, userId: userStore.currentUser }),
    })

    const reader = res.body.getReader()
    const decoder = new TextDecoder()

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      const tokens = buf.feed(chunk)
      if (tokens.length === 0) continue

      if (!firstToken) {
        firstToken = true
        const ttft = performance.now() - requestStart
        console.log('[Agent] TTFT:', Math.round(ttft) + 'ms')
      }

      const appended = tokens.join('')
      messages.value[assistantIdx].text += appended
      scheduleScrollToBottom()
    }

    console.timeEnd('[Agent] 总耗时')
    messages.value[assistantIdx].streaming = false
    memoryCount.value = messages.value.filter((m) => m.role === 'user').length * 2
  } catch (e) {
    console.error('[Agent] Error:', e)
    if (!messages.value[assistantIdx].text) {
      messages.value[assistantIdx].text = '抱歉，服务暂时不可用'
    }
    messages.value[assistantIdx].streaming = false
  } finally {
    thinking.value = false
  }
}
</script>

<style scoped>
.chat-panel {
  display: flex; flex-direction: column; height: 100%;
  background: var(--el-bg-color);
}
.chat-header {
  padding: 12px 16px; font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-light);
}
.chat-messages {
  flex: 1; overflow-y: auto; padding: 12px;
}
.chat-input {
  display: flex; padding: 12px; gap: 0;
  border-top: 1px solid var(--el-border-color-light);
}
.memory-info {
  float: right;
  font-size: 0.8rem;
  color: var(--el-text-color-secondary);
  font-weight: normal;
}
.thinking { color: var(--el-text-color-secondary); font-size: 0.85rem; font-style: italic; padding: 8px; }
.streaming-dot {
  width: 8px; height: 8px; background: var(--el-color-primary);
  border-radius: 50%; animation: pulse 1s infinite; margin: 8px;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
