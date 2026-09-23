import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置（Vite 8 + rolldown）。
 *
 * 关于代理的三个要点：
 *
 * 1. `/api` 走 http 代理，`/ws` 必须单独配 `ws: true`。
 *    漏掉 `ws: true` 时，开发环境下 WebSocket 握手会被 Vite 当成普通
 *    HTTP 请求转发而失败——表现为「后端 WS 明明能连，前端一直连不上」。
 *
 * 2. `server.proxy` 与 `preview.proxy` 是**两份独立配置**。
 *    Vite 不会把 server 的代理继承给 preview。只配了 server 的话，
 *    `npm run preview` 预览生产构建时所有 /api 请求都会 404，
 *    而前端代码本身没有任何问题——这个坑很容易被误判成「后端挂了」。
 *    因此下面把代理抽成一份 sharedProxy，两边共用。
 *
 * 3. 代理只在 dev / preview 生效。生产构建后前端是静态资源，由 Nginx
 *    或后端自身的静态资源目录托管，需要在那里配同样的转发规则
 *    （见 frontend/nginx.conf）。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backend = env.VITE_BACKEND_ORIGIN || 'http://localhost:8080'
  const wsBackend = backend.replace(/^http/, 'ws')

  /** dev 与 preview 共用的代理规则，避免两处配置漂移。 */
  const sharedProxy = {
    '/api': {
      target: backend,
      changeOrigin: true,
    },
    '/ws': {
      target: wsBackend,
      ws: true,
      changeOrigin: true,
    },
  }

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: Number(env.VITE_DEV_PORT || 5173),
      proxy: sharedProxy,
    },
    preview: {
      port: Number(env.VITE_PREVIEW_PORT || 4173),
      proxy: sharedProxy,
    },
    build: {
      // 关掉 sourcemap 是为了不要把源码结构带进生产包；
      // 需要线上排查时改为 true 并单独上传到监控平台
      sourcemap: false,
      chunkSizeWarningLimit: 1500,
      // Vite 8 的打包器已是 rolldown（不再是 rollup），官方推荐的配置键是
      // rolldownOptions；rollupOptions 只是保留的 deprecated 别名。
      rolldownOptions: {
        output: {
          // 手动分包：ECharts 与 Element Plus 体积大且各页面共用，
          // 单独切 chunk 后改业务代码不会让用户重新下载整个库。
          //
          // 注意：这里**不能**沿用 rollup 的对象写法
          //   manualChunks: { echarts: ['echarts'] }
          // rolldown 的 manualChunks 只接受函数签名（类型上是 ManualChunksFunction），
          // 官方文档明确写了 "unlike Rollup, object form is not supported"，
          // 对象形式会直接报 TS2769。等价的官方写法是 codeSplitting.groups。
          codeSplitting: {
            groups: [
              { name: 'echarts', test: /node_modules[\\/]echarts/ },
              { name: 'element', test: /node_modules[\\/]@?element-plus/ },
            ],
          },
        },
      },
    },
  }
})
