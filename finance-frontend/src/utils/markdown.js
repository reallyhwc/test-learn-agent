import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import sql from 'highlight.js/lib/languages/sql'
import 'highlight.js/styles/github.css'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('sql', sql)

// marked v18+ 使用 renderer 方式集成 highlight.js
const renderer = new marked.Renderer()
renderer.code = function({ text, lang }) {
  if (lang && hljs.getLanguage(lang)) {
    const highlighted = hljs.highlight(text, { language: lang }).value
    return `<pre><code class="hljs language-${lang}">${highlighted}</code></pre>`
  }
  const autoHighlighted = hljs.highlightAuto(text).value
  return `<pre><code class="hljs">${autoHighlighted}</code></pre>`
}
marked.use({ renderer })

/**
 * 把 markdown 文本转成可直接 v-html 的 sanitized HTML。
 * - 用 marked 解析 markdown（含代码高亮）
 * - 用 DOMPurify 移除潜在的 XSS（script、onerror、javascript: 等）
 */
export function renderMarkdown(text) {
  if (!text) return ''
  const rawHtml = marked.parse(text)
  return DOMPurify.sanitize(rawHtml, {
    USE_PROFILES: { html: true },
  })
}
