<template>
  <div class="chat-panel" role="region" aria-label="AI助手对话">
    <div class="chat-header">
      AI 助手
      <span class="memory-info" v-if="memoryCount > 0">记忆: {{ memoryCount }}/20 条</span>
    </div>
    <div class="chat-messages" ref="msgContainer" role="log" aria-live="polite">
      <ChatMessage
        v-for="m in messages"
        :key="m.id"
        :role="m.role"
        :text="m.text"
        :thinking="m.thinking"
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
        aria-label="输入消息"
      />
      <el-button
        v-if="!thinking"
        type="primary"
        @click="send"
        style="margin-left: 8px"
      >发送</el-button>
      <el-button
        v-else
        type="danger"
        plain
        @click="abort"
        style="margin-left: 8px"
      >⏹ 停止</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onUnmounted } from 'vue'
import ChatMessage from './ChatMessage.vue'
import { useUserStore } from '../stores/userStore.js'
import { useAiStore } from '../stores/aiStore.js'
import { createStreamBuffer } from '../utils/streamParser.js'
import { apiGet } from '../utils/api.js'

const userStore = useUserStore()
const aiStore = useAiStore()

const MAX_MESSAGES = 100

const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgContainer = ref(null)
const memoryCount = ref(0)

let activeController = null

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
    if (activeController) activeController.abort()
    messages.value = []
    memoryCount.value = 0
    thinking.value = false
  },
)

watch(
  () => aiStore.agentType,
  () => {
    if (activeController) activeController.abort()
    messages.value = [{
      id: newId('system'),
      role: 'assistant',
      text: `🔄 已切换到 **${aiStore.comboLabel}**`,
      streaming: false,
    }]
    memoryCount.value = 0
    thinking.value = false
    refreshMemoryCount()
  },
)

watch(
  () => aiStore.mcpType,
  () => {
    if (activeController) activeController.abort()
    memoryCount.value = 0

    if (aiStore.mcpSwitching) {
      // 由 switchMcp 触发：MCP 正在切换，Agent 将重启，阻止用户发消息
      messages.value = [{
        id: newId('system'),
        role: 'assistant',
        text: `⏳ MCP 切换中，正在重启 Agent 连接到 **${aiStore.mcpLabel}**...`,
        streaming: false,
      }]
      thinking.value = true
    } else {
      // 由 switchAgent 的 fetchConfig 触发：仅同步显示，不阻止发消息
      messages.value = [{
        id: newId('system'),
        role: 'assistant',
        text: `🔄 已切换到 **${aiStore.comboLabel}**`,
        streaming: false,
      }]
      thinking.value = false
    }
  },
)

watch(
  () => aiStore.mcpSwitching,
  (switching) => {
    if (!switching && messages.value.length > 0) {
      // 重启完成（mcpSwitching 从 true → false），更新提示
      messages.value = [{
        id: newId('system'),
        role: 'assistant',
        text: `✅ 已切换到 **${aiStore.comboLabel}**`,
        streaming: false,
      }]
      thinking.value = false
      refreshMemoryCount()
    }
  },
)

watch(() => messages.value.length, scheduleScrollToBottom)

onUnmounted(() => {
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
  if (activeController) activeController.abort()
})

let nextMsgId = 1
function newId(role) {
  return `${role}-${Date.now()}-${nextMsgId++}`
}

async function refreshMemoryCount() {
  try {
    const data = await apiGet(`${aiStore.agentApiPrefix}/memory/count?userId=${encodeURIComponent(userStore.currentUser)}`)
    memoryCount.value = data.count || 0
  } catch (_) {
    // 静默失败：UI 计数不准不致命
  }
}

function abort() {
  if (activeController) {
    activeController.abort()
    activeController = null
  }
}

async function send() {
  if (!input.value.trim() || thinking.value) return
  const text = input.value
  console.time('[Agent] 总耗时')
  input.value = ''

  messages.value.push({ id: newId('user'), role: 'user', text, streaming: false })
  const assistantMsg = {
    id: newId('assistant'),
    role: 'assistant',
    text: '',
    thinking: '',
    streaming: true,
  }
  messages.value.push(assistantMsg)

  // 防止消息过多导致 DOM 性能下降
  if (messages.value.length > MAX_MESSAGES) {
    messages.value = messages.value.slice(-MAX_MESSAGES)
  }

  const assistantIdx = messages.value.length - 1

  thinking.value = true
  const requestStart = performance.now()
  let firstToken = false
  const buf = createStreamBuffer()

  const controller = new AbortController()
  activeController = controller

  // 15s 自动超时：避免网络异常时无限等待
  const timeoutId = setTimeout(() => {
    if (!firstToken) {
      controller.abort()
    }
  }, 15000)

  // TTFT 超过 3s 时显示"正在思考"提示
  const thinkingHintId = setTimeout(() => {
    if (!firstToken && messages.value[assistantIdx]) {
      messages.value[assistantIdx].thinking = '正在思考中…'
    }
  }, 3000)

  try {
    const res = await fetch(`${aiStore.agentApiPrefix}/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text, userId: userStore.currentUser }),
      signal: controller.signal,
    })

    if (!res.ok) {
      let errorMsg = '服务暂时不可用'
      try {
        const errorData = await res.json()
        errorMsg = errorData.message || errorMsg
      } catch (_) {}
      messages.value[assistantIdx].text = `⚠️ ${errorMsg}`
      messages.value[assistantIdx].streaming = false
      thinking.value = false
      activeController = null
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      const events = buf.feed(chunk)
      if (events.length === 0) continue

      if (!firstToken) {
        firstToken = true
        const ttft = performance.now() - requestStart
        console.log('[Agent] TTFT:', Math.round(ttft) + 'ms')
      }

      for (const evt of events) {
        if (evt.type === 'error') {
          messages.value[assistantIdx].text =
            (messages.value[assistantIdx].text || '') +
            (messages.value[assistantIdx].text ? '\n\n' : '') +
            `⚠️ ${evt.payload}`
        } else if (evt.type === 'thinking') {
          messages.value[assistantIdx].thinking =
            (messages.value[assistantIdx].thinking || '') + evt.payload
        } else {
          messages.value[assistantIdx].text += evt.payload
        }
      }
      scheduleScrollToBottom()
    }

    console.timeEnd('[Agent] 总耗时')
    messages.value[assistantIdx].streaming = false
    refreshMemoryCount()
  } catch (e) {
    if (e.name === 'AbortError') {
      if (!firstToken) {
        // 超时触发的 abort（15s 内无首 token）
        console.warn('[Agent] 超时中止（15s 无响应）')
        messages.value[assistantIdx].text = '⚠️ 响应超时，请简化问题或稍后重试'
        messages.value[assistantIdx].thinking = ''
      } else {
        // 用户手动中止
        console.log('[Agent] 用户中止')
        messages.value[assistantIdx].text =
          (messages.value[assistantIdx].text || '') +
          (messages.value[assistantIdx].text ? '\n\n' : '') +
          '⏹ 已停止'
      }
    } else {
      console.error('[Agent] Error:', e)
      if (!messages.value[assistantIdx].text) {
        messages.value[assistantIdx].text = '⚠️ 连接中断，请点击重新发送'
      } else {
        messages.value[assistantIdx].text += '\n\n⚠️ 连接中断'
      }
    }
    messages.value[assistantIdx].streaming = false
  } finally {
    clearTimeout(timeoutId)
    clearTimeout(thinkingHintId)
    activeController = null
    thinking.value = false
  }
}
</script>

<style scoped>
.chat-panel {
  display: flex; flex-direction: column; height: 100%;
  background: var(--theme-bg-chat);
  transition: background var(--theme-transition), border-color var(--theme-transition);
}
.chat-header {
  padding: 14px 18px; font-weight: 700;
  color: var(--theme-text-primary);
  border-bottom: 1px solid var(--theme-border-light);
  display: flex; align-items: center; gap: 8px;
}
.chat-messages {
  flex: 1; overflow-y: auto; padding: 14px;
  background: transparent;
}
.chat-input {
  display: flex; padding: 14px; gap: 8px;
  background: var(--theme-bg-chat-input);
  border-top: 1px solid var(--theme-border-light);
  transition: background var(--theme-transition), border-color var(--theme-transition);
}
.chat-input :deep(.el-input__wrapper) {
  border-radius: 20px;
  background: var(--theme-bg-card);
  border: 1.5px solid var(--theme-border-input);
  box-shadow: none;
  transition: border-color var(--theme-transition), box-shadow var(--theme-transition);
}
.chat-input :deep(.el-input__wrapper:focus-within) {
  border-color: var(--theme-border-focus);
  box-shadow: var(--theme-shadow-focus);
}
.chat-input :deep(.el-input__inner) { color: var(--theme-text-primary); }
.chat-input :deep(.el-button--primary) {
  background: var(--theme-primary-gradient);
  color: var(--theme-text-on-primary);
  border: none;
  border-radius: 20px;
  box-shadow: var(--theme-shadow-btn);
  transition: box-shadow 0.2s;
}
.chat-input :deep(.el-button--primary:hover) {
  box-shadow: var(--theme-shadow-btn-hover);
}
.memory-info {
  float: right;
  font-size: 0.8rem;
  color: var(--theme-text-muted);
  font-weight: normal;
}
.thinking { color: var(--theme-text-muted); font-size: 0.85rem; font-style: italic; padding: 8px; }
.streaming-dot {
  width: 8px; height: 8px; background: var(--theme-primary);
  border-radius: 50%; animation: pulse 1s infinite; margin: 8px;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
