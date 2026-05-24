/**
 * 创建一个 SSE buffer。feed(chunk) 输入一段 stream chunk，
 * 返回从 buffer 里能解析出的完整 token 数组（含空字符串）。
 * 未完整的尾部留在 buffer 里等下一次 feed。
 *
 * 标准 SSE 格式：每条 event 由若干字段行（data:/event:/id:/:）组成，
 * 以 \n\n 结束。一条 event 内可有多个 data: 行（按 spec 拼接 \n），
 * 这里简化：每个 data: 行视为一个独立 token。
 */
export function createStreamBuffer() {
  let buffer = ''
  return {
    feed(chunk) {
      buffer += chunk
      const tokens = []
      let eventEnd
      while ((eventEnd = buffer.indexOf('\n\n')) !== -1) {
        const eventBlock = buffer.slice(0, eventEnd)
        buffer = buffer.slice(eventEnd + 2)
        for (const line of eventBlock.split('\n')) {
          if (line.startsWith('data:')) {
            tokens.push(line.slice(5))
          }
        }
      }
      return tokens
    },
  }
}
