// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, screen, fireEvent } from '@testing-library/react'
import UsageBar from './UsageBar'

describe('UsageBar', () => {
  afterEach(cleanup)

  it("the usage bar shows the context size against the selected model's window", () => {
    const onCompact = vi.fn()
    render(<UsageBar tokens={12345} window={262144} disabled={false} onCompact={onCompact} />)
    expect(screen.getByText('Context: 12.3k / 262k tokens (5%)')).toBeTruthy()
    expect(document.querySelector('progress')?.value).toBe(12345)
    fireEvent.click(screen.getByRole('button', { name: 'Compact' }))
    expect(onCompact).toHaveBeenCalled()
  })

  it('the usage bar shows only the size when the window is unknown', () => {
    render(<UsageBar tokens={999} window={null} disabled={false} onCompact={() => {}} />)
    expect(screen.getByText('Context: 999 tokens')).toBeTruthy()
    expect(document.querySelector('progress')).toBeNull()
    cleanup()
    render(<UsageBar tokens={0} disabled={false} onCompact={() => {}} />)
    expect((screen.getByRole('button', { name: 'Compact' }) as HTMLButtonElement).disabled).toBe(
      true,
    )
    cleanup()
    render(<UsageBar tokens={5} disabled onCompact={() => {}} />)
    expect((screen.getByRole('button', { name: 'Compact' }) as HTMLButtonElement).disabled).toBe(
      true,
    )
  })

  it('the usage bar shows the effective window with the workspace limit', () => {
    render(<UsageBar tokens={1000} window={128000} limited disabled={false} onCompact={() => {}} />)
    expect(screen.getByText('Context: 1k / 128k tokens (1%)')).toBeTruthy()
    expect(screen.getByText('limit')).toBeTruthy()
    cleanup()
    render(<UsageBar tokens={1000} window={128000} disabled={false} onCompact={() => {}} />)
    expect(screen.queryByText('limit')).toBeNull()
  })
})
