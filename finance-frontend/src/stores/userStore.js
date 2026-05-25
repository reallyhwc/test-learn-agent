import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useUserStore = defineStore('user', () => {
  const STORAGE_KEY = 'finance-selected-user'
  const THEME_KEY = 'finance-theme'

  const users = [
    { id: 'default', name: '默认用户' },
    { id: 'user-001', name: '张三' },
    { id: 'user-002', name: '李四' },
    { id: 'user-003', name: '王五' },
  ]

  const savedUser = localStorage.getItem(STORAGE_KEY)
  const currentUser = ref(
    savedUser && users.some(u => u.id === savedUser) ? savedUser : 'default'
  )

  watch(currentUser, (val) => {
    localStorage.setItem(STORAGE_KEY, val)
  })

  function setUser(id) {
    currentUser.value = id
  }

  /* ---- 主题管理 ---- */
  const savedTheme = localStorage.getItem(THEME_KEY)
  const prefersDark = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)').matches
    : false
  const theme = ref(savedTheme || (prefersDark ? 'dark' : 'light'))

  function applyTheme(value) {
    document.documentElement.setAttribute('data-theme', value)
    localStorage.setItem(THEME_KEY, value)
  }

  function toggleTheme() {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
    applyTheme(theme.value)
  }

  /* 初始化时应用主题 */
  applyTheme(theme.value)

  return { currentUser, users, setUser, theme, toggleTheme }
})
