/**
 * 归因层的展示元数据。
 *
 * 五层与后端 `Attributor.Layer` 一一对应，顺序即归因判定顺序：命中第一个失败层就停。
 * `NONE` 不是一层，它表示「判定层没能归到五层之一」——真实数据里确实存在
 * （R13 批次 35 层 + 1 条 NONE = 36 条失败），页面如实展示，不把数字凑平。
 */
import {
  ATTRIBUTION_LAYERS,
  ATTRIBUTION_LABEL,
  type Attribution,
  type AttributionLayer,
} from '@/api/eval'

export type LayerKey = AttributionLayer | 'NONE'

/** 归因判定顺序，编译在最前：上游没编好，下游的失败都不该算到下游头上 */
export const LAYER_ORDER: readonly AttributionLayer[] = ATTRIBUTION_LAYERS

/** 语义色沿用原型：召回 amber、排序 blue、生成 oxblood、兜底 line-strong，编译层用 green */
export const LAYER_COLOR_VAR: Record<LayerKey, string> = {
  COMPILE: '--green',
  RECALL: '--amber',
  RERANK: '--blue',
  GENERATION: '--oxblood',
  FALLBACK: '--line-strong',
  NONE: '--ink-faint',
}

/** 徽章配色后缀，与原型 .attr-badge 的 class 对应 */
export const LAYER_TONE: Record<LayerKey, string> = {
  COMPILE: 'compile',
  RECALL: 'recall',
  RERANK: 'rank',
  GENERATION: 'gen',
  FALLBACK: 'fallback',
  NONE: 'none',
}

export const LAYER_TEXT: Record<LayerKey, string> = {
  ...ATTRIBUTION_LABEL,
  NONE: '未归因',
}

/** 每层的判定口径，鼠标悬停与失败列表展开时作为提示 */
export const LAYER_HINT: Record<LayerKey, string> = {
  COMPILE: '期望来源在 Wiki 库里就不存在，检索再准也召不回来',
  RECALL: '期望片段没进候选窗口 candidateWindow',
  RERANK: '召回到了，但排在融合后的 topK 之外',
  GENERATION: '片段给到了，答案要点、工具调用或禁点判不过',
  FALLBACK: '该拒答的没拒、不该拒的拒了，或两路原始分低于拒答阈值',
  NONE: '用例失败但归因返回 NONE，属后端未归因，单独列出以便与失败总数对账',
}

export function isLayerKey(value: string | null | undefined): value is LayerKey {
  return (
    value === 'COMPILE' ||
    value === 'RECALL' ||
    value === 'RERANK' ||
    value === 'GENERATION' ||
    value === 'FALLBACK' ||
    value === 'NONE'
  )
}

/** 层排序权重：五层按判定顺序，NONE 排最后 */
export function layerRank(layer: LayerKey): number {
  const index = LAYER_ORDER.indexOf(layer as AttributionLayer)
  return index < 0 ? LAYER_ORDER.length : index
}

export interface LayerStat {
  key: LayerKey
  label: string
  count: number
  /**
   * 占失败总数的比例。分母是**归因五层之和**（不是 failed），
   * 这样每段占比加总恰好是 100%，而未归因的那部分单独对账、不混进分布里。
   * NONE 行不给占比，只给条数。
   */
  share: number | null
  colorVar: string
}

/** 五层合计与失败总数的对账结果，页面角落如实展示 */
export interface AttributionReconcile {
  /** 归因五层之和 */
  layerSum: number
  /** 本批次失败总数 */
  failedTotal: number
  /** 未归因条数（layers 里 NONE 的数量） */
  unattributed: number
  /** 归因合计与失败数的差 */
  gap: number
  /** 两者是否一致 */
  consistent: boolean
}

export function reconcileAttribution(
  attribution: Attribution | null | undefined,
  failedTotal: number,
  unattributed = 0,
): AttributionReconcile {
  const layerSum = sumLayers(attribution)
  const gap = failedTotal - layerSum
  return {
    layerSum,
    failedTotal,
    unattributed,
    gap,
    consistent: gap === 0,
  }
}

/**
 * 由后端 `attribution` 聚合出五层统计。
 * 占比分母用五层之和；未归因（NONE）作为附带行单独展示条数。
 */
export function buildLayerStats(
  attribution: Attribution | null | undefined,
  unattributed = 0,
): LayerStat[] {
  const denominator = sumLayers(attribution)
  const stats: LayerStat[] = LAYER_ORDER.map((layer) => {
    const count = attribution?.[layer] ?? 0
    return {
      key: layer,
      label: LAYER_TEXT[layer],
      count,
      share: denominator > 0 ? count / denominator : null,
      colorVar: LAYER_COLOR_VAR[layer],
    }
  })
  if (unattributed > 0) {
    stats.push({
      key: 'NONE',
      label: LAYER_TEXT.NONE,
      count: unattributed,
      share: null,
      colorVar: LAYER_COLOR_VAR.NONE,
    })
  }
  return stats
}

/** 五层（不含 NONE）的合计，用于和失败总数对账 */
export function sumLayers(attribution: Attribution | null | undefined): number {
  return LAYER_ORDER.reduce((sum, layer) => sum + (attribution?.[layer] ?? 0), 0)
}

/**
 * 环形图分段。只包含五层（NONE 不参与，它不是判定层），
 * 并跳过零条数的层，避免零长度分段把渐变写脏。
 */
export function donutStops(attribution: Attribution | null | undefined): string {
  const total = sumLayers(attribution)
  if (total <= 0) return 'var(--paper-deep) 0% 100%'
  let cursor = 0
  const stops: string[] = []
  for (const layer of LAYER_ORDER) {
    const count = attribution?.[layer] ?? 0
    if (count <= 0) continue
    const from = (cursor / total) * 100
    cursor += count
    const to = (cursor / total) * 100
    stops.push(`var(${LAYER_COLOR_VAR[layer]}) ${from.toFixed(3)}% ${to.toFixed(3)}%`)
  }
  return stops.join(', ')
}
