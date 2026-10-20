// M12: one WebSocket to /api/ws. JSON frames; pings get pongs; 3 s without a frame
// counts as a dead link. Reconnects with backoff (0.5 s, 1 s, 2 s, … max 5 s).
export type Status = 'connecting' | 'open' | 'reconnecting'
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Frame = any
export type Socket = { send: (m: Frame) => void; close: () => void }

export const wsUrl = () =>
  `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/ws`

export function connect(
  url: string,
  h: { onMessage: (m: Frame) => void; onStatus?: (s: Status) => void },
): Socket {
  let ws: WebSocket
  let tries = 0
  let closed = false
  let watchdog: ReturnType<typeof setTimeout> | undefined
  let retry: ReturnType<typeof setTimeout> | undefined
  const queue: string[] = []

  const arm = () => {
    clearTimeout(watchdog)
    watchdog = setTimeout(() => {
      // Do not wait for a hung socket's close handshake.
      ws.onclose = null
      ws.close()
      dropped()
    }, 3000)
  }
  const dropped = () => {
    clearTimeout(watchdog)
    if (closed) return
    h.onStatus?.('reconnecting')
    retry = setTimeout(open, Math.min(500 * 2 ** tries++, 5000))
  }
  const open = () => {
    ws = new WebSocket(url)
    ws.onopen = () => {
      tries = 0
      arm()
      for (const m of queue.splice(0)) ws.send(m)
      h.onStatus?.('open')
    }
    ws.onmessage = e => {
      arm()
      const m = JSON.parse(e.data)
      if (m.type === 'ping') send({ type: 'pong' })
      else h.onMessage(m)
    }
    ws.onclose = dropped
  }
  const send = (m: Frame) => {
    const s = JSON.stringify(m)
    if (ws.readyState === WebSocket.OPEN) ws.send(s)
    else queue.push(s)
  }
  open()
  return {
    send,
    close: () => {
      closed = true
      clearTimeout(watchdog)
      clearTimeout(retry)
      ws.close()
    },
  }
}
