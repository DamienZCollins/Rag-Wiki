import type { PageContent, WikiLink, WikiPageSummary } from '@/api/wiki'

/**
 * 合并正文的 relatedTitles 与接口的 links。
 *
 * links 带真实目标与状态，是权威数据；relatedTitles 是正文里写下的标题，
 * 正常情况下两者一一对应。只有 relatedTitles 多出来的标题才回查条目目录：
 * 查得到说明目标存在，查不到且目录确实加载完整时才标为 PENDING（待创建）。
 * 目录没加载时不猜状态，直接跳过，避免把存在的条目说成待创建。
 */
export function mergeRelatedLinks(
  content: PageContent,
  links: WikiLink[],
  pages: WikiPageSummary[],
): WikiLink[] {
  const byTitle = new Map<string, WikiLink>()
  for (const link of links) {
    if (!byTitle.has(link.title)) byTitle.set(link.title, link)
  }

  const pageByTitle = new Map<string, WikiPageSummary>()
  for (const page of pages) pageByTitle.set(page.title, page)

  const merged: WikiLink[] = []
  const seen = new Set<string>()

  const push = (link: WikiLink) => {
    if (seen.has(link.title)) return
    seen.add(link.title)
    merged.push(link)
  }

  for (const title of content.relatedTitles) {
    const known = byTitle.get(title)
    if (known) {
      push(known)
      continue
    }
    if (pages.length === 0) continue
    const page = pageByTitle.get(title)
    push(
      page
        ? { pageId: page.pageId, title: page.title, status: 'ACTIVE' }
        : { pageId: null, title, status: 'PENDING' },
    )
  }

  for (const link of links) push(link)

  return merged
}

/** 已失效条目里可以作为「当前生效」展示的关联条目 */
export function activeLinksOf(links: WikiLink[]): WikiLink[] {
  return links.filter((link) => link.status === 'ACTIVE' && Boolean(link.pageId))
}
