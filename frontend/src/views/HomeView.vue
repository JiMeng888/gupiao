<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import ChangeText from '@/components/ChangeText.vue'
import SectionCarousel from '@/components/SectionCarousel.vue'
import { newsApi, quoteApi } from '@/api/endpoints'
import { useMarketSourceStore } from '@/stores/marketSource'
import type { CarouselItem, IndexSnapshot, Mover, NewsItem } from '@/api/types'
import { formatEpochSeconds } from '@/api/types'

/**
 * 首页：指数概览 + 精选关注 + 最新资讯 + 今日涨幅榜。
 *
 * 大盘指数 / 最新资讯 / 今日涨幅榜三块是「每张一条记录」的轮播；
 * 精选关注是「一批 6 只」的网格，按轮次整片换批（见下方定时器说明）。
 *
 * 三点保持不变，因为它们解决的是「数据到不了」而不是「怎么展示」的问题：
 *
 * 1. **四块并行加载**：用 Promise.allSettled 而不是 await 串行。
 *    任何一块失败都不应该让另外三块也跟着空着（资讯源尤其不稳定）。
 * 2. **列表条数一次性取满**：轮播一次只显示一张，但请求仍按
 *    「候选池」的尺寸取（指数 5 条 / 精选 6 条 / 资讯 8 条 / 涨幅榜 10 条），
 *    否则轮播没有可切换的内容，会退化成静态卡片。
 * 3. **失败要说出原因**：并行加载只处理 fulfilled 会把失败静默掉，
 *    用户看到区块空着却不知道是「没数据」还是「取数挂了」。
 *    见下方 `errors`——原因只在区块确实无内容可显示时才呈现，
 *    不会因为一次轮询失败就把已有数据盖掉。
 */
const router = useRouter()
const source = useMarketSourceStore()

const indices = ref<IndexSnapshot[]>([])
const picks = ref<CarouselItem[]>([])
const news = ref<NewsItem[]>([])
const gainers = ref<Mover[]>([])
const loading = ref(true)

/** 首页区块标识，用于按块记录失败原因。 */
type HomeBlock = 'indices' | 'picks' | 'news' | 'gainers'

/**
 * 各区块的失败原因，仅在该区块确实没有内容可展示时呈现给用户。
 *
 * 原实现走 `Promise.allSettled` 却只处理 `fulfilled` 分支，失败被完全静默：
 * 页面表现为区块停在空态，用户分不清是「今天没有数据」还是「取数失败」。
 * 对股票应用来说这个区别很重要——前者可以忽略，后者意味着不该据此判断行情。
 *
 * 它与顶部横幅（`source.warning`）是分工而非重复：
 * 横幅说的是「数据源整体怎么了」，这里说的是「这一块为什么是空的」。
 * 后端整体健康、但某一个上游单独挂掉时，只有这里能说清楚。
 */
const errors = ref<Record<HomeBlock, string | null>>({
  indices: null,
  picks: null,
  news: null,
  gainers: null,
})

/** 从 allSettled 的某项结果里取失败原因；成功返回 null。 */
function failReason(result: PromiseSettledResult<unknown>): string | null {
  if (result.status === 'fulfilled') return null
  return result.reason instanceof Error ? result.reason.message : '数据获取失败'
}

/**
 * 精选关注：一批固定 6 只，铺成 2 列 × 3 行的网格（每行 2 张）。
 *
 * 之所以是「网格 + 整片换批」而不是「一次一条轮播」：单条轮播在一个
 * 双列卡片里只占一行高度，四周留白很大，视觉上很空；而 6 只铺满后
 * 卡片高度被内容撑实，同时 10 秒整片换一批的节奏比逐条滚动更容易
 * 一眼看出「换了」。
 */
const PICKS_PER_BATCH = 6

/** 当前批次号。每次换批 +1，作为后端的 round 参数——同一轮次结果稳定。 */
const picksRound = ref(0)

/** 换批间隔（毫秒）。与后端 round 语义配合，不传时间戳。 */
const PICKS_REFRESH_MS = 10_000

/** 是否正在换批（仅用于给网格一个轻微的加载态，不遮挡已有数据）。 */
const picksRefreshing = ref(false)

let picksTimer: number | undefined

/**
 * 拉取一批精选关注。
 *
 * 失败时保留上一批数据不清空：这是首页的常驻区块，每 10 秒一次请求，
 * 偶发失败就把它清空会变成「闪一下变空再变回来」，比显示略旧的行情更糟。
 */
async function loadPicks(round: number): Promise<void> {
  picksRefreshing.value = true
  try {
    picks.value = await quoteApi.carousel(PICKS_PER_BATCH, round)
    errors.value.picks = null
  } catch (e) {
    // 保留原数据，等下一个周期再试；原因记下来供空态显示
    errors.value.picks = e instanceof Error ? e.message : '精选关注获取失败'
  } finally {
    picksRefreshing.value = false
  }
}

/**
 * 涨幅榜的候选池。
 *
 * 榜单后端只按「涨幅从高到低」返回前 N 条，本身不提供真实排名字段，
 * 所以名次由本地按返回顺序推导——这是与后端契约一致的
 * （`topMovers` 实现里就是按指标排序后截断）。
 */
const gainersCount = computed(() => gainers.value.length)

/** 成交额（元）换算成「亿」展示；缺数据显示 -- 而不是 0.00 亿。 */
function formatAmountYi(amount: number): string {
  if (!amount) return '--'
  return (amount / 1e8).toFixed(2) + ' 亿'
}

onMounted(async () => {
  loading.value = true
  const [idx, car, nw, mv] = await Promise.allSettled([
    quoteApi.indices(),
    quoteApi.carousel(PICKS_PER_BATCH, 0),
    newsApi.list('all', 8),
    quoteApi.movers('changePct', 'desc', 10),
  ])
  if (idx.status === 'fulfilled') indices.value = idx.value
  if (car.status === 'fulfilled') picks.value = car.value
  if (nw.status === 'fulfilled') news.value = nw.value
  if (mv.status === 'fulfilled') gainers.value = mv.value
  errors.value = {
    indices: failReason(idx),
    picks: failReason(car),
    news: failReason(nw),
    gainers: failReason(mv),
  }
  loading.value = false
  // 首页有大量行情数字，数据源状态必须同步刷新一次
  void source.refresh()

  picksTimer = window.setInterval(() => {
    picksRound.value += 1
    void loadPicks(picksRound.value)
  }, PICKS_REFRESH_MS)
})

/**
 * 组件卸载必须清掉定时器。
 *
 * 首页是路由级组件，用户切走再切回来会重新挂载；不清定时器的话
 * 每进出一次首页就多一个 10 秒轮询在后台跑，且回调会写早已废弃的
 * 组件状态，最终表现为切几次页面后请求量成倍增长。
 */
onBeforeUnmount(() => {
  if (picksTimer !== undefined) {
    window.clearInterval(picksTimer)
    picksTimer = undefined
  }
})

function openStock(code: string): void {
  router.push({ name: 'stock-detail', params: { code } })
}
</script>

<template>
  <div class="page">
    <!-- 指数概览：5 个指数逐张轮播 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">大盘指数</span>
        <router-link to="/market" class="more">
          全部行情 <el-icon><ArrowRight /></el-icon>
        </router-link>
      </div>
      <div class="card__body">
        <SectionCarousel
          :loading="loading"
          :count="indices.length"
          :interval="5000"
          height="86px"
        >
          <template #default="{ index }">
            <div v-if="indices[index]" class="index-card">
              <div class="index-card__name">{{ indices[index].name }}</div>
              <div class="index-card__value num">
                <ChangeText :value="indices[index].value" :show-plus="false" />
              </div>
              <div class="index-card__change">
                <ChangeText :value="indices[index].change" />
                <ChangeText :value="indices[index].changePct" suffix="%" />
              </div>
            </div>
          </template>
          <template #loading>
            <el-skeleton :rows="2" animated />
          </template>
          <template #empty>
            <el-empty :description="errors.indices ?? '暂无指数数据'" :image-size="72" />
          </template>
        </SectionCarousel>
      </div>
    </section>

    <div class="grid grid--2" style="margin-top: 16px">
      <!-- 精选关注：6 只铺成 2 列 × 3 行网格，每 10 秒整片换批 -->
      <section class="card">
        <div class="card__header">
          <span class="card__title">精选关注</span>
          <span class="muted picks__hint" :class="{ 'is-live': picksRefreshing }">
            <span class="picks__dot" />
            每 10 秒更新 · 第 {{ picksRound + 1 }} 批
          </span>
        </div>
        <div class="card__body">
          <el-skeleton v-if="loading" :rows="3" animated />
          <el-empty
            v-else-if="!picks.length"
            :description="errors.picks ?? '暂无精选数据'"
            :image-size="72"
          />
          <div v-else class="picks-grid">
            <div
              v-for="(item, i) in picks"
              :key="item.code + '-' + picksRound + '-' + i"
              class="pick"
              @click="openStock(item.code)"
            >
              <div class="pick__top">
                <span class="pick__name">{{ item.name }}</span>
                <span class="mono muted pick__code">{{ item.code }}</span>
              </div>
              <div class="pick__price">
                <ChangeText :value="item.price" :show-plus="false" />
              </div>
              <div class="pick__bottom">
                <ChangeText :value="item.changePct" suffix="%" />
                <el-tag size="small" type="info" effect="plain">{{ item.highlight }}</el-tag>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 最新资讯 -->
      <section class="card">
        <div class="card__header">
          <span class="card__title">最新资讯</span>
          <router-link to="/news" class="more">
            更多 <el-icon><ArrowRight /></el-icon>
          </router-link>
        </div>
        <div class="card__body">
          <SectionCarousel
            :loading="loading"
            :count="news.length"
            :interval="5000"
            height="96px"
          >
            <template #default="{ index }">
              <!-- 标题来自第三方上游，用文本插值渲染；不要改成 v-html -->
              <div v-if="news[index]" class="news">
                <a
                  :href="news[index].url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="news__title"
                >
                  {{ news[index].title }}
                </a>
                <div class="news__meta muted">
                  <span>{{ news[index].source }}</span>
                  <span>{{ formatEpochSeconds(news[index].ctime, false) }}</span>
                </div>
              </div>
            </template>
            <template #loading>
              <el-skeleton :rows="3" animated />
            </template>
            <template #empty>
              <el-empty :description="errors.news ?? '资讯源暂时不可用'" :image-size="72" />
            </template>
          </SectionCarousel>
        </div>
      </section>
    </div>

    <!-- 今日涨幅榜 -->
    <section class="card" style="margin-top: 16px">
      <div class="card__header">
        <span class="card__title">今日涨幅榜</span>
        <!-- 失败时不显示「共 0 只」——那读起来像「今天没有上涨的股票」，
             而真实原因往往是上游不可用。两件事必须说清楚。 -->
        <span v-if="errors.gainers" class="muted" style="font-size: 12px">
          暂不可用
        </span>
        <span v-else class="muted" style="font-size: 12px">
          共 {{ gainersCount }} 只 · 点击卡片查看详情
        </span>
      </div>
      <div class="card__body">
        <SectionCarousel
          :loading="loading"
          :count="gainersCount"
          :interval="4000"
          height="92px"
        >
          <template #default="{ index, no }">
            <div
              v-if="gainers[index]"
              class="mover"
              @click="openStock(gainers[index].code)"
            >
              <div class="mover__rank num">{{ no }}</div>
              <div class="mover__main">
                <div class="mover__name">
                  <span>{{ gainers[index].name }}</span>
                  <span class="mono muted">{{ gainers[index].code }}</span>
                </div>
                <div class="mover__amount muted">
                  成交额 {{ formatAmountYi(gainers[index].amount) }}
                </div>
              </div>
              <div class="mover__right">
                <ChangeText :value="gainers[index].price" :show-plus="false" />
                <ChangeText :value="gainers[index].changePct" suffix="%" />
              </div>
            </div>
          </template>
          <template #loading>
            <el-skeleton :rows="3" animated />
          </template>
          <template #empty>
            <el-empty :description="errors.gainers ?? '榜单数据暂不可用'" :image-size="72" />
          </template>
        </SectionCarousel>
      </div>
    </section>
  </div>
</template>

<style scoped>
.more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.index-card {
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  cursor: pointer;
}

.index-card__name {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.index-card__value {
  font-size: 22px;
  font-weight: 600;
  margin: 2px 0;
}

.index-card__change {
  display: flex;
  gap: 12px;
  font-size: 13px;
}

/**
 * 精选关注网格。
 *
 * 2 列 × 3 行：这一块与「最新资讯」并排各占一半宽度，单列会让每格过宽、
 * 数字与名称拉得很远，3 列又会让「名称 + 代码」挤到换行。
 * 定高行（`minmax(0, 1fr)` 之外的固定值）不采用，是因为格子里
 * 标签文案长度不一，让内容撑开高度反而更整齐。
 */
.picks-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.pick {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  min-width: 0;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.pick:hover {
  background: #fafbfc;
  border-color: var(--color-up);
}

.pick__top {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.pick__name {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pick__code {
  font-size: 11px;
  flex: 0 0 auto;
}

.pick__price {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
}

.pick__bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: 13px;
  min-width: 0;
}

/** 标签可能较长（「技术面出现买点信号」），压缩而不是换行撑高卡片。 */
.pick__bottom :deep(.el-tag) {
  max-width: 52%;
  overflow: hidden;
}

.pick__bottom :deep(.el-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/** 头部右侧的「每 10 秒更新」提示：小圆点在换批瞬间点亮。 */
.picks__hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
}

.picks__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c9cdd4;
  transition: background 0.2s ease, box-shadow 0.2s ease;
}

.picks__hint.is-live .picks__dot {
  background: var(--color-up);
  box-shadow: 0 0 0 3px rgba(217, 43, 43, 0.14);
}

.news {
  padding: 6px 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.news__title {
  display: -webkit-box;
  line-clamp: 2;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.45;
  font-size: 14px;
}

.news__title:hover {
  color: var(--color-up);
}

.news__meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  margin-top: 4px;
}

/** 涨幅榜单张卡片：名次 + 名称代码 + 价与涨跌幅 */
.mover {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  height: 100%;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.mover:hover {
  background: #fafbfc;
  border-color: var(--color-up);
}

.mover__rank {
  flex: 0 0 30px;
  height: 30px;
  line-height: 30px;
  text-align: center;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--color-up);
  opacity: 0.85;
}

.mover__main {
  flex: 1;
  min-width: 0;
}

.mover__name {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.mover__name span:first-child {
  font-size: 15px;
  font-weight: 500;
}

.mover__amount {
  font-size: 12px;
  margin-top: 2px;
}

.mover__right {
  display: flex;
  align-items: baseline;
  gap: 16px;
  font-size: 16px;
  font-weight: 600;
}
</style>
