<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

/**
 * 登录 / 注册。
 *
 * 表单校验规则与后端保持一致（手机号 11 位、密码 6~64 位）。
 * 前端校验只为即时反馈，后端仍会再校验一次——
 * 前端校验永远不能当作安全边界。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const tab = ref<'login' | 'register'>('login')
const submitting = ref(false)
const errorMessage = ref<string | null>(null)

const form = reactive({
  phone: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

/** 登录成功后回到用户原本想访问的页面 */
const redirect = computed(() => (route.query.redirect as string | undefined) ?? '/')

function validate(): string | null {
  if (!/^1\d{10}$/.test(form.phone)) return '手机号格式不正确，需为 1 开头的 11 位数字'
  if (form.password.length < 6 || form.password.length > 64) return '密码长度需在 6~64 位之间'
  if (tab.value === 'register' && form.password !== form.confirmPassword) {
    return '两次输入的密码不一致'
  }
  return null
}

async function submit(): Promise<void> {
  errorMessage.value = null
  const invalid = validate()
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }

  submitting.value = true
  try {
    if (tab.value === 'login') {
      await auth.login(form.phone, form.password)
    } else {
      await auth.register(form.phone, form.password, form.nickname || undefined)
    }
    ElMessage.success(tab.value === 'login' ? '登录成功' : '注册成功，已自动登录')
    await router.replace(redirect.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    submitting.value = false
  }
}

function switchTab(next: 'login' | 'register'): void {
  tab.value = next
  errorMessage.value = null
}

/**
 * el-tabs 的 tab-change 事件参数类型是 TabPaneName（string | number），
 * 不是我们自己的字面量联合类型，所以这里显式收窄。
 * 写成内联箭头函数会触发 TS7006（参数隐式 any），因此单独提出为具名函数。
 */
function onTabChange(name: string | number): void {
  if (name === 'login' || name === 'register') switchTab(name)
}
</script>

<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="brand">
        <span class="brand__mark">股</span>
        <div>
          <h1>股票分析师</h1>
          <p class="muted">行情 · 技术指标 · 财务基本面 · AI 辅助研判</p>
        </div>
      </div>

      <el-tabs :model-value="tab" @tab-change="onTabChange">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="11 位手机号" maxlength="11" clearable />
        </el-form-item>

        <el-form-item v-if="tab === 'register'" label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="留空则用手机号打码作为昵称" />
        </el-form-item>

        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="6~64 位"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-form-item v-if="tab === 'register'" label="确认密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-alert
          v-if="errorMessage"
          type="error"
          :title="errorMessage"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />

        <el-button type="primary" style="width: 100%" :loading="submitting" @click="submit">
          {{ tab === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>

      <p class="tip muted">
        <router-link to="/">← 不登录，先看行情</router-link>
      </p>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="登录后的能力"
        description="自选股与持仓记账需要登录；行情、K 线、技术指标、财务基本面与资讯无需登录即可查看。"
      />
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: linear-gradient(135deg, #f5f6f8 0%, #eef2f7 100%);
}

.login-card {
  width: 420px;
  padding: 28px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--color-up);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
}

.brand h1 {
  margin: 0;
  font-size: 18px;
}

.brand p {
  margin: 2px 0 0;
  font-size: 12px;
}

.tip {
  text-align: center;
  font-size: 12px;
  margin: 12px 0;
}
</style>
