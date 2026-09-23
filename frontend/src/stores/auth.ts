import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi, persistTokens } from '@/api/endpoints'
import { clearTokens, getAccessToken, setSessionExpiredHandler } from '@/api/http'
import type { UserProfile } from '@/api/types'

/**
 * 登录态。
 *
 * 令牌本体只存在 localStorage 里（由 api/http.ts 负责读写），
 * store 只持有「是否已登录」这个派生状态和用户资料。
 * 不做成响应式对象的原因：令牌是给 axios 拦截器用的，不是给模板用的，
 * 放进响应式状态只会让它在开发者工具里被看到。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserProfile | null>(null)
  const loading = ref(false)
  /** 是否已经向后端确认过一次登录态。用于路由守卫避免重复请求。 */
  const resolved = ref(false)

  /**
   * 是否已登录 = **服务端已确认过的**登录态。
   *
   * ⚠️ 这里刻意不写成 `hasToken && user !== null`。
   *
   * 曾经的写法是：
   *   const hasToken = computed(() => Boolean(getAccessToken()))
   *   const isLoggedIn = computed(() => hasToken.value && user.value !== null)
   *
   * 这是个真实踩过的坑：`getAccessToken()` 读的是 localStorage，**不是响应式数据源**，
   * 所以 `hasToken` 这个 computed 内部没有任何可追踪的依赖。Vue 的 computed 只在
   * 依赖变化时重算，没有依赖就等于「首次求值后永久缓存」。
   *
   * 后果是：在登录页（此时本地还没令牌）首次渲染后，`hasToken` 被永久缓存为 false；
   * 之后用户注册/登录成功、令牌也写进了 localStorage、`user` 也拿到了，
   * 顶栏却依旧显示「未登录」，访问 /watchlist 会被路由守卫弹回登录页。
   * **刷新一次页面就恢复正常**——所以极易被当成偶发问题放过去。
   *
   * 现在直接用 `user !== null` 判定，语义上反而更准：
   * `user` 只由 `fetchMe()` 赋值，而 `fetchMe()` 在没有令牌时会提前返回并把 user 置空，
   * 所以「user 非空」本身就蕴含「本地有令牌，且服务端认可这个令牌」。
   * 同时 `user` 是 ref，登出 / 会话失效时会立即反映到界面上。
   */
  const isLoggedIn = computed(() => user.value !== null)
  const displayName = computed(() => user.value?.nickname || user.value?.phone || '未登录')

  /**
   * 会话被服务端判定失效时（令牌过期且刷新失败、或会话已被登出吊销），
   * 清掉本地资料。路由守卫会在下一次跳转时把用户送去登录页。
   */
  function handleSessionExpired(): void {
    user.value = null
    resolved.value = true
  }

  // 注册回调，让 api 层能通知到这里，而不必反向 import store
  setSessionExpiredHandler(handleSessionExpired)

  async function login(phone: string, password: string): Promise<void> {
    loading.value = true
    try {
      const pair = await authApi.login(phone, password)
      persistTokens(pair)
      await fetchMe()
    } finally {
      loading.value = false
    }
  }

  async function register(phone: string, password: string, nickname?: string): Promise<void> {
    loading.value = true
    try {
      const pair = await authApi.register(phone, password, nickname)
      persistTokens(pair)
      await fetchMe()
    } finally {
      loading.value = false
    }
  }

  async function fetchMe(): Promise<void> {
    if (!getAccessToken()) {
      user.value = null
      resolved.value = true
      return
    }
    try {
      user.value = await authApi.me()
    } catch {
      // 令牌无效 / 过期：按未登录处理，不要把异常抛给路由守卫
      user.value = null
    } finally {
      resolved.value = true
    }
  }

  /** 应用启动时调用一次：有令牌就试着换成用户资料。 */
  async function bootstrap(): Promise<void> {
    if (resolved.value) return
    await fetchMe()
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 登出接口失败也要清本地状态——用户点了退出就必须退出，
      // 不能因为后端 500 把人留在登录态里
    } finally {
      clearTokens()
      user.value = null
      resolved.value = true
    }
  }

  return {
    user,
    loading,
    resolved,
    isLoggedIn,
    displayName,
    login,
    register,
    fetchMe,
    bootstrap,
    logout,
    handleSessionExpired,
  }
})
