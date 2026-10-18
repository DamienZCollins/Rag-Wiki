/**
 * 指标的分子分母。
 *
 * 后端 `metrics` 只给比率，不给分子分母；页面要求「86/122」这样的可查证据，
 * 所以这里用接口返回的计数型字段还原出分子分母，并对不上时不做四舍五入掩盖。
 *
 * 口径来源 docs/评测指标口径.md：
 * - passRate        = passed / caseCount
 * - recallRate      = 召回命中数 / retrievalCaseCount（带期望来源的用例数）
 * - mrr / top1Rate  = 分子是加权和，只能给出分母
 * - refusalCorrectRate = 拒答判通过数 / refusalCaseCount
 * - answerPointRate = answerPointsHit / answerPointsTotal
 * - hallucinationRate  = hallucinationCount / caseCount
 * - fastPathHitRate = fastPathCount / caseCount
 */
import type { EvalMetrics } from '@/api/eval'

export interface NumeratorDenominator {
  /** 分子。数据不足以还原时为 null */
  numerator: number | null
  denominator: number | null
  /** 该指标的分母口径说明，写在指标卡 tooltip 里 */
  note: string
}

function round4(value: number): number {
  return Math.round(value * 10_000) / 10_000
}

/**
 * 由比率与分母反推整数分子。
 * 比率是 4 位小数取整后的值，反推可能有 ±1 的误差；只在还原值与比率一致时采用，
 * 否则返回 null，宁可只显示分母也不显示一个错的分子。
 */
function deriveNumerator(rate: number, denominator: number): number | null {
  if (!Number.isFinite(rate) || denominator <= 0) return null
  const guess = Math.round(rate * denominator)
  if (guess < 0 || guess > denominator) return null
  if (round4(guess / denominator) !== round4(rate)) return null
  return guess
}

export function passCounters(metrics: EvalMetrics): NumeratorDenominator {
  return {
    numerator: metrics.passed,
    denominator: metrics.caseCount,
    note: '通过用例数 / 本批次用例数',
  }
}

export function recallCounters(metrics: EvalMetrics): NumeratorDenominator {
  return {
    numerator: deriveNumerator(metrics.recallRate, metrics.retrievalCaseCount),
    denominator: metrics.retrievalCaseCount,
    note: '期望来源进入候选窗口的用例数 / 带期望来源的用例数',
  }
}

export function top1Counters(metrics: EvalMetrics): NumeratorDenominator {
  return {
    numerator: deriveNumerator(metrics.top1Rate, metrics.retrievalCaseCount),
    denominator: metrics.retrievalCaseCount,
    note: '期望来源排第 1 的用例数 / 带期望来源的用例数',
  }
}

export function mrrCounters(metrics: EvalMetrics): NumeratorDenominator {
  // MRR 的分子是 Σ(1/名次)，不是整数条数，只给分母
  return {
    numerator: null,
    denominator: metrics.retrievalCaseCount,
    note: 'Σ(1/期望来源名次) / 带期望来源的用例数',
  }
}

export function refusalCounters(metrics: EvalMetrics): NumeratorDenominator {
  return {
    numerator: deriveNumerator(metrics.refusalCorrectRate, metrics.refusalCaseCount),
    denominator: metrics.refusalCaseCount,
    note: '拒答用例判通过数 / 拒答用例数',
  }
}

export function answerPointCounters(metrics: EvalMetrics): NumeratorDenominator {
  return {
    numerator: metrics.answerPointsHit,
    denominator: metrics.answerPointsTotal,
    note: '命中要点数 / 要点总数',
  }
}

export function hallucinationCounters(metrics: EvalMetrics): NumeratorDenominator {
  return {
    numerator: metrics.hallucinationCount,
    denominator: metrics.caseCount,
    note: '有幻觉的用例数 / 全部用例数',
  }
}

export function fastPathCounters(metrics: EvalMetrics): NumeratorDenominator {
  return {
    numerator: metrics.fastPathCount,
    denominator: metrics.caseCount,
    note: '快通道命中数 / 全部用例数（含拒答与订单）',
  }
}

/** 分子分母写成 `86/122`；分子不可还原时只写分母 */
export function countersText(counters: NumeratorDenominator): string {
  if (counters.denominator === null || counters.denominator === 0) return '—'
  if (counters.numerator === null) return `/ ${counters.denominator}`
  return `${counters.numerator}/${counters.denominator}`
}
