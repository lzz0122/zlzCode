export type ThemePreference = 'light' | 'dark' | 'system'

export const MAX_PROMPT_LENGTH = 20_000
export const MAX_CONVERSATION_MESSAGES = 40
export const MAX_CONVERSATION_MESSAGE_CHARS = 100_000
export const MAX_CONVERSATION_TOTAL_CHARS = 120_000
export const MIN_TOOL_CALLS_PER_RUN = 1
export const DEFAULT_TOOL_CALLS_PER_RUN = 5
export const MAX_TOOL_CALLS_PER_RUN = 15

export interface OpenAIModel {
  id: string
  ownedBy?: string
}

export interface OpenAIPublicSettings {
  baseUrl: string
  models: OpenAIModel[]
  model: string
  reasoningEffort: string
  maxToolCalls: number
}

export interface OpenAIConnectionInput {
  baseUrl: string
  apiKey: string
}

export interface Workspace {
  id: string
  name: string
  path: string
}

export interface ConversationMessageInput {
  role: 'user' | 'assistant'
  content: string
  toolHistory?: ConversationToolHistory[]
}

export interface ConversationToolHistory {
  name: string
  arguments: string
  result: string
}

export interface ToolStep {
  id: string
  label: string
  detail?: string
  state:
    | 'awaiting_confirmation'
    | 'running'
    | 'completed'
    | 'failed'
    | 'rejected'
    | 'expired'
    | 'cancelled'
  code?: string
  confirmation?: {
    id: string
    operation: 'create' | 'delete' | 'move' | 'overwrite' | 'replace'
    path: string
    summary: string
    diff: string
    expiresAt: string
    submitting: boolean
    error?: string
  }
}

export interface RunMetrics {
  steps: number
  durationMs: number
  toolCalls?: number
  inputTokens?: number
  outputTokens?: number
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: number
  state: 'complete' | 'running' | 'error' | 'cancelled'
  statusLabel?: string
  tools?: ToolStep[]
  toolHistory?: ConversationToolHistory[]
  metrics?: RunMetrics
}

export interface Session {
  id: string
  workspaceId: string
  title: string
  createdAt: number
  updatedAt: number
  messages: Message[]
}

export type AgentEvent =
  | { type: 'status'; label: string }
  | {
      type: 'tool_confirmation_required'
      id: string
      confirmationId: string
      label: string
      operation: 'create' | 'delete' | 'move' | 'overwrite' | 'replace'
      path: string
      summary: string
      diff: string
      expiresAt: string
    }
  | { type: 'tool_started'; id: string; label: string; detail?: string }
  | {
      type: 'tool_finished'
      id: string
      state: 'completed' | 'failed'
      detail?: string
      code?: string
    }
  | { type: 'text_delta'; delta: string }
  | {
      type: 'completed'
      metrics: RunMetrics
      toolHistory: ConversationToolHistory[]
    }
  | { type: 'error'; message: string; code?: string; retryable?: boolean }

export interface RunRequest {
  runId: string
  sessionId: string
  workspace: Workspace
  prompt: string
  model: string
  reasoningEffort?: string
  maxToolCalls: number
  openai: OpenAIConnectionInput
  history: ConversationMessageInput[]
}

export function completedConversationHistory(
  messages: readonly Message[],
): ConversationMessageInput[] {
  const completedPairs: Array<readonly [ConversationMessageInput, ConversationMessageInput]> = []
  for (let index = 0; index + 1 < messages.length; index += 1) {
    const user = messages[index]
    const assistant = messages[index + 1]
    if (user.role !== 'user' || assistant.role !== 'assistant') continue
    index += 1
    if (
      user.state !== 'complete'
      || assistant.state !== 'complete'
      || !user.content.trim()
      || !assistant.content.trim()
    ) continue
    const assistantHistory: ConversationMessageInput = {
      role: 'assistant',
      content: assistant.content,
      ...(assistant.toolHistory?.length ? { toolHistory: assistant.toolHistory } : {}),
    }
    completedPairs.push([
      { role: 'user', content: user.content },
      assistantHistory,
    ])
  }

  const history: ConversationMessageInput[] = []
  let totalChars = 0
  for (let index = completedPairs.length - 1; index >= 0; index -= 1) {
    const pair = completedPairs[index]
    const pairChars = pair[0].content.length
      + pair[1].content.length
      + (pair[1].toolHistory ?? []).reduce(
        (total, exchange) => total
          + exchange.name.length
          + exchange.arguments.length
          + exchange.result.length,
        0,
      )
    if (
      pair.some(message => message.content.length > MAX_CONVERSATION_MESSAGE_CHARS)
      || history.length + pair.length > MAX_CONVERSATION_MESSAGES
      || totalChars + pairChars > MAX_CONVERSATION_TOTAL_CHARS
    ) break

    history.unshift(...pair)
    totalChars += pairChars
  }
  return history
}

export function createId(prefix: string): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `${prefix}-${crypto.randomUUID()}`
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function titleFromPrompt(prompt: string): string {
  const compact = prompt.replace(/\s+/g, ' ').trim()
  if (compact.length === 0) return '新会话'
  return compact.length > 24 ? `${compact.slice(0, 24)}…` : compact
}

export function settleRunningTools(
  tools: readonly ToolStep[] | undefined,
  state: Exclude<ToolStep['state'], 'running' | 'awaiting_confirmation'>,
  detail: string,
): ToolStep[] | undefined {
  if (tools === undefined) return undefined
  return tools.map(tool => {
    if (tool.state !== 'running' && tool.state !== 'awaiting_confirmation') return tool
    const terminalDetail = tool.detail?.trim()
      ? `${tool.detail}\n${detail}`
      : detail
    return { ...tool, state, detail: terminalDetail }
  })
}

function completedStatusLabel(tools: readonly ToolStep[] | undefined): string {
  if (!tools?.length) return '已回答（未执行工具）'
  const mutations = tools.filter(tool => tool.confirmation !== undefined)
  if (mutations.length > 0 && mutations.every(tool => tool.state === 'completed')) {
    return '文件变更已完成'
  }
  if (mutations.length > 0) return '文件变更未完成'
  return '已回答（执行了工具）'
}

export function applyAgentEvent(message: Message, event: AgentEvent): Message {
  switch (event.type) {
    case 'status':
      return { ...message, statusLabel: event.label }
    case 'tool_confirmation_required':
      return {
        ...message,
        statusLabel: '等待确认文件变更',
        tools: [
          ...(message.tools ?? []),
          {
            id: event.id,
            label: event.label,
            state: 'awaiting_confirmation',
            confirmation: {
              id: event.confirmationId,
              operation: event.operation,
              path: event.path,
              summary: event.summary,
              diff: event.diff,
              expiresAt: event.expiresAt,
              submitting: false,
            },
          },
        ],
      }
    case 'tool_started':
      return {
        ...message,
        tools: (message.tools ?? []).some(tool => tool.id === event.id)
          ? (message.tools ?? []).map(tool => tool.id === event.id
              ? { ...tool, label: event.label, detail: event.detail, state: 'running' }
              : tool)
          : [
              ...(message.tools ?? []),
              { id: event.id, label: event.label, detail: event.detail, state: 'running' },
            ],
      }
    case 'tool_finished':
      return {
        ...message,
        tools: (message.tools ?? []).map(tool =>
          tool.id === event.id
            ? {
                ...tool,
                detail: event.detail ?? tool.detail,
                state: event.code === 'CONFIRMATION_REJECTED'
                  ? 'rejected'
                  : event.code === 'CONFIRMATION_EXPIRED'
                    ? 'expired'
                    : event.state,
                code: event.code,
              }
            : tool,
        ),
      }
    case 'text_delta':
      return { ...message, content: message.content + event.delta }
    case 'completed':
      return {
        ...message,
        state: 'complete',
        statusLabel: completedStatusLabel(message.tools),
        toolHistory: event.toolHistory.length ? event.toolHistory : undefined,
        tools: settleRunningTools(
          message.tools,
          'failed',
          '运行已结束，但未收到工具完成事件',
        ),
        metrics: event.metrics,
      }
    case 'error':
      return {
        ...message,
        state: 'error',
        statusLabel: '运行失败',
        content: message.content || event.message,
        tools: settleRunningTools(message.tools, 'failed', event.message),
      }
  }
}
