// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import Composer from './Composer'

describe('Composer', () => {
  it('sends on Enter and clears the field', () => {
    const onSend = vi.fn()
    const { container } = render(<Composer onSend={onSend} disabled={false} />)
    const input = container.querySelector('textarea')!
    fireEvent.change(input, { target: { value: 'hello' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onSend).toHaveBeenCalledWith('hello')
    expect((container.querySelector('textarea') as HTMLTextAreaElement).value).toBe('')
  })

  it('does not send on Shift+Enter', () => {
    const onSend = vi.fn()
    const { container } = render(<Composer onSend={onSend} disabled={false} />)
    const input = container.querySelector('textarea')!
    fireEvent.change(input, { target: { value: 'hi' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('ignores blank input', () => {
    const onSend = vi.fn()
    const { container } = render(<Composer onSend={onSend} disabled={false} />)
    const input = container.querySelector('textarea')!
    fireEvent.change(input, { target: { value: '   ' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('does not send while disabled', () => {
    const onSend = vi.fn()
    const { container } = render(<Composer onSend={onSend} disabled={true} />)
    const input = container.querySelector('textarea')!
    fireEvent.change(input, { target: { value: 'hi' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('sends when the submit button is clicked', () => {
    const onSend = vi.fn()
    const { container } = render(<Composer onSend={onSend} disabled={false} />)
    const input = container.querySelector('textarea')!
    fireEvent.change(input, { target: { value: 'via button' } })
    fireEvent.click(container.querySelector('button')!)
    expect(onSend).toHaveBeenCalledWith('via button')
  })

  it('shows the Send label when enabled and "Sending…" when disabled', () => {
    const { container } = render(<Composer onSend={() => {}} disabled={false} />)
    expect(container.querySelector('button')!.textContent).toBe('Send')
    const { container: c2 } = render(<Composer onSend={() => {}} disabled={true} />)
    expect(c2.querySelector('button')!.textContent).toBe('Sending…')
  })

  it('the send button turns into a stop button while a reply streams', () => {
    const onStop = vi.fn()
    const { container, rerender } = render(
      <Composer onSend={() => {}} disabled={true} onStop={onStop} />,
    )
    expect(container.querySelectorAll('button')).toHaveLength(1)
    const stop = container.querySelector('button')!
    expect(stop.textContent).toBe('Stop')
    expect(stop.getAttribute('type')).toBe('button')
    fireEvent.click(stop)
    expect(onStop).toHaveBeenCalledTimes(1)
    rerender(<Composer onSend={() => {}} disabled={false} />)
    expect(container.querySelectorAll('button')).toHaveLength(1)
    expect(container.querySelector('button')!.textContent).toBe('Send')
    expect(container.querySelector('button')!.getAttribute('type')).toBe('submit')
  })

  it('the composer wraps on a narrow screen so the text box keeps the full width', () => {
    const { container } = render(<Composer onSend={() => true} disabled={false} />)
    const form = container.querySelector('form')!
    expect(form.className).toContain('flex-wrap')
    expect(form.className).toContain('sm:flex-nowrap')
    const box = container.querySelector('textarea')!
    expect(box.className).toContain('min-w-0')
    expect(box.className).toContain('basis-full')
    expect(box.className).toContain('sm:basis-0')
  })

  it('the message is trimmed before it is sent', () => {
    const onSend = vi.fn()
    const { container } = render(<Composer onSend={onSend} disabled={false} />)
    const input = container.querySelector('textarea')!
    fireEvent.change(input, { target: { value: '  hi there \n' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onSend).toHaveBeenCalledWith('hi there')
  })

  it('the input is 16px on phones and small on desktop', () => {
    const { container } = render(<Composer onSend={() => {}} disabled={false} />)
    const cls = container.querySelector('textarea')!.className
    expect(cls).toContain('text-base')
    expect(cls).toContain('sm:text-sm')
  })
})
