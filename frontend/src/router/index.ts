import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由表。
 *
 * 页面按「功能模块」划分，路径也保持一致，便于和后端接口对照：
 *   /market        行情（指数 + 轮播 + 搜索）
 *   /stock/:code   个股详情（报价 + K 线 + 指标 + 基本面）
 *   /analysis       AI 研判
 *   /watchlist      自选股
 *   /holdings       持仓
 *   /news           资讯
 *   /settings       设置与诊断
 *
 * 懒加载用 `() => import(...)`：行情与资讯是首屏刚需，其余按需加载，
 * 避免首屏把 ECharts 这种大依赖一起拉下来。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '首页' },
  },
  {
    path: '/market',
    name: 'market',
    component: () => import('@/views/MarketView.vue'),
    meta: { title: '行情' },
  },
  {
    path: '/stock/:code',
    name: 'stock-detail',
    component: () => import('@/views/StockDetailView.vue'),
    meta: { title: '个股详情' },
    props: true,
  },
  {
    path: '/analysis',
    name: 'analysis',
    component: () => import('@/views/AnalysisView.vue'),
    meta: { title: 'AI 研判' },
  },
  {
    path: '/analysis/:code',
    name: 'analysis-detail',
    component: () => import('@/views/AnalysisView.vue'),
    meta: { title: 'AI 研判' },
    props: true,
  },
  {
    path: '/watchlist',
    name: 'watchlist',
    component: () => import('@/views/WatchlistView.vue'),
    meta: { title: '自选股', requiresAuth: true },
  },
  {
    path: '/holdings',
    name: 'holdings',
    component: () => import('@/views/HoldingView.vue'),
    meta: { title: '我的持仓', requiresAuth: true },
  },
  {
    path: '/news',
    name: 'news',
    component: () => import('@/views/NewsView.vue'),
    meta: { title: '资讯' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { title: '设置' },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', hideChrome: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 登录守卫。
 *
 * 登录态判定前先 `bootstrap()`：刷新页面时 localStorage 里可能还有令牌，
 * 但 store 里的用户资料是空的。不先确认一次的话，受保护页面会在
 * 「明明已登录」的情况下被弹回登录页。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (!auth.resolved) {
    await auth.bootstrap()
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    // 带上原目标地址，登录后直接回到用户本来想去的页面
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 已登录还去登录页：直接送回首页，避免出现「登录成功但停在登录页」
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'home' }
  }

  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 股票分析师` : '股票分析师'
})

export default router
