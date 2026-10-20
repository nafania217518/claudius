// The run event handlers. M12: events arrive as socket frames (see api/socket.ts).
import type { ChatMessage, ToolInvocation } from './workspaces'

// M4: `tool_call`/`tool_result` frames carry a pre-serialized JSON ToolInvocation
// (the backend's flat wire shape); the tool handlers parse it before handing it on.
export type StreamHandlers = {
  onToken: (delta: string) => void
  onReasoning?: (delta: string) => void
  onToolCall?: (call: ToolInvocation) => void
  onToolResult?: (result: ToolInvocation) => void
  // M8: a live output delta (in `result`) for a running call, before its tool_result.
  onToolOutput?: (chunk: ToolInvocation) => void
  // M7: the backend holds this call until an `answer` frame approves or rejects it.
  onPermission?: (call: ToolInvocation) => void
  // `event: error` carries plain error text; the server still ends with `done`.
  onError?: (message: string) => void
  // M11: the compaction summary (before the turn's tokens) and the new context size.
  onCompacted?: (msg: Omit<ChatMessage, 'id'>) => void
  // A compaction starts now; `compacted` or `error` follows.
  onCompacting?: () => void
  onUsage?: (contextTokens: number) => void
  onDone: () => void
}

// M12: route one socket `event` frame to its handler. `data` is the raw payload string.
export function dispatch(event: string, data: string, handlers: StreamHandlers): void {
  if (event === 'token') handlers.onToken(data)
  else if (event === 'reasoning') handlers.onReasoning?.(data)
  else if (event === 'tool_call') handlers.onToolCall?.(JSON.parse(data))
  else if (event === 'tool_result') handlers.onToolResult?.(JSON.parse(data))
  else if (event === 'tool_output') handlers.onToolOutput?.(JSON.parse(data))
  else if (event === 'permission') handlers.onPermission?.(JSON.parse(data))
  else if (event === 'error') handlers.onError?.(data)
  else if (event === 'compacting') handlers.onCompacting?.()
  else if (event === 'compacted') handlers.onCompacted?.(JSON.parse(data))
  else if (event === 'usage') handlers.onUsage?.(JSON.parse(data).contextTokens)
  else if (event === 'done') handlers.onDone()
}
