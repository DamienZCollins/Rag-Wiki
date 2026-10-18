/**
 * 后端统一返回体与错误类型。
 * 成功码是 200 而不是 0（ResultCode.SUCCESS(200, 200)），这是与常见模板最容易踩的差异。
 */

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export type ApiErrorKind = 'business' | 'http' | 'timeout' | 'network' | 'canceled'

export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number
  readonly kind: ApiErrorKind
  /** 后端原始 message，页面展示时用它，不做二次改写 */
  readonly detail: string

  constructor(
    message: string,
    options: { code?: number; httpStatus?: number; kind: ApiErrorKind; detail?: string },
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = options.code ?? 0
    this.httpStatus = options.httpStatus ?? 0
    this.kind = options.kind
    this.detail = options.detail ?? message
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

/** 业务错误码，取值与后端 ResultCode 一一对应 */
export const ResultCode = {
  SUCCESS: 200,
  PARAM_INVALID: 400,
  NOT_FOUND: 404,
  METHOD_NOT_ALLOWED: 405,
  INTERNAL_ERROR: 500,
  BUSINESS_FAILED: 1000,
  MODEL_NOT_CONFIGURED: 1001,
  MODEL_AUTH_FAILED: 1002,
  MODEL_RATE_LIMITED: 1003,
  MODEL_TIMEOUT: 1004,
  MODEL_UNAVAILABLE: 1005,
  MODEL_UNPARSABLE: 1006,
  SESSION_BUSY: 2002,
  RAG_INDEX_EMPTY: 4001,
} as const

export type ErrorPhase = 'idle' | 'loading' | 'success' | 'empty' | 'error'
