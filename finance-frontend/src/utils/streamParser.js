/**
 * 创建一个 SSE buffer。feed(chunk) 输入一段 stream chunk，
 * 返回从 buffer 里能解析出的完整 event 数组：
 *   Array<{ type: 'data' | 'error' | 'thinking', payload: string }>
 *
 * 协议：
 * - 默认 event 类型为 'data'
 * - `event:error` 字段标记为 'error' 类型
 * - `event:thinking` 字段标记为 'thinking' 类型（LLM reasoning 过程）
 * - 一个 event 内多个 data: 行用 \n 拼接（W3C SSE spec）
 * - id: / : (comment) 全部忽略
 */
const EVENT_TYPES = new Set(['data', 'error', 'thinking'])

export function createStreamBuffer() {
  let buffer = ''
  return {
    feed(chunk) {
      buffer += chunk
      // 统一 CRLF → LF，兼容 Python（sse-starlette）和 Java 两种 SSE 格式
      buffer = buffer.replace(/\r\n/g, '\n')
      const events = []
      let eventEnd
      while ((eventEnd = buffer.indexOf('\n\n')) !== -1) {
        const eventBlock = buffer.slice(0, eventEnd)
        buffer = buffer.slice(eventEnd + 2)
        const dataLines = []
        let type = 'data'
        for (const line of eventBlock.split('\n')) {
          if (line.startsWith('data:')) {
            // 兼容 "data:xxx" 和 "data: xxx" 两种格式
            const payload = line.charAt(5) === ' ' ? line.slice(6) : line.slice(5)
            dataLines.push(payload)
          } else if (line.startsWith('event:')) {
            const evt = line.slice(6).trim()
            if (EVENT_TYPES.has(evt)) type = evt
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
