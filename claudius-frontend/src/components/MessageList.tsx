import type { ChatMessage, ToolInvocation } from '../api/workspaces'
import Message from './Message'

// The streaming flag and the pending approval belong to the last message only.
export default function MessageList({
  messages,
  streaming,
  approval,
  onAnswer,
  onOpen,
}: {
  messages: ChatMessage[]
  streaming?: boolean
  approval?: ToolInvocation | null
  onAnswer?: (approved: boolean) => void
  onOpen?: (key: string, kind: 'tools' | 'agents') => void
}) {
  if (messages.length === 0) {
    return <p className="empty text-muted text-center mt-12">Ask something to start.</p>
  }
  const last = messages.length - 1
  return (
    <div className="messages flex flex-col gap-4">
      {messages.map((m, i) =>
        i === last ? (
          <Message
            key={m.id}
            message={m}
            streaming={streaming}
            approval={approval}
            onAnswer={onAnswer}
            onOpen={onOpen}
          />
        ) : (
          <Message key={m.id} message={m} onOpen={onOpen} />
        ),
      )}
    </div>
  )
}
