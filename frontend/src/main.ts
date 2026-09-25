import { createApp } from 'vue'
import { createPinia } from 'pinia'

// 函数式组件与指令的样式必须显式引入。
//
// 模板里的 <el-xxx> 由 unplugin-vue-components 在编译期解析并连带引样式，
// 但下面这三类东西**不是模板标签**，解析器管不到它们：
//   - ElMessage / ElMessageBox：代码里直接调用（import 自 element-plus）；
//   - v-loading：指令，不是组件。
// 漏掉这些样式不会报任何错，只是消息框、确认框、加载遮罩变成没有样式的裸元素——
// 属于典型的「跑得起来但样子不对」的缺陷，因此这里逐个写清用途。
//
// 这三个入口内部会按 Element Plus 自己的依赖关系继续引入 base / input / button /
// overlay 等样式，不需要在此手工列举依赖。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'

import App from './App.vue'
import router from './router'
import './styles/main.css'

/**
 * 应用入口。
 *
 * <h2>Element Plus 采用按需引入</h2>
 * 之前是全量引入（{@code app.use(ElementPlus)} + {@code element-plus/dist/index.css}），
 * 打包后 element 这个 chunk 是 986 kB / gzip 316 kB，样式还有单独 360 kB。
 * 全量注册会把所有组件都拉进包里，而实际用到的只有二十来个，
 * 因此改为按需：组件交给 Vite 插件解析，函数式组件与指令的样式在下方显式引。
 *
 * <h2>语言包为什么从 main.ts 搬到 App.vue</h2>
 * 全量引入时可以 {@code app.use(ElementPlus, { locale: zhCn })} 一次性设置；
 * 按需引入后没有了这次 install，改为在根组件用 {@code <el-config-provider :locale>}
 * 提供——组件的内置文案（分页、空态等）才会是中文。
 *
 * <h2>图表的按需引入</h2>
 * ECharts 同理，见 {@code components/BaseChart.vue}：只注册实际用到的
 * 蜡烛图 / 折线 / 柱状与 tooltip、legend、grid、dataZoom、axisPointer。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
