import type { PageContent, WikiLink } from '@/api/wiki'

/** 正文四段固定结构，与 backend/wiki/pages/<page_id>.md 的四个二级标题一一对应 */
export interface DocSection {
  index: number
  id: string
  label: string
}

export const DOC_SECTIONS: DocSection[] = [
  { index: 1, id: 'doc-section-1', label: '摘要' },
  { index: 2, id: 'doc-section-2', label: '适用条件' },
  { index: 3, id: 'doc-section-3', label: '例外' },
  { index: 4, id: 'doc-section-4', label: '关联条目' },
]

/** 章节是否有内容。目录只列有内容的章节，避免点进去是空块 */
export function sectionHasContent(
  section: DocSection,
  content: PageContent,
  links: WikiLink[],
): boolean {
  switch (section.index) {
    case 1:
      return content.summary.trim().length > 0
    case 2:
      return content.conditions.length > 0
    case 3:
      return content.exceptions.length > 0
    case 4:
      return links.length > 0
    default:
      return false
  }
}
