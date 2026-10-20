// @vitest-environment jsdom
import { describe, it, expect, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useLayout } from './useLayout'

describe('useLayout', () => {
  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('panels start open at their default widths', () => {
    const { result } = renderHook(() => useLayout())
    expect(result.current.left).toEqual({ width: 220, open: true })
    expect(result.current.right).toEqual({ width: 300, open: true })
  })

  it('panels start closed on a narrow screen', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }))
    const { result } = renderHook(() => useLayout())
    expect(result.current.left.open).toBe(false)
    expect(result.current.right.open).toBe(false)
  })

  it('toggle closes and reopens a panel', () => {
    const { result } = renderHook(() => useLayout())
    act(() => result.current.toggle('left'))
    expect(result.current.left.open).toBe(false)
    act(() => result.current.toggle('left'))
    expect(result.current.left.open).toBe(true)
  })

  it('resize clamps the width between 160 and 600', () => {
    const { result } = renderHook(() => useLayout())
    act(() => result.current.resize('right', 50))
    expect(result.current.right.width).toBe(160)
    act(() => result.current.resize('right', 5000))
    expect(result.current.right.width).toBe(600)
    act(() => result.current.resize('right', 400))
    expect(result.current.right.width).toBe(400)
  })

  it('the layout survives a reload', () => {
    const first = renderHook(() => useLayout())
    act(() => {
      first.result.current.toggle('left')
      first.result.current.resize('right', 400)
    })
    first.unmount()
    const { result } = renderHook(() => useLayout())
    expect(result.current.left.open).toBe(false)
    expect(result.current.right.width).toBe(400)
  })
})
