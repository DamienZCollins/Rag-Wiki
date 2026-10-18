/**
 * 对比实验的分组与取臂。
 *
 * 两组口径分开：
 * 1. 索引源对比：原始文档 vs Wiki 页面，只比 mrr / top1Rate / recallRate（同为比率，0–1）
 * 2. 快慢通道对比：双通道 vs 纯 RAG，比慢通道 token 与 hallucinationRate
 *
 * 不同量纲永远不画在同一根柱子上：token 与比率先分组，比率内部再按各自的轴上限画。
 * 两臂数值相同时不画两根等高柱，改由调用方渲染「无差异」。
 */
import type { EvalMetrics, EvalRun } from '@/api/eval'

export type IndexArm = 'doc' | 'wiki' | 'unknown'

/** 索引源取 aliasTarget：`config.indexSource` 在两套索引上都写了 wiki，不可靠 */
export function indexArmOf(run: EvalRun): IndexArm {
  const target = run.config?.aliasTarget ?? ''
  if (target.includes('_doc')) return 'doc'
  if (target.includes('_wiki')) return 'wiki'
  const source = run.config?.indexSource ?? ''
  if (source === 'doc') return 'doc'
  if (source === 'wiki') return 'wiki'
  return 'unknown'
}

export const INDEX_ARM_LABEL: Record<IndexArm, string> = {
  doc: '原始文档',
  wiki: 'Wiki 页面',
  unknown: '未知索引',
}

export interface CompareArm {
  runId: string
  label: string
  /** 图例里的短名，如「原始文档」「Wiki 页面」 */
  shortLabel: string
  metrics: EvalMetrics
  caseCount: number
  indexArm: IndexArm
  fastPathEnabled: boolean
}

export function toCompareArm(run: EvalRun, shortLabel: string): CompareArm {
  return {
    runId: run.runId,
    label: run.label,
    shortLabel,
    metrics: run.metrics,
    caseCount: run.caseCount,
    indexArm: indexArmOf(run),
    fastPathEnabled: run.config?.fastPathEnabled === true,
  }
}

export type CompareGroupKind = 'index' | 'channel'

export interface CompareMetric {
  key: string
  label: string
  /** 数值展示文本，两臂各一个 */
  texts: [string, string]
  /** 柱高百分比 0–100，按所在 block 的 axisMax 计算 */
  heights: [number, number]
  /** 两臂完全相同 */
  same: boolean
  delta: number
  /** 差值展示文本 */
  deltaText: string
  /** 该指标越大越好 */
  higherBetter: boolean
}

export interface CompareBlock {
  /** 量纲说明，如「比率」「慢通道 token 总量」 */
  unit: string
  /**
   * 本组柱子的轴上限。同组共用一根轴，柱高与该轴严格成正比。
   * 轴上限会显示在图上——放大轴是为了看清差异，不是为了让差异看起来更大。
   */
  axisMax: number
  axisMaxText: string
  /** 轴上限的取法说明，如「按本组最大值取整」 */
  axisNote: string
  metrics: CompareMetric[]
}

export interface CompareGroupData {
  kind: CompareGroupKind
  title: string
  meta: string
  arms: [CompareArm, CompareArm]
  blocks: CompareBlock[]
  /** 取臂时未能控制住的变量，页面需要显式提示 */
  warnings: string[]
}

interface RatioSpec {
  key: string
  label: string
  higherBetter: boolean
  pick: (metrics: EvalMetrics) => number
}

const INDEX_RATIO_SPECS: RatioSpec[] = [
  { key: 'mrr', label: 'MRR', higherBetter: true, pick: (m) => m.mrr },
  { key: 'top1Rate', label: 'Top1 命中率', higherBetter: true, pick: (m) => m.top1Rate },
  { key: 'recallRate', label: '召回率', higherBetter: true, pick: (m) => m.recallRate },
]

const CHANNEL_RATIO_SPECS: RatioSpec[] = [
  {
    key: 'hallucinationRate',
    label: '幻觉率',
    higherBetter: false,
    pick: (m) => m.hallucinationRate,
  },
]

interface TokenSpec {
  key: string
  label: string
  pick: (metrics: EvalMetrics) => number
}

const CHANNEL_TOKEN_SPECS: TokenSpec[] = [
  { key: 'ragTokenInTotal', label: '慢通道输入 token', pick: (m) => m.ragTokenInTotal },
  { key: 'ragTokenOutTotal', label: '慢通道输出 token', pick: (m) => m.ragTokenOutTotal },
]

function ratio4(value: number): string {
  return Number.isFinite(value) ? value.toFixed(4) : '0.0000'
}

function percentOfAxis(value: number, axisMax: number): number {
  if (!Number.isFinite(value) || axisMax <= 0) return 0
  return Math.max(0, Math.min(100, (value / axisMax) * 100))
}

/** 比率轴上限：按本组最大值上取整到 0.1，最小 0.2，最多 1.0 */
function ratioAxisMax(values: number[]): number {
  const peak = Math.max(...values.filter((value) => Number.isFinite(value)), 0)
  const stepped = Math.ceil(peak * 10) / 10
  return Math.min(1, Math.max(0.2, Number(stepped.toFixed(1))))
}

function buildRatioMetric(
  spec: RatioSpec,
  arms: [CompareArm, CompareArm],
  axisMax: number,
): CompareMetric {
  const left = spec.pick(arms[0].metrics)
  const right = spec.pick(arms[1].metrics)
  const same = Math.abs(left - right) < 1e-9
  const delta = left - right
  const deltaText = same
    ? '无差异'
    : `${delta > 0 ? '+' : ''}${delta.toFixed(4)}（${spec.higherBetter ? '越高越好' : '越低越好'}）`
  return {
    key: spec.key,
    label: spec.label,
    texts: [ratio4(left), ratio4(right)],
    heights: [percentOfAxis(left, axisMax), percentOfAxis(right, axisMax)],
    same,
    delta,
    deltaText,
    higherBetter: spec.higherBetter,
  }
}

function buildTokenMetric(
  spec: TokenSpec,
  arms: [CompareArm, CompareArm],
  axisMax: number,
): CompareMetric {
  const left = spec.pick(arms[0].metrics)
  const right = spec.pick(arms[1].metrics)
  const same = left === right
  const delta = left - right
  const deltaText = same
    ? '无差异'
    : `${delta > 0 ? '+' : ''}${Math.round(delta).toLocaleString('en-US')}（越低越好）`
  return {
    key: spec.key,
    label: spec.label,
    texts: [Math.round(left).toLocaleString('en-US'), Math.round(right).toLocaleString('en-US')],
    heights: [percentOfAxis(left, axisMax), percentOfAxis(right, axisMax)],
    same,
    delta,
    deltaText,
    higherBetter: false,
  }
}

/**
 * 比率组：同一 block 内所有指标共用一根轴。
 * 轴上取整到 0.1 而不是固定 0–1——幻觉率 0.0984 vs 0.1230 在 0–1 轴上只有 2 个百分点高，
 * 看不出差别。轴上限明写在图上，放大轴是让人看清差异，不是让差异显得更大。
 */
function buildRatioBlock(specs: RatioSpec[], arms: [CompareArm, CompareArm]): CompareBlock {
  const axisMax = ratioAxisMax(
    specs.flatMap((spec) => [spec.pick(arms[0].metrics), spec.pick(arms[1].metrics)]),
  )
  return {
    unit: '比率',
    axisMax,
    axisMaxText: axisMax.toFixed(4),
    axisNote: '轴上限按本组最大值上取整到 0.1，柱高与数值成正比',
    metrics: specs.map((spec) => buildRatioMetric(spec, arms, axisMax)),
  }
}

/** token 组：轴上取到本组最大值的整千，避免最大值那根永远顶到 100% */
function buildTokenBlock(specs: TokenSpec[], arms: [CompareArm, CompareArm]): CompareBlock {
  const peak = Math.max(
    ...specs.flatMap((spec) => [spec.pick(arms[0].metrics), spec.pick(arms[1].metrics)]),
    1,
  )
  const axisMax = Math.ceil(peak / 1000) * 1000
  return {
    unit: '慢通道 token 总量（条）',
    axisMax,
    axisMaxText: axisMax.toLocaleString('en-US'),
    axisNote: '轴上限按本组最大值上取整到千',
    metrics: specs.map((spec) => buildTokenMetric(spec, arms, axisMax)),
  }
}

function sortByStartedDesc(runs: EvalRun[]): EvalRun[] {
  return [...runs].sort((a, b) => {
    const ta = new Date(a.startedAt).getTime()
    const tb = new Date(b.startedAt).getTime()
    return (Number.isNaN(tb) ? 0 : tb) - (Number.isNaN(ta) ? 0 : ta)
  })
}

function latest(runs: EvalRun[], predicate: (run: EvalRun) => boolean): EvalRun | undefined {
  return runs.find(predicate)
}

/**
 * 取索引源对比的两臂。
 * 优先让当前选中的批次入列，再按「同一 fastPathEnabled」找对侧；
 * 对侧找不到时退回最近的另一索引源批次，并在 warnings 里说明哪个变量没控住。
 */
export function buildIndexGroup(
  runs: EvalRun[],
  selected: EvalRun | null,
): CompareGroupData | null {
  const sorted = sortByStartedDesc(runs)
  const selectedArm = selected ? indexArmOf(selected) : 'unknown'
  const fp = selected?.config?.fastPathEnabled === true

  let docRun: EvalRun | undefined
  let wikiRun: EvalRun | undefined
  const warnings: string[] = []

  const wantDoc = selectedArm === 'doc'
  const sameFp = (run: EvalRun) => (run.config?.fastPathEnabled === true) === fp

  if (wantDoc) {
    docRun = selected ?? undefined
    wikiRun =
      latest(sorted, (run) => indexArmOf(run) === 'wiki' && sameFp(run)) ??
      latest(sorted, (run) => indexArmOf(run) === 'wiki')
  } else if (selectedArm === 'wiki') {
    wikiRun = selected ?? undefined
    docRun =
      latest(sorted, (run) => indexArmOf(run) === 'doc' && sameFp(run)) ??
      latest(sorted, (run) => indexArmOf(run) === 'doc')
  } else {
    const preferredFp = true
    docRun = latest(
      sorted,
      (run) => indexArmOf(run) === 'doc' && run.config?.fastPathEnabled === preferredFp,
    )
    wikiRun = latest(
      sorted,
      (run) => indexArmOf(run) === 'wiki' && run.config?.fastPathEnabled === preferredFp,
    )
  }

  if (!docRun || !wikiRun) {
    // 少一臂就画不出对比，返回 null 由页面给出说明
    return null
  }

  const arms: [CompareArm, CompareArm] = [
    toCompareArm(docRun, INDEX_ARM_LABEL.doc),
    toCompareArm(wikiRun, INDEX_ARM_LABEL.wiki),
  ]

  if (arms[0].fastPathEnabled !== arms[1].fastPathEnabled) {
    warnings.push(
      `两臂 fastPathEnabled 不同（${
        arms[0].fastPathEnabled ? '开' : '关'
      } vs ${arms[1].fastPathEnabled ? '开' : '关'}），差异不能全部归给索引源`,
    )
  }
  if (arms[0].caseCount !== arms[1].caseCount) {
    warnings.push(
      `两臂用例数不同（${arms[0].caseCount} vs ${arms[1].caseCount}），比率之间仍可比，绝对数之间不可比`,
    )
  }

  return {
    kind: 'index',
    title: '索引源对比',
    meta: `${INDEX_ARM_LABEL.doc} vs ${INDEX_ARM_LABEL.wiki}`,
    arms,
    blocks: [buildRatioBlock(INDEX_RATIO_SPECS, arms)],
    warnings,
  }
}

/**
 * 取快慢通道对比的两臂。
 * 优先固定索引源（同一 aliasTarget），找不到同索引源的对侧时退回最近批次并说明。
 */
export function buildChannelGroup(
  runs: EvalRun[],
  selected: EvalRun | null,
): CompareGroupData | null {
  const sorted = sortByStartedDesc(runs)
  const selectedFp = selected?.config?.fastPathEnabled === true
  const selectedIndex = selected ? indexArmOf(selected) : 'unknown'

  const sameIndex = (run: EvalRun) =>
    selectedIndex === 'unknown' ? true : indexArmOf(run) === selectedIndex

  let dualRun: EvalRun | undefined
  let pureRun: EvalRun | undefined
  const warnings: string[] = []

  if (selected && selectedFp) {
    dualRun = selected
    pureRun =
      latest(sorted, (run) => run.config?.fastPathEnabled === false && sameIndex(run)) ??
      latest(sorted, (run) => run.config?.fastPathEnabled === false)
  } else if (selected) {
    pureRun = selected
    dualRun =
      latest(sorted, (run) => run.config?.fastPathEnabled === true && sameIndex(run)) ??
      latest(sorted, (run) => run.config?.fastPathEnabled === true)
  } else {
    dualRun = latest(sorted, (run) => run.config?.fastPathEnabled === true)
    pureRun = latest(sorted, (run) => run.config?.fastPathEnabled === false)
  }

  if (!dualRun || !pureRun) return null

  const arms: [CompareArm, CompareArm] = [
    toCompareArm(dualRun, '双通道'),
    toCompareArm(pureRun, '纯 RAG'),
  ]

  if (arms[0].indexArm !== arms[1].indexArm) {
    warnings.push(
      `两臂索引源不同（${INDEX_ARM_LABEL[arms[0].indexArm]} vs ${
        INDEX_ARM_LABEL[arms[1].indexArm]
      }），差异不能全部归给通道开关`,
    )
  }
  if (arms[0].caseCount !== arms[1].caseCount) {
    warnings.push(
      `两臂用例数不同（${arms[0].caseCount} vs ${arms[1].caseCount}），token 总量不可直接相减`,
    )
  }

  return {
    kind: 'channel',
    title: '快慢通道对比',
    meta: '双通道 vs 纯 RAG',
    arms,
    // token 与比率分两块：量纲不同，绝不画在同一根柱子上
    blocks: [
      buildTokenBlock(CHANNEL_TOKEN_SPECS, arms),
      buildRatioBlock(CHANNEL_RATIO_SPECS, arms),
    ],
    warnings,
  }
}
