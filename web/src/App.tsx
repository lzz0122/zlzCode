import {
  CircleAlert,
  Eye,
  EyeOff,
  LoaderCircle,
  Moon,
  RefreshCw,
  RotateCcw,
  Server,
  Sun,
  X,
} from 'lucide-react'
import { useEffect, useReducer, useRef, useState } from 'react'
import { createAgentGateway } from './agent-gateway'
import { Conversation } from './components/Conversation'
import { Modal } from './components/Modal'
import { Sidebar } from './components/Sidebar'
import {
  createId,
  DEFAULT_TOOL_CALLS_PER_RUN,
  MAX_TOOL_CALLS_PER_RUN,
  MIN_TOOL_CALLS_PER_RUN,
  titleFromPrompt,
  type Message,
  type OpenAIConnectionInput,
  type RunRequest,
  type Session,
  type ThemePreference,
  type Workspace,
} from './domain'
import { resolveModelCapabilities } from './model-capabilities'
import {
  clearOpenAIApiKey,
  loadOpenAIApiKey,
  saveOpenAIApiKey,
} from './openai-credentials'
import { appReducer, loadState, saveState } from './state'

function resolveTheme(preference: ThemePreference): 'light' | 'dark' {
  if (preference !== 'system') return preference
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const gateway = createAgentGateway()

type ModelFetchFeedback =
  | { state: 'idle'; message: '' }
  | { state: 'loading'; message: string }
  | { state: 'success'; message: string }
  | { state: 'error'; message: string }

interface ActiveRunOwner {
  controller: AbortController
  runId: string | null
  sessionId: string
  messageId: string
}

const idleModelFetchFeedback: ModelFetchFeedback = { state: 'idle', message: '' }

export function normalizeOpenAIBaseUrl(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) throw new Error('请填写 OpenAI Base URL')

  let url: URL
  try {
    url = new URL(trimmed)
  } catch {
    throw new Error('OpenAI Base URL 格式无效')
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('OpenAI Base URL 只支持 http 或 https')
  }
  if (url.username || url.password) {
    throw new Error('OpenAI Base URL 不能包含用户名或密码')
  }
  if (url.search || url.hash) {
    throw new Error('OpenAI Base URL 不能包含 query 或 fragment')
  }

  return url.toString().replace(/\/+$/, '')
}

export function createOpenAIRunConfiguration(
  baseUrl: string,
  apiKey: string,
  model: string,
  reasoningEffort = '',
  maxToolCalls = DEFAULT_TOOL_CALLS_PER_RUN,
): Pick<RunRequest, 'model' | 'reasoningEffort' | 'maxToolCalls' | 'openai'> {
  const normalizedBaseUrl = normalizeOpenAIBaseUrl(baseUrl)
  const normalizedApiKey = apiKey.trim()
  if (!normalizedApiKey) throw new Error('请填写 OpenAI API Key')

  const normalizedModel = model.trim()
  if (!normalizedModel) throw new Error('请先获取或手动添加模型')
  if (normalizedModel.length > 256) throw new Error('模型 ID 不能超过 256 个字符')

  const openai: OpenAIConnectionInput = {
    baseUrl: normalizedBaseUrl,
    apiKey: normalizedApiKey,
  }
  const normalizedReasoningEffort = reasoningEffort.trim()
  if (normalizedReasoningEffort.length > 64) {
    throw new Error('推理强度名称不能超过 64 个字符')
  }
  if (
    !Number.isInteger(maxToolCalls)
    || maxToolCalls < MIN_TOOL_CALLS_PER_RUN
    || maxToolCalls > MAX_TOOL_CALLS_PER_RUN
  ) {
    throw new Error(`每次运行的工具调用上限必须是 ${MIN_TOOL_CALLS_PER_RUN}–${MAX_TOOL_CALLS_PER_RUN} 的整数`)
  }

  return {
    model: normalizedModel,
    maxToolCalls,
    openai,
    ...(normalizedReasoningEffort ? { reasoningEffort: normalizedReasoningEffort } : {}),
  }
}

export function isOpenAIRunConfigured(
  baseUrl: string,
  apiKey: string,
  model: string,
  reasoningEffort = '',
  maxToolCalls = DEFAULT_TOOL_CALLS_PER_RUN,
): boolean {
  try {
    createOpenAIRunConfiguration(baseUrl, apiKey, model, reasoningEffort, maxToolCalls)
    return true
  } catch {
    return false
  }
}

interface OpenAIApiKeyInputProps {
  value: string
  visible: boolean
  onChange: (value: string) => void
  onToggleVisibility: () => void
}

export function OpenAIApiKeyInput({
  value,
  visible,
  onChange,
  onToggleVisibility,
}: OpenAIApiKeyInputProps) {
  const visibilityLabel = visible ? '隐藏 API Key' : '显示 API Key'

  return (
    <div className="secretInput">
      <input
        aria-label="OpenAI API Key"
        autoCapitalize="none"
        autoComplete="off"
        autoCorrect="off"
        onChange={event => onChange(event.target.value)}
        placeholder="sk-..."
        spellCheck={false}
        type={visible ? 'text' : 'password'}
        value={value}
      />
      <button
        aria-label={visibilityLabel}
        aria-pressed={visible}
        className="secretToggle"
        onClick={onToggleVisibility}
        title={visibilityLabel}
        type="button"
      >
        {visible ? <EyeOff size={16} /> : <Eye size={16} />}
      </button>
    </div>
  )
}

export default function App() {
  const [state, dispatch] = useReducer(appReducer, undefined, loadState)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [search, setSearch] = useState('')
  const [draft, setDraft] = useState('')
  const [view, setView] = useState<'conversation' | 'trajectory'>('conversation')
  const [workspacePicking, setWorkspacePicking] = useState(false)
  const [workspaceError, setWorkspaceError] = useState<string | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [apiKey, setApiKey] = useState(loadOpenAIApiKey)
  const [apiKeyVisible, setApiKeyVisible] = useState(false)
  const [manualModel, setManualModel] = useState('')
  const [modelFetchFeedback, setModelFetchFeedback] = useState<ModelFetchFeedback>(
    idleModelFetchFeedback,
  )
  const workspacePickerRequest = useRef<Promise<Workspace | undefined> | null>(null)
  const sessionCreationRequest = useRef<Promise<Session> | null>(null)
  const hydratedSessions = useRef(new Set<string>())
  const activeRun = useRef<ActiveRunOwner | null>(null)
  const [activeRunOwner, setActiveRunOwner] = useState<ActiveRunOwner | null>(null)
  const modelFetchController = useRef<AbortController | null>(null)

  const activeWorkspace = state.workspaces.find(workspace => workspace.id === state.activeWorkspaceId)
  const activeSession = state.sessions.find(session => session.id === state.activeSessionId)
  const running = activeRunOwner !== null
  const selectedModel = state.openai.models.find(model => model.id === state.openai.model)
  const selectedCapabilities = resolveModelCapabilities(state.openai.baseUrl, selectedModel)
  const modelsLoading = modelFetchFeedback.state === 'loading'
  const openAIConfigured = isOpenAIRunConfigured(
    state.openai.baseUrl,
    apiKey,
    state.openai.model,
    state.openai.reasoningEffort,
    state.openai.maxToolCalls,
  )

  useEffect(() => saveState(state), [state])

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const applyTheme = () => {
      const theme = resolveTheme(state.theme)
      document.documentElement.dataset.theme = theme
      document.documentElement.style.colorScheme = theme
    }
    applyTheme()
    media.addEventListener('change', applyTheme)
    return () => media.removeEventListener('change', applyTheme)
  }, [state.theme])

  useEffect(() => {
    setView('conversation')
  }, [state.activeSessionId])

  useEffect(() => {
    const sessionId = state.activeSessionId
    if (
      sessionId === null
      || hydratedSessions.current.has(sessionId)
      || activeRun.current !== null
    ) return
    let cancelled = false
    void gateway.readSession(sessionId)
      .then(session => {
        if (cancelled || activeRun.current !== null) return
        hydratedSessions.current.add(session.id)
        dispatch({ type: 'session/hydrate', session })
      })
      .catch(error => {
        if (cancelled) return
        setWorkspaceError(error instanceof Error ? error.message : '无法读取会话')
      })
    return () => {
      cancelled = true
    }
  }, [state.activeSessionId])

  useEffect(() => () => modelFetchController.current?.abort(), [])
  useEffect(() => () => activeRun.current?.controller.abort(), [])

  const pickWorkspace = (): Promise<Workspace | undefined> => {
    if (activeRun.current !== null) return Promise.resolve(undefined)
    if (workspacePickerRequest.current !== null) return workspacePickerRequest.current

    setWorkspaceError(null)
    setWorkspacePicking(true)
    const request = gateway.pickWorkspace()
      .then(workspace => {
        if (workspace === null || activeRun.current !== null) return undefined
        dispatch({ type: 'workspace/add', workspace })
        return workspace
      })
      .catch(error => {
        setWorkspaceError(error instanceof Error ? error.message : '无法打开系统目录选择器')
        return undefined
      })
      .finally(() => {
        workspacePickerRequest.current = null
        setWorkspacePicking(false)
      })

    workspacePickerRequest.current = request
    return request
  }

  const selectWorkspace = (workspaceId: string) => {
    if (activeRun.current !== null) return
    dispatch({ type: 'workspace/select', workspaceId })
    setDraft('')
    setView('conversation')
  }

  const selectSession = (sessionId: string) => {
    if (activeRun.current !== null) return
    hydratedSessions.current.delete(sessionId)
    dispatch({ type: 'session/select', sessionId })
  }

  const newSession = async () => {
    if (activeRun.current !== null) return
    const workspace = activeWorkspace ?? await pickWorkspace()
    if (workspace !== undefined) selectWorkspace(workspace.id)
  }

  const submit = async () => {
    const prompt = draft.trim()
    if (
      !prompt
      || activeWorkspace === undefined
      || activeRun.current !== null
      || workspacePicking
      || workspacePickerRequest.current !== null
    ) return

    let runConfiguration: Pick<RunRequest, 'model' | 'reasoningEffort' | 'maxToolCalls' | 'openai'>
    try {
      runConfiguration = createOpenAIRunConfiguration(
        state.openai.baseUrl,
        apiKey,
        state.openai.model,
        state.openai.reasoningEffort,
        state.openai.maxToolCalls,
      )
    } catch (error) {
      setModelFetchFeedback({
        state: 'error',
        message: error instanceof Error ? error.message : 'OpenAI 配置无效',
      })
      setSettingsOpen(true)
      return
    }

    let session = activeSession
    if (session === undefined || session.workspaceId !== activeWorkspace.id) {
      if (sessionCreationRequest.current !== null) return
      setWorkspaceError(null)
      const creation = gateway.createSession(activeWorkspace.id)
      sessionCreationRequest.current = creation
      try {
        session = await creation
        if (activeRun.current !== null) return
        hydratedSessions.current.add(session.id)
        dispatch({ type: 'session/create', session })
      } catch (error) {
        setWorkspaceError(error instanceof Error ? error.message : '无法创建会话')
        return
      } finally {
        if (sessionCreationRequest.current === creation) {
          sessionCreationRequest.current = null
        }
      }
    }

    const userMessage: Message = {
      id: createId('message'),
      role: 'user',
      content: prompt,
      createdAt: Date.now(),
      state: 'complete',
    }
    const assistantMessage: Message = {
      id: createId('message'),
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      state: 'running',
      statusLabel: '正在发送',
    }
    const title = session.messages.length === 0 ? titleFromPrompt(prompt) : undefined

    dispatch({ type: 'message/append', sessionId: session.id, message: userMessage, title })
    dispatch({ type: 'message/append', sessionId: session.id, message: assistantMessage })
    setDraft('')
    setView('conversation')

    const controller = new AbortController()
    const owner: ActiveRunOwner = {
      controller,
      runId: null,
      sessionId: session.id,
      messageId: assistantMessage.id,
    }
    activeRun.current = owner
    setActiveRunOwner(owner)

    try {
      for await (const event of gateway.run({
        sessionId: session.id,
        idempotencyKey: createId('run'),
        prompt,
        ...runConfiguration,
      }, controller.signal)) {
        if (event.type === 'run_started') {
          const startedOwner: ActiveRunOwner = { ...owner, runId: event.runId }
          activeRun.current = startedOwner
          setActiveRunOwner(startedOwner)
          continue
        }
        dispatch({ type: 'run/event', sessionId: session.id, messageId: assistantMessage.id, event })
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        dispatch({ type: 'run/cancel', sessionId: session.id, messageId: assistantMessage.id })
      } else {
        const message = error instanceof Error ? error.message : '未知 Agent 错误'
        dispatch({
          type: 'run/event',
          sessionId: session.id,
          messageId: assistantMessage.id,
          event: { type: 'error', message },
        })
      }
    } finally {
      if (activeRun.current?.controller === controller) {
        activeRun.current = null
        setActiveRunOwner(current => current?.controller === controller ? null : current)
      }
    }
  }

  const cancelRun = () => activeRun.current?.controller.abort()

  const decideConfirmation = async (
    confirmationId: string,
    decision: 'approve' | 'reject',
  ) => {
    const owner = activeRun.current
    if (owner === null || owner.runId === null) return
    dispatch({
      type: 'run/confirmation-state',
      sessionId: owner.sessionId,
      messageId: owner.messageId,
      confirmationId,
      submitting: true,
    })
    try {
      await gateway.decideConfirmation(
        owner.runId,
        owner.sessionId,
        confirmationId,
        decision,
      )
    } catch (error) {
      dispatch({
        type: 'run/confirmation-state',
        sessionId: owner.sessionId,
        messageId: owner.messageId,
        confirmationId,
        submitting: false,
        error: error instanceof Error ? error.message : '确认请求失败',
      })
    }
  }

  const updateBaseUrl = (baseUrl: string) => {
    dispatch({ type: 'settings/openai-base-url', baseUrl })
    setModelFetchFeedback(idleModelFetchFeedback)
  }

  const updateApiKey = (value: string) => {
    setApiKey(value)
    saveOpenAIApiKey(value)
    if (modelFetchFeedback.state === 'error') {
      setModelFetchFeedback(idleModelFetchFeedback)
    }
  }

  const fetchModels = async () => {
    const key = apiKey.trim()
    if (!key) {
      setModelFetchFeedback({ state: 'error', message: '请填写 OpenAI API Key' })
      return
    }

    let baseUrl: string
    try {
      baseUrl = normalizeOpenAIBaseUrl(state.openai.baseUrl)
    } catch (error) {
      setModelFetchFeedback({
        state: 'error',
        message: error instanceof Error ? error.message : 'OpenAI Base URL 格式无效',
      })
      return
    }

    modelFetchController.current?.abort()
    const controller = new AbortController()
    modelFetchController.current = controller
    setModelFetchFeedback({ state: 'loading', message: '正在获取可用模型…' })

    try {
      const models = await gateway.listModels({ baseUrl, apiKey: key }, controller.signal)
      dispatch({ type: 'settings/openai-models', baseUrl, models })
      setModelFetchFeedback(models.length > 0
        ? { state: 'success', message: `已获取 ${models.length} 个模型` }
        : { state: 'error', message: '该地址没有返回可用模型' })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      setModelFetchFeedback({
        state: 'error',
        message: error instanceof Error ? error.message : '获取模型失败',
      })
    } finally {
      if (modelFetchController.current === controller) modelFetchController.current = null
    }
  }

  const removeModel = (modelId: string) => {
    dispatch({ type: 'settings/openai-model-remove', modelId })
    setModelFetchFeedback(idleModelFetchFeedback)
  }

  const addManualModel = () => {
    const modelId = manualModel.trim()
    if (!modelId) return
    if (modelId.length > 256) {
      setModelFetchFeedback({ state: 'error', message: '模型 ID 不能超过 256 个字符' })
      return
    }

    dispatch({ type: 'settings/openai-model-add', model: { id: modelId } })
    setManualModel('')
    setModelFetchFeedback({ state: 'success', message: `已添加模型 ${modelId}` })
  }

  const resetLocalState = () => {
    activeRun.current?.controller.abort()
    modelFetchController.current?.abort()
    modelFetchController.current = null
    dispatch({ type: 'state/reset' })
    clearOpenAIApiKey()
    setApiKey('')
    setApiKeyVisible(false)
    setManualModel('')
    setModelFetchFeedback(idleModelFetchFeedback)
  }

  return (
    <div className={`appFrame${sidebarCollapsed ? ' appFrameCollapsed' : ''}`}>
      <Sidebar
        activeSessionId={state.activeSessionId}
        activeWorkspaceId={state.activeWorkspaceId}
        collapsed={sidebarCollapsed}
        onAddWorkspace={() => void pickWorkspace()}
        onNewSession={() => void newSession()}
        onOpenSettings={() => setSettingsOpen(true)}
        onSearchChange={setSearch}
        onSelectSession={selectSession}
        onSelectWorkspace={selectWorkspace}
        onToggle={() => setSidebarCollapsed(value => !value)}
        navigationDisabled={running}
        search={search}
        sessions={state.sessions}
        workspaces={state.workspaces}
      />

      <Conversation
        configured={openAIConfigured}
        draft={draft}
        enterToSend={state.enterToSend}
        model={state.openai.model}
        models={state.openai.models}
        onCancel={cancelRun}
        onConfirm={decideConfirmation}
        onChooseWorkspace={() => void pickWorkspace()}
        onDraftChange={setDraft}
        onModelChange={model => dispatch({ type: 'settings/openai-model', model })}
        onOpenSettings={() => setSettingsOpen(true)}
        onReasoningEffortChange={reasoningEffort => dispatch({
          type: 'settings/openai-reasoning',
          reasoningEffort,
        })}
        onSubmit={() => void submit()}
        onViewChange={setView}
        reasoningEffort={state.openai.reasoningEffort}
        reasoningEfforts={selectedCapabilities.reasoningEfforts}
        running={running}
        session={activeSession}
        view={view}
        workspace={activeWorkspace}
        workspacePicking={workspacePicking}
      />

      {workspacePicking && (
        <div aria-live="polite" className="workspacePickerNotice" role="status">
          正在打开系统目录选择器…
        </div>
      )}

      {workspaceError !== null && !workspacePicking && (
        <div className="workspacePickerNotice workspacePickerNoticeError" role="alert">
          <span>{workspaceError}</span>
          <button onClick={() => setWorkspaceError(null)} type="button">关闭</button>
        </div>
      )}

      {settingsOpen && (
        <Modal onClose={() => setSettingsOpen(false)} title="设置">
          <div className="settingsGrid">
            <section className="settingsSection">
              <h3>Agent 连接</h3>
              <div className="settingsFields">
                <div className="runtimeCard">
                  <span className="runtimeIcon"><Server size={18} /></span>
                  <div>
                    <strong>{gateway.label}</strong>
                    <small>通过 HTTP/SSE 接收 Python Agent 事件；运行状态会在发起请求时反馈。</small>
                  </div>
                </div>
                <label className="settingsField">
                  <span>每次运行最大工具调用数</span>
                  <input
                    aria-label="每次运行最大工具调用数"
                    inputMode="numeric"
                    max={MAX_TOOL_CALLS_PER_RUN}
                    min={MIN_TOOL_CALLS_PER_RUN}
                    onChange={event => {
                      const maxToolCalls = event.currentTarget.valueAsNumber
                      if (
                        Number.isInteger(maxToolCalls)
                        && maxToolCalls >= MIN_TOOL_CALLS_PER_RUN
                        && maxToolCalls <= MAX_TOOL_CALLS_PER_RUN
                      ) {
                        dispatch({ type: 'settings/max-tool-calls', maxToolCalls })
                      }
                    }}
                    step={1}
                    type="number"
                    value={state.openai.maxToolCalls}
                  />
                </label>
              </div>
              <p className="settingsHint">
                可设置 {MIN_TOOL_CALLS_PER_RUN}–{MAX_TOOL_CALLS_PER_RUN}，默认 {DEFAULT_TOOL_CALLS_PER_RUN}。
                超出额度的调用不会执行，而会作为 <code>TOOL_CALL_LIMIT_EXCEEDED</code> 失败结果返回模型继续处理。
              </p>
            </section>

            <section className="settingsSection">
              <div className="settingsSectionTitle">
                <h3>OpenAI 模型连接</h3>
                <span
                  className={`settingsStatus${openAIConfigured ? ' settingsStatusConfigured' : ''}`}
                >
                  {openAIConfigured ? '已配置' : '未配置'}
                </span>
              </div>

              <div className="settingsFields">
                <label className="settingsField">
                  <span>Base URL</span>
                  <input
                    aria-label="OpenAI Base URL"
                    autoCapitalize="none"
                    autoCorrect="off"
                    inputMode="url"
                    onChange={event => updateBaseUrl(event.target.value)}
                    placeholder="https://api.openai.com/v1"
                    spellCheck={false}
                    type="url"
                    value={state.openai.baseUrl}
                  />
                </label>

                <label className="settingsField">
                  <span>API Key</span>
                  <OpenAIApiKeyInput
                    onChange={updateApiKey}
                    onToggleVisibility={() => setApiKeyVisible(value => !value)}
                    value={apiKey}
                    visible={apiKeyVisible}
                  />
                </label>

                <div className="settingsModelToolbar">
                  <div>
                    <span>可用模型</span>
                    <small>从当前 Base URL 的 <code>/models</code> 接口获取</small>
                  </div>
                  <button
                    className="modelFetchButton"
                    disabled={modelsLoading || !state.openai.baseUrl.trim() || !apiKey.trim()}
                    onClick={() => void fetchModels()}
                    type="button"
                  >
                    {modelsLoading
                      ? <LoaderCircle className="spin" size={15} />
                      : <RefreshCw size={15} />}
                    {modelsLoading ? '获取中…' : '获取模型'}
                  </button>
                </div>

                <label className="settingsField">
                  <span>手动添加模型</span>
                  <div className="manualModelInput">
                    <input
                      aria-label="手动模型 ID"
                      autoCapitalize="none"
                      autoCorrect="off"
                      maxLength={256}
                      onChange={event => setManualModel(event.target.value)}
                      onKeyDown={event => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          addManualModel()
                        }
                      }}
                      placeholder="适用于不支持 /models 的服务"
                      spellCheck={false}
                      type="text"
                      value={manualModel}
                    />
                    <button
                      disabled={!manualModel.trim()}
                      onClick={addManualModel}
                      type="button"
                    >添加</button>
                  </div>
                </label>

                {modelFetchFeedback.state !== 'idle' && (
                  <div
                    className={`settingsFeedback settingsFeedback-${modelFetchFeedback.state}`}
                    role={modelFetchFeedback.state === 'error' ? 'alert' : 'status'}
                  >
                    {modelFetchFeedback.state === 'error' && <CircleAlert size={14} />}
                    <span>{modelFetchFeedback.message}</span>
                  </div>
                )}

                {state.openai.models.length === 0 ? (
                  <div className="settingsModelEmpty">
                    可填写 Base URL 和 API Key 后获取模型，也可直接手动添加模型 ID。
                  </div>
                ) : (
                  <ul className="settingsModelList">
                    {state.openai.models.map(model => {
                      const capabilities = resolveModelCapabilities(state.openai.baseUrl, model)
                      return (
                        <li className="settingsModelItem" key={model.id}>
                          <div className="settingsModelInfo">
                            <div>
                              <strong>{model.id}</strong>
                              {state.openai.model === model.id && (
                                <span className="settingsCurrentModel">当前</span>
                              )}
                            </div>
                            <small>
                              {capabilities.provider}
                              {capabilities.reasoningEfforts.length > 0
                                ? ` · 推理强度 ${capabilities.reasoningEfforts.map(option => option.value).join(' / ')}`
                                : ' · 推理强度未适配'}
                            </small>
                          </div>
                          <button
                            aria-label={`移除模型 ${model.id}`}
                            className="settingsModelRemove"
                            onClick={() => removeModel(model.id)}
                            title={`移除 ${model.id}`}
                            type="button"
                          >
                            <X size={15} />
                          </button>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </div>

              <p className="settingsHint">
                Base URL 是 OpenAI 模型服务地址，不是本机 CodeAgent 后端地址
                （<code>VITE_AGENT_API_URL</code>）。
              </p>
              <p className="settingsHint">
                Base URL、API Key、模型列表和当前选择会保存在当前浏览器本地；仅适合可信的本机环境，重置会一并清除。
              </p>
              <p className="settingsHint">
                <code>/models</code> 不提供统一能力元数据；当前仅自动适配 DeepSeek 推理强度，其他模型需自行确认是否支持 Chat Completions。
              </p>
            </section>

            <section className="settingsSection">
              <h3>外观</h3>
              <div className="themeOptions">
                {([
                  ['light', '浅色', Sun],
                  ['dark', '深色', Moon],
                  ['system', '跟随系统', Server],
                ] as const).map(([value, label, Icon]) => (
                  <button
                    aria-pressed={state.theme === value}
                    className={state.theme === value ? 'selected' : ''}
                    key={value}
                    onClick={() => dispatch({ type: 'settings/theme', theme: value })}
                    type="button"
                  >
                    <Icon size={17} /> {label}
                  </button>
                ))}
              </div>
            </section>

            <section className="settingsSection settingsRow">
              <div>
                <h3>Enter 发送</h3>
                <p>开启后 Enter 发送，Shift + Enter 换行。</p>
              </div>
              <button
                aria-checked={state.enterToSend}
                className={`switch${state.enterToSend ? ' switchOn' : ''}`}
                onClick={() => dispatch({ type: 'settings/enter', enterToSend: !state.enterToSend })}
                role="switch"
                type="button"
              ><span /></button>
            </section>

            <section className="settingsSection settingsDanger">
              <div>
                <h3>本地会话数据</h3>
                <p>清空浏览器中的工作区引用和会话记录。</p>
              </div>
              <button className="secondaryButton" onClick={resetLocalState} type="button">
                <RotateCcw size={15} /> 重置
              </button>
            </section>

            <footer className="settingsFooter">
              Web UI ported from DeepSeek Harness commit <code>47f9438</code> under MIT.
            </footer>
          </div>
        </Modal>
      )}
    </div>
  )
}
