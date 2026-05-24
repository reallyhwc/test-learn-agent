/**
 * 创建一个 SSE buffer。feed(chunk) 输入一段 stream chunk，
 * 返回从 buffer 里能解析出的完整 SSE event payload 数组。
 * 未完整的尾部留在 buffer 里等下一次 feed。
 *
 * 标准 SSE 格式（W3C spec）：每条 event 由若干字段行（data:/event:/id:/:）组成，
 * 以 \n\n 结束。一条 event 内可有多个 data: 行，浏览器用 \n 拼回完整 payload。
 * 例如 event "data:line1\ndata:line2\n\n" → payload "line1\nline2"。
 *
 * 这种规范允许 server 安全发送含 \n 的 token（如 markdown 表格、多行代码块）。
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
        for (const line of eventBlock.split('\n')) {
          if (line.startsWith('data:')) {
            dataLines.push(line.slice(5))
          }
          // event:/id:/:comment 全部忽略
        }
        if (dataLines.length > 0) {
          events.push(dataLines.join('\n'))
        }
      }
      return events
    },
  }
}
