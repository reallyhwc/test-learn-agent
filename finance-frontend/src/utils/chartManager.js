import { createApp } from 'vue'

/**
 * 创建一个 ChartManager。每个调用方（如一条 ChatMessage）持有一个独立实例，
 * 用 Map<HTMLElement, App> 跟踪挂载的 Vue app；卸载时统一 unmount 防止泄漏。
 */
export function createChartManager(chartComponent) {
  const apps = new Map()

  return {
    mount(el, props) {
      const existing = apps.get(el)
      if (existing) {
        existing.unmount()
        apps.delete(el)
      }
      const app = createApp(chartComponent, props)
      app.mount(el)
      apps.set(el, app)
    },
    unmountAll() {
      for (const app of apps.values()) {
        try {
          app.unmount()
        } catch (_e) {
          // 防御：极端情况下根节点被外部移除导致 unmount 抛错；忽略
        }
      }
      apps.clear()
    },
    count() {
      return apps.size
    },
  }
}
