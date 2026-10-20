import { type ReactNode, useEffect, useId, useRef, useState } from 'react'

// M2: in-app modal that replaces the native `window.prompt` / `window.confirm`
// the sidebar used for workspace create/rename/delete — one card on the same
// tokens so the CRUD flow matches the app instead of popping an OS dialog.
//
// `input` true renders a text field (create/rename); false is a confirm-only
// card (delete). `onSubmit` fires with the trimmed field value, or '' when
// there is no field.
const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

export default function Dialog({
  open,
  title,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  input = false,
  initialValue = '',
  onCancel,
  onSubmit,
  children,
}: {
  open: boolean
  title: string
  confirmLabel?: string
  cancelLabel?: string
  input?: boolean
  initialValue?: string
  onCancel: () => void
  onSubmit: (value: string) => void
  // The body of a confirm-only card.
  children?: ReactNode
}) {
  const [value, setValue] = useState(initialValue)
  const cardRef = useRef<HTMLDivElement>(null)
  const titleId = useId()

  // On open: seed the field, focus the first focusable, trap Tab, and restore
  // focus to whatever was focused before the dialog opened.
  useEffect(() => {
    if (!open) return
    setValue(initialValue)
    const lastFocused = document.activeElement as HTMLElement | null
    const first = cardRef.current?.querySelector<HTMLElement>(FOCUSABLE)
    first?.focus()
    // Select the text so typing replaces the old name.
    if (first instanceof HTMLInputElement) first.select()

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel()
        return
      }
      if (e.key !== 'Tab') return
      const nodes = cardRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE)
      if (!nodes || nodes.length === 0) return
      const first = nodes[0]
      const last = nodes[nodes.length - 1]
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last?.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      lastFocused?.focus()
    }
  }, [open, initialValue, onCancel])

  if (!open) return null

  const submit = () => {
    onSubmit(input ? value.trim() : '')
  }

  return (
    <div
      className="dialog fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onClick={onCancel}
    >
      <div
        ref={cardRef}
        className="dialog__card w-full max-w-[340px] rounded-lg border border-border bg-bg p-4 shadow-lg"
        onClick={e => {
          e.stopPropagation()
        }}
      >
        <h3 id={titleId} className="dialog__title mb-3 text-[0.95rem] font-semibold text-text">
          {title}
        </h3>
        {input ? (
          <input
            className="dialog__input w-full px-3 py-2 border border-border rounded-lg bg-bg text-text outline-none"
            value={value}
            onChange={e => {
              setValue(e.target.value)
            }}
            onKeyDown={e => {
              if (e.key === 'Enter') {
                e.preventDefault()
                submit()
              }
            }}
          />
        ) : (
          children
        )}
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="dialog__cancel rounded-lg border border-border bg-transparent px-3 py-1.5 text-[0.8rem] text-text cursor-pointer"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={input && !value.trim()}
            className="dialog__confirm rounded-lg border-0 px-3 py-1.5 text-[0.8rem] font-semibold bg-text text-bg cursor-pointer disabled:opacity-50 disabled:cursor-default"
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
