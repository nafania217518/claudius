// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup, screen } from '@testing-library/react'
import Splitter from './Splitter'

describe('Splitter', () => {
  afterEach(cleanup)

  it('the handle is a vertical separator with a label', () => {
    render(<Splitter side="left" width={220} onResize={() => {}} label="Resize workspaces" />)
    const h = screen.getByRole('separator', { name: 'Resize workspaces' })
    expect(h.getAttribute('aria-orientation')).toBe('vertical')
  })

  it('dragging the left handle to the right widens the left panel', () => {
    const onResize = vi.fn()
    render(<Splitter side="left" width={220} onResize={onResize} label="l" />)
    const h = screen.getByRole('separator')
    fireEvent.pointerDown(h, { clientX: 100, pointerId: 1 })
    fireEvent.pointerMove(h, { clientX: 150, pointerId: 1 })
    expect(onResize).toHaveBeenCalledWith(270)
    fireEvent.pointerUp(h, { pointerId: 1 })
    fireEvent.pointerMove(h, { clientX: 200, pointerId: 1 })
    expect(onResize).toHaveBeenCalledTimes(1)
  })

  it('dragging the right handle to the right narrows the right panel', () => {
    const onResize = vi.fn()
    render(<Splitter side="right" width={300} onResize={onResize} label="r" />)
    const h = screen.getByRole('separator')
    fireEvent.pointerDown(h, { clientX: 500, pointerId: 1 })
    fireEvent.pointerMove(h, { clientX: 540, pointerId: 1 })
    expect(onResize).toHaveBeenCalledWith(260)
  })

  it('moving without a press does not resize', () => {
    const onResize = vi.fn()
    render(<Splitter side="left" width={220} onResize={onResize} label="l" />)
    fireEvent.pointerMove(screen.getByRole('separator'), { clientX: 150 })
    expect(onResize).not.toHaveBeenCalled()
  })
})
