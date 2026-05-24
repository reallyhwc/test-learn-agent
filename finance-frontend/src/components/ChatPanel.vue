<template>
  <div class="chat-panel">
    <div class="chat-header">
      AI 助手
      <span class="memory-info" v-if="memoryCount > 0">记忆: {{ memoryCount }}/20 条</span>
    </div>
    <div class="chat-messages" ref="msgContainer">
      <ChatMessage v-for="(m, i) in messages" :key="i" :role="m.role" :text="m.text" :id="m.id" />
      <div v-if="thinking && !messages.length" class="thinking">思考中...</div>
      <div v-if="thinking && messages.length && messages[messages.length-1].role === 'assistant'" class="streaming-dot"></div>
    </div>
    <div class="chat-input">
      <el-input v-model="input" @keyup.enter="send" placeholder="比如：我的余额是多少？" :disabled="thinking" clearable />
      <el-button type="primary" @click="send" :disabled="thinking" style="margin-left: 8px">发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, watch } from 'vue'
import ChatMessage from './ChatMessage.vue'
import { userStore } from '../stores/userStore.js'

const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgContainer = ref(null)
const memoryCount = ref(0)

// 切换用户时清空对话
watch(() => userStore.currentUser, () => {
  messages.value = []
  memoryCount.value = 0
  thinking.value = false
})

// Auto-scroll to bottom when messages change
watch(() => messages.value.length, () => {
  nextTick(() => {
    if (msgContainer.value) {
      msgContainer.value.scrollTop = msgContainer.value.scrollHeight
    }
  })
})

async function send() {
  if (!input.value.trim() || thinking.value) return
  const text = input.value
  console.time('[Agent] 总耗时')
  input.value = ''
  messages.value.push({ role: 'user', text })
  const assistantIdx = messages.value.length
  messages.value.push({ role: 'assistant', text: '', id: 'msg-' + Date.now() })
  thinking.value = true
  const requestStart = performance.now()
  let firstToken = false

  try {
    const res = await fetch(`/api/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text, userId: userStore.currentUser })
    })

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const payload = line.slice(5)
          const content = payload.startsWith(' ') ? payload.slice(1) : payload
          if (!firstToken) {
            firstToken = true
            const ttft = performance.now() - requestStart
            console.log('[Agent] TTFT:', Math.round(ttft) + 'ms')
          }
          for (const ch of content) {
            messages.value[assistantIdx].text += ch
            if (msgContainer.value) {
              msgContainer.value.scrollTop = msgContainer.value.scrollHeight
            }
            await new Promise(r => setTimeout(r, 20))
          }
        }
      }
    }
    console.timeEnd('[Agent] 总耗时')
    memoryCount.value = messages.value.filter(m => m.role === 'user').length * 2
  } catch (e) {
    console.error('[Agent] Error:', e)
    if (!messages.value[assistantIdx]?.text) {
      messages.value[assistantIdx].text = '抱歉，服务暂时不可用'
    }
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
