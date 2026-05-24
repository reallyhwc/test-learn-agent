import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '../../src/stores/userStore.js'

describe('userStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('默认用户为 default', () => {
    const store = useUserStore()
    expect(store.currentUser).toBe('default')
  })

  it('预置用户列表包含4个用户', () => {
    const store = useUserStore()
    expect(store.users).toHaveLength(4)
    expect(store.users.map(u => u.id)).toContain('default')
    expect(store.users.map(u => u.id)).toContain('user-001')
  })

  it('setUser 切换当前用户', () => {
    const store = useUserStore()
    store.setUser('user-001')
    expect(store.currentUser).toBe('user-001')
  })

  it('切换用户后写入 localStorage', async () => {
    const store = useUserStore()
    store.setUser('user-002')
    // watch 是异步的，需要等一个 tick
    await new Promise(r => setTimeout(r, 0))
    expect(localStorage.getItem('finance-selected-user')).toBe('user-002')
  })

  it('从 localStorage 恢复已保存的用户', () => {
    localStorage.setItem('finance-selected-user', 'user-003')
    // 需要重新创建 pinia 让 store 重新初始化
    setActivePinia(createPinia())
    const store = useUserStore()
    expect(store.currentUser).toBe('user-003')
  })

  it('localStorage 中存储了非法用户ID时回退到 default', () => {
    localStorage.setItem('finance-selected-user', 'invalid-user-id')
    setActivePinia(createPinia())
    const store = useUserStore()
    expect(store.currentUser).toBe('default')
  })
})
