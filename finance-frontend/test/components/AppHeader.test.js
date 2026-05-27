import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import AppHeader from '../../src/components/AppHeader.vue'

function mountHeader() {
  return mount(AppHeader, {
    global: { plugins: [createPinia(), ElementPlus] },
  })
}

describe('AppHeader', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('渲染标题和副标题', () => {
    const wrapper = mountHeader()
    expect(wrapper.find('h1').text()).toBe('Finance Agent')
    expect(wrapper.text()).toContain('智能记账助手')
  })

  it('渲染 Agent 和 MCP 双选择器区域', () => {
    const wrapper = mountHeader()
    const comboSwitch = wrapper.find('.combo-switch')
    expect(comboSwitch.exists()).toBe(true)
    // combo-switch 包含分隔符 "+"
    expect(comboSwitch.find('.combo-divider').text()).toBe('+')
    // 两个 provider-select
    const selects = comboSwitch.findAll('.provider-select')
    expect(selects.length).toBe(2)
  })

  it('渲染用户选择器', () => {
    const wrapper = mountHeader()
    const userSelect = wrapper.find('.user-select')
    expect(userSelect.exists()).toBe(true)
  })

  it('渲染主题切换按钮', () => {
    const wrapper = mountHeader()
    const themeBtn = wrapper.find('.theme-toggle')
    expect(themeBtn.exists()).toBe(true)
    expect(['🌙', '☀️']).toContain(themeBtn.text())
  })
})
