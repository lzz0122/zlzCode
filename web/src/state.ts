import {
  applyAgentEvent,
  DEFAULT_TOOL_CALLS_PER_RUN,
  MAX_TOOL_CALLS_PER_RUN,
  MIN_TOOL_CALLS_PER_RUN,
  normalizeOpenAIModels,
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
  | { type: 'session/hydrate'; session: Session }
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
    case 'session/hydrate':
      return {
        ...state,
        sessions: state.sessions.some(session => session.id === action.session.id)
          ? state.sessions.map(session => session.id === action.session.id
              ? action.session
              : session)
          : [action.session, ...state.sessions],
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
      const models = normalizeOpenAIModels([...state.openai.models, ...action.models])
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
      const models = normalizeOpenAIModels([...state.openai.models, action.model])
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

const STORAGE_KEY = 'zlz-code-agent-web-v3'
const PREVIOUS_STORAGE_KEY = 'zlz-code-agent-web-v2'
const LEGACY_STORAGE_KEY = 'zlz-code-agent-web-v1'
const LEGACY_MOCK_WORKSPACE_ID = 'workspace-zlz-code'

function toOpenAIPublicSettings(value: unknown): OpenAIPublicSettings {
  if (typeof value !== 'object' || value === null) return initialState.openai
  const candidate = value as Partial<Record<keyof OpenAIPublicSettings, unknown>>
  const models = normalizeOpenAIModels(candidate.models)
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

function localState(state: AppState, keepSessionIndex: boolean): AppState {
  const workspaces = state.workspaces.filter(workspace => workspace.id !== LEGACY_MOCK_WORKSPACE_ID)
  const sessions = keepSessionIndex
    ? state.sessions
        .filter(session => session.workspaceId !== LEGACY_MOCK_WORKSPACE_ID)
        .map(session => ({ ...session, messages: [] }))
    : []
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
    const current = localStorage.getItem(STORAGE_KEY)
    const raw = current ?? localStorage.getItem(PREVIOUS_STORAGE_KEY)
    if (raw === null) return initialState
    const saved = JSON.parse(raw) as Partial<AppState>
    const restored = localState({
      ...initialState,
      ...saved,
      openai: toOpenAIPublicSettings(saved.openai),
    }, current !== null)
    localStorage.removeItem(PREVIOUS_STORAGE_KEY)
    return restored
  } catch {
    localStorage.removeItem(PREVIOUS_STORAGE_KEY)
    return initialState
  }
}

export function saveState(state: AppState): void {
  localStorage.removeItem(LEGACY_STORAGE_KEY)
  localStorage.removeItem(PREVIOUS_STORAGE_KEY)
  localStorage.setItem(STORAGE_KEY, JSON.stringify(localState(state, true)))
}
