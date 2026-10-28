/**
 * localStorage 键命名规范与读写封装。
 *
 * <h2>键名格式：{@code 模块@配置@版本}</h2>
 * 例：{@code auth@token@1.0.0}、{@code quote@theme@1.0.0}
 *
 * <p>为什么要定这个格式，而不是沿用 {@code stocklab.accessToken} 这种点分名：
 * <ol>
 *   <li><b>可清理</b>。带模块前缀，按功能批量清除时不用逐个枚举键名；
 *       清缓存时也能一眼看出哪个键属于哪个模块。</li>
 *   <li><b>可迁移</b>。版本号是格式的一部分：将来某个存储结构变了，
 *       直接换版本号即可，新旧两份数据天然隔离，老数据可以直接丢弃，
 *       不需要写「判断字段存不存在」这类补丁逻辑。</li>
 *   <li><b>可溯源</b>。在浏览器 DevTools 里看到键名就知道它属于哪个模块、
 *       什么配置、哪个版本，不用回头翻代码。</li>
 * </ol>
 *
 * <h2>为什么必须包一层而不是直接 localStorage.getItem</h2>
 * 隐私模式 / 存储配额满 / 跨源 iframe 下，访问 localStorage 会**直接抛异常**
 * （不是返回 null）。裸调用会让「用户开了无痕模式」变成白屏。
 * 这里统一降级为「不可用」，使功能退化为「不记住偏好」而不是整体崩溃。
 */

/** 所有键的公共前缀，便于与其它站点共用域名时隔离。 */
const NAMESPACE = 'stocklab'

/** 当前存储结构版本。结构变更时递增，旧数据会被自动忽略。 */
const SCHEMA_VERSION = '1.0.0'

/**
 * 生成规范化的存储键。
 *
 * @param module 模块名，如 {@code auth} / {@code quote}
 * @param config 配置项名，如 {@code token} / {@code theme}
 * @param version 结构版本，默认 {@link SCHEMA_VERSION}
 */
export function storageKey(module: string, config: string, version: string = SCHEMA_VERSION): string {
  return `${NAMESPACE}.${module}@${config}@${version}`
}

/** 存储是否可用（隐私模式下为 false）。 */
export const storageAvailable: boolean = (() => {
  try {
    const probe = storageKey('_probe', 'avail')
    localStorage.setItem(probe, '1')
    localStorage.removeItem(probe)
    return true
  } catch {
    return false
  }
})()

/** 读取并解析 JSON；键不存在、解析失败、存储不可用时返回 fallback。 */
export function readJson<T>(key: string, fallback: T): T {
  if (!storageAvailable) {
    return fallback
  }
  try {
    const raw = localStorage.getItem(key)
    if (raw === null) {
      return fallback
    }
    return JSON.parse(raw) as T
  } catch {
    // 数据被手改坏 / 旧版本格式不兼容：丢弃，退回默认值，
    // 不要让一条坏数据把整个页面卡在解析异常上
    return fallback
  }
}

/** 写入 JSON；存储不可用或配额满时静默失败（持久化失败不该影响主流程）。 */
export function writeJson(key: string, value: unknown): void {
  if (!storageAvailable) {
    return
  }
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* 配额满：忽略 */
  }
}

/** 删除键；存储不可用时静默失败。 */
export function removeKey(key: string): void {
  if (!storageAvailable) {
    return
  }
  try {
    localStorage.removeItem(key)
  } catch {
    /* 忽略 */
  }
}

/**
 * 一次性迁移旧键到规范键。
 *
 * <p>旧版用的 {@code stocklab.accessToken} / {@code stocklab.refreshToken}
 * 拆成两个键；新版合并成一个 {@code stocklab.auth@token@1.0.0}。
 * 迁移只做一次：成功写入新键后才删旧键，避免中途失败两边都丢。
 */
export function migrateLegacyKeys(legacyToCurrent: Array<[string, string]>): void {
  if (!storageAvailable) {
    return
  }
  for (const [legacyKey, currentKey] of legacyToCurrent) {
    try {
      if (localStorage.getItem(currentKey) !== null) {
        continue
      }
      const raw = localStorage.getItem(legacyKey)
      if (raw === null) {
        continue
      }
      localStorage.setItem(currentKey, raw)
      localStorage.removeItem(legacyKey)
    } catch {
      /* 忽略 */
    }
  }
}
