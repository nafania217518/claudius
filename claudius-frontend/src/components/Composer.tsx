import { useState, type KeyboardEvent, type ReactNode } from 'react'

export default function Composer({
  onSend,
  disabled,
  onStop,
  picker,
  compacting,
}: {
  // false or a promise that resolves false keeps the text.
  onSend: (text: string) => boolean | void | Promise<boolean>
  disabled: boolean
  // Present only while a reply streams: Send turns into Stop.
  onStop?: () => void
  // M10: the model picker, rendered next to Send.
  picker?: ReactNode
  // A compaction runs: the disabled button says so instead of "Sending…".
  compacting?: boolean
}) {
  const [text, setText] = useState('')

  const submit = () => {
    if (!text.trim() || disabled) return
    const r = onSend(text.trim())
    if (r instanceof Promise) void r.then(ok => ok && setText(''))
    else if (r !== false) setText('')
  }

  // Enter sends; Shift+Enter inserts a newline.
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <form
      className="composer flex flex-wrap sm:flex-nowrap gap-2 py-3 border-t border-border"
      onSubmit={e => {
        e.preventDefault()
        submit()
      }}
    >
      {/* 16px on phones so iOS Safari does not zoom on focus. */}
      <textarea
        className="composer__input flex-1 min-w-0 basis-full sm:basis-0 resize-none py-2.5 px-3 border border-border rounded-lg bg-bg text-text text-base sm:text-sm outline-none"
        placeholder="Message claudius…"
        aria-label="Message claudius"
        value={text}
        onChange={e => {
          setText(e.target.value)
        }}
        onKeyDown={onKeyDown}
        rows={2}
      />
      {picker}
      {onStop ? (
        <button
          type="button"
          onClick={onStop}
          className="px-[18px] border border-border rounded-lg bg-bg text-text text-sm font-semibold cursor-pointer"
        >
          Stop
        </button>
      ) : (
        <button
          type="submit"
          disabled={disabled || !text.trim()}
          aria-busy={disabled}
          className="px-[18px] border-0 rounded-lg bg-text text-bg text-sm font-semibold cursor-pointer disabled:opacity-50 disabled:cursor-default"
        >
          {compacting ? 'Compacting…' : disabled ? 'Sending…' : 'Send'}
        </button>
      )}
    </form>
  )
}
