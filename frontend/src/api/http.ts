import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import type { ApiResult, TokenPair } from './types'

/**
 * HTTP 客户端与令牌生命周期管理。
 *
 * <h2>为什么这里要区分 AUTH_EXPIRED 与 AUTH_INVALID</h2>
 * 后端对「令牌过期」和「令牌非法/已注销」返回不同的业务码，两者在前端的
 * 处理策略完全相反：
 *
 * <table>
 *   <tr><th>业务码</th><th>含义</th><th>前端应对</th></tr>
 *   <tr><td>AUTH_EXPIRED</td><td>Access Token 自然过期（30 分钟）</td>
 *       <td>用 refreshToken 静默续期，用户无感</td></tr>
 *   <tr><td>AUTH_INVALID</td><td>令牌被伪造，或<b>会话已因登出被吊销</b></td>
 *       <td>不能刷新——刷新只会再吃一次 401。直接清空登录态并跳登录页</td></tr>
 *   <tr><td>AUTH_REQUIRED</td><td>请求压根没带令牌</td>
 *       <td>按游客处理，不清登录态（可能是故意访问公开接口）</td></tr>
 * </table>
 *
 * 早期实现把三者都当作「登录失效」，用 refreshToken 去续期。后果是：
 * 用户点了退出登录之后，前端仍会拿已吊销的 refreshToken 去刷新，
 * 每次都被拒，最后才跳登录页——用户看到的是「登出没反应，点哪都报错」。
 *
 * <h2>并发刷新的单飞（single-flight）</h2>
 * 首页同时发 5 个请求、恰好都撞上令牌过期时，不能发 5 次刷新请求：
 * 后端的刷新是<b>令牌轮转</b>语义——同一个 refreshToken 只能用一次，
 * 第二次就会被判为「已注销」，导致刷新失败、用户被踢下线。
 * 这里用同一个 Promise 让并发调用共享一次刷新。
 */

export const ACCESS_TOKEN_KEY = 'stocklab.accessToken'
export const REFRESH_TOKEN_KEY = 'stocklab.refreshToken'

/** 业务异常：携带后端返回的业务码，便于调用方分支处理。 */
export class BizError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status = 0) {
    super(message)
    this.name = 'BizError'
    this.code = code
    this.status = status
  }
}

// ---------------------------------------------------------------------------
// 令牌存取
// ---------------------------------------------------------------------------

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function saveTokens(pair: TokenPair): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, pair.accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, pair.refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/**
 * 登录态失效时的回调，由 auth store 注册。
 *
 * 不在这里直接 import store 是为了避免循环依赖：
 * store 需要 api 发请求，api 又需要通知 store 清状态。
 * 用回调把方向固定成单向。
 */
let onSessionExpired: (() => void) | null = null

export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

// ---------------------------------------------------------------------------
// axios 实例
// ---------------------------------------------------------------------------

/** baseURL 用相对路径：开发环境由 Vite 代理，生产由 Nginx 同域转发。 */
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 20_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 标记「已重试过」，防止刷新成功后重放再次 401 造成无限循环。 */
interface RetriableConfig extends AxiosRequestConfig {
  _retried?: boolean
}

/** 共享的刷新任务。非空表示正在刷新，此时所有 401 请求都等它。 */
let refreshPromise: Promise<TokenPair> | null = null

/** 走裸 axios，避免刷新请求本身再次进入下面的拦截器造成递归。 */
async function performRefresh(): Promise<TokenPair> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new BizError('AUTH_REQUIRED', '未登录')
  }
  const resp = await axios.post<ApiResult<TokenPair>>(
    '/api/v1/auth/refresh',
    { refreshToken },
    { headers: { 'Content-Type': 'application/json' }, timeout: 15_000 },
  )
  const pair = resp.data.data
  saveTokens(pair)
  return pair
}

function refreshOnce(): Promise<TokenPair> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      // 无论成败都要清空，否则失败后所有后续请求都会复用这个已 reject 的 Promise
      refreshPromise = null
    })
  }
  return refreshPromise
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status ?? 0
    const body = error.response?.data
    const code = body?.code ?? ''
    const message = body?.message ?? error.message ?? '请求失败'

    // 网络层失败（后端没起、断网、超时）：没有业务码可读
    if (status === 0) {
      return Promise.reject(new BizError('NETWORK_ERROR', '无法连接服务器，请检查网络或后端服务'))
    }

    const config = error.config as RetriableConfig | undefined

    if (status === 401) {
      if (code === 'AUTH_EXPIRED' && config && !config._retried) {
        try {
          await refreshOnce()
          config._retried = true
          return http.request(config)
        } catch {
          // 刷新失败：refreshToken 也过期了，只能重新登录
          clearTokens()
          onSessionExpired?.()
          return Promise.reject(new BizError('AUTH_EXPIRED', '登录已过期，请重新登录', 401))
        }
      }

      if (code === 'AUTH_INVALID') {
        // 令牌非法或会话已被登出吊销。这里**不要**尝试刷新：
        // 刷新请求只会再拿一次 AUTH_INVALID，白白多一次往返，
        // 而且会把「用户主动登出」拖成「点哪都报错」。
        clearTokens()
        onSessionExpired?.()
        return Promise.reject(new BizError(code, message, 401))
      }

      // AUTH_REQUIRED：本次请求没带令牌，按游客处理，不动登录态
      return Promise.reject(new BizError(code || 'AUTH_REQUIRED', message, 401))
    }

    return Promise.reject(new BizError(code || `HTTP_${status}`, message, status))
  },
)

// ---------------------------------------------------------------------------
// 请求封装：把 ApiResult<T> 拆包成 T
// ---------------------------------------------------------------------------

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  return response.data.data
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>) =>
    request<T>({ method: 'GET', url, params }),

  post: <T>(url: string, data?: unknown) =>
    request<T>({ method: 'POST', url, data }),

  del: <T>(url: string) =>
    request<T>({ method: 'DELETE', url }),
}
