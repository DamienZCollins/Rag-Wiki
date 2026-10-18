/**
 * 运维台展示用的格式化。
 * 只做展示转换，不推算任何后端未返回的数值，也不生成进度。
 */

export function formatInt(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return Math.round(value)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export function formatDuration(millis: number | null | undefined): string {
  if (typeof millis !== 'number' || !Number.isFinite(millis) || millis < 0) return '—'
  if (millis < 1000) return `${Math.round(millis)} ms`
  const totalSeconds = millis / 1000
  if (totalSeconds < 60) return `${totalSeconds.toFixed(1)} 秒`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = Math.floor(totalSeconds % 60)
  if (minutes < 60) return `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒`
  const hours = Math.floor(minutes / 60)
  return `${hours} 小时 ${String(minutes % 60).padStart(2, '0')} 分`
}

/** 完成时刻，本机时区显示，仅用于标记结果何时返回 */
export function formatClock(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function formatPercent(ratio: number | null | undefined, digits = 2): string {
  if (typeof ratio !== 'number' || !Number.isFinite(ratio)) return '—'
  return `${(ratio * 100).toFixed(digits)}%`
}

/** 比率型指标统一保留 4 位小数；整数计数不加小数位 */
export function formatMetric(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return Number.isInteger(value) ? formatInt(value) : value.toFixed(4)
}

export function formatSigned(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  const fixed = Math.abs(value) < 0.00005 ? '0.0000' : value.toFixed(4)
  return value > 0 ? `+${fixed}` : fixed
}
