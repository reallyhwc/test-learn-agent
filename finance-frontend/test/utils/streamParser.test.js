import { describe, it, expect } from 'vitest'
import { createStreamBuffer } from '../../src/utils/streamParser.js'

describe('createStreamBuffer', () => {
  it('单个完整 SSE event 默认是 data 类型', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:hello\n\n')).toEqual([{ type: 'data', payload: 'hello' }])
  })

  it('跨 chunk 的 SSE event 合并', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:he')).toEqual([])
    expect(buf.feed('llo\n\n')).toEqual([{ type: 'data', payload: 'hello' }])
  })

  it('多个连续 event', () => {
    const buf = createStreamBuffer()
    const tokens = buf.feed('data:你\n\ndata:好\n\n')
    expect(tokens).toEqual([
      { type: 'data', payload: '你' },
      { type: 'data', payload: '好' },
    ])
  })

  it('保留 token 内的前导空格', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data: hello world\n\n')).toEqual([
      { type: 'data', payload: ' hello world' },
    ])
  })

  it('忽略 id:/comment:; event: 是类型标记不忽略', () => {
    const buf = createStreamBuffer()
    expect(buf.feed(': comment\nid:1\ndata:x\n\n')).toEqual([
      { type: 'data', payload: 'x' },
    ])
  })

  it('UTF-8 多字节字符不被截断', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:这\n\ndata:是\n\n')).toEqual([
      { type: 'data', payload: '这' },
      { type: 'data', payload: '是' },
    ])
  })

  it('空 data 行返回空字符串 payload', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:\n\n')).toEqual([{ type: 'data', payload: '' }])
  })

  it('行末仅 \\n 不结束 event（需要 \\n\\n）', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:abc\n')).toEqual([])
    expect(buf.feed('\n')).toEqual([{ type: 'data', payload: 'abc' }])
  })

  it('一个 event 内多个 data: 行用 \\n 拼接（SSE spec）', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:line1\ndata:line2\n\n')).toEqual([
      { type: 'data', payload: 'line1\nline2' },
    ])
  })

  it('markdown 表格场景：多行 \\n 在一个 event 里通过 multi-data 编码', () => {
    const buf = createStreamBuffer()
    const sseText =
      'data:| 项 | 值 |\n' + 'data:|---|---|\n' + 'data:| a | 1 |\n\n'
    expect(buf.feed(sseText)).toEqual([
      { type: 'data', payload: '| 项 | 值 |\n|---|---|\n| a | 1 |' },
    ])
  })

  it('多个 event，每个 event 都各自合并 data: 行', () => {
    const buf = createStreamBuffer()
    const sseText = 'data:a1\ndata:a2\n\n' + 'data:b1\ndata:b2\n\n'
    expect(buf.feed(sseText)).toEqual([
      { type: 'data', payload: 'a1\na2' },
      { type: 'data', payload: 'b1\nb2' },
    ])
  })

  it('event:error 标记为 error 类型', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('event:error\ndata:LLM 调用失败\n\n')).toEqual([
      { type: 'error', payload: 'LLM 调用失败' },
    ])
  })

  it('error event 的 multi-data 也合并', () => {
    const buf = createStreamBuffer()
    const sseText = 'event:error\ndata:line1\ndata:line2\n\n'
    expect(buf.feed(sseText)).toEqual([
      { type: 'error', payload: 'line1\nline2' },
    ])
  })

  it('混合 data event 和 error event', () => {
    const buf = createStreamBuffer()
    const sseText = 'data:正常\n\nevent:error\ndata:失败\n\n'
    expect(buf.feed(sseText)).toEqual([
      { type: 'data', payload: '正常' },
      { type: 'error', payload: '失败' },
    ])
  })

  it('未知 event 类型当 data 处理（默认）', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('event:custom\ndata:x\n\n')).toEqual([
      { type: 'data', payload: 'x' },
    ])
  })

  it('event:thinking 标记为 thinking 类型', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('event:thinking\ndata:正在分析\n\n')).toEqual([
      { type: 'thinking', payload: '正在分析' },
    ])
  })

  it('thinking event multi-data 也合并', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('event:thinking\ndata:Step1\ndata:Step2\n\n')).toEqual([
      { type: 'thinking', payload: 'Step1\nStep2' },
    ])
  })

  it('thinking → data 混合（典型 LLM 流式输出）', () => {
    const buf = createStreamBuffer()
    const sseText =
      'event:thinking\ndata:思考中\n\n' +
      'event:thinking\ndata:分析问题\n\n' +
      'data:这是答案\n\n'
    expect(buf.feed(sseText)).toEqual([
      { type: 'thinking', payload: '思考中' },
      { type: 'thinking', payload: '分析问题' },
      { type: 'data', payload: '这是答案' },
    ])
  })
})
