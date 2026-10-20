import { useState } from 'react'
import type { Workspaces } from '../hooks/useWorkspaces'
import Dialog from './Dialog'

// M2: the workspace switcher. Create/rename/delete open an in-app `Dialog`
// (the native `window.prompt`/`window.confirm` used before were an OS popup that
// didn't match the app). The mutation handlers come from `useWorkspaces` and
// never reject (they surface into its `error`), so `submit` just awaits the
// chosen one and closes — no `.then().then()` chain that drops rejections.
// Create/rename read their name from the dialog's text field; delete is a
// confirm-only card because it wipes the workspace dir.
type DialogState =
  | { kind: 'create' }
  | { kind: 'rename'; id: string; name: string }
  | { kind: 'delete'; id: string; name: string }

export default function Sidebar({
  workspaces,
  activeId,
  onSelect,
  create,
  rename,
  remove,
  running = [],
  width,
  open = true,
  onToggle,
}: {
  workspaces: Workspaces['workspaces']
  activeId?: string
  onSelect: (id: string) => void
  create: Workspaces['create']
  rename: Workspaces['rename']
  remove: Workspaces['remove']
  // Ids of the workspaces with a run in progress.
  running?: string[]
  // Panel width on desktop; undefined keeps the fixed default.
  width?: number
  // false renders only a rail with an expand button.
  open?: boolean
  onToggle?: () => void
}) {
  const [dialog, setDialog] = useState<DialogState | null>(null)
  const close = () => {
    setDialog(null)
  }

  const submit = async (d: DialogState, value: string) => {
    const name = value.trim()
    if (d.kind === 'create') {
      if (name) await create(name)
    } else if (d.kind === 'rename') {
      if (name && name !== d.name) await rename(d.id, name)
    } else {
      await remove(d.id)
    }
    close()
  }

  if (!open) {
    return (
      <aside className="sidebar w-full sm:w-8 shrink-0 flex sm:flex-col p-1 border-b sm:border-b-0 sm:border-r border-border">
        <button
          type="button"
          aria-label="Show workspaces"
          className="border-0 bg-transparent text-muted cursor-pointer hover:text-text"
          onClick={onToggle}
        >
          »
        </button>
      </aside>
    )
  }

  return (
    <aside
      className={`sidebar w-full ${width ? 'sm:w-(--w)' : 'sm:w-[220px]'} shrink-0 flex flex-col p-3 border-b sm:border-b-0 sm:border-r border-border`}
      style={width ? ({ '--w': `${width}px` } as React.CSSProperties) : undefined}
    >
      <div className="sidebar__head flex items-baseline gap-2 pb-3 border-b border-border">
        {onToggle && (
          <button
            type="button"
            aria-label="Hide workspaces"
            className="border-0 bg-transparent text-muted text-xs cursor-pointer hover:text-text"
            onClick={onToggle}
          >
            «
          </button>
        )}
        <h2 className="text-[0.9rem] mr-auto">Workspaces</h2>
        <button
          type="button"
          className="sidebar__new border-0 bg-transparent text-text text-xs cursor-pointer"
          onClick={() => {
            setDialog({ kind: 'create' })
          }}
        >
          + New
        </button>
      </div>
      <ul className="sidebar__list list-none m-0 p-0 flex flex-row flex-wrap sm:flex-col gap-0.5">
        {workspaces.map(ws => (
          <li
            key={ws.id}
            className={`sidebar__item flex items-center min-w-0 max-w-full py-1.5 px-2 rounded-md${
              ws.id === activeId ? ' is-active bg-assist-bubble' : ''
            }`}
          >
            <button
              type="button"
              // One line; a long name fades out over its last 24px instead of wrapping.
              className="sidebar__name min-w-0 flex-1 whitespace-nowrap overflow-hidden [mask-image:linear-gradient(to_right,#000_calc(100%_-_24px),transparent)] border-0 bg-transparent text-text text-[0.85rem] cursor-pointer text-left p-0"
              onClick={() => {
                onSelect(ws.id)
              }}
            >
              {ws.name}
            </button>
            {running.includes(ws.id) && (
              <span
                title="running"
                aria-label="running"
                className="sidebar__running mx-1 inline-block w-1.5 h-1.5 rounded-full bg-green-600"
              />
            )}
            {/* Rename and delete show on the active workspace only. */}
            {ws.id === activeId && (
              <div className="sidebar__actions flex gap-1">
                <button
                  type="button"
                  aria-label="Rename"
                  title="Rename"
                  className="border-0 bg-transparent text-muted cursor-pointer p-0.5 rounded hover:text-text"
                  onClick={() => {
                    setDialog({ kind: 'rename', id: ws.id, name: ws.name })
                  }}
                >
                  <svg
                    viewBox="0 0 24 24"
                    width="14"
                    height="14"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="block"
                    aria-hidden="true"
                  >
                    <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
                  </svg>
                </button>
                <button
                  type="button"
                  aria-label="Delete"
                  title="Delete"
                  className="border-0 bg-transparent text-muted cursor-pointer p-0.5 rounded hover:text-text"
                  onClick={() => {
                    setDialog({ kind: 'delete', id: ws.id, name: ws.name })
                  }}
                >
                  <svg
                    viewBox="0 0 24 24"
                    width="14"
                    height="14"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="block"
                    aria-hidden="true"
                  >
                    <polyline points="3 6 5 6 21 6" />
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                    <line x1="10" y1="11" x2="10" y2="17" />
                    <line x1="14" y1="11" x2="14" y2="17" />
                  </svg>
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {dialog && (
        <Dialog
          open
          title={
            dialog.kind === 'create'
              ? 'New workspace'
              : dialog.kind === 'rename'
                ? 'Rename workspace'
                : `Delete "${dialog.name}"?`
          }
          confirmLabel={
            dialog.kind === 'delete' ? 'Delete' : dialog.kind === 'rename' ? 'Rename' : 'Create'
          }
          input={dialog.kind !== 'delete'}
          initialValue={dialog.kind === 'rename' ? dialog.name : ''}
          onCancel={close}
          onSubmit={value => {
            void submit(dialog, value)
          }}
        >
          {dialog.kind === 'delete' && (
            <p className="mb-3 text-[0.85rem] text-muted">
              This deletes the workspace directory and its chat history.
            </p>
          )}
        </Dialog>
      )}
    </aside>
  )
}
