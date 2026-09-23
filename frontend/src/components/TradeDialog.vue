<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { holdingApi, quoteApi } from '@/api/endpoints'
import type { HoldingView, StockBrief } from '@/api/types'

/**
 * 买入 / 卖出记账弹窗。
 *
 * 三个细节值得说明：
 *
 * 1. <b>买入时股票可选</b>。买入模式下「股票」是可搜索的下拉框
 *    （输入代码/名称关键字实时联想，点选带入），支持第一次买入某只股票；
 *    卖出模式下是只读文本——卖出只能针对已持有的标的。
 *
 * 2. <b>买入日期可选</b>。买入模式提供日期选择器（默认今天），
 *    支持补录历史买点；卖出不提供（卖出记当天即可）。
 *
 * 3. <b>卖出时先查可卖股数</b>。后端在超量卖出时返回 400
 *    `INSUFFICIENT_SHARES`，让用户提交后再报错体验很差。
 *    这里打开弹窗就把该标的的持仓查出来，把上限和「全部卖出」按钮摆上。
 */
const props = defineProps<{
  modelValue: boolean
  mode: 'buy' | 'sell'
  code: string
  name?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  done: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const title = computed(() => (props.mode === 'buy' ? '记一笔买入' : '记一笔卖出'))

const form = ref({ shares: 1, price: 0 })
const submitting = ref(false)
const holding = ref<HoldingView | null>(null)
const lastResult = ref<HoldingView | null>(null)

// 买入日期：默认当天，可改历史（精确到日期）
const buyDate = ref('')

// 股票搜索联想状态
const selectedCode = ref('')
const selectedName = ref('')
const searchOptions = ref<StockBrief[]>([])
const searchLoading = ref(false)

const sellableShares = computed(() => holding.value?.shares ?? 0)
const canSellAll = computed(() => props.mode === 'sell' && sellableShares.value > 0)

/** 预估金额，让用户下单前能看到总价 */
const estimatedAmount = computed(() => form.value.shares * form.value.price)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    lastResult.value = null
    form.value = { shares: 1, price: 0 }
    buyDate.value = todayStr()
    selectedCode.value = props.code
    selectedName.value = props.name ?? ''
    // 从持仓/行情页带入的股票，先塞一条选项让下拉框能正确显示「名称（代码）」，
    // 用户仍可输入关键字重新搜索
    searchOptions.value =
      props.code && props.name
        ? [{ code: props.code, name: props.name, exchange: 'SH' }]
        : []
    await prefill()
  },
)

function todayStr(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function prefill(): Promise<void> {
  if (!props.code) return
  // 并行取最新价与当前持仓，任何一路失败都不阻塞用户手动填写
  const [q, holdings] = await Promise.allSettled([
    quoteApi.quote(props.code),
    holdingApi.list(),
  ])

  if (q.status === 'fulfilled' && q.value.price > 0) {
    form.value.price = Number(q.value.price.toFixed(2))
  }
  if (holdings.status === 'fulfilled') {
    holding.value = holdings.value.find((h) => h.code === props.code) ?? null
  }
}

/** 远程搜索：输入关键字实时联想股票 */
let searchSeq = 0
async function searchStocks(keyword: string): Promise<void> {
  if (!keyword || keyword.trim() === '') {
    searchOptions.value = []
    return
  }
  const seq = ++searchSeq
  searchLoading.value = true
  try {
    const results = await quoteApi.search(keyword.trim())
    // 只采纳最新一次请求的结果，避免竞态下旧结果覆盖新结果
    if (seq === searchSeq) {
      searchOptions.value = results
    }
  } catch {
    if (seq === searchSeq) searchOptions.value = []
  } finally {
    if (seq === searchSeq) searchLoading.value = false
  }
}

/** 选中联想结果后，带入名称，并预填最新价 */
async function onSelectChange(code: string): Promise<void> {
  if (!code) {
    selectedName.value = ''
    return
  }
  const matched = searchOptions.value.find((s) => s.code === code)
  selectedName.value = matched?.name ?? selectedName.value
  try {
    const q = await quoteApi.quote(code)
    if (q.price > 0) form.value.price = Number(q.price.toFixed(2))
  } catch {
    // 取价失败不阻塞，用户手动填价即可
  }
}

function fillAll(): void {
  if (sellableShares.value > 0) form.value.shares = sellableShares.value
}

async function submit(): Promise<void> {
  if (form.value.shares <= 0) {
    ElMessage.warning('股数必须大于 0')
    return
  }
  if (form.value.price <= 0) {
    ElMessage.warning('价格必须大于 0')
    return
  }
  // 买入模式必须有股票；卖出模式用 props.code
  const targetCode = props.mode === 'buy' ? selectedCode.value : props.code
  if (!targetCode) {
    ElMessage.warning('请先选择股票')
    return
  }

  submitting.value = true
  try {
    const result =
      props.mode === 'buy'
        ? await holdingApi.buy(targetCode, form.value.shares, form.value.price, selectedName.value, buyDate.value)
        : await holdingApi.sell(targetCode, form.value.shares, form.value.price)

    lastResult.value = result
    ElMessage.success(props.mode === 'buy' ? '已记入持仓' : '已记入卖出')
    emit('done')

    if (props.mode === 'buy') {
      // 买入没有额外的「成交结果」可看，直接关闭
      visible.value = false
    } else {
      // 卖出保留弹窗展示已实现盈亏，并刷新可卖股数
      holding.value = await holdingApi
        .list()
        .then((list) => list.find((h) => h.code === targetCode) ?? null)
        .catch(() => holding.value)
      form.value.shares = 0
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="480px" destroy-on-close>
    <el-form label-width="88px" label-position="right">
      <el-form-item label="股票">
        <!-- 买入：可搜索选择股票（支持首次买入）；卖出：只读展示已持有的标的 -->
        <el-select
          v-if="mode === 'buy'"
          v-model="selectedCode"
          filterable
          remote
          clearable
          :remote-method="searchStocks"
          :loading="searchLoading"
          placeholder="输入代码或名称搜索"
          style="width: 100%"
          @change="onSelectChange"
        >
          <el-option
            v-for="item in searchOptions"
            :key="item.code"
            :label="`${item.name}（${item.code}）`"
            :value="item.code"
          />
        </el-select>
        <span v-else>{{ name || '--' }} <span class="mono muted">{{ code }}</span></span>
      </el-form-item>

      <el-form-item v-if="mode === 'buy'" label="买入日期">
        <el-date-picker
          v-model="buyDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          placeholder="选择买入日期"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item v-if="mode === 'sell'" label="可卖股数">
        <span class="num">{{ sellableShares }}</span>
        <el-button v-if="canSellAll" link type="primary" @click="fillAll">全部卖出</el-button>
      </el-form-item>

      <el-form-item label="股数">
        <el-input-number v-model="form.shares" :min="1" :step="100" style="width: 100%" />
      </el-form-item>

      <el-form-item label="单价">
        <el-input-number
          v-model="form.price"
          :min="0.01"
          :precision="2"
          :step="0.01"
          style="width: 100%"
        />
      </el-form-item>

      <el-form-item label="预估金额">
        <span class="num">{{ estimatedAmount.toFixed(2) }} 元</span>
        <span class="muted" style="margin-left: 8px; font-size: 12px">
          （按填写的单价计算，非实际成交额）
        </span>
      </el-form-item>
    </el-form>

    <!-- 卖出结果：把已实现盈亏明确展示出来 -->
    <el-alert v-if="lastResult && mode === 'sell'" type="success" :closable="false" show-icon>
      <template #title>本次卖出结果</template>
      <div class="result">
        <div>卖出股数：<span class="num">{{ lastResult.soldShares ?? '--' }}</span></div>
        <div>
          成交价：<span class="num">{{ lastResult.currentPrice ?? '--' }}</span>
          <span class="muted" style="font-size: 12px">
            （卖出响应里的 currentPrice 是本次成交价，不是市价）
          </span>
        </div>
        <div>
          已实现盈亏：
          <span
            class="num"
            :class="{
              'text-up': (lastResult.realizedPnl ?? 0) > 0,
              'text-down': (lastResult.realizedPnl ?? 0) < 0,
            }"
          >
            {{ lastResult.realizedPnl === null ? '--' : lastResult.realizedPnl.toFixed(2) }}
          </span>
        </div>
        <div>剩余股数：<span class="num">{{ lastResult.shares }}</span></div>
      </div>
    </el-alert>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ mode === 'buy' ? '确认买入' : '确认卖出' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.result {
  display: grid;
  gap: 4px;
  font-size: 13px;
  margin-top: 4px;
}
</style>
