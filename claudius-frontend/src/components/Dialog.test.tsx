// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import Dialog from './Dialog'

// The dialog is the in-app replacement for window.prompt/confirm, so it gets its
// own tests: input vs confirm-only rendering, submit (trimmed), and the three
// ways to dismiss (Cancel button, overlay click, Escape) vs the card click that
// must NOT dismiss.

const open = (props: Record<string, unknown> = {}) =>
  render(
    <Dialog
      open
      title="New workspace"
      confirmLabel="Create"
      onCancel={vi.fn()}
      onSubmit={vi.fn()}
      {...props}
    />,
  )

const input = () => document.querySelector('input')!

describe('Dialog', () => {
  afterEach(() => {
    cleanup()
  })

  it('renders nothing when closed', () => {
    render(<Dialog open={false} title="New workspace" onCancel={() => {}} onSubmit={() => {}} />)
    expect(document.querySelector('.dialog')).toBeNull()
  })

  it('seeds the field from initialValue on open', () => {
    open({ input: true, initialValue: 'Beta' })
    expect(input().value).toBe('Beta')
  })

  it('reflects typed input in the field', () => {
    open({ input: true })
    fireEvent.change(input(), { target: { value: 'Gamma' } })
    expect(input().value).toBe('Gamma')
  })

  it('submits the trimmed value on Enter in the field', () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    open({ input: true, onSubmit, onCancel })
    fireEvent.change(input(), { target: { value: '  Beta  ' } })
    fireEvent.keyDown(input(), { key: 'Enter' })
    expect(onSubmit).toHaveBeenCalledWith('Beta')
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('submits the trimmed value from the confirm button', () => {
    const onSubmit = vi.fn()
    open({ input: true, onSubmit })
    fireEvent.change(input(), { target: { value: '  Beta  ' } })
    fireEvent.click(document.querySelector('.dialog__confirm')!)
    expect(onSubmit).toHaveBeenCalledWith('Beta')
  })

  it('renders a confirm-only card with no field and submits "" on confirm', () => {
    const onSubmit = vi.fn()
    open({ input: false, confirmLabel: 'Delete', onSubmit })
    expect(input()).toBeNull()
    fireEvent.click(document.querySelector('.dialog__confirm')!)
    expect(onSubmit).toHaveBeenCalledWith('')
  })

  it('cancels from the Cancel button without submitting', () => {
    const onSubmit = vi.fn()
    const onCancel = vi.fn()
    open({ onSubmit, onCancel })
    fireEvent.click(document.querySelector('.dialog__cancel')!)
    expect(onCancel).toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('cancels when the overlay is clicked', () => {
    const onCancel = vi.fn()
    open({ onCancel })
    fireEvent.click(document.querySelector('.dialog')!)
    expect(onCancel).toHaveBeenCalled()
  })

  it('does not cancel when the card is clicked', () => {
    const onCancel = vi.fn()
    open({ onCancel })
    fireEvent.click(document.querySelector('.dialog__card')!)
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('cancels on Escape', () => {
    const onCancel = vi.fn()
    open({ onCancel })
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(onCancel).toHaveBeenCalled()
  })

  it('renders children as the body when given', () => {
    open({ title: 'Allow Bash?', children: <pre className="body">ls</pre> })
    expect(document.querySelector('.body')!.textContent).toBe('ls')
    expect(document.querySelector('p')).toBeNull()
  })

  it('uses a custom cancel label', () => {
    open({ cancelLabel: 'Reject' })
    expect(document.querySelector('.dialog__cancel')!.textContent).toBe('Reject')
  })

  it('the text field is selected on open', () => {
    open({ input: true, initialValue: 'old' })
    expect(document.activeElement).toBe(input())
    expect(input().selectionStart).toBe(0)
    expect(input().selectionEnd).toBe(3)
  })
})
