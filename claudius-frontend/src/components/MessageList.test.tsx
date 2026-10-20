import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import MessageList from './MessageList'

describe('MessageList', () => {
  it('shows the empty prompt when there are no messages', () => {
    const html = renderToStaticMarkup(<MessageList messages={[]} />)
    expect(html).toContain('Ask something to start.')
  })

  it('renders a Message per turn', () => {
    const html = renderToStaticMarkup(
      <MessageList messages={[{ id: '1', role: 'user', content: 'hi', createdAt: 0 }]} />,
    )
    expect(html).toContain('msg--user')
    expect(html).toContain('hi')
  })

  it('the streaming flag and the approval reach only the last message', () => {
    const tool = { id: 't', name: 'bash', arguments: '{}', result: '' }
    const m = (id: string) => ({
      id,
      role: 'assistant' as const,
      content: '',
      tools: [tool],
      createdAt: 0,
    })
    const html = renderToStaticMarkup(
      <MessageList messages={[m('1'), m('2')]} streaming approval={tool} onAnswer={() => {}} />,
    )
    expect(html.match(/Waiting for your approval/g)).toHaveLength(1)
    expect(html.match(/">…<\/div>/g)).toHaveLength(1)
  })
})
