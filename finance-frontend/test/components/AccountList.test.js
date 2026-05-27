import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'

const mockApiGet = vi.fn()
vi.mock('../../src/utils/api.js', () => ({
  apiGet: (...args) => mockApiGet(...args),
  handleApiError: vi.fn(),
}))

import { mount, flushPromises } from '@vue/test-utils'
import AccountList from '../../src/components/AccountList.vue'

function mountAccountList() {
  return mount(AccountList, {
    global: { plugins: [createPinia(), ElementPlus] },
  })
}

describe('AccountList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockApiGet.mockResolvedValue([])
  })

  it('渲染标题', async () => {
    const wrapper = mountAccountList()
    await flushPromises()
    expect(wrapper.find('h3').text()).toBe('账户')
  })

  it('mount 时加载账户数据', async () => {
    mountAccountList()
    await flushPromises()
    expect(mockApiGet).toHaveBeenCalledWith(expect.stringContaining('/api/accounts'))
  })

  it('空数据时显示空状态', async () => {
    mockApiGet.mockResolvedValue([])
    const wrapper = mountAccountList()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无账户')
  })

  it('有数据时渲染账户卡片', async () => {
    mockApiGet.mockResolvedValue([
      { id: 1, name: '工商银行', type: 'BANK', balance: 15000.5 },
      { id: 2, name: '现金钱包', type: 'CASH', balance: 800 },
    ])

    const wrapper = mountAccountList()
    await flushPromises()
    expect(wrapper.text()).toContain('工商银行')
    expect(wrapper.text()).toContain('现金钱包')
    expect(wrapper.text()).toContain('储蓄')
    expect(wrapper.text()).toContain('现金')
  })

  it('余额格式化显示', async () => {
    mockApiGet.mockResolvedValue([
      { id: 1, name: '信用卡', type: 'CARD', balance: -2500.8 },
    ])

    const wrapper = mountAccountList()
    await flushPromises()
    expect(wrapper.text()).toContain('2500.80')
    expect(wrapper.find('.negative').exists()).toBe(true)
  })
})
