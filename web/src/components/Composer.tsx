import { ArrowUp, Folder, Settings2, Square } from 'lucide-react'
import type { KeyboardEvent } from 'react'
import {
  MAX_PROMPT_LENGTH,
  type OpenAIModel,
  type Workspace,
} from '../domain'
import type { ReasoningEffortOption } from '../model-capabilities'

interface ComposerProps {
  workspace: Workspace | undefined
  configured: boolean
  draft: string
  models: OpenAIModel[]
  model: string
  reasoningEffort: string
  reasoningEfforts: readonly ReasoningEffortOption[]
  running: boolean
  workspacePicking: boolean
  enterToSend: boolean
  hero?: boolean
  onDraftChange: (value: string) => void
  onModelChange: (value: string) => void
  onOpenSettings: () => void
  onReasoningEffortChange: (value: string) => void
  onChooseWorkspace: () => void
  onSubmit: () => void
  onCancel: () => void
}

export function Composer({
  workspace,
  configured,
  draft,
  models,
  model,
  reasoningEffort,
  reasoningEfforts,
  running,
  workspacePicking,
  enterToSend,
  hero = false,
  onDraftChange,
  onModelChange,
  onOpenSettings,
  onReasoningEffortChange,
  onChooseWorkspace,
  onSubmit,
  onCancel,
}: ComposerProps) {
  const promptLength = draft.trim().length
  const canSend = workspace !== undefined
    && configured
    && model.length > 0
    && promptLength > 0
    && promptLength <= MAX_PROMPT_LENGTH
    && !running
    && !workspacePicking
  const needsWorkspace = workspace === undefined
  const needsConfiguration = !configured
  const placeholder = workspacePicking
    ? '正在打开系统目录选择器…'
    : needsWorkspace
      ? '选择一个工作区开始'
      : needsConfiguration
        ? '先在设置中配置 OpenAI 连接'
        : '输入消息'

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!enterToSend || event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
    event.preventDefault()
    if (canSend) onSubmit()
  }

  return (
    <div className={`composerShell${hero ? ' composerHero' : ''}`}>
      <div className="composerMetaRow">
        <button className="workspacePicker" disabled={workspacePicking || running} onClick={onChooseWorkspace} type="button">
          <Folder size={16} />
          <span>{workspacePicking ? '正在打开…' : workspace?.name ?? '选择工作区'}</span>
        </button>
      </div>

      <div
        className={`composerCard${needsWorkspace ? ' composerNeedsWorkspace' : ''}${
          !needsWorkspace && needsConfiguration ? ' composerNeedsConfiguration' : ''
        }`}
        onClick={needsWorkspace && !workspacePicking && !running ? onChooseWorkspace : undefined}
      >
        <textarea
          aria-label="消息输入"
          disabled={needsWorkspace || needsConfiguration || running || workspacePicking}
          maxLength={MAX_PROMPT_LENGTH}
          onChange={event => onDraftChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          rows={hero ? 2 : 1}
          value={draft}
        />

        <div className="composerActions">
          {needsConfiguration && (
            <button
              className="configureOpenAIButton"
              onClick={event => {
                event.stopPropagation()
                onOpenSettings()
              }}
              type="button"
            >
              <Settings2 size={14} /> 配置 OpenAI
            </button>
          )}

          <div className="composerTrailing">
            {reasoningEfforts.length > 0 && (
              <select
                aria-label="推理强度"
                className="reasoningSelect"
                disabled={workspace === undefined || running || workspacePicking}
                onChange={event => onReasoningEffortChange(event.target.value)}
                value={reasoningEffort}
              >
                {reasoningEfforts.map(option => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            )}
            <select
              aria-label="模型"
              className="modelSelect"
              disabled={workspace === undefined || running || workspacePicking || models.length === 0}
              onChange={event => onModelChange(event.target.value)}
              value={model}
            >
              {models.length === 0 && <option value="">请先获取模型</option>}
              {models.map(option => (
                <option key={option.id} value={option.id}>{option.id}</option>
              ))}
            </select>
            {running ? (
              <button aria-label="停止运行" className="sendButton stopButton" onClick={onCancel} type="button">
                <Square fill="currentColor" size={13} />
              </button>
            ) : (
              <button
                aria-label="发送消息"
                className="sendButton"
                disabled={!canSend}
                onClick={event => {
                  event.stopPropagation()
                  onSubmit()
                }}
                type="button"
              >
                <ArrowUp size={19} strokeWidth={2.4} />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
