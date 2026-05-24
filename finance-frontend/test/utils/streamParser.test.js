import { describe, it, expect } from 'vitest'
import { createStreamBuffer } from '../../src/utils/streamParser.js'

describe('createStreamBuffer', () => {
  it('单个完整 SSE event', () => {
    const buf = createStreamBuffer()
    const tokens = buf.feed('data:hello\n\n')
    expect(tokens).toEqual(['hello'])
  })

  it('跨 chunk 的 SSE event 合并', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:he')).toEqual([])
    expect(buf.feed('llo\n\n')).toEqual(['hello'])
  })

  it('多个连续 event', () => {
    const buf = createStreamBuffer()
    const tokens = buf.feed('data:你\n\ndata:好\n\n')
    expect(tokens).toEqual(['你', '好'])
  })

  it('保留 token 内的前导空格', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data: hello world\n\n')).toEqual([' hello world'])
  })

  it('忽略非 data: 行（注释/event/id）', () => {
    const buf = createStreamBuffer()
    const tokens = buf.feed(': comment\nevent:msg\nid:1\ndata:x\n\n')
    expect(tokens).toEqual(['x'])
  })

  it('UTF-8 多字节字符不被截断', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:这\n\ndata:是\n\n')).toEqual(['这', '是'])
  })

  it('空 data 行返回空字符串 token', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:\n\n')).toEqual([''])
  })

  it('行末仅 \\n 不结束 event（需要 \\n\\n）', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:abc\n')).toEqual([])
    expect(buf.feed('\n')).toEqual(['abc'])
  })
})
