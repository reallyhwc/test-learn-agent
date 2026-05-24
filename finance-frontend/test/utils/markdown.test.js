import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '../../src/utils/markdown.js'

describe('renderMarkdown', () => {
  it('普通文本不变', () => {
    expect(renderMarkdown('hello')).toContain('hello')
  })

  it('表格被渲染为 <table>', () => {
    const md = '| a | b |\n| - | - |\n| 1 | 2 |'
    const html = renderMarkdown(md)
    expect(html).toContain('<table>')
    expect(html).toContain('<th>a</th>')
  })

  it('代码块被渲染为 <pre><code>', () => {
    const html = renderMarkdown('```js\nconst x = 1\n```')
    expect(html).toContain('<pre>')
    expect(html).toContain('<code')
  })

  it('XSS: <script> 被剥离', () => {
    const html = renderMarkdown('<script>alert(1)</script>正文')
    expect(html).not.toContain('<script>')
    expect(html).toContain('正文')
  })

  it('XSS: img onerror 属性被剥离', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)">')
    expect(html).not.toMatch(/onerror/i)
  })

  it('XSS: javascript: 链接被剥离', () => {
    const html = renderMarkdown('[click](javascript:alert(1))')
    expect(html).not.toMatch(/javascript:/i)
  })

  it('null/undefined/空字符串返回空串', () => {
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
    expect(renderMarkdown('')).toBe('')
  })
})
