/** 运维操作台内部共用的展示类型。接口数据类型一律从 api/* 引入，不在本文件重复定义。 */

export interface MetaItem {
  label: string
  value: string
  /** 数值类默认用等宽字体，文案类可置 false */
  mono?: boolean
  tone?: 'ok' | 'warn' | 'bad'
  /** 需要通栏展示的长值，例如 runId */
  wide?: boolean
}

/** 操作失败：保留后端原始 message 与业务码，不做二次改写 */
export interface OpsFailure {
  message: string
  code: number | null
  httpStatus: number | null
  kind: string | null
}

export type OpsPhase = 'idle' | 'running' | 'success' | 'error'

/** 新结果覆盖旧结果，且失败时清空旧结果，避免把上一次的数据当成本次结果 */
export interface OpsOutcome<T> {
  data: T
  /** 页面实际等待的毫秒数，仅用于说明等待了多久，不代表后端进度 */
  elapsedMs: number
  finishedAt: string
}

export interface ConfirmOptions {
  title: string
  message?: string
  impact?: string
  tone?: 'normal' | 'danger'
  confirmText?: string
  cancelText?: string
}
