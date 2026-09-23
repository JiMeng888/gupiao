<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import SourceBadge from '@/components/SourceBadge.vue'
import { authApi } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { useMarketSourceStore } from '@/stores/marketSource'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
import { formatEpochSeconds } from '@/api/types'

/**
 * 设置与诊断。
 *
 * 「数据源诊断」这一块是这个应用最该给用户看的东西：
 * 行情数据真正来自哪里、哪些操作在走降级源，全部如实列出。
 * 把它藏在设置里而不是完全不给，是权衡后的选择——
 * 普通用户看顶栏徽标就够了，想深究的人在这里能看到逐项明细。
 */
const router = useRouter()
const auth = useAuthStore()
const source = useMarketSourceStore()
const socket = useQuoteSocketStore()

const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdSubmitting = ref(false)

const servingEntries = computed(() =>
  Object.entries(source.status?.servingByAction ?? {}).map(([action, provider]) => ({
    action,
    provider,
    // 只有 mock（演示数据）才是真正的「降级」：备用真实源（如 tencent）
    // 接管时数据仍是真实行情，标红「演示数据」会误导用户
    degraded: provider === 'mock',
  })),
)

/** 人话解释每个 action 对应界面上哪一块 */
const ACTION_LABEL: Record<string, string> = {
  getQuote: '实时报价（个股报价、自选与持仓的现价）',
  getBars: 'K 线（个股详情页的蜡烛图）',
  getIndices: '大盘指数',
  search: '股票搜索',
}

onMounted(() => {
  void source.refresh()
})

async function changePassword(): Promise<void> {
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (pwdForm.newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  pwdSubmitting.value = true
  try {
    const res = await authApi.changePassword(pwdForm.oldPassword, pwdForm.newPassword)
    ElMessage.success(res.message || '密码已修改')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '修改失败')
  } finally {
    pwdSubmitting.value = false
  }
}

async function handleLogout(): Promise<void> {
  await auth.logout()
  ElMessage.success('已退出登录')
  router.push({ name: 'home' })
}
</script>

<template>
  <div class="page">
    <!-- 账号 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">账号</span>
      </div>
      <div class="card__body">
        <template v-if="auth.isLoggedIn">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="昵称">{{ auth.user?.nickname }}</el-descriptions-item>
            <el-descriptions-item label="手机号">{{ auth.user?.phone }}</el-descriptions-item>
            <el-descriptions-item label="用户 ID">
              <span class="mono">{{ auth.user?.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="登录方式">双令牌 JWT（Access 30 分钟 / Refresh 30 天）</el-descriptions-item>
          </el-descriptions>
          <div style="margin-top: 12px">
            <el-button type="danger" plain @click="handleLogout">退出登录</el-button>
          </div>
          <p class="muted note">
            退出登录会吊销<b>整个会话</b>：当前这一对 access / refresh 令牌立即失效，
            不是等它们自然过期。
          </p>
        </template>
        <el-empty v-else description="尚未登录">
          <el-button type="primary" @click="router.push('/login')">去登录</el-button>
        </el-empty>
      </div>
    </section>

    <!-- 修改密码 -->
    <section v-if="auth.isLoggedIn" class="card">
      <div class="card__header">
        <span class="card__title">修改密码</span>
      </div>
      <div class="card__body">
        <el-form label-width="120px" style="max-width: 460px">
          <el-form-item label="原密码">
            <el-input v-model="pwdForm.oldPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pwdForm.newPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pwdForm.confirmPassword" type="password" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="pwdSubmitting" @click="changePassword">
              提交
            </el-button>
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="改密不会踢掉其他设备的登录"
          description="已签发的 Access Token 在 30 分钟内仍然有效。要实现「改密即全端下线」，需要引入令牌版本号机制，当前版本未实现。"
        />
      </div>
    </section>

    <!-- 数据源诊断 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">行情数据源诊断</span>
        <div class="actions">
          <SourceBadge />
          <el-button size="small" :icon="Refresh" :loading="source.loading" @click="source.refresh">
            刷新
          </el-button>
        </div>
      </div>
      <div class="card__body">
        <el-alert
          v-if="source.warning"
          :type="source.degraded ? 'error' : 'warning'"
          :title="source.warning"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />

        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="配置的主数据源">
            <span class="mono">{{ source.status?.provider ?? '--' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="主数据源就绪">
            <el-tag :type="source.ready ? 'success' : 'warning'" size="small">
              {{ source.ready ? '已就绪' : '未就绪' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="当前是否降级">
            <el-tag :type="source.degraded ? 'danger' : 'success'" size="small">
              {{ source.degraded ? '是（部分数据为演示数据）' : '否' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-table
          :data="servingEntries"
          size="small"
          style="margin-top: 12px"
          empty-text="尚未发生任何行情调用，暂无数据"
        >
          <el-table-column label="操作" min-width="300">
            <template #default="{ row }">
              {{ ACTION_LABEL[row.action] ?? row.action }}
              <span class="mono muted">{{ row.action }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实际服务源" width="220">
            <template #default="{ row }">
              <el-tag :type="row.degraded ? 'danger' : row.provider === source.status?.provider ? 'success' : 'warning'" size="small" effect="plain">
                {{ row.provider }}{{ row.degraded ? '（演示数据）' : row.provider === source.status?.provider ? '' : '（备用真实源）' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <p class="muted note">
          降级是<b>逐次调用</b>发生的：同一时刻完全可能 K 线走真实源、实时报价走演示源，
          因此这里按操作分列，而不是给一个笼统的「当前数据源」。
        </p>
      </div>
    </section>

    <!-- 实时连接 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">实时行情连接</span>
        <el-tag :type="socket.connected ? 'success' : 'info'" size="small">
          {{ socket.connected ? '已连接' : '未连接' }}
        </el-tag>
      </div>
      <div class="card__body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="端点">
            <span class="mono">/ws/quote</span>
          </el-descriptions-item>
          <el-descriptions-item label="已订阅标的数">
            {{ socket.codeCount }} / 50
          </el-descriptions-item>
          <el-descriptions-item label="最近一帧">
            <span class="mono">{{ formatEpochSeconds(socket.lastFrameAt) }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-table
          v-if="socket.rejected.length"
          :data="socket.rejected"
          size="small"
          style="margin-top: 12px"
        >
          <el-table-column label="被拒绝的代码" min-width="200">
            <template #default="{ row }">
              <!-- 该字段来自客户端输入并由服务端原样回传，属于不可信内容：
                   必须用文本插值渲染 -->
              <span class="mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" width="200" />
        </el-table>

        <p class="muted note">
          WebSocket 未做鉴权（推送内容为公开行情）。断线后前端按 1s→2s→4s…最多 30s
          指数退避自动重连，并恢复此前订阅的标的。
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

.note {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
</style>
