import type { AgentEvent, ConversationToolHistory, RunMetrics } from './domain'

export class AgentGatewayError extends Error {
  readonly code?: string
  readonly retryable?: boolean
  readonly status?: number

  constructor(
    message: string,
    options: { code?: string; retryable?: boolean; status?: number; cause?: unknown } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'AgentGatewayError'
    this.code = options.code
    this.retryable = options.retryable
    this.status = options.status
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function isOptionalString(value: unknown): value is string | undefined {
  return value === undefined || isString(value)
}

function isOptionalBoolean(value: unknown): value is boolean | undefined {
  return value === undefined || typeof value === 'boolean'
}

function isMutationOperation(value: unknown): boolean {
  return value === 'create'
    || value === 'delete'
    || value === 'move'
    || value === 'overwrite'
    || value === 'replace'
}

function isNonNegativeFinite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function isOptionalMetric(value: unknown): value is number | undefined {
  return value === undefined || isNonNegativeFinite(value)
}

function isRunMetrics(value: unknown): value is RunMetrics {
  if (!isPlainObject(value)) return false
  return isNonNegativeFinite(value.steps)
    && isNonNegativeFinite(value.durationMs)
    && isOptionalMetric(value.toolCalls)
    && isOptionalMetric(value.inputTokens)
    && isOptionalMetric(value.outputTokens)
}

function isConversationToolHistory(value: unknown): value is ConversationToolHistory {
  if (!isPlainObject(value)) return false
  return isString(value.name)
    && /^[A-Za-z0-9_-]{1,64}$/.test(value.name)
    && isString(value.arguments)
    && value.arguments.length <= 20_000
    && isString(value.result)
    && value.result.length <= 20_000
}

function isToolHistory(value: unknown): value is ConversationToolHistory[] {
  return Array.isArray(value)
    && value.length <= 15
    && value.every(isConversationToolHistory)
}

export function isAgentEvent(value: unknown): value is AgentEvent {
  if (!isPlainObject(value) || !isString(value.type)) return false

  switch (value.type) {
    case 'status':
      return isString(value.label)
    case 'tool_confirmation_required':
      return isString(value.id)
        && isString(value.confirmationId)
        && isString(value.label)
        && isMutationOperation(value.operation)
        && isString(value.path)
        && isString(value.summary)
        && isString(value.diff)
        && isString(value.expiresAt)
    case 'tool_started':
      return isString(value.id)
        && isString(value.label)
        && isOptionalString(value.detail)
    case 'tool_finished':
      return isString(value.id)
        && (value.state === 'completed' || value.state === 'failed')
        && isOptionalString(value.detail)
        && isOptionalString(value.code)
    case 'text_delta':
      return isString(value.delta)
    case 'completed':
      return isRunMetrics(value.metrics) && isToolHistory(value.toolHistory)
    case 'error':
      return isString(value.message)
        && isOptionalString(value.code)
        && isOptionalBoolean(value.retryable)
    default:
      return false
  }
}

function eventData(frame: string): string | null {
  const data: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith(':')) continue
    if (line === 'data') {
      data.push('')
    } else if (line.startsWith('data:')) {
      const value = line.slice(5)
      data.push(value.startsWith(' ') ? value.slice(1) : value)
    }
  }
  return data.length === 0 ? null : data.join('\n')
}

function parseFrame(frame: string): AgentEvent | null {
  const data = eventData(frame)
  if (data === null || data.length === 0 || data === '[DONE]') return null

  let payload: unknown
  try {
    payload = JSON.parse(data)
  } catch (error) {
    throw new AgentGatewayError('Agent API 返回了非法 JSON 事件', {
      code: 'AGENT_STREAM_INVALID',
      retryable: false,
      cause: error,
    })
  }
  if (!isAgentEvent(payload)) {
    throw new AgentGatewayError('Agent API 返回了无效事件结构', {
      code: 'AGENT_STREAM_INVALID',
      retryable: false,
    })
  }
  return payload
}

function assertEventStreamContentType(contentType: string | null): void {
  const mime = contentType?.split(';', 1)[0].trim().toLowerCase()
  if (mime !== 'text/event-stream') {
    throw new AgentGatewayError('Agent API 未返回 text/event-stream', {
      code: 'AGENT_STREAM_CONTENT_TYPE_INVALID',
      retryable: false,
    })
  }
}

export async function* parseAgentEventStream(response: Response): AsyncIterable<AgentEvent> {
  assertEventStreamContentType(response.headers.get('content-type'))
  if (response.body === null) {
    throw new AgentGatewayError('Agent API 未返回流式响应体', {
      code: 'AGENT_STREAM_MISSING_BODY',
      retryable: true,
    })
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let terminal: AgentEvent | null = null

  const handleFrame = (frame: string): AgentEvent | null => {
    const event = parseFrame(frame)
    if (event === null) return null
    if (terminal !== null) {
      throw new AgentGatewayError('Agent API 在终态后继续发送事件', {
        code: 'AGENT_STREAM_TERMINAL_INVALID',
        retryable: false,
      })
    }
    if (event.type === 'completed' || event.type === 'error') {
      terminal = event
      return null
    }
    return event
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (value !== undefined) buffer += decoder.decode(value, { stream: !done })
      if (done) buffer += decoder.decode()

      let boundary = /\r?\n\r?\n/.exec(buffer)
      while (boundary !== null) {
        const frame = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        const event = handleFrame(frame)
        if (event !== null) yield event
        boundary = /\r?\n\r?\n/.exec(buffer)
      }

      if (done) break
    }

    if (buffer.length > 0) {
      const event = handleFrame(buffer)
      if (event !== null) yield event
    }
    if (terminal === null) {
      throw new AgentGatewayError('Agent API 流在完成前中断', {
        code: 'AGENT_STREAM_INCOMPLETE',
        retryable: true,
      })
    }
    yield terminal
  } finally {
    reader.releaseLock()
  }
}
