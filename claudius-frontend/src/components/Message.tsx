import { Fragment, memo } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkBreaks from 'remark-breaks'
import type { ChatMessage, ToolInvocation } from '../api/workspaces'
import { agentLabel, fmt } from '../fmt'

// Assistant replies render as markdown (GFM, a single newline is a line break).
// Raw HTML stays text: react-markdown skips it.
// User messages stay plain pre-wrap text.
// memo'd so streaming one message doesn't re-render the whole transcript.
// M3: an assistant turn carries a reasoning trace (thinking) shown above the
// answer in a native <details> — collapsed by default, expanded on click, no
// extra state. Absent for user turns and answer-only turns.
// Tool and sub-agent calls render as chips (label + status) in one wrapping row.
// A click opens the call in the Inspector; details live there, not in the chat.
// Index keys are safe: the list is append-only and never reorders.

type Part =
  | { kind: 'thinking'; text: string }
  | { kind: 'answer'; text: string }
  | { kind: 'tools'; calls: { t: ToolInvocation; i: number }[] }

// Splits a turn into thinking, chips and answer parts in arrival order.
// Each tool carries the thinking and answer lengths at call time.
// A tool without offsets sits after all thinking and before the answer.
// Consecutive tools with equal offsets (parallel calls) share one chips row.
function parts(m: ChatMessage): Part[] {
  const thinking = m.thinking ?? ''
  const out: Part[] = []
  let t = 0
  let a = 0
  let last: { kind: 'tools'; calls: { t: ToolInvocation; i: number }[] } | null = null
  const cut = (tAt: number, aAt: number) => {
    if (tAt > t) out.push({ kind: 'thinking' as const, text: thinking.slice(t, tAt) })
    if (aAt > a) out.push({ kind: 'answer' as const, text: m.content.slice(a, aAt) })
    if (tAt > t || aAt > a) last = null
    t = Math.max(t, tAt)
    a = Math.max(a, aAt)
  }
  m.tools?.forEach((tool, i) => {
    cut(tool.thinkingAt ?? thinking.length, tool.answerAt ?? 0)
    if (last) last.calls.push({ t: tool, i })
    else out.push((last = { kind: 'tools', calls: [{ t: tool, i }] }))
  })
  cut(thinking.length, m.content.length)
  return out
}

// A call waiting for approval shows Approve and Reject under the chips.
export default memo(function Message({
  message,
  streaming = false,
  approval,
  onAnswer,
  onOpen,
}: {
  message: ChatMessage
  streaming?: boolean
  approval?: ToolInvocation | null
  onAnswer?: (approved: boolean) => void
  onOpen?: (key: string, kind: 'tools' | 'agents') => void
}) {
  // M11: a compaction summary is a muted, collapsed block; a pending one is a placeholder.
  if (message.role === 'summary' && message.pending)
    return (
      <div className="msg msg--summary msg--compacting text-[0.8rem] text-muted">
        Compacting context…
      </div>
    )
  if (message.role === 'summary') {
    const { tokensBefore: b, tokensAfter: a } = message
    return (
      <details className="msg msg--summary text-[0.8rem] text-muted">
        <summary>
          {b != null && a != null
            ? `Context compacted · ~${fmt(b - a)} tokens saved (${fmt(b)} → ${fmt(a)})`
            : 'Context compacted'}
        </summary>
        <div className="px-3.5 py-2 whitespace-pre-wrap break-words">{message.content}</div>
      </details>
    )
  }
  return (
    <div className={`msg msg--${message.role} flex flex-col gap-1`}>
      <div className="text-[0.7rem] uppercase tracking-[0.05em] text-muted">
        {message.role}
        {/* M10: which provider/model answered this turn. */}
        {message.model ? (
          <span className="msg__model ml-2 normal-case tracking-normal">{message.model}</span>
        ) : null}
      </div>
      {parts(message).map((p, k) =>
        p.kind === 'thinking' ? (
          <details key={k} className="thinking text-[0.8rem] text-muted">
            <summary>thinking</summary>
            <div className="px-3.5 py-2 rounded-[10px] whitespace-pre-wrap break-words">
              {p.text}
            </div>
          </details>
        ) : p.kind === 'answer' ? (
          message.role === 'user' ? (
            <div
              key={k}
              className="px-3.5 py-2.5 rounded-[10px] whitespace-pre-wrap break-words bg-user-bubble"
            >
              {p.text}
            </div>
          ) : (
            <div
              key={k}
              className="msg__body px-3.5 py-2.5 rounded-[10px] break-words bg-assist-bubble"
            >
              <Markdown remarkPlugins={[remarkGfm, remarkBreaks]}>{p.text}</Markdown>
            </div>
          )
        ) : (
          <Fragment key={k}>
            <div className="chips tools flex flex-wrap gap-1.5">
              {p.calls.map(({ t, i }) => {
                const agent = t.name === 'agent'
                return (
                  <button
                    key={i}
                    type="button"
                    className="chip px-2 py-0.5 rounded-full border border-border text-[0.78rem] font-mono cursor-pointer hover:bg-assist-bubble"
                    onClick={() => onOpen?.(`${message.id}:${i}`, agent ? 'agents' : 'tools')}
                  >
                    {`${agent ? agentLabel(t.arguments) : t.name} · ${t.result ? 'done' : 'running…'}${t.compacted ? ' · compacted' : ''}`}
                  </button>
                )
              })}
            </div>
            {approval?.id != null && p.calls.some(({ t }) => t.id === approval.id && !t.result) ? (
              <div className="tool__approval flex items-center gap-2 text-[0.78rem]">
                <span>Waiting for your approval</span>
                <button
                  className="px-2 py-0.5 rounded border border-border"
                  onClick={() => onAnswer?.(true)}
                >
                  Approve
                </button>
                <button
                  className="px-2 py-0.5 rounded border border-border"
                  onClick={() => onAnswer?.(false)}
                >
                  Reject
                </button>
              </div>
            ) : null}
          </Fragment>
        ),
      )}
      {/* The "…" placeholder shows only while streaming; a finished empty turn has no bubble. */}
      {!message.content && streaming ? (
        <div className="px-3.5 py-2.5 rounded-[10px] bg-assist-bubble">…</div>
      ) : null}
      {/* M12: a saved turn error, e.g. the run was cut by a server restart. */}
      {message.error ? (
        <div role="alert" className="msg__error text-[0.8rem] text-red-600">
          {message.error}
        </div>
      ) : null}
    </div>
  )
})
