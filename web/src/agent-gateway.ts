import type {
  AgentEvent,
  OpenAIConnectionInput,
  OpenAIModel,
  RunRequest,
  Workspace,
} from './domain'
import { AgentGatewayError, parseAgentEventStream } from './sse'

export { AgentGatewayError } from './sse'

export interface AgentGateway {
  readonly label: string
  listModels(connection: OpenAIConnectionInput, signal: AbortSignal): Promise<OpenAIModel[]>
  pickWorkspace(): Promise<Workspace | null>
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
    detail = 'Python 后端未启动或发生内部错误，请确认 127.0.0.1:8000 服务正常'
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
      `${action}失败：无法连接 Python 后端，请确认 127.0.0.1:8000 服务已启动`,
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
      throw new AgentGatewayError('获取模型失败：Python 后端返回了无效模型列表', {
        code: 'MODEL_LIST_INVALID',
        retryable: false,
      })
    }

    return payload.models.flatMap(item => {
      if (typeof item !== 'object' || item === null) return []
      const candidate = item as { id?: unknown; ownedBy?: unknown }
      if (typeof candidate.id !== 'string' || !candidate.id.trim()) return []
      const id = candidate.id.trim()
      const ownedBy = typeof candidate.ownedBy === 'string' && candidate.ownedBy.trim()
        ? candidate.ownedBy.trim()
        : undefined
      return [ownedBy === undefined ? { id } : { id, ownedBy }]
    })
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

  async *run(request: RunRequest, signal: AbortSignal): AsyncIterable<AgentEvent> {
    const response = await apiFetch(this.endpoint('/api/agent/runs'), {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
      signal,
    }, 'Agent API 请求')

    if (!response.ok) {
      throw await apiError(response, 'Agent API 请求')
    }
    for await (const event of parseAgentEventStream(response)) yield event
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
