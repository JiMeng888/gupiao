import { api, clearTokens, getRefreshToken, saveTokens } from './http'
import type {
  AnalysisReport,
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

  carousel: (count = 6) => api.get<CarouselItem[]>('/quote/carousel', { count }),

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

  quote: (code: string) => api.get<Quote>(`/quote/${encodeURIComponent(code)}`),

  bars: (code: string, query: BarsQuery = {}) =>
    api.get<Bar[]>(`/quote/${encodeURIComponent(code)}/bars`, {
      period: query.period ?? 'daily',
      limit: query.limit ?? 120,
      start: query.start ?? '',
      end: query.end ?? '',
    }),
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
   * 获取分析结论。
   *
   * `GET` 优先返回数据库里的历史存档（省一次大模型调用，也省用户等待），
   * `POST` 强制重新生成。UI 上应该把这两件事区分成两个按钮，
   * 而不是一个「分析」按钮每次都在烧钱。
   */
  get: (code: string) => api.get<AnalysisReport>(`/analysis/${encodeURIComponent(code)}`),

  regenerate: (code: string) => api.post<AnalysisReport>(`/analysis/${encodeURIComponent(code)}`),

  history: (code: string, limit = 10) =>
    api.get<AnalysisReport[]>(`/analysis/${encodeURIComponent(code)}/history`, { limit }),
}
