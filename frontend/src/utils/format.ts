/**
 * 展示层数字格式化。
 *
 * 单独成文件的原因：行情里的成交额、成交量动辄上亿，直接打印 12 位数字
 * 在表格里既占宽度又难读。这些换算规则（万 / 亿）必须全站一致，
 * 散落在各个视图里迟早会出现「这个页面用亿、那个页面用万元」。
 */

/**
 * 金额格式化为「亿 / 万」。
 *
 * 负数（如亏损金额）保留符号：`-1.23亿`。
 * 传入 null / NaN 时返回占位符而不是 0——「没有数据」与「金额为零」不同。
 */
export function formatMoney(
  value: number | null | undefined,
  digits = 2,
  placeholder = '--',
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return placeholder
  const abs = Math.abs(value)
  const sign = value < 0 ? '-' : ''
  if (abs >= 1e8) return `${sign}${(abs / 1e8).toFixed(digits)}亿`
  if (abs >= 1e4) return `${sign}${(abs / 1e4).toFixed(digits)}万`
  return `${sign}${abs.toFixed(digits)}`
}

/** 成交量（单位：手）格式化。 */
export function formatVolume(
  value: number | null | undefined,
  digits = 2,
  placeholder = '--',
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return placeholder
  const abs = Math.abs(value)
  if (abs >= 1e8) return `${(abs / 1e8).toFixed(digits)}亿手`
  if (abs >= 1e4) return `${(abs / 1e4).toFixed(digits)}万手`
  return `${abs}手`
}

/** 普通数值，空值统一占位。 */
export function formatNumber(
  value: number | null | undefined,
  digits = 2,
  placeholder = '--',
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return placeholder
  return value.toFixed(digits)
}

/**
 * 百分比数值（后端返回的就是百分数，如 1.23 表示 1.23%）。
 * 这里只做格式化，不加 `%` 后缀由调用方决定 —— 涨跌场景要用 ChangeText 着色。
 */
export function formatPercent(
  value: number | null | undefined,
  digits = 2,
  placeholder = '--',
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return placeholder
  return `${value.toFixed(digits)}%`
}
