import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/**
 * 单元测试配置。
 *
 * <p>为什么不直接在 vite.config.ts 里加 `test` 段：那份配置里带着 dev/preview
 * 的代理与生产打包的手动分包，测试一样也用不上，混在一起会让
 * 「改打包配置」和「改测试配置」互相牵连。这里只保留测试真正需要的三样：
 * Vue SFC 编译、`@` 别名、jsdom 环境。
 *
 * <p>只用 jsdom 不用 node：组件测试要挂载真实 DOM。纯逻辑测试在 jsdom 下
 * 同样能跑，多出来的开销可以忽略，但省掉了「换个测试就得换环境」的麻烦。
 *
 * <p>不启用 `globals`：`describe / it / expect` 一律从 'vitest' 显式 import。
 * 好处是 tsconfig 不需要额外声明 `types: ["vitest/globals"]`，
 * 且读代码时能一眼看出这些符号从哪来。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    // 测试不应有机会污染或依赖生产产物
    clearMocks: true,
    restoreMocks: true,
  },
})
