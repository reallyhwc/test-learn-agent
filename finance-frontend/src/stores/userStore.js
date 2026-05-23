import { reactive } from 'vue'

export const userStore = reactive({
  currentUser: 'default',
  users: [
    { id: 'zhangsan', name: '张三' },
    { id: 'lisi', name: '李四' },
    { id: 'wangwu', name: '王五' },
    { id: 'default', name: '默认用户' }
  ],
  setUser(id) {
    this.currentUser = id
  }
})
