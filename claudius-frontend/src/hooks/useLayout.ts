import { useCallback, useEffect, useState } from 'react'

export type Side = 'left' | 'right'
type Panel = { width: number; open: boolean }
type Layout = Record<Side, Panel>

// The side panels' width and open state. Saved in localStorage.
// Phones start with both panels closed.
const KEY = 'claudius.layout'
const MIN = 160
const MAX = 600
const clamp = (w: number) => Math.min(MAX, Math.max(MIN, w))

const initial = (): Layout => {
  const open = window.matchMedia?.('(min-width: 640px)').matches ?? true
  const defaults: Layout = { left: { width: 220, open }, right: { width: 300, open } }
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null') as Partial<Layout> | null
    return saved
      ? { left: { ...defaults.left, ...saved.left }, right: { ...defaults.right, ...saved.right } }
      : defaults
  } catch {
    return defaults
  }
}

export function useLayout() {
  const [layout, setLayout] = useState<Layout>(initial)

  useEffect(() => {
    try {
      localStorage.setItem(KEY, JSON.stringify(layout))
    } catch {
      // Storage is off: the layout is not remembered.
    }
  }, [layout])

  const resize = useCallback((side: Side, width: number) => {
    setLayout(l => ({ ...l, [side]: { ...l[side], width: clamp(width) } }))
  }, [])
  const toggle = useCallback((side: Side) => {
    setLayout(l => ({ ...l, [side]: { ...l[side], open: !l[side].open } }))
  }, [])

  return { ...layout, resize, toggle }
}
