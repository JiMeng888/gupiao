<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Star } from '@element-plus/icons-vue'
import ChangeText from '@/components/ChangeText.vue'
import TradeDialog from '@/components/TradeDialog.vue'
import { quoteApi, watchlistApi } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { useMarketSourceStore } from '@/stores/marketSource'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
import type { IndexSnapshot, Mover, StockBrief } from '@/api/types'
import { formatEpochSeconds } from '@/api/types'

/**
 * 行情页。
 *
 * 四块内容：大盘指数、市场榜单、全市场检索、实时推送监视。
 *
 * 「实时推送监视」不是为了炫技：WebSocket 出问题时最难判断的是
 * 「服务端没推」还是「前端没连上」。把连接状态、服务端确认的订阅集合、
 * 最近一帧的时间都摆出来，排查时一眼就能定位是哪一侧的问题。
 */
const router = useRouter()
const auth = useAuthStore()
const source = useMarketSourceStore()
const socket = useQuoteSocketStore()

const indices = ref<IndexSnapshot[]>([])
const keyword = ref('')
const results = ref<StockBrief[]>([])
const searching = ref(false)
const searched = ref(false)

// 记一笔买入弹窗状态
const tradeVisible = ref(false)
const tradeTarget = ref<{ code: string; name: string } | null>(null)

/** 页面打开就订阅指数成分里的几只，让监视面板有真实数据可看 */
const WATCH_ON_ENTER = ['600519', '000001', '300750']

const watched = computed(() => WATCH_ON_ENTER.map((code) => socket.quotes[code]).filter(Boolean))

// ---------------- 市场榜单 ----------------

/** 三个榜共用一张表：切换只是换排序参数，不换组件。 */
type BoardKey = 'gain' | 'loss' | 'amount'

const BOARD_PARAMS: Record<BoardKey, { metric: 'changePct' | 'amount'; order: 'asc' | 'desc' }> = {
  gain: { metric: 'changePct', order: 'desc' },
  loss: { metric: 'changePct', order: 'asc' },
  amount: { metric: 'amount', order: 'desc' },
}

const board = ref<BoardKey>('gain')
const movers = ref<Mover[]>([])
const moversLoading = ref(false)

async function loadMovers(): Promise<void> {
  moversLoading.value = true
  try {
    const params = BOARD_PARAMS[board.value]
    movers.value = await quoteApi.movers(params.metric, params.order, 10)
  } catch {
    // 榜单拿不到（上游抖动）就让表格显示空态，不打断页面其余板块
    movers.value = []
  } finally {
    moversLoading.value = false
  }
}

/** 成交额以元为单位，展示换算成「亿」；缺数据时显示 -- 而不是 0.00 亿。 */
function formatAmountYi(amount: number): string {
  if (!amount) return '--'
  return (amount / 1e8).toFixed(2) + ' 亿'
}

onMounted(async () => {
  void source.refresh()
  void loadMovers()
  try {
    indices.value = await quoteApi.indices()
  } catch {
    indices.value = []
  }
  // acquire 返回释放函数；本页常驻，不需要在卸载时主动退订，
  // 连接断开时 store 会自动恢复订阅集合
  socket.acquire(WATCH_ON_ENTER)
})

async function doSearch(): Promise<void> {
  const kw = keyword.value.trim()
  if (!kw) {
    ElMessage.warning('请输入股票代码或名称')
    return
  }
  searching.value = true
  try {
    results.value = await quoteApi.search(kw)
    searched.value = true
  } catch (e) {
    results.value = []
    ElMessage.error(e instanceof Error ? e.message : '搜索失败')
  } finally {
    searching.value = false
  }
}

function openStock(code: string): void {
  router.push({ name: 'stock-detail', params: { code } })
}

async function addToWatchlist(code: string): Promise<void> {
  if (!auth.isLoggedIn) {
    ElMessage.info('请先登录后再添加自选')
    router.push({ name: 'login', query: { redirect: '/market' } })
    return
  }
  try {
    await watchlistApi.addItem(code)
    ElMessage.success('已加入自选')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  }
}

/** 打开「记一笔买入」弹窗（需登录） */
function openBuy(code: string, name: string): void {
  if (!auth.isLoggedIn) {
    ElMessage.info('请先登录后再记一笔买入')
    router.push({ name: 'login', query: { redirect: '/market' } })
    return
  }
  tradeTarget.value = { code, name }
  tradeVisible.value = true
}
</script>

<template>
  <div class="page">
    <section class="card">
      <div class="card__header">
        <span class="card__title">大盘指数</span>
      </div>
      <div class="card__body">
        <el-table :data="indices" size="small" empty-text="暂无指数数据">
          <el-table-column prop="name" label="指数" min-width="140" />
          <el-table-column prop="code" label="代码" width="120">
            <template #default="{ row }">
              <span class="mono muted">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最新" align="right" width="120">
            <template #default="{ row }">
              <ChangeText :value="row.value" :show-plus="false" />
            </template>
          </el-table-column>
          <el-table-column label="涨跌" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="row.change" />
            </template>
          </el-table-column>
          <el-table-column label="涨跌幅" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="row.changePct" suffix="%" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section class="card">
      <div class="card__header">
        <span class="card__title">市场榜单</span>
        <el-radio-group v-model="board" size="small" @change="loadMovers">
          <el-radio-button value="gain">涨幅榜</el-radio-button>
          <el-radio-button value="loss">跌幅榜</el-radio-button>
          <el-radio-button value="amount">成交额榜</el-radio-button>
        </el-radio-group>
      </div>
      <div class="card__body">
        <el-table
          v-loading="moversLoading"
          :data="movers"
          size="small"
          empty-text="榜单数据暂不可用"
          :row-style="{ cursor: 'pointer' }"
          @row-click="(row: Mover) => openStock(row.code)"
        >
          <el-table-column type="index" label="#" width="52" align="center" />
          <el-table-column label="股票" min-width="160">
            <template #default="{ row }">
              <span>{{ row.name }}</span>
              <span class="mono muted" style="margin-left: 8px">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最新价" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="row.price" :show-plus="false" />
            </template>
          </el-table-column>
          <el-table-column label="涨跌幅" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="row.changePct" suffix="%" />
            </template>
          </el-table-column>
          <el-table-column label="涨跌额" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="row.change" />
            </template>
          </el-table-column>
          <el-table-column label="成交额" align="right" width="120">
            <template #default="{ row }">
              <span class="mono">{{ formatAmountYi(row.amount) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section class="card">
      <div class="card__header">
        <span class="card__title">全市场检索</span>
        <span class="muted" style="font-size: 12px">支持代码（600519）与名称（贵州茅台）</span>
      </div>
      <div class="card__body">
        <div class="search-bar">
          <el-input
            v-model="keyword"
            placeholder="输入股票代码或名称，回车搜索"
            clearable
            @keyup.enter="doSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button type="primary" :loading="searching" @click="doSearch">搜索</el-button>
        </div>

        <el-table
          v-if="results.length || searched"
          :data="results"
          size="small"
          style="margin-top: 12px"
          empty-text="没有匹配的股票"
        >
          <el-table-column prop="code" label="代码" width="110">
            <template #default="{ row }">
              <span class="mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="exchange" label="交易所" width="90" />
          <el-table-column label="操作" width="280" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openStock(row.code)">查看详情</el-button>
              <el-button link type="primary" @click="addToWatchlist(row.code)">
                <el-icon><Star /></el-icon> 加自选
              </el-button>
              <el-button link type="primary" @click="openBuy(row.code, row.name)">
                记一笔买入
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section class="card">
      <div class="card__header">
        <span class="card__title">实时推送监视</span>
        <el-tag :type="socket.connected ? 'success' : 'info'" size="small">
          {{ socket.connected ? '已连接 /ws/quote' : '未连接' }}
        </el-tag>
      </div>
      <div class="card__body">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="订阅标的数">
            {{ socket.codeCount }} / 50
          </el-descriptions-item>
          <el-descriptions-item label="服务端确认的集合">
            <span class="mono">{{ socket.subscribedCodes.join(', ') || '（空）' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="最近一帧时间">
            <span class="mono">{{ formatEpochSeconds(socket.lastFrameAt) }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="socket.lastError"
          type="warning"
          :title="socket.lastError"
          show-icon
          :closable="false"
          style="margin-top: 12px"
        />

        <el-table :data="watched" size="small" style="margin-top: 12px" empty-text="等待首帧推送…">
          <el-table-column prop="name" label="名称" min-width="130" />
          <el-table-column prop="code" label="代码" width="110">
            <template #default="{ row }">
              <span class="mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最新价" align="right" width="120">
            <template #default="{ row }">
              <ChangeText :value="row.price" :show-plus="false" />
            </template>
          </el-table-column>
          <el-table-column label="涨跌幅" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="row.changePct" suffix="%" />
            </template>
          </el-table-column>
          <el-table-column label="推送时间" align="right" width="150">
            <template #default="{ row }">
              <span class="mono muted">{{ formatEpochSeconds(row.ts) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <!-- 记一笔买入弹窗（从搜索结果带入股票） -->
    <TradeDialog
      v-model="tradeVisible"
      mode="buy"
      :code="tradeTarget?.code ?? ''"
      :name="tradeTarget?.name"
      @done="tradeVisible = false"
    />
  </div>
</template>

<style scoped>
.search-bar {
  display: flex;
  gap: 8px;
  max-width: 520px;
}
</style>
