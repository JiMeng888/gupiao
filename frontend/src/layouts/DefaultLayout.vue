<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, User, SwitchButton, Setting, Star, Wallet, Sunny, Moon } from '@element-plus/icons-vue'
import SourceBadge from '@/components/SourceBadge.vue'
import { quoteApi } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { useMarketSourceStore } from '@/stores/marketSource'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
import { useThemeStore } from '@/stores/theme'
import type { StockBrief } from '@/api/types'

/**
 * 主布局：顶栏 + 内容区 + 页脚。
 *
 * 顶栏承载三类全局信息：导航、全局搜索、以及**数据源状态徽标**。
 * 徽标放在全局位置是有意为之：用户可能在任意页面看到行情数字，
 * 而「这些数字是不是真实的」是每个页面都成立的前提。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const source = useMarketSourceStore()
const socket = useQuoteSocketStore()
const theme = useThemeStore()

const activeMenu = computed(() => route.path)

/** 主题按钮图标与提示：跟随系统时用太阳图标，避免表达出错误的当前状态。 */
const themeIcon = computed(() => (theme.isDark ? Moon : Sunny))
const themeTooltip = computed(() => {
  if (theme.choice === null) return '当前跟随系统主题，点击切换为亮色'
  return theme.isDark ? '当前为暗色，点击切换为跟随系统' : '当前为亮色，点击切换为暗色'
})

/** 数据源状态复检间隔。低频是有意为之：它只需要在故障后「一个周期内」被发现。 */
const SOURCE_REFRESH_MS = 30_000

let sourceTimer: number | undefined

const searching = ref(false)
const options = ref<StockBrief[]>([])
/** 搜索框的双向绑定值。选中候选项后立即跳转，所以成功后要清空 */
const searchValue = ref<string>('')

/** 远程搜索：输入代码或名称都走后端的全 A 股检索接口。 */
async function remoteSearch(keyword: string): Promise<void> {
  const kw = keyword.trim()
  if (!kw) {
    options.value = []
    return
  }
  searching.value = true
  try {
    options.value = await quoteApi.search(kw)
  } catch {
    // 搜索失败不打扰用户：清空候选即可，用户继续输入会重试
    options.value = []
  } finally {
    searching.value = false
  }
}

function goStock(code: string): void {
  options.value = []
  searchValue.value = ''
  router.push({ name: 'stock-detail', params: { code } })
}

async function handleLogout(): Promise<void> {
  await auth.logout()
  ElMessage.success('已退出登录')
  router.push({ name: 'home' })
}

onMounted(() => {
  // 数据源状态是全局信息，进应用就拉一次
  void source.refresh()
  // 建立实时行情长连接；具体订阅由各页面按需 acquire
  socket.connect()

  // 周期性复检数据源状态。
  //
  // 只在挂载时拉一次是不够的：运行中上游才被风控断连、或后端中途挂掉时，
  // 横幅会一直停留在「一切正常」的状态——而它恰恰是页面唯一一处
  // 告诉用户「这些数字不可信」的地方。补一个低频轮询（30 秒，
  // 相对 3 秒的行情推送几乎无成本），让警示能在故障发生后一个周期内出现。
  sourceTimer = window.setInterval(() => {
    void source.refresh()
  }, SOURCE_REFRESH_MS)
})

onBeforeUnmount(() => {
  if (sourceTimer !== undefined) {
    window.clearInterval(sourceTimer)
    sourceTimer = undefined
  }
})
</script>

<template>
  <div class="layout">
    <header class="layout__header">
      <div class="layout__header-inner">
        <router-link to="/" class="brand">
          <span class="brand__mark">股</span>
          <span class="brand__text">股票分析师</span>
        </router-link>

        <el-menu
          :default-active="activeMenu"
          mode="horizontal"
          class="nav"
          router
          :ellipsis="false"
        >
          <el-menu-item index="/">首页</el-menu-item>
          <el-menu-item index="/market">行情</el-menu-item>
          <el-menu-item index="/analysis">AI 研判</el-menu-item>
          <el-menu-item index="/watchlist">自选股</el-menu-item>
          <el-menu-item index="/holdings">持仓</el-menu-item>
          <el-menu-item index="/news">资讯</el-menu-item>
        </el-menu>

        <div class="layout__actions">
          <el-select
            v-model="searchValue"
            class="search"
            filterable
            remote
            clearable
            reserve-keyword
            placeholder="搜索股票代码或名称"
            :remote-method="remoteSearch"
            :loading="searching"
            size="default"
            @change="goStock"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
            <el-option
              v-for="item in options"
              :key="item.code"
              :label="`${item.name} ${item.code}`"
              :value="item.code"
            />
          </el-select>

          <el-tooltip
            :content="socket.connected ? '实时行情已连接' : '实时行情未连接（每 3 秒轮询的替代方案不可用）'"
            placement="bottom"
          >
            <span class="ws-dot" :class="{ 'ws-dot--on': socket.connected }" />
          </el-tooltip>

          <!--
            主题切换（M5）。三态：跟随系统 / 亮 / 暗。
            放在顶栏而不是设置页：看盘时环境光会变（白天/夜里），
            用户需要在不离开当前页面的前提下切换。
          -->
          <el-tooltip :content="themeTooltip" placement="bottom">
            <el-button text circle :icon="themeIcon" @click="theme.cycle()" />
          </el-tooltip>

          <SourceBadge />

          <el-dropdown v-if="auth.isLoggedIn" trigger="click">
            <span class="user">
              <el-icon><User /></el-icon>
              {{ auth.displayName }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/watchlist')">
                  <el-icon><Star /></el-icon> 我的自选
                </el-dropdown-item>
                <el-dropdown-item @click="router.push('/holdings')">
                  <el-icon><Wallet /></el-icon> 我的持仓
                </el-dropdown-item>
                <el-dropdown-item @click="router.push('/settings')">
                  <el-icon><Setting /></el-icon> 设置
                </el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <el-button v-else type="primary" plain @click="router.push('/login')">登录</el-button>
        </div>
      </div>
    </header>

    <!-- 数据源降级提示：放在内容区之上而不是塞进某个页面，
         因为任何页面的行情数字都受它影响 -->
    <div v-if="source.warning" class="layout__warning">
      <el-alert
        :type="source.warningType"
        :title="source.warningTitle ?? '行情数据源异常'"
        :description="source.warning"
        show-icon
        :closable="false"
      />
    </div>

    <main class="layout__main">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <!-- 页脚免责声明。
         这里以前写的是「行情为公开接口」——「公开」二字很容易被读成
         「已获数据方授权」，而实际上游是各家财经网站给自家网页用的内部接口，
         既没有授权，也没有可用性承诺。既然降级时会退回演示数据，
         页脚就必须把这件事说明白，否则用户无法判断屏幕上的数字能不能信。 -->
    <footer class="layout__footer">
      <p class="layout__footer-line muted">
        数据来源：行情与资讯取自第三方财经网站的内部数据接口（东方财富 / 腾讯 / 新浪），
        <strong>未获数据方授权，亦无可用性承诺</strong>，
        可能延迟、缺失、字段变更，或在降级时切换为内置演示数据
        （降级时页面顶部有醒目提示，请以该提示为准）。
      </p>
      <p class="layout__footer-line muted">
        AI 研判由大模型生成，仅作辅助参考，可能出错。
        <strong>本应用不构成任何投资建议，据此操作风险自担。</strong>
      </p>
    </footer>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.layout__header {
  position: sticky;
  top: 0;
  z-index: 100;
  /*
    半透明 + 模糊而不是实色：
    深色下实色顶栏会和内容区连成一片，滚动时分不清哪是页头；
    让内容从底下透出一点，边界由模糊自然产生，比加一条更粗的线干净。
    取值在亮/暗两套变量里各自定义（--color-surface-translucent）。
  */
  background: var(--color-surface-translucent);
  backdrop-filter: saturate(180%) blur(12px);
  -webkit-backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--color-border);
}

.layout__header-inner {
  display: flex;
  align-items: center;
  gap: 16px;
  height: var(--header-height);
  max-width: var(--layout-max-width);
  margin: 0 auto;
  padding: 0 16px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  background: var(--color-up);
  color: #fff;
  font-size: 14px;
}

.nav {
  flex: 1;
  border-bottom: none !important;
}

.layout__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search {
  width: 240px;
}

.ws-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-border-strong);
  flex: none;
}

.ws-dot--on {
  background: var(--color-down);
  box-shadow: 0 0 0 3px rgba(10, 158, 92, 0.15);
}

.user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--color-text-regular);
  outline: none;
}

.layout__warning {
  max-width: var(--layout-max-width);
  margin: 12px auto 0;
  padding: 0 16px;
  width: 100%;
}

.layout__main {
  flex: 1;
}

.layout__footer {
  max-width: var(--layout-max-width);
  margin: 0 auto;
  padding: 20px 16px 32px;
  width: 100%;
  font-size: 12px;
}

/* 页脚现在有两段：段间留 6px，最后一段不留底边距，
   否则会在 padding 之外多出一截空白 */
.layout__footer-line {
  margin: 0 0 6px;
  line-height: 1.7;
}

.layout__footer-line:last-child {
  margin-bottom: 0;
}
</style>
