import { describe, it, expect } from 'vitest'
import { createChartManager } from '../../src/utils/chartManager.js'

const FakeChart = {
  template: '<div class="fake-chart">{{ headers.join(",") }}</div>',
  props: ['type', 'title', 'headers', 'rows'],
}

describe('createChartManager', () => {
  it('mount 后 DOM 里出现挂载内容', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el = document.createElement('div')
    root.appendChild(el)

    const mgr = createChartManager(FakeChart)
    mgr.mount(el, { type: 'bar', title: '', headers: ['a', 'b'], rows: [['x', '1']] })

    expect(el.querySelector('.fake-chart')).toBeTruthy()
    expect(el.textContent).toContain('a,b')

    document.body.removeChild(root)
  })

  it('unmountAll 后 mount 内容被清掉', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el = document.createElement('div')
    root.appendChild(el)

    const mgr = createChartManager(FakeChart)
    mgr.mount(el, { type: 'pie', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    expect(el.querySelector('.fake-chart')).toBeTruthy()

    mgr.unmountAll()
    expect(el.querySelector('.fake-chart')).toBeFalsy()

    document.body.removeChild(root)
  })

  it('count() 正确反映已 mount 数量', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el1 = document.createElement('div')
    const el2 = document.createElement('div')
    root.appendChild(el1)
    root.appendChild(el2)

    const mgr = createChartManager(FakeChart)
    expect(mgr.count()).toBe(0)

    mgr.mount(el1, { type: 'bar', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    mgr.mount(el2, { type: 'pie', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    expect(mgr.count()).toBe(2)

    mgr.unmountAll()
    expect(mgr.count()).toBe(0)

    document.body.removeChild(root)
  })

  it('对同一 el 重复 mount 会先 unmount 旧 app（避免重复实例）', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el = document.createElement('div')
    root.appendChild(el)

    const mgr = createChartManager(FakeChart)
    mgr.mount(el, { type: 'bar', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    mgr.mount(el, { type: 'pie', title: '', headers: ['c', 'd'], rows: [['y', '2']] })

    expect(mgr.count()).toBe(1)
    expect(el.textContent).toContain('c,d')

    document.body.removeChild(root)
  })
})
