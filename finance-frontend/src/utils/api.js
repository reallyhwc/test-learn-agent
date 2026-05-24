import { ElMessage } from 'element-plus'

const API_TIMEOUT = 30000

export class ApiError extends Error {
  constructor(message, status, data) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.data = data
  }
}

export async function apiFetch(url, options = {}) {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), options.timeout || API_TIMEOUT)

  try {
    const response = await fetch(url, {
      ...options,
      signal: options.signal || controller.signal,
    })

    if (!response.ok) {
      let errorData = null
      try { errorData = await response.json() } catch (_) {}
      const message = errorData?.message || `请求失败 (${response.status})`
      throw new ApiError(message, response.status, errorData)
    }

    return response
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error.name === 'AbortError') {
      throw new ApiError('请求超时', 408)
    }
    throw new ApiError('网络连接失败', 0)
  } finally {
    clearTimeout(timeoutId)
  }
}

export async function apiGet(url, options = {}) {
  const response = await apiFetch(url, { ...options, method: 'GET' })
  return response.json()
}

export async function apiPost(url, data, options = {}) {
  const response = await apiFetch(url, {
    ...options,
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...options.headers },
    body: JSON.stringify(data),
  })
  return response.json()
}

export function handleApiError(error, customMessage) {
  const message = customMessage || error.message || '操作失败'
  ElMessage.error(message)
  console.error('[API Error]', error)
}
