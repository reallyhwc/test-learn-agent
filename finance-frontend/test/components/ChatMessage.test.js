import { describe, it, expect, vi } from 'vitest'

vi.mock('../../src/components/ChartRenderer.vue', () => ({
  default: { template: '<div class="chart-stub"></div>' },
}))

import { mount } from '@vue/test-utils'
import ChatMessage from '../../src/components/ChatMessage.vue'

describe('ChatMessage', () => {
  it('user 消息总是纯文本（不跑 markdown）', () => {
    const w = mount(ChatMessage, {
      props: { role: 'user', text: '**加粗** 测试', streaming: false, id: 'u1' },
    })
    expect(w.text()).toContain('**加粗** 测试')
    expect(w.html()).not.toContain('<strong>')
  })

  it('assistant + streaming=true → 纯文本，无 marked', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '## 标题\n正文', streaming: true, id: 'a1' },
    })
    expect(w.text()).toContain('## 标题')
    expect(w.html()).not.toContain('<h2>')
  })

  it('assistant + streaming=false → markdown 渲染', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '## 标题\n正文', streaming: false, id: 'a2' },
    })
    expect(w.html()).toContain('<h2')
    expect(w.text()).toContain('标题')
  })

  it('streaming 由 true → false 切换时触发 markdown 渲染', async () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '**粗**', streaming: true, id: 'a3' },
    })
    expect(w.html()).not.toContain('<strong>')
    await w.setProps({ streaming: false })
    expect(w.html()).toContain('<strong>')
  })

  it('XSS payload 被剥离', () => {
    const w = mount(ChatMessage, {
      props: {
        role: 'assistant',
        text: '<img src=x onerror="alert(1)">',
        streaming: false,
        id: 'a4',
      },
    })
    expect(w.html()).not.toMatch(/onerror/i)
  })

  it('feedback 按钮 streaming=true 时不显示', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '答', streaming: true, id: 'a5' },
    })
    expect(w.find('.feedback-btn').exists()).toBe(false)
  })

  it('feedback 按钮 streaming=false 时显示', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '答', streaming: false, id: 'a6' },
    })
    expect(w.find('.feedback-btn').exists()).toBe(true)
  })
})
