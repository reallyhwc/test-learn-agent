import { describe, it, expect } from 'vitest'
import { extractTableData } from '../../src/utils/chartExtractor.js'

function makeTable(html) {
  const div = document.createElement('div')
  div.innerHTML = html
  return div.querySelector('table')
}

describe('extractTableData', () => {
  it('2 列 + ≤8 行 → pie', () => {
    const t = makeTable(`
      <table><thead><tr><th>类别</th><th>金额</th></tr></thead>
      <tbody><tr><td>餐饮</td><td>¥1,200</td></tr>
      <tr><td>交通</td><td>¥300</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.chartType).toBe('pie')
    expect(r.headers).toEqual(['类别', '金额'])
    expect(r.rows).toEqual([['餐饮', '1200'], ['交通', '300']])
  })

  it('多数值列 → bar', () => {
    const t = makeTable(`
      <table><thead><tr><th>月</th><th>收入</th><th>支出</th></tr></thead>
      <tbody><tr><td>3月</td><td>10000</td><td>5000</td></tr>
      <tr><td>4月</td><td>12000</td><td>6000</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.chartType).toBe('bar')
    expect(r.headers).toEqual(['月', '收入', '支出'])
  })

  it('行数 > 8 → bar', () => {
    const rows = Array.from({ length: 10 }, (_, i) =>
      `<tr><td>项${i}</td><td>${i * 10}</td></tr>`).join('')
    const t = makeTable(`<table><thead><tr><th>名</th><th>值</th></tr></thead>
      <tbody>${rows}</tbody></table>`)
    const r = extractTableData(t)
    expect(r.chartType).toBe('bar')
    expect(r.rows).toHaveLength(10)
  })

  it('没有数值列 → null', () => {
    const t = makeTable(`<table><thead><tr><th>a</th><th>b</th></tr></thead>
      <tbody><tr><td>x</td><td>y</td></tr></tbody></table>`)
    expect(extractTableData(t)).toBeNull()
  })

  it('表头列数 < 2 → null', () => {
    const t = makeTable(`<table><thead><tr><th>仅一列</th></tr></thead>
      <tbody><tr><td>x</td></tr></tbody></table>`)
    expect(extractTableData(t)).toBeNull()
  })

  it('清洗千分位逗号', () => {
    const t = makeTable(`<table><thead><tr><th>名</th><th>额</th></tr></thead>
      <tbody><tr><td>合计</td><td>¥12,345.67</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.rows[0]).toEqual(['合计', '12345.67'])
  })

  it('百分号清洗', () => {
    const t = makeTable(`<table><thead><tr><th>名</th><th>占比</th></tr></thead>
      <tbody><tr><td>食</td><td>30%</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.rows[0]).toEqual(['食', '30'])
  })

  it('空 tbody → null', () => {
    const t = makeTable(`<table><thead><tr><th>a</th><th>b</th></tr></thead>
      <tbody></tbody></table>`)
    expect(extractTableData(t)).toBeNull()
  })
})
