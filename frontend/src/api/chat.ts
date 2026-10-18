/** 对话接口的请求类型与 SSE 事件类型定义。事件结构见 com.ragwiki.agent.protocol.Event */

export interface ChatRequest {
  sessionId: string
  message: string
}

export type EventType =
  | 'STARTED'
  | 'THINKING'
  | 'TEXT_DELTA'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'FINISHED'
  | 'ERROR'

export type FinishReason = 'COMPLETED' | 'MAX_STEPS' | 'CANCELLED' | 'FAILED'

export interface StartedEvent {
  type: 'STARTED'
  sessionId: string
  userMessage: string
}

export interface ThinkingEvent {
  type: 'THINKING'
}

export interface TextDeltaEvent {
  type: 'TEXT_DELTA'
  text: string
}

export interface ToolCallEvent {
  type: 'TOOL_CALL'
  callId: string
  name: string
  arguments: string
}

export interface ToolResultEvent {
  type: 'TOOL_RESULT'
  callId: string
  name: string
  success: boolean
  content: string
}

export interface FinishedEvent {
  type: 'FINISHED'
  reason: FinishReason
  tokenIn: number
  tokenOut: number
}

export interface ErrorEvent {
  type: 'ERROR'
  /** 数值码或事件名（MAX_STEPS） */
  code: string
  message: string
}

export type ChatEvent =
  | StartedEvent
  | ThinkingEvent
  | TextDeltaEvent
  | ToolCallEvent
  | ToolResultEvent
  | FinishedEvent
  | ErrorEvent

export const EVENT_TYPES: readonly EventType[] = [
  'STARTED',
  'THINKING',
  'TEXT_DELTA',
  'TOOL_CALL',
  'TOOL_RESULT',
  'FINISHED',
  'ERROR',
]

export interface ChatHandlers {
  onEvent: (event: ChatEvent) => void
  onParseError?: (raw: string, error: unknown) => void
  /** 流结束但没收到 FINISHED：断流，必须明确提示 */
  onDisconnect?: (reason: 'network' | 'closed') => void
}
