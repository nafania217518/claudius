import { useRef, type PointerEvent } from 'react'
import type { Side } from '../hooks/useLayout'

// A drag handle between a side panel and the chat. Hidden on phones.
export default function Splitter({
  side,
  width,
  onResize,
  label,
}: {
  side: Side
  width: number
  onResize: (width: number) => void
  label: string
}) {
  // The pointer x and the panel width when the drag started; null = no drag.
  const start = useRef<{ x: number; width: number } | null>(null)

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    // jsdom has no setPointerCapture.
    e.currentTarget.setPointerCapture?.(e.pointerId)
    start.current = { x: e.clientX, width }
  }
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    if (!start.current) return
    const dx = e.clientX - start.current.x
    onResize(side === 'left' ? start.current.width + dx : start.current.width - dx)
  }
  const end = () => {
    start.current = null
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      className="splitter hidden sm:block w-1 shrink-0 cursor-col-resize touch-none hover:bg-border"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={end}
      onPointerCancel={end}
    />
  )
}
