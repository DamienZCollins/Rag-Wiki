/**
 * 评测页的数字格式化。
 *
 * 口径与 docs/评测指标口径.md 对齐：比率统一 4 位小数（与后端 Metrics.round 一致），
 * 千分位只用于 token 与条数，延迟按量级在毫秒与秒之间切换。
 */

const RATE_DIGITS = 4

/** 比率。4 位小数，非有限值退回 0，与后端空集返回 0 的行为一致 */
export function formatRate(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0.0000'
  return value.toFixed(RATE_DIGITS)
}

/** 比率转百分比文本，保留两位小数（`0.7049` → `70.49%`），只用于需读百分数的场合 */
export function formatPercent(value: number | null | undefined, digits = 2): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0.00%'
  return `${(value * 100).toFixed(digits)}%`
}

/** 整数千分位。token 总量与用例条数用它 */
export function formatInt(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0'
  return Math.round(value).toLocaleString('en-US')
}

/** 大数缩写，用于 KPI 卡等窄容器（`276232` → `276.2k`） */
export function formatCompact(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '0'
  const abs = Math.abs(value)
  if (abs < 1000) return String(Math.round(value))
  if (abs < 1_000_000) return `${(value / 1000).toFixed(1)}k`
  return `${(value / 1_000_000).toFixed(2)}M`
}

/**
 * 延迟。小于 1 秒给毫秒，其余给秒并保留一位，
 * 避免把 40 ms 与 61868 ms 写成同一量纲看不出差别。
 */
export function formatLatency(ms: number | null | undefined): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

/** 差值带符号，默认 4 位小数，用于门槛与基线对比 */
export function formatDelta(value: number | null | undefined, digits = RATE_DIGITS): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  const text = value.toFixed(digits)
  if (Number(text) === 0) return `0.${'0'.repeat(digits)}`
  return value > 0 ? `+${text}` : text
}

/** 差值转百分点（`0.0246` → `+2.46 pp`），只在明确讲「百分点」的位置用 */
export function formatPoints(value: number | null | undefined, digits = 2): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  const scaled = value * 100
  const text = scaled.toFixed(digits)
  if (Number(text) === 0) return `0.${'0'.repeat(digits)} pp`
  return `${scaled > 0 ? '+' : ''}${text} pp`
}

/** 时间戳转 `2026-10-15 10:23`。后端给的是 UTC ISO 串，这里按本地时区展示 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`
}

/** 全量耗时（毫秒）转时刻表式短文本，如 `18.3 min` */
export function formatDuration(ms: number | null | undefined): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '—'
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  const minutes = ms / 60_000
  if (minutes < 60) return `${minutes.toFixed(1)} min`
  return `${(minutes / 60).toFixed(1)} h`
}

/**
 * 基线对比的方向。
 * `higherBetter` 为 true 的指标涨是好事；`hallucinationRate` 一类涨是坏事。
 * 差值落在容差内时不判好坏，返回 `flat`——口径见《评测指标口径》第 9 节。
 */
export type DeltaTone = 'up' | 'down' | 'flat'

export function deltaTone(delta: number, tolerance: number, higherBetter: boolean): DeltaTone {
  if (Math.abs(delta) <= tolerance) return 'flat'
  const good = higherBetter ? delta > 0 : delta < 0
  return good ? 'up' : 'down'
}
