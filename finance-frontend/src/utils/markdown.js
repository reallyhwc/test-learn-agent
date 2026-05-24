import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 把 markdown 文本转成可直接 v-html 的 sanitized HTML。
 * - 用 marked 解析 markdown
 * - 用 DOMPurify 移除潜在的 XSS（script、onerror、javascript: 等）
 */
export function renderMarkdown(text) {
  if (!text) return ''
  const rawHtml = marked.parse(text)
  return DOMPurify.sanitize(rawHtml, {
    USE_PROFILES: { html: true },
  })
}
