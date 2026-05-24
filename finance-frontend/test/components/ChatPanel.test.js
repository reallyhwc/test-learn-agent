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

// Mock api.js
vi.mock('../../src/utils/api.js', () => ({
  apiGet: vi.fn().mockResolvedValue([]),
  apiPost: vi.fn(),
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
})
