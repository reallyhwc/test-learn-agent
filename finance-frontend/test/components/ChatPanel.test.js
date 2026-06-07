import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'

// Mock ChatMessage 子组件
vi.mock('../../src/components/ChatMessage.vue', () => ({
  default: {
    name: 'ChatMessage',
    props: ['role', 'text', 'thinking', 'id', 'streaming'],
    template: '<div class="chat-msg-stub" :data-role="role">{{ text }}</div>',
  },
}))

// Mock ConfirmationCard 子组件
vi.mock('../../src/components/ConfirmationCard.vue', () => ({
  default: {
    name: 'ConfirmationCard',
    props: ['confirmationId', 'toolName', 'description', 'parameters', 'expiresAt'],
    template: '<div class="confirm-card-stub"><span class="confirm-desc">{{ description }}</span><button class="confirm-btn" @click="$emit(\'confirm\')">确认</button><button class="cancel-btn" @click="$emit(\'cancel\')">取消</button></div>',
  },
}))

// Mock api.js
vi.mock('../../src/utils/api.js', () => ({
  apiGet: vi.fn().mockResolvedValue([]),
  apiPost: vi.fn().mockResolvedValue({}),
  handleApiError: vi.fn(),
}))

// Mock streamParser
vi.mock('../../src/utils/streamParser.js', () => ({
  createStreamBuffer: vi.fn(() => ({
    feed: vi.fn(),
    flush: vi.fn().mockReturnValue([]),
  })),
}))

import { mount } from '@vue/test-utils'
import ChatPanel from '../../src/components/ChatPanel.vue'
import { useAiStore } from '../../src/stores/aiStore.js'
import { apiPost } from '../../src/utils/api.js'

function mountPanel() {
  return mount(ChatPanel, {
    global: { plugins: [createPinia(), ElementPlus] },
  })
}

describe('ChatPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    global.fetch = vi.fn()
  })

  it('渲染基本结构：标题、输入框、发送按钮', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('.chat-header').text()).toContain('AI 助手')
    expect(wrapper.find('.chat-input').exists()).toBe(true)
    expect(wrapper.find('input').exists()).toBe(true)
    expect(wrapper.find('button').exists()).toBe(true)
  })

  it('输入框为空时点击发送不触发请求', async () => {
    const wrapper = mountPanel()
    await wrapper.find('button').trigger('click')
    expect(global.fetch).not.toHaveBeenCalled()
  })

  it('空白字符串不触发发送', async () => {
    const wrapper = mountPanel()
    const inputEl = wrapper.find('input')
    await inputEl.setValue('   ')
    await wrapper.find('button').trigger('click')
    expect(global.fetch).not.toHaveBeenCalled()
  })

  it('发送后触发 fetch', async () => {
    const mockReader = {
      read: vi.fn().mockResolvedValueOnce({ done: true, value: undefined }),
    }
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => mockReader },
    })

    const wrapper = mountPanel()
    await wrapper.find('input').setValue('你好')
    await wrapper.find('button').trigger('click')
    await vi.dynamicImportSettled()

    expect(global.fetch).toHaveBeenCalled()
  })

  it('发送消息后 messages 包含用户消息', async () => {
    const mockReader = {
      read: vi.fn().mockResolvedValueOnce({ done: true, value: undefined }),
    }
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => mockReader },
    })

    const wrapper = mountPanel()
    await wrapper.find('input').setValue('测试消息')
    await wrapper.find('button').trigger('click')
    await vi.dynamicImportSettled()

    const userMsg = wrapper.findAll('.chat-msg-stub').find(el => el.attributes('data-role') === 'user')
    expect(userMsg).toBeTruthy()
  })

  // === Multi-Agent 端点选择 ===

  it('single 模式调用 /chat/stream', async () => {
    localStorage.setItem('finance-agent-mode', 'single')
    const pinia = createPinia()
    setActivePinia(pinia)

    const mockReader = { read: vi.fn().mockResolvedValueOnce({ done: true, value: undefined }) }
    global.fetch = vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => mockReader } })

    const wrapper = mount(ChatPanel, {
      global: { plugins: [pinia, ElementPlus] },
    })
    await wrapper.find('input').setValue('你好')
    await wrapper.find('button').trigger('click')
    await vi.dynamicImportSettled()

    const [url] = global.fetch.mock.calls[0]
    expect(url).toContain('/chat/stream')
    expect(url).not.toContain('multi-agent')
  })

  it('multi 模式调用 /chat/multi-agent/stream', async () => {
    localStorage.setItem('finance-agent-mode', 'multi')
    const pinia = createPinia()
    setActivePinia(pinia)

    const mockReader = { read: vi.fn().mockResolvedValueOnce({ done: true, value: undefined }) }
    global.fetch = vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => mockReader } })

    const wrapper = mount(ChatPanel, {
      global: { plugins: [pinia, ElementPlus] },
    })
    await wrapper.find('input').setValue('你好')
    await wrapper.find('button').trigger('click')
    await vi.dynamicImportSettled()

    const [url] = global.fetch.mock.calls[0]
    expect(url).toContain('/chat/multi-agent/stream')
  })

  // === HITL 确认/取消 ===

  it('handleConfirm 发送确认请求并更新消息', async () => {
    const wrapper = mountPanel()
    const vm = wrapper.vm

    vm.messages = [{
      id: 'confirm-001',
      role: 'confirmation',
      confirmationId: 'test-confirm-id',
      toolName: 'add_transaction',
      description: '添加一笔交易',
      parameters: { amount: 30, type: 'EXPENSE', category: '餐饮' },
    }]

    await vm.handleConfirm(vm.messages[0])

    expect(apiPost).toHaveBeenCalledWith('/api/chat/confirm', { confirmationId: 'test-confirm-id' })
    expect(vm.messages[0].role).toBe('assistant')
    expect(vm.messages[0].text).toBe('操作已执行')
  })

  it('handleCancel 发送取消请求并更新消息', async () => {
    const wrapper = mountPanel()
    const vm = wrapper.vm

    vm.messages = [{
      id: 'confirm-002',
      role: 'confirmation',
      confirmationId: 'test-cancel-id',
      toolName: 'add_transaction',
      description: '添加一笔交易',
      parameters: { amount: 50, type: 'EXPENSE', category: '交通' },
    }]

    await vm.handleCancel(vm.messages[0])

    expect(apiPost).toHaveBeenCalledWith('/api/chat/cancel', { confirmationId: 'test-cancel-id' })
    expect(vm.messages[0].role).toBe('assistant')
    expect(vm.messages[0].text).toBe('操作已取消')
  })

  it('confirmation 消息渲染 ConfirmationCard 组件', async () => {
    const wrapper = mountPanel()

    wrapper.vm.messages = [{
      id: 'confirm-003',
      role: 'confirmation',
      confirmationId: 'test-render-id',
      toolName: 'add_transaction',
      description: '将添加一笔交易',
      parameters: { amount: 100, type: 'EXPENSE', category: '购物' },
    }]

    await wrapper.vm.$nextTick()

    expect(wrapper.find('.confirm-card-stub').exists()).toBe(true)
    expect(wrapper.find('.confirm-desc').text()).toBe('将添加一笔交易')
  })
})
