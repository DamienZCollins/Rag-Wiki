/**
 * 条目展示用的日期与计数格式化。
 * 后端 effectiveAt / expiresAt 是无时区的 LocalDate（yyyy-MM-dd），updatedAt 是 UTC Instant；
 * LocalDate 原样展示，Instant 统一换算到 Asia/Shanghai 再展示。
 */

const SHANGHAI = 'Asia/Shanghai'
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: SHANGHAI,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function toPartsMap(parts: Intl.DateTimeFormatPart[]): Record<string, string> {
  const map: Record<string, string> = {}
  for (const part of parts) {
    map[part.type] = part.value
  }
  return map
}

/** LocalDate 原样返回；Instant 转成 Asia/Shanghai 的 yyyy-MM-dd HH:mm */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  if (DATE_ONLY.test(value)) return value
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const map = toPartsMap(dateTimeFormatter.formatToParts(date))
  return `${map.year}-${map.month}-${map.day} ${map.hour}:${map.minute}`
}

/** 只要日期部分，同样按 Asia/Shanghai 换算 */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  if (DATE_ONLY.test(value)) return value
  return formatDateTime(value).slice(0, 10)
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前，超过 30 天回落到日期 */
export function formatRelative(
  value: string | number | null | undefined,
  now = Date.now(),
): string {
  if (value === null || value === undefined) return ''
  const time = typeof value === 'number' ? value : new Date(value).getTime()
  if (Number.isNaN(time)) return ''

  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  const diff = Math.max(0, now - time)

  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  return typeof value === 'number'
    ? formatDateTime(new Date(value).toISOString())
    : formatDate(value)
}

/** 耗时展示：128 秒 -> 2 分 8 秒 */
export function formatDuration(milliseconds: number): string {
  if (milliseconds < 1000) return `${Math.max(0, Math.round(milliseconds))} 毫秒`
  const totalSeconds = Math.round(milliseconds / 1000)
  if (totalSeconds < 60) return `${totalSeconds} 秒`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return seconds === 0 ? `${minutes} 分` : `${minutes} 分 ${seconds} 秒`
}
