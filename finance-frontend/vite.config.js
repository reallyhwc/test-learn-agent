import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync, existsSync } from 'fs'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))

// 读取 config.yaml 获取 Agent 端口
function getAgentPort() {
  const configPath = resolve(__dirname, '../config.yaml')
  if (!existsSync(configPath)) return 8081

  try {
    const content = readFileSync(configPath, 'utf-8')
    const agentMatch = content.match(/^\s*agent:\s*(\w+)/m)
    const agentType = agentMatch ? agentMatch[1] : 'java'
    const sectionStart = content.indexOf(`agent-${agentType}:`)
    if (sectionStart >= 0) {
      const section = content.substring(sectionStart, sectionStart + 100)
      const pm = section.match(/port:\s*(\d+)/)
      if (pm) return parseInt(pm[1])
    }
    return agentType === 'python' ? 8084 : 8081
  } catch {
    return 8081
  }
}

const agentPort = getAgentPort()
console.log(`[vite] Agent 端口: ${agentPort}`)

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api/chat': {
        target: `http://localhost:${agentPort}`,
        changeOrigin: true
      },
      '/api/config': {
        target: `http://localhost:${agentPort}`,
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
