/**
 * 创建一个 SSE buffer。feed(chunk) 输入一段 stream chunk，
 * 返回从 buffer 里能解析出的完整 event 数组：
 *   Array<{ type: 'data' | 'error', payload: string }>
 *
 * 协议：
 * - 默认 event 类型为 'data'
 * - `event:error` 字段把当前 event 类型标记为 'error'
 * - 一个 event 内多个 data: 行用 \n 拼接（W3C SSE spec）
 * - id: / : (comment) 全部忽略
 */
export function createStreamBuffer() {
  let buffer = ''
  return {
    feed(chunk) {
      buffer += chunk
      const events = []
      let eventEnd
      while ((eventEnd = buffer.indexOf('\n\n')) !== -1) {
        const eventBlock = buffer.slice(0, eventEnd)
        buffer = buffer.slice(eventEnd + 2)
        const dataLines = []
        let type = 'data'
        for (const line of eventBlock.split('\n')) {
          if (line.startsWith('data:')) {
            dataLines.push(line.slice(5))
          } else if (line.startsWith('event:')) {
            const evt = line.slice(6).trim()
            if (evt === 'error') type = 'error'
          }
        }
        if (dataLines.length > 0) {
          events.push({ type, payload: dataLines.join('\n') })
        }
      }
      return events
    },
  }
}
