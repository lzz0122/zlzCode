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
    sessionId: string,
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
  pendingApprovalId?: unknown
  pendingToolCallId?: unknown
  errorCode?: unknown
  errorMessage?: unknown
  errorRetryable?: unknown
}

interface ApprovalResponse {
  approvalId: unknown
  sessionId: unknown
  runId: unknown
  toolCallId: unknown
  status: unknown
  expiresAt: unknown
  toolName: unknown
  relativePaths: unknown
  presentationSummary: unknown
  runStatus: unknown
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

function pollingDelay(): Promise<'poll'> {
  return new Promise(resolve => window.setTimeout(() => resolve('poll'), 400))
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
      const events = parseAgentEventStream(eventsResponse)[Symbol.asyncIterator]()
      let nextEvent = await events.next()
      while (!nextEvent.done) {
        const event = nextEvent.value
        yield event

        if (event.type !== 'tool_started') {
          nextEvent = await events.next()
          continue
        }

        const followingEvent = events.next()
        while (true) {
          const result = await Promise.race([
            followingEvent.then(value => ({ kind: 'event' as const, value })),
            pollingDelay().then(() => ({ kind: 'poll' as const })),
          ])
          if (result.kind === 'event') {
            nextEvent = result.value
            break
          }

          const runResponse = await apiFetch(
            this.endpoint(`/api/agent/runs/${encodeURIComponent(payload.runId)}?sessionId=${encodeURIComponent(request.sessionId)}`),
            { method: 'GET', headers: { Accept: 'application/json' }, signal },
            '查询文件变更审批',
          )
          if (!runResponse.ok) throw await apiError(runResponse, '查询文件变更审批')
          const currentRun = await runResponse.json() as RunResponse
          if (currentRun.runId !== payload.runId || currentRun.sessionId !== request.sessionId) {
            throw new AgentGatewayError('查询文件变更审批失败：Java 后端返回了无效运行结构', {
              code: 'RUN_RESPONSE_INVALID',
              retryable: false,
            })
          }
          if (currentRun.status !== 'WAITING_APPROVAL') continue

          const approvalId = requiredString(currentRun.pendingApprovalId)
          const toolCallId = requiredString(currentRun.pendingToolCallId)
          if (approvalId === undefined || toolCallId !== event.id) {
            throw new AgentGatewayError('查询文件变更审批失败：Java 后端返回了无效待审批状态', {
              code: 'APPROVAL_RESPONSE_INVALID',
              retryable: false,
            })
          }

          const approvalResponse = await apiFetch(
            this.endpoint(`/api/agent/runs/${encodeURIComponent(payload.runId)}/approvals/${encodeURIComponent(approvalId)}?sessionId=${encodeURIComponent(request.sessionId)}`),
            { method: 'GET', headers: { Accept: 'application/json' }, signal },
            '读取文件变更审批',
          )
          if (!approvalResponse.ok) throw await apiError(approvalResponse, '读取文件变更审批')
          const approval = await approvalResponse.json() as ApprovalResponse
          const relativePaths = Array.isArray(approval.relativePaths)
            ? approval.relativePaths.filter((path): path is string => typeof path === 'string')
            : []
          const presentation = requiredString(approval.presentationSummary)
          const expiresAt = requiredString(approval.expiresAt)
          if (approval.approvalId !== approvalId
            || approval.sessionId !== request.sessionId
            || approval.runId !== payload.runId
            || approval.toolCallId !== toolCallId
            || approval.status !== 'PENDING'
            || approval.runStatus !== 'WAITING_APPROVAL'
            || relativePaths.length === 0
            || presentation === undefined
            || expiresAt === undefined) {
            throw new AgentGatewayError('读取文件变更审批失败：Java 后端返回了无效审批结构', {
              code: 'APPROVAL_RESPONSE_INVALID',
              retryable: false,
            })
          }

          const [summary, ...previewLines] = presentation.split('\n')
          /*
           * 背景：后端按协议在 WAITING_APPROVAL 期间保持 SSE 静默，前端只能通过 Run pending ID 发现审批。
           * 设计意图：把查询到的 Approval 投影为现有页面事件，复用工具卡和后续原 SSE，不新增第二套 UI 状态机。
           * 关键约束：事件必须沿用 pendingToolCallId；若创建新工具 ID，会同时留下“正在写入”和“等待审批”两张卡片。
           */
          yield {
            type: 'tool_confirmation_required',
            id: toolCallId,
            confirmationId: approvalId,
            label: approval.toolName === 'write' ? '写入文件' : String(approval.toolName),
            operation: summary.startsWith('将创建 ') ? 'create' : 'overwrite',
            path: relativePaths[0],
            summary,
            diff: previewLines.join('\n').trim() || presentation,
            expiresAt,
          }
          nextEvent = await followingEvent
          break
        }
      }
    } catch (streamError) {
      if (streamError instanceof DOMException && streamError.name === 'AbortError') {
        throw streamError
      }

      /*
       * 背景：SSE 只是 Run 的实时交付通道，无终态断流时前端会丢失 RunStore 中已收口的失败原因。
       * 设计意图：仅在流解析失败后读取一次同一 Run，再复用现有 error 事件链路展示持久化结论。
       * 关键约束：查询必须携带原 sessionId，且不得用此失败兜底替代上方审批轮询或恢复成功结果，否则会混淆 SSE 事件顺序。
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
    sessionId: string,
    confirmationId: string,
    decision: 'approve' | 'reject',
  ): Promise<void> {
    const response = await apiFetch(
      this.endpoint(
        `/api/agent/runs/${encodeURIComponent(runId)}/approvals/${encodeURIComponent(confirmationId)}`,
      ),
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          sessionId,
          decision: decision === 'approve' ? 'APPROVE' : 'REJECT',
        }),
      },
      '提交文件变更审批',
    )
    if (!response.ok) throw await apiError(response, '提交文件变更审批')
  }
}

export function createAgentGateway(): AgentGateway {
  return new HttpAgentGateway(import.meta.env.VITE_AGENT_API_URL?.trim() ?? '')
}
