import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet } from '../utils/api.js'

export const useAiStore = defineStore('ai', () => {
  const agentType = ref('java')
  const mcpType = ref('java')
  const agentPort = ref(8081)
  const loading = ref(false)
  const availableAgents = ref(['java', 'python'])
  const availableMcps = ref(['java', 'python'])

  async function fetchConfig() {
    loading.value = true
    try {
      const data = await apiGet('/api/config')
      agentType.value = data.agent || 'java'
      mcpType.value = data.mcp || 'java'
      agentPort.value = data.agent_port || 8081
      availableAgents.value = data.available_agents || ['java', 'python']
      availableMcps.value = data.available_mcps || ['java', 'python']
    } catch (e) {
      console.warn('获取 AI 配置失败，使用默认值:', e.message)
    } finally {
      loading.value = false
    }
  }

  function getAgentLabel(type) {
    return type === 'java' ? 'Java (Spring AI)' : 'Python (LangChain)'
  }

  return {
    agentType, mcpType, agentPort, loading,
    availableAgents, availableMcps,
    fetchConfig, getAgentLabel,
  }
})
