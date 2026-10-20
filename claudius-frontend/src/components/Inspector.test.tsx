import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import type { ChatMessage } from '../api/workspaces'
import Inspector from './Inspector'

const messages: ChatMessage[] = [
  {
    id: 'm',
    role: 'assistant',
    content: '',
    createdAt: 0,
    tools: [
      { name: 'bash', arguments: '{"cmd":"ls"}', result: 'R0', output: 'x'.repeat(5000) + 'TAIL' },
      { name: 'agent', arguments: '{"description":"scan","prompt":"P1"}', result: '' },
      {
        name: 'read',
        arguments: '{}',
        result: '',
        output: 'x'.repeat(5000) + 'TAIL',
        compacted: true,
      },
    ],
  },
]
const view = (p: Partial<Parameters<typeof Inspector>[0]> = {}) =>
  render(<Inspector messages={messages} tab="tools" onTab={() => {}} onSelect={() => {}} {...p} />)
const detail = () => document.querySelector('.inspector__detail')!

describe('Inspector', () => {
  afterEach(cleanup)

  it('shows Tools and Agents tabs and lists the items of the active tab', () => {
    const onTab = vi.fn()
    view({ onTab })
    expect(screen.getByRole('tab', { name: 'Tools' }).getAttribute('aria-selected')).toBe('true')
    const items = () => [...document.querySelectorAll('.inspector__item')].map(e => e.textContent)
    expect(items()).toEqual(['bashdone', 'readrunning…'])
    fireEvent.click(screen.getByRole('tab', { name: 'Agents' }))
    expect(onTab).toHaveBeenCalledWith('agents')
    cleanup()
    view({ tab: 'agents' })
    expect(items()).toEqual(['agent · scanrunning…'])
    cleanup()
    view({ messages: [] })
    expect(screen.getByText('No tool calls yet.')).toBeTruthy()
    cleanup()
    view({ messages: [], tab: 'agents' })
    expect(screen.getByText('No sub-agents yet.')).toBeTruthy()
  })

  it('the selected item shows its arguments, live output and result', () => {
    view({ selected: 'm:0' })
    const d = detail()
    expect(d.textContent).toContain('bash')
    expect(d.textContent).toContain('Arguments')
    expect(d.textContent).toContain('{"cmd":"ls"}')
    // A finished call shows its result; the live output is gone (the result holds it).
    expect(d.querySelector('.inspector__output')).toBeNull()
    expect(d.querySelector('.inspector__result')!.textContent).toBe('R0')
    expect(document.querySelector('.inspector__item.bg-assist-bubble')!.textContent).toBe(
      'bashdone',
    )
    cleanup()
    view({ selected: 'm:2' })
    expect(detail().textContent).not.toContain('Arguments')
    expect(detail().textContent).toContain('compacted')
    const out = detail().querySelector('.inspector__output')!
    expect(out.className).toContain('max-h-60')
    expect(out.className).toContain('overflow-auto')
    expect(out.textContent).toBe('x'.repeat(3996) + 'TAIL')
    cleanup()
    const agent = { name: 'agent', arguments: '{"description":"scan","prompt":"P1"}', result: 'R1' }
    const done: ChatMessage[] = [
      { id: 'm', role: 'assistant', content: '', createdAt: 0, tools: [agent] },
    ]
    view({ messages: done, tab: 'agents', selected: 'm:0' })
    expect(detail().textContent).toContain('Prompt')
    expect(detail().textContent).toContain('P1')
    expect(detail().textContent).toContain('Report')
    expect(detail().textContent).not.toContain('Arguments')
  })

  it('without a selection the latest item of the tab is shown', () => {
    view()
    expect(detail().textContent).toContain('TAIL')
    cleanup()
    view({ selected: 'm:1' })
    expect(detail().textContent).toContain('TAIL')
  })

  it('clicking an item selects it', () => {
    const onSelect = vi.fn()
    view({ onSelect })
    fireEvent.click(screen.getByText('bash'))
    expect(onSelect).toHaveBeenCalledWith('m:0')
  })

  it('a closed panel shows only an expand button that calls onToggle', () => {
    const onToggle = vi.fn()
    view({ open: false, onToggle })
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Show inspector' }))
    expect(onToggle).toHaveBeenCalled()
  })

  it('the hide button calls onToggle and the width prop sets the panel width', () => {
    const onToggle = vi.fn()
    view({ width: 250, onToggle })
    const aside = document.querySelector('aside')!
    expect(aside.style.getPropertyValue('--w')).toBe('250px')
    expect(aside.className).toContain('sm:w-(--w)')
    fireEvent.click(screen.getByRole('button', { name: 'Hide inspector' }))
    expect(onToggle).toHaveBeenCalled()
  })
})
