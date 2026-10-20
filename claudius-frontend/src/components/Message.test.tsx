import { describe, it, expect, afterEach } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import Message from './Message'

// Static markup is enough to prove the text, the markdown and the placeholder land.
describe('Message', () => {
  afterEach(cleanup)

  it('renders the role and content', () => {
    const html = renderToStaticMarkup(
      <Message message={{ id: '1', role: 'assistant', content: 'Hello there', createdAt: 0 }} />,
    )
    expect(html).toContain('Hello there')
    expect(html).toContain('assistant')
  })

  it('a streaming assistant message with no text shows the placeholder, a finished one shows no bubble', () => {
    const empty = { id: '1', role: 'assistant' as const, content: '', createdAt: 0 }
    expect(renderToStaticMarkup(<Message message={empty} streaming />)).toContain('…')
    const done = renderToStaticMarkup(<Message message={{ ...empty, error: 'stopped' }} />)
    expect(done).not.toContain('…')
    expect(done).not.toContain('msg__body')
    expect(done).toContain('assistant')
    expect(done).toContain('stopped')
  })

  it('a single newline in a reply renders as a line break', () => {
    const html = renderToStaticMarkup(
      <Message message={{ id: '1', role: 'assistant', content: '1\n2\n3', createdAt: 0 }} />,
    )
    expect(html.match(/<br/g)?.length).toBe(2)
  })

  it('an assistant reply renders markdown: headings, bold, lists and fenced code', () => {
    const content = '# Title\n\n**bold**\n\n- one\n- two\n\n```\ncode\n```\n\n<b>raw</b>'
    const html = renderToStaticMarkup(
      <Message message={{ id: '1', role: 'assistant', content, createdAt: 0 }} />,
    )
    expect(html).toContain('msg__body')
    expect(html).toContain('<h1>Title</h1>')
    expect(html).toContain('<strong>bold</strong>')
    expect(html).toContain('<li>one</li>')
    expect(html).toMatch(/<pre><code>code\n<\/code><\/pre>/)
    expect(html).not.toContain('<b>raw</b>')
    const user = renderToStaticMarkup(
      <Message message={{ id: '2', role: 'user', content: '**hi**', createdAt: 0 }} />,
    )
    expect(user).toContain('**hi**')
  })

  it('tool and agent chips show the name and status and open the item on click', () => {
    const tools = [
      { id: 'a', name: 'bash', arguments: '{}', result: '' },
      { id: 'b', name: 'agent', arguments: '{"description":"scan","prompt":"p"}', result: 'r' },
      { id: 'c', name: 'read', arguments: '{}', result: 'SECRET', compacted: true },
    ]
    const opened: [string, string][] = []
    const { container } = render(
      <Message
        message={{ id: 'm', role: 'assistant', content: '', tools, createdAt: 0 }}
        onOpen={(k, kind) => opened.push([k, kind])}
      />,
    )
    const chips = [...container.querySelectorAll('button.chip')].map(b => b.textContent)
    expect(chips).toEqual(['bash · running…', 'agent · scan · done', 'read · done · compacted'])
    expect(container.textContent).not.toContain('SECRET')
    fireEvent.click(screen.getByText('bash · running…'))
    fireEvent.click(screen.getByText('agent · scan · done'))
    expect(opened).toEqual([
      ['m:0', 'tools'],
      ['m:1', 'agents'],
    ])
  })

  it('the approval row stays in the chat', () => {
    const tool = { id: 'a', name: 'bash', arguments: '{}', result: '' }
    const { container } = render(
      <Message
        message={{ id: '1', role: 'assistant', content: '', tools: [tool], createdAt: 0 }}
        approval={tool}
      />,
    )
    expect(container.querySelector('.tool__approval')!.textContent).toContain(
      'Waiting for your approval',
    )
  })

  it('a pending approval shows Approve and Reject on its tool card', () => {
    const tools = [
      { id: 'a', name: 'bash', arguments: '{}', result: '' },
      { id: 'b', name: 'read', arguments: '{}', result: '' },
    ]
    render(
      <Message
        message={{ id: '1', role: 'assistant', content: '', tools, createdAt: 0 }}
        approval={tools[0]}
        onAnswer={() => {}}
      />,
    )
    expect(screen.getByText('Waiting for your approval')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeTruthy()
  })

  it('Approve and Reject call onAnswer with true and false', () => {
    const tool = { id: 'a', name: 'bash', arguments: '{}', result: '' }
    const answers: boolean[] = []
    render(
      <Message
        message={{ id: '1', role: 'assistant', content: '', tools: [tool], createdAt: 0 }}
        approval={tool}
        onAnswer={a => answers.push(a)}
      />,
    )
    fireEvent.click(screen.getByText('Approve'))
    fireEvent.click(screen.getByText('Reject'))
    expect(answers).toEqual([true, false])
  })

  it('shows a collapsible thinking block when the trace is present', () => {
    const html = renderToStaticMarkup(
      <Message
        message={{
          id: '1',
          role: 'assistant',
          content: 'Answer',
          thinking: 'Let me think',
          createdAt: 0,
        }}
      />,
    )
    expect(html).toContain('Let me think')
    expect(html).toContain('<details')
    expect(html).toContain('<summary>thinking</summary>')
  })

  it('hides the thinking block when there is no trace', () => {
    const html = renderToStaticMarkup(
      <Message message={{ id: '1', role: 'assistant', content: 'Answer', createdAt: 0 }} />,
    )
    expect(html).not.toContain('<details')
  })

  it('an assistant turn shows which model answered', () => {
    const html = renderToStaticMarkup(
      <Message
        message={{ id: '1', role: 'assistant', content: 'Hi', model: 'local/qwen3', createdAt: 0 }}
      />,
    )
    expect(html).toContain('local/qwen3')
  })

  it('a summary message renders as a collapsible compacted block', () => {
    const html = renderToStaticMarkup(
      <Message message={{ id: '1', role: 'summary', content: 'Earlier work', createdAt: 0 }} />,
    )
    expect(html).toContain('<details')
    expect(html).toContain('<summary>Context compacted</summary>')
    expect(html).toContain('Earlier work')
    expect(html).toContain('text-muted')
  })

  it('the summary block shows the estimated tokens saved', () => {
    const row = { id: '1', role: 'summary' as const, content: 'x', createdAt: 0 }
    const html = renderToStaticMarkup(
      <Message message={{ ...row, tokensBefore: 19800, tokensAfter: 1200 }} />,
    )
    expect(html).toContain('Context compacted · ~18.6k tokens saved (19.8k → 1.2k)')
    const plain = renderToStaticMarkup(
      <Message message={{ ...row, tokensBefore: null, tokensAfter: null }} />,
    )
    expect(plain).toContain('<summary>Context compacted</summary>')
  })

  it('a saved turn error shows under the assistant message', () => {
    const html = renderToStaticMarkup(
      <Message
        message={{
          id: 'a',
          role: 'assistant',
          content: 'part',
          error: 'interrupted by a server restart',
          createdAt: 0,
        }}
      />,
    )
    expect(html).toContain('role="alert"')
    expect(html).toContain('interrupted by a server restart')
  })

  // Lists thinking, chip and answer blocks in document order.
  const order = (el: HTMLElement) =>
    [...el.querySelectorAll('.thinking, .chip, .msg__body')].map(n =>
      n.classList.contains('chip')
        ? 'chip'
        : (n.classList.contains('thinking') ? 'T:' : 'A:') +
          n.textContent?.replace(/^thinking/, ''),
    )

  it('a turn renders thinking, tool chips, more thinking and the answer in arrival order', () => {
    const tools = [
      { id: 'a', name: 'now', arguments: '{}', result: 'x', thinkingAt: 2, answerAt: 0 },
    ]
    const { container } = render(
      <Message
        message={{
          id: '1',
          role: 'assistant',
          content: 'done',
          thinking: 'ABCD',
          tools,
          createdAt: 0,
        }}
      />,
    )
    expect(order(container)).toEqual(['T:AB', 'chip', 'T:CD', 'A:done'])
  })

  it('a row without offsets keeps the old order: thinking, chips, answer', () => {
    const tools = [
      { id: 'a', name: 'x', arguments: '{}', result: '' },
      { id: 'b', name: 'y', arguments: '{}', result: '' },
    ]
    const { container } = render(
      <Message
        message={{
          id: '1',
          role: 'assistant',
          content: 'done',
          thinking: 'ABCD',
          tools,
          createdAt: 0,
        }}
      />,
    )
    expect(order(container)).toEqual(['T:ABCD', 'chip', 'chip', 'A:done'])
    expect(container.querySelectorAll('.tools')).toHaveLength(1)
  })

  it('parallel calls with the same offsets share one chips row', () => {
    const tools = [
      { id: 'a', name: 'x', arguments: '{}', result: '', thinkingAt: 1, answerAt: 2 },
      { id: 'b', name: 'y', arguments: '{}', result: '', thinkingAt: 1, answerAt: 2 },
    ]
    const { container } = render(
      <Message
        message={{
          id: '1',
          role: 'assistant',
          content: 'okdone',
          thinking: 'AB',
          tools,
          createdAt: 0,
        }}
      />,
    )
    expect(container.querySelectorAll('.tools')).toHaveLength(1)
    expect(container.querySelectorAll('.tools .chip')).toHaveLength(2)
    expect(order(container)).toEqual(['T:A', 'A:ok', 'chip', 'chip', 'T:B', 'A:done'])
  })
})
