import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, expect, it, describe, vi } from 'vitest'
import type { ApiResult, TokenPair } from './types'

/**
 * `api/http.ts` 的令牌生命周期测试。
 *
 * <h2>为什么这段逻辑最值得测</h2>
 * 它是全站唯一「出错就会把用户踢下线」的地方，而且三条分支的<b>表现极其相似</b>：
 * 都是「请求失败 + 跳登录页」。肉眼和手工点按几乎无法区分
 * 「该刷新却直接登出」「不该刷新却刷新了」「并发刷新把令牌轮转废掉」——
 * 只有断言能分清：
 *
 * <table>
 *   <tr><th>场景</th><th>正确行为</th><th>写错时的后果</th></tr>
 *   <tr><td>令牌自然过期（AUTH_EXPIRED）</td><td>静默刷新一次后重放请求</td>
 *       <td>用户每 30 分钟被踢一次</td></tr>
 *   <tr><td>已登出 / 令牌非法（AUTH_INVALID）</td><td>不刷新，直接清状态</td>
 *       <td>登出后「点哪都报错」（刷新只会再吃一次 401）</td></tr>
 *   <tr><td>并发多个请求同时过期</td><td><b>只刷新一次</b>，其余共用</td>
 *       <td>后端是令牌轮转：第二个刷新请求会被判为已注销，用户被踢下线</td></tr>
 * </table>
 *
 * <h2>为什么用自定义 adapter</h2>
 * 直接 mock axios 模块会把「拦截器 ↔ 请求」之间的真实调用顺序一起 mock 掉，
 * 而那正是被测的东西。改成替换 adapter（保留 axios 全部真实逻辑，
 * 只把最外层的网络收发换掉），断言才能真的覆盖到拦截器链。
 *
 * <p>注意 adapter 必须在 <b>`./http` 被导入之前</b>写好：
 * axios 实例在 `create()` 时就把当时的 defaults 合并进自己的 defaults，
 * 之后再改 `axios.defaults.adapter` 对已创建的实例无效。
 */

/**
 * adapter 收到的配置对象。
 *
 * <p>用 axios 的 {@code InternalAxiosRequestConfig} 而不是裸 {@code any}：
 * 拦截器是按「真实 axios 契约」工作的，把配置写成 any 会让
 * 「少传了 headers / method 拼错」这类问题在编译期溜过去，
 * 等到运行时才发现请求根本没发出去。
 */
type AnyConfig = InternalAxiosRequestConfig & { _retried?: boolean }
type Handler = (config: AnyConfig) => AxiosResponse | Promise<AxiosResponse>

/** 每次测试重设的路由表与调用记录。 */
let routes: Array<{ match: (config: AnyConfig) => boolean; handler: Handler }> = []
let calls: string[] = []

function ok(config: AnyConfig, data: unknown, status = 200): AxiosResponse {
  return { data, status, statusText: 'OK', headers: {}, config, request: {} } as AxiosResponse
}

/** 构造一个带 response 的 AxiosError——拦截器要读 `error.response.data` 里的业务码。 */
function fail(config: AnyConfig, status: number, data: unknown): never {
  const response = { data, status, statusText: 'ERR', headers: {}, config, request: {} } as AxiosResponse
  throw new AxiosError(`Request failed with status code ${status}`, String(status), config, {}, response)
}

/** 无 response 的失败（断网 / 超时），用于验证 status === 0 那条分支。 */
function networkFail(config: AnyConfig): never {
  throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config, {})
}

axios.defaults.adapter = async (config: any) => {
  const typed = config as AnyConfig
  const url = String(typed.url ?? '')
  calls.push(`${String(typed.method ?? 'get').toUpperCase()} ${url}`)
  const route = routes.find((r) => r.match(typed))
  if (!route) {
    return fail(typed, 599, { code: 'NO_ROUTE', message: `测试未配置路由：${url}`, data: null })
  }
  return route.handler(typed)
}

/** 走动态 import，确保 adapter 已就位（见文件头说明）。 */
const httpMod = await import('./http')
const endpointsMod = await import('./endpoints')

const { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, BizError, api } = httpMod
const { analysisApi, authApi } = endpointsMod

function tokenPair(tag: string): TokenPair {
  return {
    accessToken: `access-${tag}`,
    refreshToken: `refresh-${tag}`,
    tokenType: 'Bearer',
    expiresIn: 1800,
  }
}

function apiResult<T>(data: T, code = 'OK'): ApiResult<T> {
  return { code, message: '', data }
}

beforeEach(() => {
  routes = []
  calls = []
  localStorage.clear()
  httpMod.setSessionExpiredHandler(() => {})
})

describe('AUTH_EXPIRED：静默刷新并重放', () => {
  it('三个请求同时过期时只发一次刷新（令牌轮转下多发一次就会被踢下线）', async () => {
    let refreshCount = 0

    routes = [
      {
        match: (c) => String(c.url).includes('/auth/refresh'),
        handler: (c) => {
          refreshCount++
          return ok(c, apiResult(tokenPair('new')))
        },
      },
      {
        match: (c) => String(c.url).startsWith('/quote/'),
        handler: (c) => {
          // 重放请求带 _retried 标记，直接成功；首次一律 401
          if (c._retried) {
            return ok(c, apiResult({ url: c.url }))
          }
          return fail(c, 401, apiResult(null, 'AUTH_EXPIRED'))
        },
      },
    ]

    // 两个令牌合并存在同一个键里（见 http.ts 的 ACCESS_TOKEN_KEY 注释），
    // 所以用 saveTokens 写入，而不是各自 setItem
    httpMod.saveTokens(tokenPair('old'))

    const results = await Promise.all([
      api.get<{ url: string }>('/quote/600519'),
      api.get<{ url: string }>('/quote/000858'),
      api.get<{ url: string }>('/quote/300750'),
    ])

    expect(refreshCount).toBe(1)
    expect(results.map((r) => r.url)).toEqual(['/quote/600519', '/quote/000858', '/quote/300750'])
    // 刷新成功后新令牌已落盘（合并键，读出来是 JSON 对象）
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toContain('"access-new"')
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toContain('"refresh-new"')
    expect(httpMod.getAccessToken()).toBe('access-new')
    expect(httpMod.getRefreshToken()).toBe('refresh-new')
  })

  it('刷新失败时清空本地令牌并通知登出，不无限重试', async () => {
    const onExpired = vi.fn()
    httpMod.setSessionExpiredHandler(onExpired)

    routes = [
      {
        match: (c) => String(c.url).includes('/auth/refresh'),
        handler: (c) => fail(c, 401, apiResult(null, 'AUTH_INVALID')),
      },
      {
        match: (c) => String(c.url).startsWith('/quote/'),
        handler: (c) => fail(c, 401, apiResult(null, 'AUTH_EXPIRED')),
      },
    ]

    // 两个令牌合并存在同一个键里（见 http.ts 的 ACCESS_TOKEN_KEY 注释），
    // 所以用 saveTokens 写入，而不是各自 setItem
    httpMod.saveTokens(tokenPair('old'))

    await expect(api.get('/quote/600519')).rejects.toMatchObject({ code: 'AUTH_EXPIRED' })

    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
    expect(onExpired).toHaveBeenCalledTimes(1)
    // 刷新只尝试一次：不能因为「重放又 401」而进入刷新循环
    expect(calls.filter((c) => c.includes('/auth/refresh'))).toHaveLength(1)
  })
})

describe('AUTH_INVALID：不刷新，直接清状态', () => {
  it('会话已被登出吊销时不去刷新（否则登出后点哪都报错）', async () => {
    const onExpired = vi.fn()
    httpMod.setSessionExpiredHandler(onExpired)

    routes = [
      {
        match: (c) => String(c.url).startsWith('/quote/'),
        handler: (c) => fail(c, 401, apiResult(null, 'AUTH_INVALID')),
      },
    ]

    // 两个令牌合并存在同一个键里（见 http.ts 的 ACCESS_TOKEN_KEY 注释），
    // 所以用 saveTokens 写入，而不是各自 setItem
    httpMod.saveTokens(tokenPair('old'))

    await expect(api.get('/quote/600519')).rejects.toMatchObject({ code: 'AUTH_INVALID' })

    expect(calls.some((c) => c.includes('/auth/refresh'))).toBe(false)
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
    expect(onExpired).toHaveBeenCalledTimes(1)
  })

  it('AUTH_REQUIRED（压根没带令牌）不动登录态，按游客处理', async () => {
    const onExpired = vi.fn()
    httpMod.setSessionExpiredHandler(onExpired)

    routes = [
      {
        match: (c) => String(c.url).startsWith('/quote/'),
        handler: (c) => fail(c, 401, apiResult(null, 'AUTH_REQUIRED')),
      },
    ]

    // 未登录时故意访问受保护接口：不应清状态（本来就没有状态）
    await expect(api.get('/quote/600519')).rejects.toMatchObject({ code: 'AUTH_REQUIRED' })

    expect(calls.some((c) => c.includes('/auth/refresh'))).toBe(false)
    expect(onExpired).not.toHaveBeenCalled()
  })
})

describe('超时预算', () => {
  it('AI 接口按分析预算给超时，普通接口用默认值——不能吃同一个 20s', async () => {
    const seen: number[] = []
    routes = [
      {
        match: () => true,
        handler: (c) => {
          seen.push(Number(c.timeout))
          return ok(c, apiResult({}))
        },
      },
    ]

    await analysisApi.get('600519')
    await analysisApi.regenerate('600519')
    await analysisApi.submit('600519')
    await api.get('/quote/600519')

    expect(seen).toEqual([
      httpMod.TIMEOUT.analysis,
      httpMod.TIMEOUT.analysis,
      httpMod.TIMEOUT.default,
      httpMod.TIMEOUT.default,
    ])
    // 分析预算必须大于后端 app.llm.timeout(45s)，否则「重新生成」必然前端先断
    expect(httpMod.TIMEOUT.analysis).toBeGreaterThan(45_000)
  })

  it('提交异步任务走默认超时：它只返回 taskId，不等生成', async () => {
    routes = [{ match: (c) => String(c.url).includes('/async'), handler: (c) => ok(c, apiResult({ taskId: 't', status: 'PENDING' }), 202) }]

    const submission = await analysisApi.submit('600519')

    expect(submission.taskId).toBe('t')
    expect(calls).toEqual(['POST /analysis/600519/async'])
  })
})

describe('路径与错误码', () => {
  it('异步任务查询用 /analysis/tasks/{taskId}，并对 taskId 做编码', async () => {
    routes = [{ match: () => true, handler: (c) => ok(c, apiResult({ taskId: 't', status: 'RUNNING', report: null, error: null })) }]

    await analysisApi.task('a/b c')

    expect(calls).toEqual(['GET /analysis/tasks/a%2Fb%20c'])
  })

  it('没有响应体的失败（断网 / 超时）映射为 NETWORK_ERROR 而不是被当成业务错误', async () => {
    routes = [{ match: () => true, handler: (c) => networkFail(c) }]

    await expect(api.get('/quote/600519')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })

  it('普通 5xx 保留 HTTP 状态码，便于区分「后端坏了」与「业务拒绝」', async () => {
    routes = [{ match: () => true, handler: (c) => fail(c, 502, apiResult(null, 'LLM_UPSTREAM_ERROR')) }]

    // BizError 是从动态 import 的模块里解构出来的，**只有值没有类型**，
    // 因此不能直接写 `as BizError`，要用 InstanceType<typeof BizError> 取出实例类型
    const thrown = await api.get('/analysis/600519').catch((e: unknown) => e)
    const error = thrown as InstanceType<typeof BizError>

    expect(error).toBeInstanceOf(BizError)
    expect(error.code).toBe('LLM_UPSTREAM_ERROR')
    expect(error.status).toBe(502)
  })

  it('登出无论后端返回什么，本地令牌都必须清掉', async () => {
    routes = [
      { match: (c) => String(c.url).includes('/auth/logout'), handler: (c) => fail(c, 500, apiResult(null, 'INTERNAL')) },
    ]

    // 两个令牌合并存在同一个键里（见 http.ts 的 ACCESS_TOKEN_KEY 注释），
    // 所以用 saveTokens 写入，而不是各自 setItem
    httpMod.saveTokens(tokenPair('old'))

    // 登出接口失败会向上抛（调用方可能想提示用户），但本地令牌必须先被清掉
    await authApi.logout().catch(() => undefined)

    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })
})
