import {
  normalizeOpenAIModels,
  type AgentEvent,
  type ConversationToolHistory,
  type Message,
  type OpenAIConnectionInput,
  type OpenAIModel,
  type RunRequest,
  type Session,
  type Workspace,
  titleFromPrompt,
} from './domain'
import { AgentGatewayError, parseAgentEventStream } from './sse'

export { AgentGatewayError } from './sse'

export interface AgentGateway {
  readonly label: string
  listModels(connection: OpenAIConnectionInput, signal: AbortSignal): Promise<OpenAIModel[]>
  pickWorkspace(): Promise<Workspace | null>
  createSession(workspaceId: string): Promise<Session>
  readSession(sessionId: string): Promise<Session>
  run(request: RunRequest, signal: AbortSignal): AsyncIterable<AgentEvent>
  decideConfirmation(
    runId: string,
    confirmationId: string,
    decision: 'approve' | 'reject',
  ): Promise<void>
}

interface WorkspacePickerResponse {
  cancelled: boolean
  workspace: Workspace | null
}

interface ModelListResponse {
  models: unknown
}

interface RunResponse {
  runId: unknown
  sessionId: unknown
  status?: unknown
  errorCode?: unknown
  errorMessage?: unknown
  errorRetryable?: unknown
}

type JsonRecord = Record<string, unknown>

function jsonRecord(value: unknown): JsonRecord | undefined {
  return typeof value === 'object' && value !== null ? value as JsonRecord : undefined
}

function requiredString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function timestamp(value: unknown): number | undefined {
  if (typeof value !== 'string') return undefined
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function sessionResponseError(): AgentGatewayError {
  return new AgentGatewayError('读取会话失败：Java 后端返回了无效会话结构', {
    code: 'SESSION_RESPONSE_INVALID',
    retryable: false,
  })
}

function parseToolHistory(value: unknown): ConversationToolHistory[] {
  if (!Array.isArray(value)) throw sessionResponseError()
  return value.map(item => {
    const candidate = jsonRecord(item)
    const name = requiredString(candidate?.name)
    const argumentsValue = typeof candidate?.arguments === 'string' ? candidate.arguments : undefined
    const result = typeof candidate?.result === 'string' ? candidate.result : undefined
    if (name === undefined || argumentsValue === undefined || result === undefined) {
      throw sessionResponseError()
    }
    return { name, arguments: argumentsValue, result }
  })
}

function parseSession(value: unknown): Session {
  const candidate = jsonRecord(value)
  const id = requiredString(candidate?.sessionId)
  const workspaceId = requiredString(candidate?.workspaceId)
  const createdAt = timestamp(candidate?.createdAt)
  const updatedAt = timestamp(candidate?.updatedAt)
  if (
    id === undefined
    || workspaceId === undefined
    || createdAt === undefined
    || updatedAt === undefined
    || !Array.isArray(candidate?.turns)
  ) throw sessionResponseError()

  const messages = candidate.turns.flatMap(item => {
    const turn = jsonRecord(item)
    const runId = requiredString(turn?.runId)
    const state = turn?.state
    const turnCreatedAt = timestamp(turn?.createdAt)
    const user = jsonRecord(turn?.user)
    const userContent = requiredString(user?.content)
    if (
      runId === undefined
      || (state !== 'completed' && state !== 'incomplete')
      || turnCreatedAt === undefined
      || userContent === undefined
    ) throw sessionResponseError()

    const userMessage: Message = {
      id: `${runId}-user`,
      role: 'user',
      content: userContent,
      createdAt: turnCreatedAt,
      state: 'complete',
    }
    if (state === 'incomplete') {
      return [
        userMessage,
        {
          id: `${runId}-assistant`,
          role: 'assistant' as const,
          content: '',
          createdAt: turnCreatedAt,
          state: 'error' as const,
          statusLabel: '运行未完成',
        },
      ]
    }

    const assistant = jsonRecord(turn?.assistant)
    const assistantContent = requiredString(assistant?.content)
    if (assistantContent === undefined) throw sessionResponseError()
    const toolHistory = parseToolHistory(assistant?.toolHistory)
    return [
      userMessage,
      {
        id: `${runId}-assistant`,
        role: 'assistant' as const,
        content: assistantContent,
        createdAt: turnCreatedAt,
        state: 'complete' as const,
        statusLabel: toolHistory.length > 0 ? '已回答（执行了工具）' : '已回答（未执行工具）',
        ...(toolHistory.length > 0 ? { toolHistory } : {}),
      },
    ]
  })
  const firstUser = messages.find(message => message.role === 'user')
  return {
    id,
    workspaceId,
    title: firstUser === undefined ? '新会话' : titleFromPrompt(firstUser.content),
    createdAt,
    updatedAt,
    messages,
  }
}

async function apiError(response: Response, action: string): Promise<Error> {
  let detail = ''
  let code: string | undefined
  let retryable: boolean | undefined

  try {
    const payload = await response.json() as { detail?: unknown }
    if (typeof payload.detail === 'string') {
      detail = payload.detail
    } else if (typeof payload.detail === 'object' && payload.detail !== null) {
      const candidate = payload.detail as {
        code?: unknown
        message?: unknown
        retryable?: unknown
      }
      if (typeof candidate.message === 'string') detail = candidate.message
      if (typeof candidate.code === 'string') code = candidate.code
      if (typeof candidate.retryable === 'boolean') retryable = candidate.retryable
    }
  } catch {
    // The HTTP status remains useful when the response is not JSON.
  }

  if (!detail && response.status >= 500) {
    detail = 'Java 后端未启动或发生内部错误，请确认 127.0.0.1:8000 服务正常'
  }

  return new AgentGatewayError(`${action}失败：${detail || `HTTP ${response.status}`}`, {
    code,
    retryable,
    status: response.status,
  })
}

async function apiFetch(
  input: string,
  init: RequestInit,
  action: string,
): Promise<Response> {
  try {
    return await fetch(input, init)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new AgentGatewayError(
      `${action}失败：无法连接 Java 后端，请确认 127.0.0.1:8000 服务已启动`,
      { code: 'BACKEND_UNREACHABLE', retryable: true, cause: error },
    )
  }
}

export class HttpAgentGateway implements AgentGateway {
  readonly label: string
  private readonly baseUrl: string

  constructor(baseUrl = '') {
    this.baseUrl = baseUrl.replace(/\/+$/, '')
    this.label = this.baseUrl ? `HTTP Agent · ${this.baseUrl}` : 'HTTP Agent · 当前站点'
  }

  private endpoint(path: string): string {
    return `${this.baseUrl}${path}`
  }

  async listModels(
    connection: OpenAIConnectionInput,
    signal: AbortSignal,
  ): Promise<OpenAIModel[]> {
    const response = await apiFetch(this.endpoint('/api/openai/models'), {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(connection),
      signal,
    }, '获取模型')

    if (!response.ok) {
      throw await apiError(response, '获取模型')
    }

    const payload = await response.json() as ModelListResponse
    if (!Array.isArray(payload.models)) {
      throw new AgentGatewayError('获取模型失败：Java 后端返回了无效模型列表', {
        code: 'MODEL_LIST_INVALID',
        retryable: false,
      })
    }

    return normalizeOpenAIModels(payload.models)
  }

  async pickWorkspace(): Promise<Workspace | null> {
    const response = await apiFetch(this.endpoint('/api/workspaces/pick'), {
      method: 'POST',
      headers: { Accept: 'application/json' },
    }, '打开系统目录选择器')

    if (!response.ok) {
      throw await apiError(response, '打开系统目录选择器')
    }

    const payload = await response.json() as WorkspacePickerResponse
    return payload.cancelled ? null : payload.workspace
  }

  async createSession(workspaceId: string): Promise<Session> {
    const response = await apiFetch(this.endpoint('/api/sessions'), {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ workspaceId }),
    }, '创建会话')
    if (!response.ok) throw await apiError(response, '创建会话')
    return parseSession(await response.json())
  }

  async readSession(sessionId: string): Promise<Session> {
    const response = await apiFetch(
      this.endpoint(`/api/sessions/${encodeURIComponent(sessionId)}`),
      { method: 'GET', headers: { Accept: 'application/json' } },
      '读取会话',
    )
    if (!response.ok) throw await apiError(response, '读取会话')
    return parseSession(await response.json())
  }

  async *run(request: RunRequest, signal: AbortSignal): AsyncIterable<AgentEvent> {
    const response = await apiFetch(this.endpoint('/api/agent/runs'), {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
      signal,
    }, 'Agent API 请求')

    if (!response.ok) {
      throw await apiError(response, 'Agent API 请求')
    }
    const payload = await response.json() as RunResponse
    if (typeof payload.runId !== 'string' || payload.runId.length === 0
      || payload.sessionId !== request.sessionId) {
      throw new AgentGatewayError('Agent API 请求失败：Java 后端返回了无效运行结构', {
        code: 'RUN_RESPONSE_INVALID',
        retryable: false,
      })
    }

    const eventsResponse = await apiFetch(
      this.endpoint(`/api/agent/runs/${encodeURIComponent(payload.runId)}/events?sessionId=${encodeURIComponent(request.sessionId)}`),
      { method: 'GET', headers: { Accept: 'text/event-stream' }, signal },
      'Agent 事件流请求',
    )
    if (!eventsResponse.ok) throw await apiError(eventsResponse, 'Agent 事件流请求')
    try {
      for await (const event of parseAgentEventStream(eventsResponse)) yield event
    } catch (streamError) {
      if (streamError instanceof DOMException && streamError.name === 'AbortError') {
        throw streamError
      }

      /*
       * 背景：SSE 只是 Run 的实时交付通道，无终态断流时前端会丢失 RunStore 中已收口的失败原因。
       * 设计意图：仅在流解析失败后读取一次同一 Run，再复用现有 error 事件链路展示持久化结论。
       * 关键约束：查询必须携带原 sessionId，且不得改为轮询或成功恢复，否则会扩大协议范围并混淆 SSE 事件顺序。
       */
      const runResponse = await apiFetch(
        this.endpoint(`/api/agent/runs/${encodeURIComponent(payload.runId)}?sessionId=${encodeURIComponent(request.sessionId)}`),
        { method: 'GET', headers: { Accept: 'application/json' }, signal },
        'Agent 运行状态请求',
      )
      if (!runResponse.ok) throw await apiError(runResponse, 'Agent 运行状态请求')

      const currentRun = await runResponse.json() as RunResponse
      if (currentRun.runId !== payload.runId || currentRun.sessionId !== request.sessionId) {
        throw new AgentGatewayError('Agent 运行状态请求失败：Java 后端返回了无效运行结构', {
          code: 'RUN_RESPONSE_INVALID',
          retryable: false,
        })
      }
      if (currentRun.status === 'FAILED'
        && typeof currentRun.errorCode === 'string'
        && typeof currentRun.errorMessage === 'string'
        && typeof currentRun.errorRetryable === 'boolean') {
        yield {
          type: 'error',
          code: currentRun.errorCode,
          message: currentRun.errorMessage,
          retryable: currentRun.errorRetryable,
        }
        return
      }
      if (currentRun.status === 'RUNNING') {
        yield {
          type: 'error',
          code: 'AGENT_RUN_UNAVAILABLE',
          message: 'Agent 运行状态已丢失，请重新发起任务',
          retryable: true,
        }
        return
      }
      throw streamError
    }
  }

  async decideConfirmation(
    runId: string,
    confirmationId: string,
    decision: 'approve' | 'reject',
  ): Promise<void> {
    const response = await apiFetch(
      this.endpoint(
        `/api/agent/runs/${encodeURIComponent(runId)}/confirmations/${encodeURIComponent(confirmationId)}`,
      ),
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ decision }),
      },
      '提交文件变更确认',
    )
    if (!response.ok) throw await apiError(response, '提交文件变更确认')
  }
}

export function createAgentGateway(): AgentGateway {
  return new HttpAgentGateway(import.meta.env.VITE_AGENT_API_URL?.trim() ?? '')
}
