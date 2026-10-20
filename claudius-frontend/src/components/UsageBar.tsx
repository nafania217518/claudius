import { useState } from 'react'
import { fmt } from '../fmt'

// M11: the workspace context size against the effective window, plus Compact
// and the per-workspace context limit (empty = the model's window).
const MIN_LIMIT = 65536

export default function UsageBar({
  tokens,
  window,
  limited,
  limit,
  onLimit,
  disabled,
  onCompact,
}: {
  tokens: number
  window?: number | null
  limited?: boolean
  limit?: number | null
  onLimit?: (n: number | null) => void
  disabled: boolean
  onCompact: () => void
}) {
  const [invalid, setInvalid] = useState(false)
  const save = (raw: string) => {
    const n = raw.trim() ? Number(raw) : null
    if ((n ?? null) === (limit ?? null)) return
    if (n !== null && !(n >= MIN_LIMIT)) return setInvalid(true)
    setInvalid(false)
    onLimit?.(n)
  }
  return (
    <div className="usage flex flex-wrap items-center gap-2 text-[0.8rem] text-muted">
      <span>
        {window
          ? `Context: ${fmt(tokens)} / ${fmt(window)} tokens (${Math.round((tokens / window) * 100)}%)`
          : `Context: ${fmt(tokens)} tokens`}
      </span>
      {limited ? <span className="usage__limited text-[0.7rem]">limit</span> : null}
      {window ? <progress className="h-1 w-24" value={tokens} max={window} /> : null}
      <input
        key={limit ?? ''}
        type="number"
        aria-label="Context limit"
        placeholder="model default"
        defaultValue={limit ?? ''}
        min={MIN_LIMIT}
        onKeyDown={e => e.key === 'Enter' && save(e.currentTarget.value)}
        onBlur={e => save(e.currentTarget.value)}
        className="ml-auto w-32 px-2 py-0.5 border border-border rounded-lg bg-bg text-text"
      />
      <button
        type="button"
        onClick={onCompact}
        disabled={disabled || tokens === 0}
        className="px-2 py-0.5 border border-border rounded-lg bg-bg text-text cursor-pointer disabled:opacity-50 disabled:cursor-default"
      >
        Compact
      </button>
      {invalid ? (
        <span className="w-full text-red-600">Context limit must be at least 65536 tokens.</span>
      ) : null}
    </div>
  )
}
