import { useCallback, useEffect, useRef, useState } from 'react'
import { listProviders, type Provider } from './api/providers'
import type { PermissionMode } from './api/workspaces'
import { useChat } from './hooks/useChat'
import { useWorkspaces } from './hooks/useWorkspaces'
import { useLayout } from './hooks/useLayout'
import MessageList from './components/MessageList'
import Composer from './components/Composer'
import Sidebar from './components/Sidebar'
import Dialog from './components/Dialog'
import ModelPicker, { type Selection } from './components/ModelPicker'
import ProvidersPanel from './components/ProvidersPanel'
import UsageBar from './components/UsageBar'
import Inspector from './components/Inspector'
import Splitter from './components/Splitter'

// M2: workspace switcher on the left, one chat thread per workspace on the
// right. Orchestration lives in useWorkspaces/useChat; App only composes them,
// keeps the newest message in view, and surfaces each hook's `error`.
export default function App() {
  const ws = useWorkspaces()
  const layout = useLayout()
  const chat = useChat(ws.activeId, ws.setTokens)
  const bodyRef = useRef<HTMLElement>(null)

  // Load a transcript when the active workspace changes.
  useEffect(() => {
    if (ws.activeId) chat.loadTranscript(ws.activeId)
  }, [ws.activeId, chat.loadTranscript])

  // Keep the newest message in view, but only when the user was at the bottom.
  // A scroll event records that before the messages change.
  const atBottom = useRef(true)
  const onScroll = () => {
    const el = bodyRef.current!
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight <= 40
  }
  useEffect(() => {
    const el = bodyRef.current
    if (el && atBottom.current) el.scrollTop = el.scrollHeight
  }, [chat.messages])

  // M10: providers load once and again after the settings panel saves.
  const [providers, setProviders] = useState<Provider[]>([])
  const [settings, setSettings] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const loadProviders = useCallback(() => {
    listProviders()
      .then(p => {
        setProviders(p)
        setLoadError(null)
      })
      .catch((e: unknown) => setLoadError(e instanceof Error ? e.message : String(e)))
  }, [])
  useEffect(loadProviders, [loadProviders])
  const closeSettings = useCallback(() => setSettings(false), [])

  // The selection is derived: a pick made in this workspace wins; else the
  // workspace's last choice; else the first provider + its default model.
  // Keying the pick by workspace id resets it on a workspace switch.
  const [picked, setPicked] = useState<{ wsId?: string; sel: Selection } | null>(null)
  const active = ws.workspaces.find(w => w.id === ws.activeId)
  const first = providers[0]
  const selection: Selection =
    picked && picked.wsId === ws.activeId
      ? picked.sel
      : active?.lastProviderId && providers.some(p => p.id === active.lastProviderId)
        ? { providerId: active.lastProviderId, model: active.lastModel ?? '' }
        : { providerId: first?.id ?? '', model: first?.defaultModel ?? '' }

  const { remember } = ws
  const { send: chatSend } = chat
  // M11: the selected model's window; null/undefined = unknown.
  // The effective window is the smaller of it and the workspace limit.
  const modelWindow = providers
    .find(p => p.id === selection.providerId)
    ?.models.find(m => m.id === selection.model)?.contextWindow
  const limit = active?.contextLimit
  const limited = !!limit && (!modelWindow || limit < modelWindow)
  const contextWindow = limited ? limit : modelWindow
  const tokens = active?.contextTokens ?? 0
  // The text waiting on the compact-first prompt.
  const [askCompact, setAskCompact] = useState<{
    text: string
    done: (ok: boolean) => void
  } | null>(null)

  const send = (text: string, compact?: boolean) => {
    if (!compact && contextWindow && tokens > contextWindow) {
      return new Promise<boolean>(done => setAskCompact({ text, done }))
    }
    const id = ws.activeId
    const sel = selection.providerId ? selection : undefined
    // Same "<provider name>/<model>" label the backend saves for this turn.
    const name = providers.find(p => p.id === sel?.providerId)?.name
    void chatSend(text, sel, name && sel ? `${name}/${sel.model}` : '', compact)?.then(ok => {
      if (ok && id && sel) remember(id, sel.providerId, sel.model)
    })
    return true
  }

  // The inspector's tab and selected call; a chip click opens both.
  const [panel, setPanel] = useState<{ tab: 'tools' | 'agents'; selected?: string }>({
    tab: 'tools',
  })
  const open = useCallback(
    (key: string, kind: 'tools' | 'agents') => setPanel({ tab: kind, selected: key }),
    [],
  )
  // A new run drops the selection, so the panel follows the latest call.
  useEffect(() => {
    if (chat.streaming) setPanel(p => (p.selected ? { tab: p.tab } : p))
  }, [chat.streaming])

  const error = ws.error ?? chat.error ?? loadError

  return (
    <div className="app flex flex-col sm:flex-row h-full">
      <Sidebar
        workspaces={ws.workspaces}
        activeId={ws.activeId}
        onSelect={ws.select}
        create={ws.create}
        rename={ws.rename}
        remove={ws.remove}
        running={chat.running}
        width={layout.left.width}
        open={layout.left.open}
        onToggle={() => layout.toggle('left')}
      />
      {layout.left.open && (
        <Splitter
          side="left"
          width={layout.left.width}
          onResize={w => layout.resize('left', w)}
          label="Resize workspaces"
        />
      )}
      <div className="app__main flex-1 min-w-0 w-full flex flex-col max-w-[760px] mx-auto px-4">
        <header className="app__header flex items-baseline py-3 border-b border-border">
          <h1 className="text-[1.1rem]">claudius</h1>
          <div className="ml-auto flex items-center gap-2">
            {/* M13: the active workspace's permission mode; bypass is shown in red. */}
            {ws.activeId && (
              <select
                aria-label="Permissions"
                title="What the model may do without asking"
                className={`app__mode text-xs bg-bg text-text border border-border rounded px-1 py-0.5 ${
                  (active?.permissionMode ?? 'ASK') === 'BYPASS' ? 'text-red-600' : ''
                }`}
                value={active?.permissionMode ?? 'ASK'}
                onChange={e => void ws.setMode(ws.activeId!, e.target.value as PermissionMode)}
              >
                <option value="ASK">Ask</option>
                <option value="ACCEPT_EDITS">Accept edits</option>
                <option value="BYPASS">Bypass permissions</option>
              </select>
            )}
            {/* M10: opens the provider settings panel. */}
            <button
              type="button"
              aria-label="Settings"
              title="Settings"
              className="app__settings border-0 bg-transparent text-muted cursor-pointer hover:text-text"
              onClick={() => setSettings(true)}
            >
              <svg
                viewBox="0 0 24 24"
                width="18"
                height="18"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                className="block"
                aria-hidden="true"
              >
                <circle cx="12" cy="12" r="3" />
                <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" />
              </svg>
            </button>
          </div>
        </header>
        {/* Hoisted above the branch so a failed initial load (no active
            workspace) still surfaces instead of silently showing zero workspaces. */}
        {error && <div className="app__error text-[0.8rem] text-red-600">{error}</div>}
        {ws.activeId ? (
          <>
            <main
              className="app__body flex-1 overflow-y-auto py-4 text-sm"
              ref={bodyRef}
              onScroll={onScroll}
            >
              <MessageList
                messages={chat.messages}
                streaming={chat.streaming}
                approval={chat.approval}
                onAnswer={chat.answer}
                onOpen={open}
              />
            </main>
            <div className="app__status text-[0.8rem] text-muted">
              {chat.status === 'reconnecting'
                ? 'reconnecting…'
                : chat.loading
                  ? 'loading…'
                  : chat.compacting
                    ? 'compacting…'
                    : chat.streaming
                      ? 'streaming…'
                      : 'idle'}
            </div>
            <UsageBar
              tokens={tokens}
              window={contextWindow}
              limited={limited}
              limit={limit}
              onLimit={n => void ws.setLimit(ws.activeId!, n)}
              disabled={chat.streaming || chat.compacting}
              onCompact={() => void chat.compact()}
            />
            <Composer
              onSend={send}
              disabled={chat.streaming || chat.loading || chat.compacting}
              onStop={chat.streaming ? chat.stop : undefined}
              compacting={chat.compacting}
              picker={
                providers.length ? (
                  <ModelPicker
                    providers={providers}
                    value={selection}
                    onChange={sel => setPicked({ wsId: ws.activeId, sel })}
                    disabled={chat.streaming}
                  />
                ) : undefined
              }
            />
          </>
        ) : (
          <div className="empty-state flex flex-1 items-center justify-center">
            <p className="empty text-muted text-center px-8">
              Create a new or select an existing workspace to continue.
            </p>
          </div>
        )}
      </div>
      {ws.activeId && layout.right.open && (
        <Splitter
          side="right"
          width={layout.right.width}
          onResize={w => layout.resize('right', w)}
          label="Resize inspector"
        />
      )}
      {ws.activeId && (
        <Inspector
          messages={chat.messages}
          tab={panel.tab}
          onTab={tab => setPanel({ tab })}
          selected={panel.selected}
          onSelect={selected => setPanel(p => ({ ...p, selected }))}
          width={layout.right.width}
          open={layout.right.open}
          onToggle={() => layout.toggle('right')}
        />
      )}
      {settings && <ProvidersPanel onClose={closeSettings} onSaved={loadProviders} />}
      {askCompact !== null && (
        <Dialog
          open
          title="Compact first?"
          confirmLabel="Yes"
          cancelLabel="No"
          onSubmit={() => {
            setAskCompact(null)
            send(askCompact.text, true)
            askCompact.done(true)
          }}
          onCancel={() => {
            setAskCompact(null)
            askCompact.done(false)
          }}
        >
          <p className="mb-3 text-[0.85rem] text-muted">
            The context is bigger than the model's window. Compact it first?
          </p>
        </Dialog>
      )}
    </div>
  )
}
