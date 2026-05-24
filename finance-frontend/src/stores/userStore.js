import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useUserStore = defineStore('user', () => {
  const STORAGE_KEY = 'finance-selected-user'
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

  return { currentUser, users, setUser }
})
