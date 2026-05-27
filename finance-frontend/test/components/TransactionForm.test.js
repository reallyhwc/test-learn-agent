import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'

// Mock api.js
const mockApiGet = vi.fn()
const mockApiPost = vi.fn()
vi.mock('../../src/utils/api.js', () => ({
  apiGet: (...args) => mockApiGet(...args),
  apiPost: (...args) => mockApiPost(...args),
  handleApiError: vi.fn(),
}))

import { mount, flushPromises } from '@vue/test-utils'
import TransactionForm from '../../src/components/TransactionForm.vue'

function mountForm() {
  return mount(TransactionForm, {
    global: { plugins: [createPinia(), ElementPlus] },
  })
}

describe('TransactionForm', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockApiGet.mockImplementation((url) => {
      if (url.includes('/api/accounts')) {
        return Promise.resolve([{ id: 1, name: '现金', type: 'CASH', balance: 10000 }])
      }
      if (url.includes('/api/categories')) {
        return Promise.resolve([{ name: '餐饮', type: 'EXPENSE' }, { name: '理财', type: 'INCOME' }])
      }
      return Promise.resolve([])
    })
  })

  it('渲染表单标题和保存按钮', async () => {
    const wrapper = mountForm()
    await flushPromises()
    expect(wrapper.find('h3').text()).toBe('记一笔')
    expect(wrapper.text()).toContain('保存')
  })

  it('mount 时加载账户和分类数据', async () => {
    mountForm()
    await flushPromises()
    expect(mockApiGet).toHaveBeenCalledWith(expect.stringContaining('/api/accounts'))
    expect(mockApiGet).toHaveBeenCalledWith('/api/categories')
  })

  it('必填字段为空时不触发提交', async () => {
    const wrapper = mountForm()
    await flushPromises()

    // 通过 el-form 的 native submit
    const formEl = wrapper.find('.tx-form form')
    if (formEl.exists()) {
      await formEl.trigger('submit')
    } else {
      // el-form 渲染后 fallback：直接调用 submit 方法
      await wrapper.vm.submit?.()
    }
    await flushPromises()
    expect(mockApiPost).not.toHaveBeenCalled()
  })

  it('成功提交后触发 saved 事件', async () => {
    mockApiPost.mockResolvedValue({ id: 100 })

    const wrapper = mountForm()
    await flushPromises()

    const vm = wrapper.vm
    vm.form.accountId = 1
    vm.form.type = 'EXPENSE'
    vm.form.amount = 50
    vm.form.note = '午餐'
    // categorySelection 需要 [一级分类, 二级分类] 才能通过校验
    vm.categorySelection = ['餐饮', '午餐']

    await vm.submit()
    await flushPromises()

    expect(mockApiPost).toHaveBeenCalledWith(
      '/api/transactions',
      expect.objectContaining({
        accountId: 1,
        type: 'EXPENSE',
        amount: 50,
        category: '餐饮',
        subCategory: '午餐',
      })
    )
    expect(wrapper.emitted('saved')).toBeTruthy()
  })

  it('提交成功后显示成功消息并清空金额', async () => {
    mockApiPost.mockResolvedValue({ id: 101 })

    const wrapper = mountForm()
    await flushPromises()

    const vm = wrapper.vm
    vm.form.accountId = 1
    vm.form.type = 'INCOME'
    vm.form.amount = 100
    vm.categorySelection = ['理财', '利息']

    await vm.submit()
    await flushPromises()

    expect(wrapper.text()).toContain('保存成功')
    expect(vm.form.amount).toBeNull()
  })
})
