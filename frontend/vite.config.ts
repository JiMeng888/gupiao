import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

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
    plugins: [
      vue(),
      // Element Plus 按需引入：模板里的 <el-xxx> 由这个插件在编译期解析成
      // `element-plus/es/components/xxx` 的精确导入，并连带引入该组件的样式，
      // 因此不需要在 main.ts 里 app.use(ElementPlus) 与引全量 CSS。
      //
      // 为什么**没有**同时上 unplugin-auto-import：
      // 它解决的是「不写 import 就能用 ElMessage」这类函数式组件。本项目所有
      // ElMessage / ElMessageBox 都是显式 import 的，引入 AutoImport 只会让
      // 这些符号变成来源不明的全局变量，反而降低可读性。函数式组件缺的只是
      // 样式，在 main.ts 里显式引三个 style 入口即可（见 main.ts 注释）。
      //
      // directives 默认为 true，因此 v-loading 会被解析并连带引入 loading 样式。
      //
      // dts 关掉——这是实际试过之后的选择，不是省事：
      //   1. 默认会往项目根目录写 components.d.ts，而 tsconfig 的 include 是
      //      "src/**"，根目录那份不在 TS 程序里，等于生成一份没人读的文件；
      //   2. 把 dts 指到 src/ 让它真正参与 vue-tsc 检查后，多出 20 多条报错：
      //      el-table-column 的插槽参数类型是宽泛的 DefaultRow，与页面中的
      //      HoldingView / WatchlistItem 没有泛型关联，于是「把 row 传给
      //      livePrice(row)」这类调用全部报不可赋值。给插槽加类型标注会更糟——
      //      参数变窄后因函数参数逆变而报错更多；
      //   3. 唯一的解法是逐处 `row as HoldingView` 强转。那既不带来任何真实的
      //      类型安全（row 的属性访问照样是宽类型），又往模板里塞十几处噪声。
      // 结论：按需引入的收益在体积，不在模板类型；模板里 row 的类型在改造前
      // 也没有被检查过，保持原状即可。
      Components({ resolvers: [ElementPlusResolver()], dts: false }),
    ],
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
