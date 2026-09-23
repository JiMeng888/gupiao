<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
 * 四个区块都以轮播形态呈现（每张一条记录，自动切换）。
 *
 * 两点保持不变，因为它们解决的是「数据到不了」而不是「怎么展示」的问题：
 *
 * 1. **四块并行加载**：用 Promise.allSettled 而不是 await 串行。
 *    任何一块失败都不应该让另外三块也跟着空着（资讯源尤其不稳定）。
 * 2. **列表条数一次性取满**：轮播一次只显示一张，但请求仍按
 *    「候选池」的尺寸取（指数 5 条 / 精选 6 条 / 资讯 8 条 / 涨幅榜 10 条），
 *    否则轮播没有可切换的内容，会退化成静态卡片。
 */
const router = useRouter()
const source = useMarketSourceStore()

const indices = ref<IndexSnapshot[]>([])
const picks = ref<CarouselItem[]>([])
const news = ref<NewsItem[]>([])
const gainers = ref<Mover[]>([])
const loading = ref(true)

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
    quoteApi.carousel(6),
    newsApi.list('all', 8),
    quoteApi.movers('changePct', 'desc', 10),
  ])
  if (idx.status === 'fulfilled') indices.value = idx.value
  if (car.status === 'fulfilled') picks.value = car.value
  if (nw.status === 'fulfilled') news.value = nw.value
  if (mv.status === 'fulfilled') gainers.value = mv.value
  loading.value = false
  // 首页有大量行情数字，数据源状态必须同步刷新一次
  void source.refresh()
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
            <el-empty description="暂无指数数据" :image-size="72" />
          </template>
        </SectionCarousel>
      </div>
    </section>

    <div class="grid grid--2" style="margin-top: 16px">
      <!-- 精选关注 -->
      <section class="card">
        <div class="card__header">
          <span class="card__title">精选关注</span>
          <span class="muted" style="font-size: 12px">同一自然日内结果稳定</span>
        </div>
        <div class="card__body">
          <SectionCarousel
            :loading="loading"
            :count="picks.length"
            :interval="4000"
            height="96px"
          >
            <template #default="{ index }">
              <!-- 用 div 而不是 li：轮播项在 DOM 上不再是列表子项，
                   继续用 li 会得到「li 的父亲不是 ul」的无效结构 -->
              <div
                v-if="picks[index]"
                class="pick"
                @click="openStock(picks[index].code)"
              >
                <div class="pick__main">
                  <span class="pick__name">{{ picks[index].name }}</span>
                  <span class="mono muted">{{ picks[index].code }}</span>
                </div>
                <div class="pick__right">
                  <ChangeText :value="picks[index].price" :show-plus="false" />
                  <ChangeText :value="picks[index].changePct" suffix="%" />
                </div>
                <el-tag size="small" type="info" effect="plain">
                  {{ picks[index].highlight }}
                </el-tag>
              </div>
            </template>
            <template #loading>
              <el-skeleton :rows="3" animated />
            </template>
            <template #empty>
              <el-empty description="暂无精选数据" :image-size="72" />
            </template>
          </SectionCarousel>
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
              <el-empty description="资讯源暂时不可用" :image-size="72" />
            </template>
          </SectionCarousel>
        </div>
      </section>
    </div>

    <!-- 今日涨幅榜 -->
    <section class="card" style="margin-top: 16px">
      <div class="card__header">
        <span class="card__title">今日涨幅榜</span>
        <span class="muted" style="font-size: 12px">
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
            <el-empty description="榜单数据暂不可用" :image-size="72" />
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

.pick {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 22px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  height: 100%;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.pick:hover {
  background: #fafbfc;
  border-color: var(--color-up);
}

.pick__main {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.pick__name {
  font-size: 15px;
  font-weight: 500;
}

.pick__right {
  display: flex;
  gap: 12px;
  font-size: 14px;
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
