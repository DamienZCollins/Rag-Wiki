import { formatDate } from './format'

/**
 * 生效期文案。
 *
 * 生效期与失效期都可能为空：两者都空时写「未标注」，只有失效期空时写「长期」。
 * 关键是空值不能被当成「已失效」——状态判断只看 summary.status，不看日期。
 */
export function formatEffectivePeriod(
  effectiveAt: string | null | undefined,
  expiresAt: string | null | undefined,
): string {
  const start = effectiveAt ? formatDate(effectiveAt) : null
  const end = expiresAt ? formatDate(expiresAt) : null

  if (!start && !end) return '未标注'
  if (!start) return `未标注 — ${end}`
  if (!end) return `${start} — 长期`
  return `${start} — ${end}`
}
