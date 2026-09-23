/// <reference types="vite/client" />

/**
 * .vue 单文件组件的模块声明。
 * 缺了它，TypeScript 无法解析 `import Xxx from './Xxx.vue'`，vue-tsc 会报错。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

/** 环境变量类型声明：让 import.meta.env.XXX 有类型提示。 */
interface ImportMetaEnv {
  readonly VITE_BACKEND_ORIGIN?: string
  readonly VITE_DEV_PORT?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
