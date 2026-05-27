import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '../../src/stores/aiStore.js'
import { apiGet, apiPost } from '../../src/utils/api.js'

vi.mock('../../src/utils/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

describe('aiStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
    // mock fetch for _waitForAgentReady
    global.fetch = vi.fn().mockResolvedValue({ ok: true })
  })

  it('默认 agentType 为 java', () => {
    const store = useAiStore()
    expect(store.agentType).toBe('java')
  })

  it('默认 mcpType 为 java', () => {
    const store = useAiStore()
    expect(store.mcpType).toBe('java')
  })

  it('agentApiPrefix 当 agentType 为 java 时返回 /api-java', () => {
    const store = useAiStore()
    expect(store.agentApiPrefix).toBe('/api-java')
  })

  it('agentApiPrefix 当 agentType 为 python 时返回 /api-py', () => {
    const store = useAiStore()
    store.agentType = 'python'
    expect(store.agentApiPrefix).toBe('/api-py')
  })

  it('agentLabel 当 java 时为 Java Agent', () => {
    const store = useAiStore()
    expect(store.agentLabel).toBe('Java Agent')
  })

  it('agentLabel 当 python 时为 Python Agent', () => {
    const store = useAiStore()
    store.agentType = 'python'
    expect(store.agentLabel).toBe('Python Agent')
  })

  it('mcpLabel 当 java 时为 Java MCP', () => {
    const store = useAiStore()
    expect(store.mcpLabel).toBe('Java MCP')
  })

  it('mcpLabel 当 python 时为 Python MCP', () => {
    const store = useAiStore()
    store.mcpType = 'python'
    expect(store.mcpLabel).toBe('Python MCP')
  })

  it('comboLabel 组合 agentLabel 和 mcpLabel', () => {
    const store = useAiStore()
    expect(store.comboLabel).toBe('Java Agent + Java MCP')
  })

  it('comboLabel 跟随切换变化', () => {
    const store = useAiStore()
    store.agentType = 'python'
    store.mcpType = 'python'
    expect(store.comboLabel).toBe('Python Agent + Python MCP')
  })

  it('switchAgent 切换 agentType 并写入 localStorage', async () => {
    apiGet.mockResolvedValue({ mcp: 'java' })
    const store = useAiStore()
    await store.switchAgent('python')
    expect(store.agentType).toBe('python')
    expect(localStorage.getItem('finance-agent-type')).toBe('python')
  })

  it('switchAgent 相同值不重复切换', async () => {
    const store = useAiStore()
    await store.switchAgent('java')
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('switchAgent 切换后同步远端 mcpType', async () => {
    apiGet.mockResolvedValue({ mcp: 'python' })
    const store = useAiStore()
    await store.switchAgent('python')
    expect(store.mcpType).toBe('python')
    expect(localStorage.getItem('finance-mcp-type')).toBe('python')
  })

  it('switchMcp 切换 mcpType 并更新 mcpSwitching 状态', async () => {
    apiPost.mockResolvedValue({})
    const store = useAiStore()
    const promise = store.switchMcp('python')
    expect(store.mcpSwitching).toBe(true)
    await promise
    expect(store.mcpType).toBe('python')
    expect(store.mcpSwitching).toBe(false)
    expect(localStorage.getItem('finance-mcp-type')).toBe('python')
  })

  it('switchMcp 相同值不切换', async () => {
    const store = useAiStore()
    await store.switchMcp('java')
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('从 localStorage 恢复 agentType', () => {
    localStorage.setItem('finance-agent-type', 'python')
    setActivePinia(createPinia())
    const store = useAiStore()
    expect(store.agentType).toBe('python')
  })

  it('从 localStorage 恢复 mcpType', () => {
    localStorage.setItem('finance-mcp-type', 'python')
    setActivePinia(createPinia())
    const store = useAiStore()
    expect(store.mcpType).toBe('python')
  })

  it('fetchConfig 获取远端配置并同步 mcpType', async () => {
    apiGet.mockResolvedValue({ mcp: 'python' })
    const store = useAiStore()
    await store.fetchConfig()
    expect(store.mcpType).toBe('python')
    expect(store.loading).toBe(false)
  })
})
