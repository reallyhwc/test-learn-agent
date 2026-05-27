import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // Agent 代理（前端通过 aiStore.agentApiPrefix 选择 /api-py 或 /api-java）
      '/api-py': {
        target: 'http://localhost:8084',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api-py/, '/api')
      },
      '/api-java': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api-java/, '/api')
      },
      // Backend REST API（CRUD 通路）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
