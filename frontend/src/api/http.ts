import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import type { ApiResult, TokenPair } from './types'
import { readJson, removeKey, storageAvailable, storageKey, writeJson } from '@/utils/storage'

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

/**
 * 令牌存储键，遵循 {@code 模块@配置@版本} 规范（见 utils/storage.ts）。
 *
 * <p>两个令牌合并成一个键：它们总是同生共死（登录时一起写、登出时一起删），
 * 拆成两个键只会让「写了一半失败」成为可能——留下一个没有 refreshToken 的
 * accessToken，用户会卡在「看起来登录了但一刷新就被踢」的状态。
 */
export const ACCESS_TOKEN_KEY = storageKey('auth', 'token')

/**
 * Refresh Token 的存储位置。
 *
 * <p>与 {@link ACCESS_TOKEN_KEY} <b>是同一个键</b>：两个令牌总是同生共死
 * （登录时一起写、登出时一起删），分成两个键只会让「写了一半失败」成为可能——
 * 留下一个没有 refreshToken 的 accessToken，用户会卡在
 * 「看起来登录了，但一刷新就被踢」的状态。
 * 保留这个别名是为了让调用处读起来语义清楚，而不是真的有两个键。
 */
export const REFRESH_TOKEN_KEY = ACCESS_TOKEN_KEY

/** 旧版本遗留键名，启动时迁移一次。 */
export const LEGACY_TOKEN_KEYS = ['stocklab.accessToken', 'stocklab.refreshToken'] as const
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
  return readJson<TokenPair | null>(ACCESS_TOKEN_KEY, null)?.accessToken ?? null
}

export function getRefreshToken(): string | null {
  return readJson<TokenPair | null>(REFRESH_TOKEN_KEY, null)?.refreshToken ?? null
}

export function saveTokens(pair: TokenPair): void {
  writeJson(ACCESS_TOKEN_KEY, pair)
}

/**
 * 把旧版本拆分存储的令牌合并到新键。
 *
 * <p>顺序很重要：先读齐两个旧值，再写新键，最后才删旧键。
 * 反过来（边读边删）一旦中间抛异常，就会丢掉还没读出来的那个。
 */
function migrateLegacyTokens(): void {
  if (!storageAvailable) {
    return
  }
  try {
    if (localStorage.getItem(ACCESS_TOKEN_KEY) !== null) {
      return
    }
    const access = localStorage.getItem(LEGACY_TOKEN_KEYS[0])
    const refresh = localStorage.getItem(LEGACY_TOKEN_KEYS[1])
    if (!access) {
      return
    }
    writeJson(ACCESS_TOKEN_KEY, {
      accessToken: access,
      refreshToken: refresh ?? '',
      tokenType: 'Bearer',
      expiresIn: 0,
    })
    removeKey(LEGACY_TOKEN_KEYS[0])
    removeKey(LEGACY_TOKEN_KEYS[1])
  } catch {
    /* 忽略 */
  }
}


migrateLegacyTokens()

export function clearTokens(): void {
  removeKey(ACCESS_TOKEN_KEY)
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

/**
 * 超时预算。
 *
 * <h2>为什么必须存在「按请求覆盖」这件事</h2>
 * 三个环节的超时是互相约束的，而最短的那个如果设在客户端，就一定会先断：
 *
 * <table>
 *   <tr><th>环节</th><th>预算</th><th>说明</th></tr>
 *   <tr><td>常规接口</td><td>20s</td><td>行情 / 自选 / 持仓，上游超时只有 6s</td></tr>
 *   <tr><td>AI 分析</td><td>75s</td><td>后端单次大模型上限 45s，再留排队与取数余量</td></tr>
 *   <tr><td>Nginx 反代</td><td>120s</td><td>为 AI 链路留的</td></tr>
 * </table>
 *
 * 早期只有全局 20s，于是配好大模型 Key 后「重新生成」在客户端 20 秒必断，
 * 而后端其实还在跑、几十秒后仍会把报告写进库——用户看到的是「AI 分析坏了」。
 * 20s 对行情够用，对分析远远不够，所以超时必须按接口性质给。
 */
export const TIMEOUT = {
  /** 常规读写：行情、指标、基本面、自选、持仓 */
  default: 20_000,
  /**
   * AI 分析。必须 > 后端 app.llm.timeout（45s）且 < Nginx（120s）。
   * 取值 75s：45s 生成 + 取数/排队/网络往返的余量。
   */
  analysis: 75_000,
} as const

/** baseURL 用相对路径：开发环境由 Vite 代理，生产由 Nginx 同域转发。 */
const http = axios.create({
  baseURL: '/api/v1',
  timeout: TIMEOUT.default,
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

    // 主动取消（切换周期 / 组件卸载）：**不是错误**，单独用一个码区分。
    // 调用方靠这个码判断「这次失败是我自己取消的」从而静默忽略，
    // 否则每次切周期都会弹一个「无法连接服务器」，把正常操作报成故障
    if (axios.isCancel(error)) {
      return Promise.reject(new BizError('REQUEST_ABORTED', '请求已取消'))
    }

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

/**
 * 请求封装。
 *
 * `timeout` 为可选覆盖项，不传时用 axios 实例的全局值。
 * 需要覆盖的只有 AI 相关接口——见 {@link TIMEOUT} 的说明。
 */
/**
 * 单次请求的附加选项。
 *
 * <h2>signal：为什么「切换周期必须取消上一个在途请求」</h2>
 * 行情页的周期切换是高频操作（分时 / 5日 / 日K / 周K …）。
 * 若不在切换时取消上一个请求，就会出现竞态：
 * 用户从「日K」切到「周K」，日K 的响应因为上游慢而晚到，
 * 后到的旧数据会覆盖已经渲染好的周K——界面上表现为
 * 「点了周K，画面先闪一下日K 再跳回周K」，且每次网络抖动都可能不同。
 *
 * <p>这类 bug 在开发环境很难复现（局域网几乎无延迟），
 * 却在真实网络下稳定出现。因此在 api 层统一支持 AbortSignal，
 * 由调用方（见 {@code StockChart.vue}）在每次切换前 abort 上一个 controller。
 */
export interface RequestOptions {
  /** 覆盖全局超时预算，单位毫秒。见 {@link TIMEOUT}。 */
  timeout?: number
  /** 取消信号。abort 后请求以 NETWORK_ERROR 失败，调用方据此忽略结果。 */
  signal?: AbortSignal
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>, options?: RequestOptions) =>
    request<T>({ method: 'GET', url, params, ...options }),

  post: <T>(url: string, data?: unknown, options?: RequestOptions) =>
    request<T>({ method: 'POST', url, data, ...options }),

  del: <T>(url: string) =>
    request<T>({ method: 'DELETE', url }),
}
