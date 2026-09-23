<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, User, SwitchButton, Setting, Star, Wallet } from '@element-plus/icons-vue'
import SourceBadge from '@/components/SourceBadge.vue'
import { quoteApi } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { useMarketSourceStore } from '@/stores/marketSource'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
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

const activeMenu = computed(() => route.path)

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
        :type="source.degraded ? 'error' : 'warning'"
        :title="source.degraded ? '当前行情包含演示数据' : '行情数据源未就绪'"
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

    <footer class="layout__footer">
      <span class="muted">
        数据来源：行情为公开接口，AI 研判为辅助参考。
        <strong>本应用不构成任何投资建议。</strong>
      </span>
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
  background: var(--color-surface);
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
  background: #c8cdd4;
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
</style>
