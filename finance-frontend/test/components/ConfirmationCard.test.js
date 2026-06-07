import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConfirmationCard from '../../src/components/ConfirmationCard.vue'

function mountCard(props = {}) {
  return mount(ConfirmationCard, {
    props: {
      confirmationId: 'test-id-001',
      toolName: 'add_transaction',
      description: '将添加一笔交易：午餐 ¥30.00',
      parameters: { amount: 30, type: 'EXPENSE', category: '餐饮', note: '午餐' },
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConfirmationCard', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-06-07T12:00:00'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染确认标题和描述', () => {
    const wrapper = mountCard()
    expect(wrapper.find('.card-header').text()).toContain('操作确认')
    expect(wrapper.find('.desc').text()).toBe('将添加一笔交易：午餐 ¥30.00')
  })

  it('显示 confirm 和 cancel 按钮', () => {
    const wrapper = mountCard()
    const buttons = wrapper.findAll('.el-button')
    const confirmBtn = buttons.find(b => b.text() === '确认执行')
    const cancelBtn = buttons.find(b => b.text() === '取消')
    expect(confirmBtn).toBeTruthy()
    expect(cancelBtn).toBeTruthy()
  })

  it('点击确认按钮 emit confirm 事件', async () => {
    const wrapper = mountCard()
    await wrapper.find('.el-button--primary').trigger('click')
    expect(wrapper.emitted('confirm')).toBeTruthy()
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('点击取消按钮 emit cancel 事件', async () => {
    const wrapper = mountCard()
    const buttons = wrapper.findAll('.el-button')
    const cancelBtn = buttons.find(b => b.text() === '取消')
    await cancelBtn.trigger('click')
    expect(wrapper.emitted('cancel')).toBeTruthy()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('显示参数标签和格式化值', () => {
    const wrapper = mountCard()
    const paramRows = wrapper.findAll('.param-row')
    expect(paramRows.length).toBeGreaterThanOrEqual(3)

    const text = wrapper.text()
    expect(text).toContain('金额')
    expect(text).toContain('¥30.00')
    expect(text).toContain('餐饮')
    expect(text).toContain('午餐')
  })

  it('金额类型显示为中文', () => {
    const wrapper = mountCard({
      confirmationId: 'test-002',
      toolName: 'add_transaction',
      description: '收入',
      parameters: { amount: 100, type: 'INCOME', category: '工资', note: '月薪' },
    })
    const text = wrapper.text()
    expect(text).toContain('¥100.00')
    expect(text).toContain('收入')
  })

  it('不传入 expiresAt 时默认倒计时 60', async () => {
    const wrapper = mountCard({
      confirmationId: 'test-003',
      toolName: 'add_transaction',
      description: '测试',
      parameters: { amount: 10, type: 'EXPENSE', category: '其他' },
      expiresAt: '',
    })
    expect(wrapper.find('.countdown').text()).toContain('s 后自动取消')
  })

  it('显示基于 expiresAt 的正确倒计时', async () => {
    const future = new Date('2026-06-07T12:01:00').toISOString() // 60s 后
    const wrapper = mountCard({
      confirmationId: 'test-004',
      toolName: 'add_transaction',
      description: '测试倒计时',
      parameters: { amount: 50, type: 'EXPENSE', category: '交通' },
      expiresAt: future,
    })

    await vi.advanceTimersByTimeAsync(1000)

    const countdownText = wrapper.find('.countdown').text()
    expect(countdownText).toMatch(/\d+s 后自动取消/)
  })

  it('displayParams 过滤掉内部字段', () => {
    const wrapper = mountCard({
      confirmationId: 'test-005',
      toolName: 'add_transaction',
      description: '过滤测试',
      parameters: {
        confirmationId: 'should-not-show',
        toolName: 'should-not-show',
        description: 'should-not-show',
        expiresAt: 'should-not-show',
        amount: 200,
        type: 'EXPENSE',
        category: '购物',
      },
    })
    const text = wrapper.text()
    expect(text).toContain('¥200.00')
    expect(text).toContain('购物')
    expect(text).not.toContain('should-not-show')
  })

  it('未知参数 key 显示原始 key', () => {
    const wrapper = mountCard({
      confirmationId: 'test-006',
      toolName: 'add_transaction',
      description: '未知参数',
      parameters: { amount: 10, type: 'EXPENSE', category: '其他', customField: 'test-value' },
    })
    expect(wrapper.text()).toContain('customField')
    expect(wrapper.text()).toContain('test-value')
  })

  it('组件销毁时清除定时器', () => {
    const clearIntervalSpy = vi.spyOn(global, 'clearInterval')
    const wrapper = mountCard({
      confirmationId: 'test-007',
      toolName: 'add_transaction',
      description: '定时器清理测试',
      parameters: { amount: 10, type: 'EXPENSE', category: '其他' },
      expiresAt: new Date('2026-06-07T12:01:00').toISOString(),
    })
    wrapper.unmount()
    expect(clearIntervalSpy).toHaveBeenCalled()
    clearIntervalSpy.mockRestore()
  })
})
