import type { ChatMessage } from '../api/workspaces'
import { agentLabel } from '../fmt'

type Tab = 'tools' | 'agents'

function prompt(args: string) {
  try {
    const p = JSON.parse(args)?.prompt
    return typeof p === 'string' ? p : ''
  } catch {
    return ''
  }
}

const pre = 'font-mono whitespace-pre-wrap break-words max-h-60 overflow-auto text-muted'

// The right-hand panel: tool calls and sub-agent calls, one tab each.
// An item key is `${message.id}:${index}` into message.tools.
// Without a valid selection the tab's latest item shows, so live output needs no click.
export default function Inspector({
  messages,
  tab,
  onTab,
  selected,
  onSelect,
  width,
  open = true,
  onToggle,
}: {
  messages: ChatMessage[]
  tab: Tab
  onTab: (t: Tab) => void
  selected?: string
  onSelect: (key: string) => void
  // Panel width on desktop; undefined keeps the fixed default.
  width?: number
  // false renders only a rail with an expand button.
  open?: boolean
  onToggle?: () => void
}) {
  if (!open) {
    return (
      <aside className="inspector w-full sm:w-8 shrink-0 flex sm:flex-col p-1 border-t sm:border-t-0 sm:border-l border-border">
        <button
          type="button"
          aria-label="Show inspector"
          className="border-0 bg-transparent text-muted cursor-pointer hover:text-text"
          onClick={onToggle}
        >
          «
        </button>
      </aside>
    )
  }
  const items = messages.flatMap(m =>
    (m.tools ?? []).map((t, i) => ({ key: `${m.id}:${i}`, t, agent: t.name === 'agent' })),
  )
  const list = items.filter(x => x.agent === (tab === 'agents'))
  const cur = list.find(x => x.key === selected) ?? list[list.length - 1]
  const label = (x: (typeof list)[number]) => (x.agent ? agentLabel(x.t.arguments) : x.t.name)
  const status = (x: (typeof list)[number]) => (x.t.result ? 'done' : 'running…')
  return (
    <aside
      className={`inspector w-full ${width ? 'sm:w-(--w)' : 'sm:w-[300px]'} shrink-0 flex flex-col p-3 border-t sm:border-t-0 sm:border-l border-border overflow-y-auto text-[0.8rem]`}
      style={width ? ({ '--w': `${width}px` } as React.CSSProperties) : undefined}
    >
      <div role="tablist" className="flex gap-1 mb-2">
        {(['tools', 'agents'] as const).map(t => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            className={`px-2 py-0.5 rounded ${tab === t ? 'bg-assist-bubble' : ''}`}
            onClick={() => onTab(t)}
          >
            {t === 'tools' ? 'Tools' : 'Agents'}
          </button>
        ))}
        {onToggle && (
          <button
            type="button"
            aria-label="Hide inspector"
            className="ml-auto border-0 bg-transparent text-muted cursor-pointer hover:text-text"
            onClick={onToggle}
          >
            »
          </button>
        )}
      </div>
      {list.length === 0 ? (
        <p className="text-muted">
          {tab === 'tools' ? 'No tool calls yet.' : 'No sub-agents yet.'}
        </p>
      ) : (
        <div className="flex flex-col gap-0.5 mb-3">
          {list.map(x => (
            <button
              key={x.key}
              type="button"
              className={`inspector__item flex justify-between px-2 py-0.5 rounded text-left font-mono ${x === cur ? 'bg-assist-bubble' : ''}`}
              onClick={() => onSelect(x.key)}
            >
              <span className="truncate">{label(x)}</span>
              <span className="text-muted ml-2">{status(x)}</span>
            </button>
          ))}
        </div>
      )}
      {cur ? (
        <div className="inspector__detail flex flex-col gap-1">
          <div className="font-mono">{label(cur)}</div>
          <div className="text-muted">
            {status(cur)}
            {cur.t.compacted ? <span className="ml-1.5">compacted</span> : null}
          </div>
          {cur.agent ? (
            <>
              <h3>Prompt</h3>
              <pre className={pre}>{prompt(cur.t.arguments)}</pre>
            </>
          ) : cur.t.arguments && cur.t.arguments !== '{}' ? (
            <>
              <h3>Arguments</h3>
              <pre className={pre}>{cur.t.arguments}</pre>
            </>
          ) : null}
          {cur.t.output && !cur.t.result ? (
            <>
              <h3>Output</h3>
              <pre className={`inspector__output ${pre}`}>{cur.t.output.slice(-4000)}</pre>
            </>
          ) : null}
          {cur.t.result ? (
            <>
              <h3>{cur.agent ? 'Report' : 'Result'}</h3>
              <pre className={`inspector__result ${pre}`}>{cur.t.result}</pre>
            </>
          ) : null}
        </div>
      ) : null}
    </aside>
  )
}
