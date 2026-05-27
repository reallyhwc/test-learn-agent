import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'

const mockApiGet = vi.fn()
vi.mock('../../src/utils/api.js', () => ({
  apiGet: (...args) => mockApiGet(...args),
  handleApiError: vi.fn(),
}))

import { mount, flushPromises } from '@vue/test-utils'
import TransactionList from '../../src/components/TransactionList.vue'

function mountList() {
  return mount(TransactionList, {
    global: { plugins: [createPinia(), ElementPlus] },
  })
}

describe('TransactionList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockApiGet.mockImplementation((url) => {
      if (url.includes('/api/categories')) {
        return Promise.resolve([
          { name: '餐饮', type: 'EXPENSE', children: [{ name: '午餐' }] },
          { name: '工资', type: 'INCOME', children: [] },
        ])
      }
      if (url.includes('/api/transactions')) {
        return Promise.resolve({ items: [], total: 0 })
      }
      return Promise.resolve([])
    })
  })

  it('渲染标题', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.find('h3').text()).toBe('交易记录')
  })

  it('mount 时加载分类和交易数据', async () => {
    mountList()
    await flushPromises()
    expect(mockApiGet).toHaveBeenCalledWith('/api/categories')
    expect(mockApiGet).toHaveBeenCalledWith(expect.stringContaining('/api/transactions'))
  })

  it('空数据时显示空状态提示', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无交易记录')
  })

  it('有数据时渲染表格', async () => {
    mockApiGet.mockImplementation((url) => {
      if (url.includes('/api/categories')) return Promise.resolve([])
      if (url.includes('/api/transactions')) {
        return Promise.resolve({
          items: [
            { id: 1, date: '2026-05-01', category: '餐饮', subCategory: '午餐', type: 'EXPENSE', amount: 35.5, note: '外卖' },
          ],
          total: 1,
        })
      }
      return Promise.resolve([])
    })

    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('餐饮')
    expect(wrapper.text()).toContain('35.50')
  })
})
