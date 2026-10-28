import {
  api,
  clearTokens,
  getRefreshToken,
  saveTokens,
  TIMEOUT,
  type RequestOptions,
} from './http'
import type {
  AnalysisReport,
  AnalysisTaskSubmission,
  AnalysisTaskView,
  Bar,
  CarouselItem,
  DupontAnalysis,
  FundamentalOverview,
  HoldingSummary,
  HoldingView,
  IndexSnapshot,
  IndicatorResponse,
  MarketSourceStatus,
  Mover,
  NewsChannelView,
  NewsItem,
  Quote,
  Sector,
  StatementsResponse,
  StockBrief,
  TokenPair,
  UserProfile,
  WatchlistGroup,
  WatchlistItem,
} from './types'

/**
 * 后端接口封装。
 *
 * 每个函数对应一个后端端点，路径与后端 Controller 的映射严格一致
 * （对照过 WatchlistController / HoldingController 等源文件，不是凭记忆写的）。
 * 参数名保持与后端 `@RequestParam` 同名，避免出现「本地叫 keyword、
 * 后端叫 q」这类只有联调时才会暴露的问题。
 */

// ---------------------------------------------------------------------------
// 鉴权
// ---------------------------------------------------------------------------

export const authApi = {
  register: (phone: string, password: string, nickname?: string) =>
    api.post<TokenPair>('/auth/register', { phone, password, nickname }),

  login: (phone: string, password: string) =>
    api.post<TokenPair>('/auth/login', { phone, password }),

  refresh: (refreshToken: string) =>
    api.post<TokenPair>('/auth/refresh', { refreshToken }),

  /**
   * 登出。
   *
   * 只需带 Authorization 头即可——后端会从 Access Token 里读出会话 ID
   * 并吊销整个会话（access + refresh 一起失效）。请求体里再带一份
   * refreshToken 是加固，不是必需。
   *
   * 注意：只有请求体是必填时才需要 `@RequestBody`；后端这里 body 可选，
   * 所以我们传 `{}` 也不会 400。
   */
  logout: async () => {
    const refreshToken = getRefreshToken()
    try {
      await api.post<{ ok: boolean }>('/auth/logout', refreshToken ? { refreshToken } : {})
    } finally {
      // 无论后端返回什么，本地令牌都必须清掉：
      // 不然登出接口一旦失败，用户会卡在「已登出但还带着令牌」的状态
      clearTokens()
    }
  },

  me: () => api.get<UserProfile>('/auth/me'),

  changePassword: (oldPassword: string, newPassword: string) =>
    api.post<{ ok: boolean; message: string }>('/auth/password', { oldPassword, newPassword }),
}

/** 登录成功后统一落盘令牌，避免每个调用点各写一遍。 */
export function persistTokens(pair: TokenPair): void {
  saveTokens(pair)
}

// ---------------------------------------------------------------------------
// 行情
// ---------------------------------------------------------------------------

export interface BarsQuery {
  period?: string
  limit?: number
  start?: string
  end?: string
}

export const quoteApi = {
  indices: () => api.get<IndexSnapshot[]>('/quote/indices'),

  /**
   * 首页精选关注。
   *
   * `round` 是「第几批」而不是翻页页码：同一轮次后端返回结果稳定，
   * 前端每 10 秒把它 +1 即可换一批。不要传时间戳——那样每个客户端
   * 每次请求拿到的都是新组合，既无法复现也会让后端失去可缓存性。
   */
  carousel: (count = 6, round = 0) => api.get<CarouselItem[]>('/quote/carousel', { count, round }),

  search: (q: string) => api.get<StockBrief[]>('/quote/search', { q }),

  /**
   * 市场榜单。跌幅榜用 metric=changePct + order=asc 表达，
   * 没有单独的「跌幅」维度——后端只认两个排序字段。
   */
  movers: (
    metric: 'changePct' | 'amount' = 'changePct',
    order: 'asc' | 'desc' = 'desc',
    limit = 10,
  ) => api.get<Mover[]>('/quote/movers', { metric, order, limit }),

  source: () => api.get<MarketSourceStatus>('/quote/source'),

  /**
   * 板块榜单。
   *
   * 上游不可用时后端返回 503，这里会抛 BizError，
   * 调用方必须自己处理空态——不能 catch 掉当空列表，
   * 「暂不可用」和「没有板块数据」在界面上是两回事。
   */
  sectors: (limit = 10, options?: RequestOptions) =>
    api.get<Sector[]>('/quote/sectors', { limit }, options),

  quote: (code: string, options?: RequestOptions) =>
    api.get<Quote>(`/quote/${encodeURIComponent(code)}`, undefined, options),

  /** `signal` 用于周期切换时取消上一个在途请求，见 StockChart.vue。 */
  bars: (code: string, query: BarsQuery = {}, options?: RequestOptions) =>
    api.get<Bar[]>(
      `/quote/${encodeURIComponent(code)}/bars`,
      {
        period: query.period ?? 'daily',
        limit: query.limit ?? 120,
        start: query.start ?? '',
        end: query.end ?? '',
      },
      options,
    ),
}

// ---------------------------------------------------------------------------
// 技术指标
// ---------------------------------------------------------------------------

export const indicatorApi = {
  /**
   * 查询技术指标。
   *
   * 参数名是 `indicators`（复数），不是 `names`——写错时后端不会报错，
   * 而是走默认值返回 MACD/RSI/BOLL/KDJ，前端会误以为「我要的指标拿到了」。
   */
  get: (code: string, indicators: string[] = ['MACD', 'RSI', 'BOLL', 'KDJ'], limit = 120) =>
    api.get<IndicatorResponse>(`/indicator/${encodeURIComponent(code)}`, {
      indicators: indicators.join(','),
      limit,
    }),
}

// ---------------------------------------------------------------------------
// 基本面
// ---------------------------------------------------------------------------

export const fundamentalApi = {
  overview: (code: string) => api.get<FundamentalOverview>(`/fundamental/${encodeURIComponent(code)}`),

  dupont: (code: string) => api.get<DupontAnalysis>(`/fundamental/${encodeURIComponent(code)}/dupont`),

  statements: (code: string, periods = 8) =>
    api.get<StatementsResponse>(`/fundamental/${encodeURIComponent(code)}/statements`, { periods }),

  /** 同行对比，后端限制最多 6 只（再多会被 400 拒绝）。 */
  compare: (codes: string[]) =>
    api.get<FundamentalOverview[]>('/fundamental/compare', { codes: codes.join(',') }),
}

// ---------------------------------------------------------------------------
// 资讯
// ---------------------------------------------------------------------------

export const newsApi = {
  channels: () => api.get<NewsChannelView[]>('/news/channels'),

  list: (channel = 'all', limit = 20) =>
    api.get<NewsItem[]>('/news/list', { channel, limit }),
}

// ---------------------------------------------------------------------------
// 自选股
// ---------------------------------------------------------------------------

export const watchlistApi = {
  groups: () => api.get<WatchlistGroup[]>('/watchlist/groups'),

  createGroup: (name: string, sortOrder?: number, parentId?: string) =>
    api.post<WatchlistGroup>('/watchlist/groups', { name, sortOrder, parentId }),

  deleteGroup: (groupId: string) => api.del<{ ok: boolean }>(`/watchlist/groups/${groupId}`),

  items: (groupId?: string) =>
    api.get<WatchlistItem[]>('/watchlist/items', groupId ? { groupId } : undefined),

  addItem: (code: string, groupId?: string) =>
    api.post<WatchlistItem>('/watchlist/items', { code, groupId }),

  removeItem: (itemId: string) => api.del<{ ok: boolean }>(`/watchlist/items/${itemId}`),
}

// ---------------------------------------------------------------------------
// 持仓
// ---------------------------------------------------------------------------

export const holdingApi = {
  list: () => api.get<HoldingView[]>('/holdings'),

  summary: () => api.get<HoldingSummary>('/holdings/summary'),

  buy: (code: string, shares: number, price: number, name?: string, buyDate?: string) =>
    api.post<HoldingView>('/holdings/buy', { code, name, shares, price, buyDate }),

  sell: (code: string, shares: number, price: number) =>
    api.post<HoldingView>('/holdings/sell', { code, shares, price }),

  remove: (code: string) => api.del<{ ok: boolean }>(`/holdings/${encodeURIComponent(code)}`),
}

// ---------------------------------------------------------------------------
// AI 分析
// ---------------------------------------------------------------------------

export const analysisApi = {
  /**
   * 读取分析结论。
   *
   * `GET` 优先返回数据库里的历史存档（省一次大模型调用，也省用户等待）。
   *
   * <b>注意这不是一个「纯读」接口</b>：库里没有存档时后端会同步生成一次，
   * 也就是会等大模型 10~40 秒。所以这里的超时预算取 TIMEOUT.analysis（75s），
   * 不能吃全局的 20s——那样在没有存档的标的上必然超时，
   * 而后端其实还在跑、稍后仍会把报告写进库，用户只会看到「获取分析失败」。
   *
   * 想避免这种等待，请用 {@link submit} + {@link pollAnalysisTask} 走异步链路。
   */
  get: (code: string, timeout: number = TIMEOUT.analysis) =>
    api.get<AnalysisReport>(`/analysis/${encodeURIComponent(code)}`, undefined, { timeout }),

  /**
   * 强制重新生成（<b>同步</b>，会真实占用一个请求线程等大模型跑完）。
   *
   * 保留它是为了兼容与调试；界面上的「重新生成」应当用 {@link submit}，
   * 否则后端的异步线程池等于白建。
   */
  regenerate: (code: string, timeout: number = TIMEOUT.analysis) =>
    api.post<AnalysisReport>(`/analysis/${encodeURIComponent(code)}`, undefined, { timeout }),

  /**
   * 提交异步生成任务，立即返回 taskId（HTTP 202）。
   *
   * 代码非法时后端仍会同步返回 404，所以这个调用本身很快，
   * 用默认超时即可——真正的等待发生在轮询阶段。
   */
  submit: (code: string) =>
    api.post<AnalysisTaskSubmission>(
      `/analysis/${encodeURIComponent(code)}/async`,
      undefined,
      { timeout: TIMEOUT.default },
    ),

  /**
   * 查询异步任务状态。纯读库、不触发模型调用，用默认超时。
   *
   * taskId 失效（本地残留的过期 ID）时后端返回 404，
   * 调用方需要据此停止轮询而不是一直重试。
   */
  task: (taskId: string) =>
    api.get<AnalysisTaskView>(`/analysis/tasks/${encodeURIComponent(taskId)}`),

  history: (code: string, limit = 10) =>
    api.get<AnalysisReport[]>(`/analysis/${encodeURIComponent(code)}/history`, { limit }),
}
