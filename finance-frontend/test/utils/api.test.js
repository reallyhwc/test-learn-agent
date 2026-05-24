import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Mock element-plus 的 ElMessage
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn() },
}))

import { apiFetch, apiGet, apiPost, ApiError, handleApiError } from '../../src/utils/api.js'
import { ElMessage } from 'element-plus'

describe('api.js', () => {
  const originalFetch = global.fetch

  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    global.fetch = originalFetch
  })

  describe('apiFetch', () => {
    it('成功请求返回 response', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ data: 'ok' }),
      })

      const response = await apiFetch('/api/test')
      expect(response.ok).toBe(true)
      expect(global.fetch).toHaveBeenCalledWith('/api/test', expect.any(Object))
    })

    it('HTTP 错误抛出 ApiError 并携带 status', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        json: () => Promise.resolve({ message: '资源不存在' }),
      })

      await expect(apiFetch('/api/missing')).rejects.toThrow(ApiError)
      try {
        await apiFetch('/api/missing')
      } catch (error) {
        expect(error.status).toBe(404)
        expect(error.message).toBe('资源不存在')
      }
    })

    it('HTTP 错误且 body 不是 JSON 时使用默认消息', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        json: () => Promise.reject(new Error('not json')),
      })

      await expect(apiFetch('/api/error')).rejects.toThrow('请求失败 (500)')
    })

    it('网络错误抛出 ApiError', async () => {
      global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

      await expect(apiFetch('/api/network-fail')).rejects.toThrow('网络连接失败')
    })

    it('超时时抛出 408 ApiError', async () => {
      global.fetch = vi.fn().mockImplementation(() =>
        new Promise((_, reject) => {
          setTimeout(() => reject(Object.assign(new Error(), { name: 'AbortError' })), 10)
        })
      )

      await expect(apiFetch('/api/timeout', { timeout: 1 })).rejects.toThrow('请求超时')
    })
  })

  describe('apiGet', () => {
    it('GET 请求并解析 JSON', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve([{ id: 1 }]),
      })

      const result = await apiGet('/api/items')
      expect(result).toEqual([{ id: 1 }])
    })
  })

  describe('apiPost', () => {
    it('POST 请求携带 JSON body', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ id: 100 }),
      })

      const result = await apiPost('/api/items', { name: 'test' })
      expect(result).toEqual({ id: 100 })
      expect(global.fetch).toHaveBeenCalledWith(
        '/api/items',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ name: 'test' }),
        })
      )
    })
  })

  describe('handleApiError', () => {
    it('显示错误消息', () => {
      const error = new ApiError('测试错误', 400)
      handleApiError(error)
      expect(ElMessage.error).toHaveBeenCalledWith('测试错误')
    })

    it('支持自定义消息', () => {
      handleApiError(new Error(), '自定义消息')
      expect(ElMessage.error).toHaveBeenCalledWith('自定义消息')
    })
  })
})
