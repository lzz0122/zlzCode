import {
  applyAgentEvent,
  DEFAULT_TOOL_CALLS_PER_RUN,
  MAX_TOOL_CALLS_PER_RUN,
  MIN_TOOL_CALLS_PER_RUN,
  settleRunningTools,
  type AgentEvent,
  type Message,
  type OpenAIModel,
  type OpenAIPublicSettings,
  type Session,
  type ThemePreference,
  type Workspace,
} from './domain'
import { normalizeReasoningEffort, resolveModelCapabilities } from './model-capabilities'

export interface AppState {
  workspaces: Workspace[]
  sessions: Session[]
  activeWorkspaceId: string | null
  activeSessionId: string | null
  theme: ThemePreference
  enterToSend: boolean
  openai: OpenAIPublicSettings
}

export const initialState: AppState = {
  workspaces: [],
  sessions: [],
  activeWorkspaceId: null,
  activeSessionId: null,
  theme: 'system',
  enterToSend: true,
  openai: {
    baseUrl: 'https://api.openai.com/v1',
    models: [],
    model: '',
    reasoningEffort: '',
    maxToolCalls: DEFAULT_TOOL_CALLS_PER_RUN,
  },
}

export type AppAction =
  | { type: 'workspace/add'; workspace: Workspace }
  | { type: 'workspace/select'; workspaceId: string }
  | { type: 'session/create'; session: Session }
  | { type: 'session/select'; sessionId: string }
  | { type: 'message/append'; sessionId: string; message: Message; title?: string }
  | { type: 'run/event'; sessionId: string; messageId: string; event: AgentEvent }
  | {
      type: 'run/confirmation-state'
      sessionId: string
      messageId: string
      confirmationId: string
      submitting: boolean
      error?: string
    }
  | { type: 'run/cancel'; sessionId: string; messageId: string }
  | { type: 'settings/theme'; theme: ThemePreference }
  | { type: 'settings/enter'; enterToSend: boolean }
  | { type: 'settings/openai-base-url'; baseUrl: string }
  | { type: 'settings/openai-models'; baseUrl: string; models: OpenAIModel[] }
  | { type: 'settings/openai-model-add'; model: OpenAIModel }
  | { type: 'settings/openai-model'; model: string }
  | { type: 'settings/openai-model-remove'; modelId: string }
  | { type: 'settings/openai-reasoning'; reasoningEffort: string }
  | { type: 'settings/max-tool-calls'; maxToolCalls: number }
  | { type: 'state/reset' }

function withSelectedModel(settings: OpenAIPublicSettings, requestedModel: string): OpenAIPublicSettings {
  const selected = settings.models.find(model => model.id === requestedModel)
    ?? settings.models[0]
  const capabilities = resolveModelCapabilities(settings.baseUrl, selected)
  return {
    ...settings,
    model: selected?.id ?? '',
    reasoningEffort: normalizeReasoningEffort(capabilities, settings.reasoningEffort),
  }
}

function updateSession(
  sessions: Session[],
  sessionId: string,
  update: (session: Session) => Session,
): Session[] {
  return sessions.map(session => session.id === sessionId ? update(session) : session)
}

export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'workspace/add': {
      const exists = state.workspaces.some(workspace => workspace.path === action.workspace.path)
      const workspaces = exists ? state.workspaces : [...state.workspaces, action.workspace]
      const selected = workspaces.find(workspace => workspace.path === action.workspace.path) ?? action.workspace
      return {
        ...state,
        workspaces,
        activeWorkspaceId: selected.id,
        activeSessionId: null,
      }
    }
    case 'workspace/select': {
      const workspace = state.workspaces.find(item => item.id === action.workspaceId)
      if (workspace === undefined) return state
      return {
        ...state,
        activeWorkspaceId: workspace.id,
        activeSessionId: null,
      }
    }
    case 'session/create':
      return {
        ...state,
        sessions: [action.session, ...state.sessions],
        activeWorkspaceId: action.session.workspaceId,
        activeSessionId: action.session.id,
      }
    case 'session/select': {
      const session = state.sessions.find(item => item.id === action.sessionId)
      if (session === undefined) return state
      return {
        ...state,
        activeWorkspaceId: session.workspaceId,
        activeSessionId: session.id,
      }
    }
    case 'message/append':
      return {
        ...state,
        sessions: updateSession(state.sessions, action.sessionId, session => ({
          ...session,
          title: action.title ?? session.title,
          updatedAt: Date.now(),
          messages: [...session.messages, action.message],
        })),
      }
    case 'run/event':
      return {
        ...state,
        sessions: updateSession(state.sessions, action.sessionId, session => ({
          ...session,
          updatedAt: Date.now(),
          messages: session.messages.map(message =>
            message.id === action.messageId && message.state === 'running'
              ? applyAgentEvent(message, action.event)
              : message,
          ),
        })),
      }
    case 'run/confirmation-state':
      return {
        ...state,
        sessions: updateSession(state.sessions, action.sessionId, session => ({
          ...session,
          messages: session.messages.map(message => message.id === action.messageId
            ? {
                ...message,
                tools: message.tools?.map(tool => tool.confirmation?.id === action.confirmationId
                  ? {
                      ...tool,
                      confirmation: {
                        ...tool.confirmation,
                        submitting: action.submitting,
                        error: action.error,
                      },
                    }
                  : tool),
              }
            : message),
        })),
      }
    case 'run/cancel':
      return {
        ...state,
        sessions: updateSession(state.sessions, action.sessionId, session => ({
          ...session,
          updatedAt: Date.now(),
          messages: session.messages.map(message =>
            message.id === action.messageId && message.state === 'running'
              ? {
                  ...message,
                  state: 'cancelled',
                  statusLabel: '已停止',
                  tools: settleRunningTools(message.tools, 'cancelled', '运行已停止'),
                }
              : message,
          ),
        })),
      }
    case 'settings/theme':
      return { ...state, theme: action.theme }
    case 'settings/enter':
      return { ...state, enterToSend: action.enterToSend }
    case 'settings/openai-base-url':
      if (action.baseUrl === state.openai.baseUrl) return state
      return {
        ...state,
        openai: {
          baseUrl: action.baseUrl,
          models: [],
          model: '',
          reasoningEffort: '',
          maxToolCalls: state.openai.maxToolCalls,
        },
      }
    case 'settings/openai-models': {
      const models = toOpenAIModels([...state.openai.models, ...action.models])
      return {
        ...state,
        openai: withSelectedModel({
          ...state.openai,
          baseUrl: action.baseUrl,
          models,
        }, state.openai.model),
      }
    }
    case 'settings/openai-model-add': {
      const models = toOpenAIModels([...state.openai.models, action.model])
      return {
        ...state,
        openai: withSelectedModel({ ...state.openai, models }, action.model.id),
      }
    }
    case 'settings/openai-model':
      return {
        ...state,
        openai: withSelectedModel(state.openai, action.model),
      }
    case 'settings/openai-model-remove': {
      const models = state.openai.models.filter(model => model.id !== action.modelId)
      return {
        ...state,
        openai: withSelectedModel({ ...state.openai, models }, state.openai.model),
      }
    }
    case 'settings/openai-reasoning': {
      const selected = state.openai.models.find(model => model.id === state.openai.model)
      const capabilities = resolveModelCapabilities(state.openai.baseUrl, selected)
      if (!capabilities.reasoningEfforts.some(option => option.value === action.reasoningEffort)) {
        return state
      }
      return {
        ...state,
        openai: { ...state.openai, reasoningEffort: action.reasoningEffort },
      }
    }
    case 'settings/max-tool-calls':
      if (
        !Number.isInteger(action.maxToolCalls)
        || action.maxToolCalls < MIN_TOOL_CALLS_PER_RUN
        || action.maxToolCalls > MAX_TOOL_CALLS_PER_RUN
      ) return state
      return {
        ...state,
        openai: { ...state.openai, maxToolCalls: action.maxToolCalls },
      }
    case 'state/reset':
      return initialState
  }
}

const STORAGE_KEY = 'zlz-code-agent-web-v2'
const LEGACY_STORAGE_KEY = 'zlz-code-agent-web-v1'
const LEGACY_MOCK_WORKSPACE_ID = 'workspace-zlz-code'

function toOpenAIModels(value: unknown): OpenAIModel[] {
  if (!Array.isArray(value)) return []

  const models = new Map<string, OpenAIModel>()
  for (const item of value) {
    if (typeof item !== 'object' || item === null) continue
    const candidate = item as { id?: unknown; ownedBy?: unknown }
    if (typeof candidate.id !== 'string') continue
    const id = candidate.id.trim()
    if (!id || id.length > 256) continue
    const ownedBy = typeof candidate.ownedBy === 'string' && candidate.ownedBy.trim()
      ? candidate.ownedBy.trim().slice(0, 128)
      : undefined
    const existing = models.get(id)
    if (existing?.ownedBy !== undefined && ownedBy === undefined) continue
    models.set(id, ownedBy === undefined ? { id } : { id, ownedBy })
  }

  return [...models.values()].sort((left, right) => left.id.localeCompare(right.id))
}

function toOpenAIPublicSettings(value: unknown): OpenAIPublicSettings {
  if (typeof value !== 'object' || value === null) return initialState.openai
  const candidate = value as Partial<Record<keyof OpenAIPublicSettings, unknown>>
  const models = toOpenAIModels(candidate.models)
  const requestedModel = typeof candidate.model === 'string' ? candidate.model : ''
  const reasoningEffort = typeof candidate.reasoningEffort === 'string'
    ? candidate.reasoningEffort
    : ''
  const maxToolCalls = (
    typeof candidate.maxToolCalls === 'number'
    && Number.isInteger(candidate.maxToolCalls)
    && candidate.maxToolCalls >= MIN_TOOL_CALLS_PER_RUN
    && candidate.maxToolCalls <= MAX_TOOL_CALLS_PER_RUN
  ) ? candidate.maxToolCalls : DEFAULT_TOOL_CALLS_PER_RUN
  return withSelectedModel({
    baseUrl: typeof candidate.baseUrl === 'string'
      ? candidate.baseUrl
      : initialState.openai.baseUrl,
    models,
    model: requestedModel,
    reasoningEffort,
    maxToolCalls,
  }, requestedModel)
}

function normalizePersistedMessage(message: Message): Message {
  const {
    contextAttached: _legacyContextAttached,
    ...sanitized
  } = message as Message & { contextAttached?: unknown }
  if (sanitized.state === 'running') {
    return {
      ...sanitized,
      state: 'cancelled',
      statusLabel: '页面已刷新，运行已中断',
      tools: settleRunningTools(
        sanitized.tools,
        'cancelled',
        '页面已刷新，工具运行已中断',
      ),
    }
  }
  if (!sanitized.tools?.some(tool => tool.state === 'running')) return sanitized
  if (sanitized.state === 'cancelled') {
    const detail = sanitized.statusLabel === '页面已刷新，运行已中断'
      ? '页面已刷新，工具运行已中断'
      : '运行已停止'
    return {
      ...sanitized,
      tools: settleRunningTools(sanitized.tools, 'cancelled', detail),
    }
  }
  if (sanitized.state === 'error') {
    return {
      ...sanitized,
      tools: settleRunningTools(sanitized.tools, 'failed', '运行失败，工具未完成'),
    }
  }
  return {
    ...sanitized,
    tools: settleRunningTools(
      sanitized.tools,
      'failed',
      '运行已结束，但未收到工具完成事件',
    ),
  }
}

function withoutLegacyMockData(state: AppState): AppState {
  const workspaces = state.workspaces.filter(workspace => workspace.id !== LEGACY_MOCK_WORKSPACE_ID)
  const sessions = state.sessions
    .filter(session =>
      session.workspaceId !== LEGACY_MOCK_WORKSPACE_ID && session.messages.length > 0,
    )
    .map(session => ({
      ...session,
      messages: session.messages.map(normalizePersistedMessage),
    }))
  const activeSessionExists = sessions.some(session => session.id === state.activeSessionId)

  return {
    ...state,
    workspaces,
    sessions,
    activeWorkspaceId:
      state.activeWorkspaceId === LEGACY_MOCK_WORKSPACE_ID ? null : state.activeWorkspaceId,
    activeSessionId: activeSessionExists ? state.activeSessionId : null,
    openai: toOpenAIPublicSettings(state.openai),
  }
}

export function loadState(): AppState {
  try {
    localStorage.removeItem(LEGACY_STORAGE_KEY)
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === null) return initialState
    const saved = JSON.parse(raw) as Partial<AppState>
    return withoutLegacyMockData({
      ...initialState,
      ...saved,
      openai: toOpenAIPublicSettings(saved.openai),
    })
  } catch {
    return initialState
  }
}

export function saveState(state: AppState): void {
  localStorage.removeItem(LEGACY_STORAGE_KEY)
  localStorage.setItem(STORAGE_KEY, JSON.stringify(withoutLegacyMockData(state)))
}
