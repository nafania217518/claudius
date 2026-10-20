// Token counts: 950, 1.2k, 18.6k, 128k.
export const fmt = (n: number) => {
  if (n < 1000) return String(n)
  const k = n / 1000
  return `${k >= 100 ? Math.round(k) : +k.toFixed(1)}k`
}

// An agent call's label: `agent · <description>`, or plain `agent`.
export function agentLabel(args: string) {
  try {
    const d = JSON.parse(args)?.description
    return typeof d === 'string' && d ? `agent · ${d}` : 'agent'
  } catch {
    return 'agent'
  }
}
