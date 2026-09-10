import {
  Ban,
  Check,
  ChevronRight,
  CircleAlert,
  Clock3,
  LoaderCircle,
  MessageSquareText,
  Route,
  TerminalSquare,
  X,
} from 'lucide-react'
import { useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  type Message,
  type OpenAIModel,
  type Session,
  type ToolStep,
  type Workspace,
} from '../domain'
import type { ReasoningEffortOption } from '../model-capabilities'
import { BrandMark } from './Brand'
import { Composer } from './Composer'

interface ConversationProps {
  session: Session | undefined
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
  view: 'conversation' | 'trajectory'
  onViewChange: (view: 'conversation' | 'trajectory') => void
  onDraftChange: (value: string) => void
  onModelChange: (value: string) => void
  onOpenSettings: () => void
  onReasoningEffortChange: (value: string) => void
  onChooseWorkspace: () => void
  onSubmit: () => void
  onCancel: () => void
  onConfirm: (confirmationId: string, decision: 'approve' | 'reject') => void
}

function ToolCard({
  tool,
  onConfirm,
}: {
  tool: ToolStep
  onConfirm: ConversationProps['onConfirm']
}) {
  const icon = tool.state === 'running' || tool.state === 'awaiting_confirmation'
    ? <LoaderCircle size={14} />
    : tool.state === 'failed' || tool.state === 'expired'
      ? <CircleAlert size={14} />
      : tool.state === 'cancelled' || tool.state === 'rejected'
        ? <Ban size={14} />
        : <Check size={14} />

  return (
    <details
      className={`toolCard toolCard-${tool.state}`}
      open={
        tool.state === 'awaiting_confirmation'
        || tool.state === 'running'
        || tool.state === 'failed'
        || tool.state === 'cancelled'
      }
    >
      <summary>
        <span className={`toolState toolState-${tool.state}`}>
          {icon}
        </span>
        <TerminalSquare size={16} />
        <span>{tool.label}</span>
        <ChevronRight className="toolChevron" size={15} />
      </summary>
      {tool.detail && <pre>{tool.detail}</pre>}
      {tool.confirmation && (
        <div className="confirmationPanel">
          <div className="confirmationMeta">
            <code>{tool.confirmation.path}</code>
            <span>{tool.confirmation.summary}</span>
          </div>
          <pre className="confirmationDiff">{tool.confirmation.diff}</pre>
          {tool.confirmation.error && (
            <div className="confirmationError">{tool.confirmation.error}</div>
          )}
          {tool.state === 'awaiting_confirmation' && (
            <div className="confirmationActions">
              <button
                className="confirmationReject"
                disabled={tool.confirmation.submitting}
                onClick={() => onConfirm(tool.confirmation!.id, 'reject')}
                type="button"
              >
                <X size={14} /> 拒绝
              </button>
              <button
                className="confirmationApprove"
                disabled={tool.confirmation.submitting}
                onClick={() => onConfirm(tool.confirmation!.id, 'approve')}
                type="button"
              >
                {tool.confirmation.submitting
                  ? <LoaderCircle className="spin" size={14} />
                  : <Check size={14} />}
                批准
              </button>
            </div>
          )}
        </div>
      )}
    </details>
  )
}

export function AssistantMarkdown({ content }: { content: string }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml>
      {content}
    </ReactMarkdown>
  )
}

function AssistantMessage({
  message,
  onConfirm,
}: {
  message: Message
  onConfirm: ConversationProps['onConfirm']
}) {
  return (
    <article className="assistantMessage">
      <div className="assistantMarker"><BrandMark size={22} /></div>
      <div className="assistantBody">
        {(message.state === 'running' || message.statusLabel) && (
          <div className={`runStatus runStatus-${message.state}`}>
            {message.state === 'running' && <LoaderCircle className="spin" size={15} />}
            {message.state === 'error' && <CircleAlert size={15} />}
            {message.state === 'complete' && <Check size={15} />}
            <span>{message.statusLabel ?? '正在运行'}</span>
          </div>
        )}
        {(message.tools ?? []).length > 0 && (
          <div className="toolList">
            {message.tools?.map(tool => (
              <ToolCard key={tool.id} tool={tool} onConfirm={onConfirm} />
            ))}
          </div>
        )}
        {message.content && (
          <div className="messageContent">
            <AssistantMarkdown content={message.content} />
          </div>
        )}
        {message.state === 'error' && (message.errorCode || message.errorRetryable !== undefined) && (
          <div className="messageMetrics">
            {message.errorCode && <code>{message.errorCode}</code>}
            {message.errorRetryable !== undefined && (
              <span>{message.errorRetryable ? '可重新发起' : '不建议直接重试'}</span>
            )}
          </div>
        )}
        {message.metrics && (
          <div className="messageMetrics">
            <span><Route size={13} /> {message.metrics.steps} 步</span>
            <span><Clock3 size={13} /> {(message.metrics.durationMs / 1000).toFixed(1)} 秒</span>
          </div>
        )}
      </div>
    </article>
  )
}

function Trajectory({ session }: { session: Session }) {
  const tools = session.messages.flatMap(message => message.tools ?? [])
  return (
    <div className="trajectoryView">
      <div className="trajectoryIntro">
        <Route size={19} />
        <div>
          <strong>执行轨迹</strong>
          <span>按发生顺序查看 Agent 状态和工具调用</span>
        </div>
      </div>
      {tools.length === 0 ? (
        <div className="emptyTrajectory">发送一条任务后，这里会展示工具执行轨迹。</div>
      ) : (
        <ol className="trajectoryList">
          {tools.map((tool, index) => (
            <li key={`${tool.id}-${index}`}>
              <span className={`trajectoryDot trajectoryDot-${tool.state}`} />
              <div>
                <strong>{tool.label}</strong>
                {tool.detail && <code>{tool.detail}</code>}
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

export function Conversation(props: ConversationProps) {
  const { session, workspace, view } = props
  const scrollRef = useRef<HTMLDivElement>(null)
  const hasMessages = (session?.messages.length ?? 0) > 0

  useEffect(() => {
    if (view === 'conversation') {
      scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
    }
  }, [session?.messages, view])

  return (
    <main className="conversationRoot">
      {hasMessages && session && (
        <header className="conversationHeader">
          <div className="conversationTitle">
            <span>{workspace?.name ?? '工作区'}</span>
            <span>/</span>
            <strong>{session.title}</strong>
          </div>
          <div className="conversationTabs">
            <button
              className={view === 'conversation' ? 'active' : ''}
              onClick={() => props.onViewChange('conversation')}
              type="button"
            >
              <MessageSquareText size={15} /> 对话
            </button>
            <button
              className={view === 'trajectory' ? 'active' : ''}
              onClick={() => props.onViewChange('trajectory')}
              type="button"
            >
              <Route size={15} /> 轨迹
            </button>
          </div>
        </header>
      )}

      {!hasMessages ? (
        <div className="heroArea">
          <div className="heroGlow" />
          <div className="heroHeadline">
            <BrandMark className="heroLogo" size={36} />
            <h1>探索未至之境</h1>
          </div>
          <Composer {...props} hero />
        </div>
      ) : view === 'trajectory' && session ? (
        <Trajectory session={session} />
      ) : (
        <div className="messageScroll" ref={scrollRef}>
          <div className="messageColumn">
            {session?.messages.map(message => message.role === 'user' ? (
              <article className="userMessage" key={message.id}>
                <span>{message.content}</span>
              </article>
            ) : (
              <AssistantMessage key={message.id} message={message} onConfirm={props.onConfirm} />
            ))}
          </div>
        </div>
      )}

      {hasMessages && (
        <div className="composerDock">
          <Composer {...props} />
        </div>
      )}
    </main>
  )
}
