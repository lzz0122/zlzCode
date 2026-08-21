import {
  ChevronDown,
  Folder,
  FolderPlus,
  MessageSquarePlus,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Settings,
} from 'lucide-react'
import type { Session, Workspace } from '../domain'
import { BrandMark, BrandWordmark } from './Brand'

interface SidebarProps {
  collapsed: boolean
  workspaces: Workspace[]
  sessions: Session[]
  activeWorkspaceId: string | null
  activeSessionId: string | null
  navigationDisabled: boolean
  search: string
  onSearchChange: (value: string) => void
  onToggle: () => void
  onNewSession: () => void
  onSelectWorkspace: (workspaceId: string) => void
  onSelectSession: (sessionId: string) => void
  onAddWorkspace: () => void
  onOpenSettings: () => void
}

function formatUpdatedAt(timestamp: number): string {
  const date = new Date(timestamp)
  const today = new Date()
  if (date.toDateString() === today.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

export function Sidebar({
  collapsed,
  workspaces,
  sessions,
  activeWorkspaceId,
  activeSessionId,
  navigationDisabled,
  search,
  onSearchChange,
  onToggle,
  onNewSession,
  onSelectWorkspace,
  onSelectSession,
  onAddWorkspace,
  onOpenSettings,
}: SidebarProps) {
  if (collapsed) {
    return (
      <aside className="sidebar sidebarCollapsed">
        <button aria-label="展开侧边栏" className="railBrand" onClick={onToggle} type="button">
          <span className="railMark"><BrandMark size={26} /></span>
          <PanelLeftOpen className="railOpen" size={21} />
        </button>
        <button aria-label="新建会话" className="railButton" disabled={navigationDisabled} onClick={onNewSession} type="button">
          <MessageSquarePlus size={20} />
        </button>
        <button aria-label="添加工作区" className="railButton" disabled={navigationDisabled} onClick={onAddWorkspace} type="button">
          <FolderPlus size={20} />
        </button>
        <div className="railSpacer" />
        <button aria-label="设置" className="railButton" onClick={onOpenSettings} type="button">
          <Settings size={20} />
        </button>
      </aside>
    )
  }

  const query = search.trim().toLocaleLowerCase()

  return (
    <aside className="sidebar">
      <div className="sidebarBrandRow">
        <button className="brandButton" disabled={navigationDisabled} onClick={onNewSession} type="button">
          <BrandWordmark />
        </button>
        <button aria-label="收起侧边栏" className="iconButton" onClick={onToggle} type="button">
          <PanelLeftClose size={19} />
        </button>
      </div>

      <button className="newSessionButton" disabled={navigationDisabled} onClick={onNewSession} type="button">
        <MessageSquarePlus size={17} />
        <span>新会话</span>
      </button>

      <div className="sidebarSectionHeader">
        <span>工作区</span>
        <div className="sidebarHeaderActions">
          <button aria-label="添加工作区" className="headerIconButton" disabled={navigationDisabled} onClick={onAddWorkspace} type="button">
            <FolderPlus size={17} />
          </button>
        </div>
      </div>

      <label className="sessionSearch">
        <Search size={15} />
        <input
          aria-label="搜索会话"
          onChange={event => onSearchChange(event.target.value)}
          placeholder="搜索会话…"
          value={search}
        />
      </label>

      <div aria-label="会话" className="workspaceTree" role="tree">
        {workspaces.length === 0 && (
          <button className="emptyWorkspace" disabled={navigationDisabled} onClick={onAddWorkspace} type="button">
            <FolderPlus size={18} />
            <span>添加第一个工作区</span>
          </button>
        )}

        {workspaces.map(workspace => {
          const workspaceSessions = sessions
            .filter(session => session.workspaceId === workspace.id)
            .filter(session => query.length === 0 || session.title.toLocaleLowerCase().includes(query))
            .sort((a, b) => b.updatedAt - a.updatedAt)

          return (
            <section className="workspaceGroup" key={workspace.id}>
              <button
                aria-expanded="true"
                className="workspaceLabel"
                data-active={workspace.id === activeWorkspaceId || undefined}
                disabled={navigationDisabled}
                onClick={() => onSelectWorkspace(workspace.id)}
                role="treeitem"
                type="button"
              >
                <ChevronDown size={14} />
                <Folder size={15} />
                <span title={workspace.path}>{workspace.name}</span>
              </button>
              {workspaceSessions.length === 0 ? (
                <div className="emptySessions">暂无会话</div>
              ) : (
                workspaceSessions.map(session => (
                  <button
                    className="sessionRow"
                    data-active={session.id === activeSessionId || undefined}
                    disabled={navigationDisabled}
                    key={session.id}
                    onClick={() => onSelectSession(session.id)}
                    role="treeitem"
                    type="button"
                  >
                    <span className="sessionTitle">{session.title}</span>
                    <span className="sessionTime">{formatUpdatedAt(session.updatedAt)}</span>
                  </button>
                ))
              )}
            </section>
          )
        })}
      </div>

      <button className="settingsButton" onClick={onOpenSettings} type="button">
        <Settings size={18} />
        <span>设置</span>
      </button>
    </aside>
  )
}
