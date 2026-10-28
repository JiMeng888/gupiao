import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { storageKey } from '@/utils/storage'

/**
 * 主题状态（任务书 M5）。
 *
 * <h2>为什么主题是全局 store 而不是组件内的 ref</h2>
 * 主题要在两处生效：DOM 上的 {@code data-theme} 属性（给 CSS 变量用），
 * 以及 ECharts 的 option（它读不到 CSS，得由 {@code useChartTheme}
 * 从计算样式里取）。两处消费同一个状态，就必须有一个唯一来源，
 * 否则会出现「页面是暗的、K 线还是亮色」的半切换状态。
 *
 * <h2>为什么默认跟随系统</h2>
 * 大多数用户从没动过主题设置。默认跟随 {@code prefers-color-scheme}
 * 能让深色模式的系统用户一进来就是深色，
 * 而手动选择过的用户则以显式选择为准（存进 localStorage）。
 *
 * <h2>为什么用 data 属性而不是 class</h2>
 * 见 styles/main.css 中的说明：data 属性在 DevTools 里一眼可辨，
 * 也不会被组件 scoped 样式意外覆盖。
 */

/** 用户显式选择；null 表示「跟随系统」。 */
type ThemeChoice = 'light' | 'dark' | null

const CHOICE_KEY = storageKey('ui', 'theme')

function readChoice(): ThemeChoice {
  try {
    const raw = localStorage.getItem(CHOICE_KEY)
    return raw === 'light' || raw === 'dark' ? raw : null
  } catch {
    // 隐私模式下访问 localStorage 会抛异常；主题降级为跟随系统即可，
    // 不能因为读不到偏好就让整个应用起不来
    return null
  }
}

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches
}

export const useThemeStore = defineStore('theme', () => {
  const choice = ref<ThemeChoice>(readChoice())
  const systemDark = ref(systemPrefersDark())

  /** 实际生效的主题。 */
  const current = computed<'light' | 'dark'>(() => {
    if (choice.value) return choice.value
    return systemDark.value ? 'dark' : 'light'
  })

  const isDark = computed(() => current.value === 'dark')

  /** 把主题写到 <html data-theme>，CSS 变量随之切换。 */
  function apply(): void {
    if (typeof document === 'undefined') return
    const root = document.documentElement
    if (current.value === 'dark') {
      root.setAttribute('data-theme', 'dark')
    } else {
      // 亮色是 :root 的默认状态，移除属性即可，不留多余的 dark 残留
      root.removeAttribute('data-theme')
    }
    // 让移动端浏览器把状态栏/地址栏也染成同色，避免顶部出现突兀的白条。
    // 这里的色值必须与 main.css 里 [data-theme='dark'] 的 --color-bg 一致，
    // 否则地址栏和页面底会差出一档，看着像没对齐。
    document.querySelector('meta[name="theme-color"]')?.setAttribute(
      'content',
      current.value === 'dark' ? '#0e1013' : '#ffffff',
    )
  }

  function setChoice(next: 'light' | 'dark'): void {
    choice.value = next
    try {
      localStorage.setItem(CHOICE_KEY, next)
    } catch {
      /* 忽略：仅影响持久化，不影响本次会话生效 */
    }
  }

  /** 三态循环：跟随系统 -> 亮 -> 暗 -> 跟随系统。 */
  function cycle(): void {
    if (choice.value === null) setChoice('light')
    else if (choice.value === 'light') setChoice('dark')
    else {
      choice.value = null
      try {
        localStorage.removeItem(CHOICE_KEY)
      } catch {
        /* 忽略 */
      }
    }
  }

  watch(current, apply, { immediate: true })

  // 只在浏览器环境注册系统主题监听
  if (typeof window !== 'undefined' && window.matchMedia) {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = (e: MediaQueryListEvent): void => {
      systemDark.value = e.matches
    }
    // Safari 14 之前只有 addListener
    if (media.addEventListener) media.addEventListener('change', onChange)
    else media.addListener?.(onChange)
  }

  return { choice, current, isDark, setChoice, cycle, apply }
})
