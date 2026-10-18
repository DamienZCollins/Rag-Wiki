import type { SearchResponse } from '@/api/rag'
import type { CitationList, CitationRef } from './types'

/**
 * 引用解析与正文分块。
 *
 * 工具返回的 search_knowledge 内容是给人看的文本，编号 [n] 是模型写引用的唯一依据，
 * 所以这里按后端 SearchKnowledgeTool.renderCitation 的固定格式反解，不做模糊匹配。
 */

/** 后端 rag.refuse 配置：两路最高原始分都低于各自阈值才拒答 */
export const REFUSE_THRESHOLD = {
  bm25: 8.0,
  vector: 0.72,
} as const

const HEADER_RE = /^\[(\d{1,3})\]\s*《(.+?)》\s*$/
const FIELD_RE = /^\s*(小节|来源|片段|正文)：(.*)$/

/** 解析一次 search_knowledge 的返回，得到编号到片段的映射 */
export function parseToolCitations(callId: string, question: string, content: string): CitationList {
  const items: CitationRef[] = []
  let current: CitationRef | null = null
  let inBody = false

  for (const line of content.split('\n')) {
    const header = HEADER_RE.exec(line)
    if (header) {
      current = {
        index: Number(header[1]),
        title: header[2].trim(),
        chunkId: '',
        docId: '',
        pageId: null,
        headingPath: '',
        version: null,
        sourceDocs: [],
        content: '',
        bm25Score: null,
        bm25Rank: null,
        vectorScore: null,
        vectorRank: null,
        rrfScore: null,
        enriched: false,
      }
      items.push(current)
      inBody = false
      continue
    }
    if (!current) continue

    if (inBody) {
      // 正文可以跨行，遇到下一个编号头才结束
      current.content = current.content ? `${current.content}\n${line}` : line
      continue
    }

    const field = FIELD_RE.exec(line)
    if (!field) continue
    const value = field[2].trim()
    switch (field[1]) {
      case '小节':
        current.headingPath = value
        break
      case '来源': {
        // 形如 policy-refund-timeline（v2025.1）
        const matched = /^(.+?)（(.+?)）\s*$/.exec(value)
        if (matched) {
          current.docId = matched[1].trim()
          current.version = matched[2].trim()
        } else {
          current.docId = value
        }
        break
      }
      case '片段':
        current.chunkId = value
        break
      case '正文':
        current.content = value
        inBody = true
        break
      default:
        break
    }
  }

  return { callId, question, items }
}

/** 工具返回命中失败时是结构化 JSON，不当作引用解析 */
export function isToolMiss(content: string): boolean {
  const text = content.trim()
  if (!text.startsWith('{')) return false
  try {
    const parsed = JSON.parse(text) as { hit?: unknown }
    return parsed.hit === false
  } catch {
    return false
  }
}

/** 从工具入参里取检索问题，取不到就用调用时的原文 */
export function questionOf(argumentsText: string): string {
  try {
    const parsed = JSON.parse(argumentsText) as { question?: unknown }
    if (typeof parsed.question === 'string') return parsed.question
  } catch {
    // 入参不是合法 JSON 时退回原文
  }
  return argumentsText
}

/**
 * 按「最新列表优先」回溯编号。
 * 模型每一轮只看到最后一次检索结果，所以后一次检索会覆盖前一次的同号片段。
 */
export function findCitation(lists: CitationList[], index: number): CitationRef | null {
  for (let i = lists.length - 1; i >= 0; i -= 1) {
    const hit = lists[i].items.find((item) => item.index === index)
    if (hit) return hit
  }
  return null
}

/** 本轮出现过的最大编号，用来判断越界 */
export function maxCitationIndex(lists: CitationList[]): number {
  let max = 0
  for (const list of lists) {
    for (const item of list.items) {
      if (item.index > max) max = item.index
    }
  }
  return max
}

/** 用检索接口的复算结果回填两路原始分与 RRF 分，按 chunkId 对齐 */
export function applyScores(lists: CitationList[], response: SearchResponse): void {
  const byChunk = new Map(response.chunks.map((chunk) => [chunk.chunkId, chunk]))
  for (const list of lists) {
    for (const item of list.items) {
      const chunk = byChunk.get(item.chunkId)
      if (!chunk) continue
      item.pageId = chunk.pageId ?? item.pageId
      item.sourceDocs = chunk.sourceDocs ?? []
      if (chunk.content) item.content = chunk.content
      item.bm25Score = chunk.bm25Score
      item.bm25Rank = chunk.bm25Rank
      item.vectorScore = chunk.vectorScore
      item.vectorRank = chunk.vectorRank
      item.rrfScore = chunk.rrfScore
      item.enriched = true
    }
  }
}

export type InlineNode =
  | { type: 'text'; text: string }
  | { type: 'strong'; text: string }
  | { type: 'cite'; index: number }

export type BlockNode =
  | { type: 'p'; inlines: InlineNode[] }
  | { type: 'ul'; items: InlineNode[][] }
  | { type: 'ol'; items: InlineNode[][] }

const CITE_RE = /\[(\d{1,3})\]/g
const STRONG_RE = /\*\*([^*\n]+)\*\*/g

function pushPlain(target: InlineNode[], text: string) {
  if (!text) return
  let cursor = 0
  CITE_RE.lastIndex = 0
  let match = CITE_RE.exec(text)
  while (match) {
    if (match.index > cursor) {
      target.push({ type: 'text', text: text.slice(cursor, match.index) })
    }
    target.push({ type: 'cite', index: Number(match[1]) })
    cursor = match.index + match[0].length
    match = CITE_RE.exec(text)
  }
  if (cursor < text.length) {
    target.push({ type: 'text', text: text.slice(cursor) })
  }
}

/** 行内解析：先切 **加粗**，再在普通文本里切 [n] 角标 */
export function parseInline(text: string): InlineNode[] {
  const nodes: InlineNode[] = []
  let cursor = 0
  STRONG_RE.lastIndex = 0
  let match: RegExpExecArray | null = STRONG_RE.exec(text)
  while (match) {
    if (match.index > cursor) {
      pushPlain(nodes, text.slice(cursor, match.index))
    }
    nodes.push({ type: 'strong', text: match[1] })
    cursor = match.index + match[0].length
    match = STRONG_RE.exec(text)
  }
  if (cursor < text.length) {
    pushPlain(nodes, text.slice(cursor))
  }
  return nodes
}

const UL_RE = /^\s*[-*]\s+(.*)$/
const OL_RE = /^\s*\d+[.)]\s+(.*)$/

/** 正文分块：空行分段，- 开头成无序列表，数字开头成有序列表 */
export function parseBlocks(text: string): BlockNode[] {
  const blocks: BlockNode[] = []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let paragraph: string[] = []
  let list: { type: 'ul' | 'ol'; items: InlineNode[][] } | null = null

  const flushParagraph = () => {
    if (paragraph.length === 0) return
    blocks.push({ type: 'p', inlines: parseInline(paragraph.join('\n')) })
    paragraph = []
  }
  const flushList = () => {
    if (!list) return
    blocks.push({ type: list.type, items: list.items })
    list = null
  }

  for (const line of lines) {
    if (line.trim().length === 0) {
      flushParagraph()
      flushList()
      continue
    }
    const ul = UL_RE.exec(line)
    const ol = ul ? null : OL_RE.exec(line)
    if (ul || ol) {
      flushParagraph()
      const type = ul ? 'ul' : 'ol'
      if (!list || list.type !== type) {
        flushList()
        list = { type, items: [] }
      }
      list.items.push(parseInline((ul ?? ol)![1]))
      continue
    }
    flushList()
    paragraph.push(line)
  }
  flushParagraph()
  flushList()
  return blocks
}

export function formatScore(value: number | null | undefined, digits = 3): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toFixed(digits)
}
