import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { MarketSourceStatus } from '@/api/types'

/**
 * 数据源状态端点的替身。
 *
 * 用 `vi.hoisted` 而不是普通顶层 const：`vi.mock` 的工厂函数会被提升到所有
 * import 之前执行，此时普通 const 还处在暂时性死区（ReferenceError: Cannot
 * access before initialization）。hoisted 的内容与被提升的 mock 处于同一层，
 * 这是官方推荐的写法。
 */
const { sourceMock } = vi.hoisted(() => ({ sourceMock: vi.fn() }))

vi.mock('@/api/endpoints', () => ({
  quoteApi: { source: sourceMock },
}))

// 静态 import 即可：上面的 vi.mock 已被提升，此处拿到的是替身模块。
import { useMarketSourceStore } from './marketSource'

/**
 * 行情数据源状态 store。
 *
 * <p>这个 store 的全部价值在于「如实告知」：用户不该把演示数据当成真实行情。
 * 之前它有一个方向完全相反的缺陷——`warning` 依赖 `status` 非空，而后端
 * 整体不可用时状态端点自己也请求失败，`status` 一直是 null，于是横幅
 * <b>恰恰在最该出现的场合消失了</b>。下面用「不可达」「降级」「未就绪」
 * 三种情形分别钉住，并单独验证「恢复后错误状态被清掉」。
 */
describe('行情数据源状态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function status(overrides: Partial<MarketSourceStatus> = {}): MarketSourceStatus {
    return {
      provider: 'eastmoney',
      ready: true,
      displayName: '东方财富（真实行情）',
      degraded: false,
      servingByAction: {},
      notice: null,
      ...overrides,
    }
  }

  it('尚未加载时不提示，避免首屏闪一条假警告', () => {
    const store = useMarketSourceStore()

    expect(store.badgeText).toBe('数据源未知')
    expect(store.badgeType).toBe('info')
    expect(store.unreachable).toBe(false)
    expect(store.warningTitle).toBeNull()
    expect(store.warning).toBeNull()
  })

  it('后端不可达时必须给出警示（曾经的缺陷就是这里横幅消失）', async () => {
    const store = useMarketSourceStore()
    sourceMock.mockRejectedValue(new Error('Network Error'))

    await store.refresh()

    expect(store.unreachable).toBe(true)
    expect(store.badgeText).toBe('数据源不可用')
    expect(store.badgeType).toBe('danger')
    expect(store.warningTitle).toBe('无法连接后端服务')
    expect(store.warningType).toBe('error')
    // 文案要说清「无法确认真实性」，而不只是「请求失败」
    expect(store.warning).toContain('Network Error')
    expect(store.warning).toContain('请勿据此做出投资决策')
  })

  it('降级时徽标与警示都指向「演示数据」', async () => {
    const store = useMarketSourceStore()
    sourceMock.mockResolvedValue(
      status({ degraded: true, notice: '榜单与个股行情当前由内置演示数据提供' }),
    )

    await store.refresh()

    expect(store.unreachable).toBe(false)
    expect(store.badgeText).toBe('演示数据')
    expect(store.badgeType).toBe('danger')
    expect(store.warningTitle).toBe('当前行情包含演示数据')
    expect(store.warningType).toBe('error')
    expect(store.warning).toBe('榜单与个股行情当前由内置演示数据提供')
  })

  it('主数据源未就绪但未降级时，用较低一级的 warning 提示', async () => {
    const store = useMarketSourceStore()
    sourceMock.mockResolvedValue(
      status({ ready: false, displayName: '东方财富', notice: '主数据源尚未就绪' }),
    )

    await store.refresh()

    expect(store.badgeText).toBe('数据源未就绪')
    expect(store.badgeType).toBe('warning')
    expect(store.warningTitle).toBe('行情数据源未就绪')
    expect(store.warningType).toBe('warning')
    expect(store.warning).toBe('主数据源尚未就绪')
  })

  it('一切正常时展示主数据源名称且不提示', async () => {
    const store = useMarketSourceStore()
    sourceMock.mockResolvedValue(status())

    await store.refresh()

    expect(store.badgeText).toBe('东方财富（真实行情）')
    expect(store.badgeType).toBe('success')
    expect(store.warningTitle).toBeNull()
    expect(store.warning).toBeNull()
  })

  it('从失败恢复后错误状态必须被清掉，否则横幅会永远挂着', async () => {
    const store = useMarketSourceStore()

    sourceMock.mockRejectedValueOnce(new Error('Network Error'))
    await store.refresh()
    expect(store.unreachable).toBe(true)

    sourceMock.mockResolvedValueOnce(status())
    await store.refresh()

    expect(store.unreachable).toBe(false)
    expect(store.lastError).toBeNull()
    expect(store.warning).toBeNull()
    expect(store.badgeText).toBe('东方财富（真实行情）')
  })

  it('列出实际走降级源的操作，供设置页定位', async () => {
    const store = useMarketSourceStore()
    sourceMock.mockResolvedValue(
      status({
        degraded: true,
        provider: 'eastmoney',
        servingByAction: { getQuote: 'eastmoney', getTopMovers: 'mock' },
      }),
    )

    await store.refresh()

    expect(store.degradedActions).toEqual([{ action: 'getTopMovers', source: 'mock' }])
  })
})
