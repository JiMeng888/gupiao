import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/main.css'

/**
 * 应用入口。
 *
 * Element Plus 这里用的是<b>全量引入</b>而不是按需引入。
 * 取舍说明：按需引入需要额外的 unplugin-vue-components 配置与自动生成的
 * 类型声明文件，对脚手架来说是额外的隐性依赖；全量引入多出的体积
 * 基本都在 element 这个独立 chunk 里，首屏 gzip 后差异有限。
 * 若后续要压缩体积，把这里改成按需引入即可，组件代码无需改动。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
