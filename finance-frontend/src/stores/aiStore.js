import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiGet, apiPost } from '../utils/api.js'

const AGENT_KEY = 'finance-agent-type'
const MCP_KEY = 'finance-mcp-type'

export const useAiStore = defineStore('ai', () => {
  const agentType = ref(localStorage.getItem(AGENT_KEY) || 'java')
  const mcpType = ref(localStorage.getItem(MCP_KEY) || 'java')
  const loading = ref(false)
  const mcpSwitching = ref(false)

  const agentApiPrefix = computed(() => {
    return agentType.value === 'python' ? '/api-py' : '/api-java'
  })

  const agentLabel = computed(() => {
    return agentType.value === 'java' ? 'Java Agent' : 'Python Agent'
  })

  const mcpLabel = computed(() => {
    return mcpType.value === 'java' ? 'Java MCP' : 'Python MCP'
  })

  const comboLabel = computed(() => {
    return `${agentLabel.value} + ${mcpLabel.value}`
  })

  async function switchAgent(type) {
    if (type === agentType.value) return
    agentType.value = type
    localStorage.setItem(AGENT_KEY, type)
    // 切换 Agent 后查询新 Agent 的实际 MCP 配置并同步前端状态
    // 两个 Agent 是独立进程，各自连着自己的 MCP
    try {
      const data = await apiGet(`${agentApiPrefix.value}/config`)
      if (data.mcp && data.mcp !== mcpType.value) {
        mcpType.value = data.mcp
        localStorage.setItem(MCP_KEY, data.mcp)
      }
    } catch (_) {
      // Agent 可能不在线，保持前端当前 mcpType
    }
  }

  async function switchMcp(type) {
    if (type === mcpType.value) return
    mcpSwitching.value = true
    mcpType.value = type
    localStorage.setItem(MCP_KEY, type)
    try {
      await apiPost(`${agentApiPrefix.value}/switch-mcp`, { mcpType: type })
      // Agent 进程正在重启，轮询 health 端点等待就绪
      await _waitForAgentReady()
    } catch (e) {
      console.error('MCP 切换失败:', e.message)
      throw e
    } finally {
      mcpSwitching.value = false
    }
  }

  async function _waitForAgentReady(maxAttempts = 15, intervalMs = 1000) {
    // 重启流程：kill(0.5s) → sleep(2s) → 启动(3-8s)
    // 先等 3s 让旧进程完全退出，避免轮询到旧进程的 health 200
    await new Promise(resolve => setTimeout(resolve, 3000))

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        const response = await fetch(`${agentApiPrefix.value}/actuator/health`)
        if (response.ok) return
      } catch (_) {
        // Agent 尚未就绪，继续等待
      }
      await new Promise(resolve => setTimeout(resolve, intervalMs))
    }
    console.warn('Agent 重启等待超时，可能仍在启动中')
  }

  async function fetchConfig() {
    loading.value = true
    try {
      const data = await apiGet(`${agentApiPrefix.value}/config`)
      if (data.mcp) mcpType.value = data.mcp
    } catch (e) {
      console.warn('获取 AI 配置失败，使用默认值:', e.message)
    } finally {
      loading.value = false
    }
  }

  return {
    agentType, mcpType, loading, mcpSwitching,
    agentApiPrefix, agentLabel, mcpLabel, comboLabel,
    switchAgent, switchMcp, fetchConfig,
  }
})
