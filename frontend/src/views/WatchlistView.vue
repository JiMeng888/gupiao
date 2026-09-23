<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'
import ChangeText from '@/components/ChangeText.vue'
import { watchlistApi } from '@/api/endpoints'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
import type { WatchlistGroup, WatchlistItem } from '@/api/types'
import { formatEpochSeconds } from '@/api/types'
import { formatNumber } from '@/utils/format'

/**
 * 自选股。
 *
 * 行情有两路来源，优先级如下：
 *   1. WebSocket 推送（每 3 秒一帧）——最新
 *   2. 列表接口返回的快照——首次加载时的兜底
 *
 * 这样处理是因为「先 REST 拿快照再等第一帧推送」会有几秒空窗，
 * 表格里出现几秒的 `--` 会被误认为数据缺失。
 */
const router = useRouter()
const socket = useQuoteSocketStore()

const groups = ref<WatchlistGroup[]>([])
const activeGroup = ref<string>('')
const items = ref<WatchlistItem[]>([])
const loading = ref(false)

const newGroupName = ref('')
const creating = ref(false)

/** 每只标的当前展示价：推送优先，回落列表快照 */
function priceOf(item: WatchlistItem): number | null {
  return socket.quotes[item.code]?.price ?? item.price
}

function changePctOf(item: WatchlistItem): number | null {
  return socket.quotes[item.code]?.changePct ?? item.changePct
}

function changeOf(item: WatchlistItem): number | null {
  return socket.quotes[item.code]?.change ?? item.change
}

const totalCount = computed(() => groups.value.reduce((sum, g) => sum + g.itemCount, 0))

/** 订阅当前列表里全部标的的实时行情，返回释放函数。 */
let release: (() => void) | null = null

function resubscribe(): void {
  release?.()
  release = null
  const codes = items.value.map((i) => i.code)
  if (codes.length) release = socket.acquire(codes)
}

onMounted(async () => {
  await loadGroups()
  await loadItems()
})

watch(activeGroup, () => void loadItems())

async function loadGroups(): Promise<void> {
  try {
    groups.value = await watchlistApi.groups()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分组加载失败')
  }
}

async function loadItems(): Promise<void> {
  loading.value = true
  try {
    items.value = await watchlistApi.items(activeGroup.value || undefined)
    resubscribe()
  } catch (e) {
    items.value = []
    ElMessage.error(e instanceof Error ? e.message : '自选列表加载失败')
  } finally {
    loading.value = false
  }
}

async function createGroup(): Promise<void> {
  const name = newGroupName.value.trim()
  if (!name) {
    ElMessage.warning('请输入分组名称')
    return
  }
  creating.value = true
  try {
    await watchlistApi.createGroup(name)
    newGroupName.value = ''
    ElMessage.success('分组已创建')
    await loadGroups()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

async function removeItem(item: WatchlistItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认将 ${item.name}（${item.code}）移出自选？`, '提示', {
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  try {
    await watchlistApi.removeItem(item.id)
    socket.dropQuote(item.code)
    ElMessage.success('已移出自选')
    await Promise.all([loadItems(), loadGroups()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '移除失败')
  }
}

async function removeGroup(): Promise<void> {
  if (!activeGroup.value) return
  const group = groups.value.find((g) => g.id === activeGroup.value)
  if (!group) return
  try {
    await ElMessageBox.confirm(
      `删除分组「${group.name}」会同时删除组内全部 ${group.itemCount} 只自选，确认继续？`,
      '危险操作',
      { type: 'warning', confirmButtonText: '确认删除' },
    )
  } catch {
    return
  }
  try {
    await watchlistApi.deleteGroup(activeGroup.value)
    activeGroup.value = ''
    ElMessage.success('分组已删除')
    await Promise.all([loadGroups(), loadItems()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}
</script>

<template>
  <div class="page">
    <section class="card">
      <div class="card__header">
        <span class="card__title">
          我的自选
          <span class="muted" style="font-size: 12px; font-weight: 400">
            共 {{ totalCount }} 只
          </span>
        </span>
        <div class="actions">
          <el-input
            v-model="newGroupName"
            placeholder="新建分组名称"
            size="small"
            style="width: 160px"
            @keyup.enter="createGroup"
          />
          <el-button size="small" :icon="Plus" :loading="creating" @click="createGroup">
            新建分组
          </el-button>
          <el-button size="small" :icon="Refresh" @click="loadItems">刷新</el-button>
        </div>
      </div>

      <div class="card__body">
        <el-tabs v-model="activeGroup" class="groups">
          <el-tab-pane label="全部" name="" />
          <el-tab-pane
            v-for="g in groups"
            :key="g.id"
            :label="`${g.name}（${g.itemCount}）`"
            :name="g.id"
          />
        </el-tabs>

        <div v-if="activeGroup" class="group-ops">
          <el-button link type="danger" :icon="Delete" @click="removeGroup">
            删除当前分组
          </el-button>
        </div>

        <el-table
          v-loading="loading"
          :data="items"
          size="small"
          empty-text="暂无自选，可在行情页搜索后加入"
        >
          <el-table-column prop="code" label="代码" width="110">
            <template #default="{ row }">
              <span class="mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="140">
            <template #default="{ row }">
              <el-link type="primary" :underline="false" @click="router.push(`/stock/${row.code}`)">
                {{ row.name }}
              </el-link>
            </template>
          </el-table-column>
          <el-table-column label="最新价" align="right" width="120">
            <template #default="{ row }">
              <ChangeText :value="priceOf(row)" :show-plus="false" />
            </template>
          </el-table-column>
          <el-table-column label="涨跌" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="changeOf(row)" />
            </template>
          </el-table-column>
          <el-table-column label="涨跌幅" align="right" width="110">
            <template #default="{ row }">
              <ChangeText :value="changePctOf(row)" suffix="%" />
            </template>
          </el-table-column>
          <el-table-column label="加入时间" align="right" width="150">
            <template #default="{ row }">
              <span class="mono muted">{{ formatEpochSeconds(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="router.push(`/stock/${row.code}`)">
                详情
              </el-button>
              <el-button link type="danger" @click="removeItem(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <p class="hint muted">
          行情列优先取 WebSocket 实时推送；未收到推送时回落到列表接口的快照值
          （最新价 {{ formatNumber(items[0]?.price) }} 这类值即属后者）。
          <span v-if="!socket.connected">当前实时连接未建立，表中数值为快照值。</span>
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.groups :deep(.el-tabs__header) {
  margin-bottom: 8px;
}

.group-ops {
  margin-bottom: 8px;
}

.hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
