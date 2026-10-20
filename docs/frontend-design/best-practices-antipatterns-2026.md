# Best Practices & Anti-Patterns — claudius React frontend (as of 21 Sep 2026)

**VERIFICATION STATUS: Verified 2026-09-21 via mcp-browser.** Live web research (React 19 /
React Compiler stable in 19.2, Tailwind v4, TanStack Query v5, rehype-harden, WAI-ARIA APG
2026, Shiki, streaming-UI roundups). Real source URLs are in *Sources*.
**Corrections vs. the prior draft:**
- React Compiler is **stable now** (shipped with React 19.2, 1 Oct 2025; on by default in
  Next.js 15) — no longer "coming"; the "let the compiler memoize" stance is settled.
- React 19.2 also stabilized **`<Activity>`**, **`useEffectEvent`**, and **`cacheSignal`** —
  added below (`useEffectEvent` matters for the streaming path).
- **`useFormState` is deprecated → `useActionState`** (both React 19).
- Modal a11y: 2026 APG uses **`aria-modal`** on the dialog instead of `aria-hidden` on the
  background.
- Sanitization: for **agent/LLM markdown** use **rehype-harden** (vercel-labs,
  prompt-injection aware) and sanitize the **parsed HTML/AST, not the raw source** — a
  markdown parser is *not* your XSS boundary.
- Dark mode on Tailwind **v4** is `@theme` + `@variant dark` (OKLCH), not class-switching.
- Lint/format toolchain 2026: **Biome v2 / Oxlint**, or **ESLint 9 flat config**.
- Virtualization: **TanStack Virtual** (primary) + **react-virtuoso** (variable height);
  `react-virtualized` is the legacy one to stop adopting.

> Context: claudius is a Vite + Tailwind React SPA (dark mode) that live-streams a
> coding-agent GUI over WebSocket/SSE from a Kotlin/Spring AI backend — a growing chat
> transcript, live tool-call cards, approve/reject dialogs, settings, sub-agent chips, a
> usage bar, plus code highlighting and inline plan comments.

---

## Best practices 2026

### Component / data / state
- **Server state ≠ client store.** Keep server data (transcript history, tool-call log,
  settings from the API) in a data-fetching layer (TanStack Query / React Query). The
  client store (Zustand/Jotai) holds *UI-only* state: which panel is open, draft message
  text, expand/collapse. TanStack Query v5's whole pitch is "replaces the boilerplate of
  managing server state" — it is explicitly **not** a client-store replacement.
- **Keep local state local.** Lift state only when two+ components genuinely share it;
  don't hoist to a store "just in case."
- **Derive, don't store.** Compute derived values in render (or `useMemo` only if the
  compiler can't see it). No `useEffect` to sync a value that's derivable from props/state.
- **Discriminated-union event types.** Type the WS/SSE message protocol as a closed union
  (`{type:'token'|'tool_call'|'tool_result'|'agent_start'...}`) — exhaustiveness checks
  catch missing handlers.
- **Stable identity for list items.** Key transcript/tool-card rows by a stable server id
  (event/message id), never by array index.

### React 19 & the React Compiler (the settled shift)
- **Let the React Compiler do memoization.** It is **stable since React 19.2 (Oct 2025)**
  and on by default in new Next.js projects — auto-memoization removes most manual
  `useMemo`/`useCallback`/`memo`. Prefer *not* adding them by hand; never disable the
  compiler to paper over a re-render.
- **React 19.2 additions.** **`<Activity>`** keeps background UI (e.g. a hidden sub-agent
  panel) mounted and its state preserved without full unmount/remount — use it for
  background sub-agent views. **`useEffectEvent`** gives a stable ref to an event/closure
  without re-running the effect — useful for stream callbacks that must not churn the
  effect lifecycle. **`cacheSignal`** for reactive caching inside the render layer.
- **`useOptimistic` for approve/reject.** Optimistically flip a tool-call card to
  "approved" the instant the user clicks, then reconcile on server confirmation (rolls back
  on error).
- **Actions + `useActionState` / `useFormStatus`** for submit-style flows (send message,
  save settings): they give you pending state + error + reset without hand-rolled async in
  `onClick`. `useActionState` replaces the now-deprecated `useFormState`. Prefer native
  `<form>` + action over controlled click handlers.
- **`use()`** replaces `useContext` and reads promises — use it inside Suspense for lazy
  data / context.
- **Context-as-a-prop** (`<Ctx>`) over `<Ctx.Provider>` where you're already passing a
  value; **`ref` as a prop** — drop most `forwardRef` wrappers.
- **Server Components for the static shell.** Initial history load, settings page, and any
  server-fetched view benefit from RSC + Suspense. The live streaming surface stays
  client-side — don't force RSC onto it.
- **`useTransition` / `startTransition`** for non-urgent work: mark transcript token appends
  and list updates as transitions so input stays responsive during a fast stream (a
  low-priority transition yields to urgent work like a keystroke).
- **Progressive rendering with Suspense + error boundaries** at the boundaries of each
  panel, so one failed tool card can't blank the transcript.

### Streaming / progressive rendering
- **Coalesce token updates.** Don't `setState` per token — batch into a ref and flush on
  `requestAnimationFrame` (~60fps) or a short interval; the browser can't paint faster than
  that anyway.
- **Keep the live stream out of the global store.** Buffer tokens in a ref / local state,
  commit a *message object* to the store (or data layer) only when it's finalized or
  periodically — not on every token.
- **Backpressure.** Cap the pending buffer and throttle commits; don't let an inbound 100
  msg/s stream out-run your paint budget.
- **Idempotent event handling.** Dedupe by event id / sequence number; a redelivered or
  replayed event must not duplicate a card.
- **Unmount cleanup.** Abort the SSE/WebSocket request (AbortController) and cancel timers
  on unmount — never leak a stream into a torn-down component.
- **Auto-scroll vs. user-scroll.** Auto-follow the bottom only while the user is pinned to
  the bottom; if they scroll up, pause auto-scroll and show a "jump to latest" affordance.

### Accessibility
- **Transcript = `role="log"` + `aria-live="polite"`** (not `assertive`) so screen readers
  announce new content without interrupting; `role="log"` carries an *implicit* `aria-live`
  of `polite`, so the region is a running feed of updates. Keep it bounded.
- **Approve/reject dialogs are real modals:** focus-trapped, `Esc` closes, focus returns to
  the trigger on close, labelled with `aria-labelledby`/`aria-describedby`. Per the 2026
  WAI-ARIA APG, set **`aria-modal="true"`** on the dialog (it makes the background inert
  for AT) rather than juggling `aria-hidden` on the surrounding content.
- **Keyboard-first:** every tool-card action is reachable and operable by keyboard; visible
  focus rings; no keyboard traps.
- **Respect `prefers-reduced-motion`** (WCAG 2.2.2 / 2.3.3) for auto-scroll/animations;
  provide non-color status (icon/text, not just red/green) for tool-call state.
- **Color-contrast** in dark mode (the primary theme here) — verify WCAG AA on text and
  status chips.

### Security
- **React's built-in escaping** makes JSX XSS-safe — rely on it; the risk is only where you
  opt out.
- **Never render un-sanitized HTML/markdown via `dangerouslySetInnerHTML`.** Agent output
  and user content are untrusted. **A markdown parser is not your XSS boundary** — turn off
  raw HTML *and* still apply an explicit sanitizer; sanitize the **parsed HTML/AST, not the
  raw source** (sanitizing source pre-parse is unsafe — the parser can regenerate executable
  HTML). For **agent/LLM markdown specifically**, use **rehype-harden** (vercel-labs,
  validates link/image URL origins to blunt prompt-injection) and/or **DOMPurify** on the
  output, or render markdown to React elements (e.g. `codeToHast`) rather than `innerHTML`.
- **Code highlighting:** use Shiki/Prism with escaped output (prefer Shiki in a web worker
  — its own best-performance guide says highlighting is CPU-heavy; offload it). Render via
  `codeToHast` (HAST→React), never `innerHTML` a highlighter's HTML.
- **No secrets/keys rendered client-side**; settings that hold API tokens must round-trip
  through the backend and never be logged or echoed into the DOM.

### Forms
- Native `<form>` + Action with progressive enhancement; controlled inputs only where you
  need live transform; use `useFormStatus` for pending UI.

### TypeScript
- **`strict` on**; no `any` (use `unknown` + narrowing); prefer `satisfies` over casts;
  avoid `as` assertions on protocol messages — a wrong cast there is a live bug.
- Discriminated unions for all WS/SSE event shapes and for tool-call status.

### i18n / theming
- **Dark mode via Tailwind v4 `@theme` + `@variant dark`** (design tokens as CSS
  variables, OKLCH, native `prefers-color-scheme`), not hardcoded colors or per-property
  class switching; keep tokens in one place.
- **i18n-ready:** no hardcoded UI strings in components; no assumptions about LTR/width.

### Toolchain (lint / format / build)
- **ESLint 9 flat config is the default**, or the Rust toolchain **Biome v2** (linter +
  formatter in one binary, type-aware rules since v2) / **Oxlint** for speed. Don't ship
  legacy `.eslintrc` or a Biome v1 config.

### Testing
- Test **behavior, not implementation** (Testing Library); mock the stream (MSW / fake
  WS/SSE source) rather than the internals; snapshot sparingly; e2e the approve/reject path
  with Playwright. Assert on DOM the user sees, not on store shape.

### Build / bundle
- Vite with **route/component `React.lazy` + Suspense** (settings, sub-agent detail);
  **tree-shake**, watch bundle with an analyzer; offload Shiki to a worker; avoid large
  deps that pull the whole agent surface above the fold.

### Changed since older advice (flag in reviews)
| Old advice (≤2023) | 2026 consensus |
| --- | --- |
| Memoize aggressively with `useMemo`/`useCallback`/`memo` | **React Compiler** (stable in 19.2) auto-memos; stop hand-memoizing; don't disable it |
| Global store for everything | **Server state in TanStack Query**; store only UI state |
| Hand-rolled async in `onClick` | **Actions + `useActionState` + `useFormStatus`** (`useFormState` deprecated) |
| Manual optimistic state | **`useOptimistic`** |
| `useContext` + `forwardRef` | **`use()`**, context-as-prop, `ref`-as-prop, `useEffectEvent` |
| Per-token `setState` streaming | **Coalesce + `useTransition`**, keep stream out of the store |
| `aria-hidden` on background for modals | **`aria-modal="true"`** on the dialog (2026 APG) |
| Sanitize markdown source | **Sanitize parsed HTML/AST** (rehype-harden / DOMPurify); parser ≠ XSS boundary |

---

## Anti-patterns 2026
- **`setState` in a per-token loop** for streaming → jank, lost updates; coalesce instead.
- **Storing server/stream state in a global store** → whole app re-renders on every token.
- **Re-rendering the entire list on each token** (un-virtualized growing array) → O(n) per
  update; virtualize + commit finalized items only.
- **`useEffect` over-engineering** — syncing derivable values, dependency-array churn,
  "run once" via empty-deps-as-a-hack; derive in render or use `useEffectEvent` instead.
- **`setTimeout`/`setInterval` for "next tick"** — use `useTransition`, effects with proper
  cleanup, or the compiler; timers are a smell.
- **Prop-drilling the whole store / "props as a bag"** — passing 15 props or spreading
  `...rest` through everything; keep props to what a component actually uses.
- **Inline objects/functions in props** (`onClick={() => f(x)}`, `style={{...}}`) creating
  new identities each render → children that can't stay memoized.
- **Unstable / unkeyed list keys** — indexing a reorderable list; duplicate/empty keys.
- **Unbounded growing arrays without virtualization** — the transcript is unbounded; it must
  be windowed.
- **Blocking the main thread on the stream** — parsing/highlighting a huge transcript or the
  whole event log inline during a fast stream; offload to a worker / throttle.
- **Ignoring Suspense + error boundaries** — no error boundary means one bad tool card
  crashes the transcript.
- **Over-abstracting / premature generics** — a 3-arg generic "genericToolCard" for two
  variants; a factory for one product. Ship the concrete thing.
- **Deep nesting** — callback hell, nested ternaries, 5-deep indented render branches.
- **Over-fetching** — pulling the full history/tool-log/usage when the view needs a page;
  paginate or window the fetch.
- **Not memoizing / not splitting children** so a token append re-renders the settings panel
  and sub-agent chips too.
- **Shipping un-sanitized markdown/HTML** (`dangerouslySetInnerHTML` on agent output, or
  trusting the markdown parser as the security boundary) → XSS / prompt-injection.
- **Hardcoded styles / colors** instead of `@theme` CSS variables — dark-mode and theming
  debt.
- **a11y gaps** — modals without focus trap/return, no `aria-modal`, no `aria-live`
  transcript, color-only status, missing keyboard paths.
- **No unmount cleanup** — a live WS/SSE or rAF timer leaking after the view unmounts.
- **Legacy tooling** — `.eslintrc` / Biome v1 config, or adopting `react-virtualized` (the
  library to *stop* adopting) instead of TanStack Virtual.

---

## Streaming / live-agent-specific
- **Coalesce token updates** into a ref, flush on rAF/throttle — never per-token state.
- **Keep the stream out of the global store;** commit finalized messages, not tokens.
- **Backpressure:** cap the pending buffer; the UI must stay responsive to a bursty stream.
- **Unmount cleanup:** abort the request and cancel timers when the component/view unmounts.
- **Auto-scroll vs. user-scroll:** follow the bottom only while pinned; show "jump to
  latest" when the user scrolls up.
- **Optimistic approve/reject:** flip card state on click via `useOptimistic`, reconcile on
  server confirm, roll back on error.
- **Idempotent event handling:** dedupe by event id/sequence so replays/redeliveries don't
  duplicate cards.
- **Discriminated-union event protocol** + exhaustive switch, so a new event type can't be
  silently dropped.
- **Non-urgent updates via `useTransition`** so a fast stream never blocks typing.
- **Stable stream callbacks via `useEffectEvent`** so the WS/SSE handler ref doesn't churn
  the effect lifecycle or re-subscribe.
- **Progressive rendering:** stream in partial content under Suspense; a partial/failed
  card must not block the rest.

---

## For claudius specifically (checklist)
- **Transcript:** virtualized (**TanStack Virtual** / **react-virtuoso** for variable
  height), key rows by stable message id, `role="log"` + `aria-live="polite"`, tokens
  coalesced via ref+rAF, auto-scroll only when pinned to bottom, "jump to latest" when
  scrolled up.
- **Tool-call cards:** memoized row component (compiler handles it), discriminated-union-
  typed, explicit status transitions (running → approved/rejected → result), non-color
  status indicator.
- **Approve/reject:** `useOptimistic` + Action, idempotent, real modal (focus trap, Esc,
  focus return, `aria-modal="true"`), rolls back on server error.
- **Settings:** local form state + Action submit, `useFormStatus` for pending, React
  Compiler handles memoization; tokens/API keys never rendered in the DOM.
- **Sub-agent chips / panels:** live status, stable keys, virtualize if many; use
  **`<Activity>`** to keep a hidden sub-agent panel mounted without losing its state; don't
  let one agent's stream re-render unrelated chips.
- **Usage bar:** memoized, derived from a usage event, throttled — not recomputed per token.
- **Code highlighting + inline plan comments:** Shiki in a worker with `codeToHast`
  (escaped/HAST output); **sanitized** markdown only (rehype-harden / DOMPurify on the
  parsed output); keep parsing off the main thread.
- **Dark mode:** Tailwind v4 `@theme` + `@variant dark` (CSS vars, OKLCH) +
  `prefers-color-scheme`; WCAG AA contrast.
- **Boundaries:** error boundary per panel + Suspense so a bad stream segment can't blank the
  UI.

---

## Bottom line for claudius
The 2026 story is *less manual work, more discipline at the boundaries*: let the React
Compiler (now stable in 19.2) memoize, put server/stream data in a data-fetching layer
(TanStack Query, not the store), and spend your effort on the live surface — coalesce token
updates, keep the growing transcript virtualized and out of the global store, make
approve/reject an idempotent optimistic Action in a real accessible modal (`aria-modal`,
focus-trapped, focus-returning), and render only **sanitized** agent markdown with
highlighting off the main thread. Get those eight things right and the agent GUI stays
smooth under a fast stream; the React 19/19.2 API surface (`useOptimistic`, Actions,
`use()`, `useEffectEvent`, `<Activity>`) makes each of them shorter than the equivalent
2023 code, and Tailwind v4 + Biome/Oxlint flatten the tooling.

---

## Sources (live, mcp-browser, 2026-09-21)
- React 19.2 & React Compiler (stable) — https://react.dev/blog/2025/10/01/react-19-2 ;
  https://shubhamjha.com/blog/react-compiler-status
- React 19.2 `Activity` / `useEffectEvent` / `cacheSignal` —
  https://blog.logrocket.com/react-19-2-is-here/
- React 19 form hooks / Actions / `useOptimistic` (`useFormState`→`useActionState`) —
  https://stacknotice.com/blog/react-19-form-hooks-useactionstate-guide-2026 ;
  https://samcheek.com/blog/react-19-features-useoptimistic-use-server-actions-2026
- Server vs client state (TanStack Query v5 vs Zustand/Jotai) —
  https://tanstack.com/query/latest/docs/framework/react/guides/does-this-replace-client-state ;
  https://vucense.com/dev-corner/react-state-management-2026/
- Tailwind v4 dark mode / `@theme` — https://tailwindcss.com/docs/dark-mode ;
  https://tailwindcss.com/docs/theme
- Lint/format 2026 (Biome v2 / Oxlint / ESLint 9 flat config) —
  https://www.devbolt.dev/blog/biome-vs-eslint ;
  https://tech-insider.org/eslint-vs-biome-vs-oxlint-2026/
- Virtualization 2026 (TanStack Virtual / react-virtuoso / react-window; avoid
  react-virtualized) — https://www.pistack.xyz/posts/2026-08-17-javascript-virtual-scroll-
  libraries-tanstack-virtual-react-window-react-virtualized-comparison/
- Sanitizing agent/LLM markdown (rehype-harden, "parser is not the XSS boundary") —
  https://github.com/vercel-labs/markdown-sanitizers ;
  https://github.com/vercel-labs/markdown-sanitizers/tree/main/rehype-harden ;
  https://dev.to/mdfold/your-markdown-parser-is-not-your-xss-boundary-2gpk
- ARIA live regions (`role="log"`/`aria-live`) —
  https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Guides/Live_regions
- Modal dialog / `aria-modal` focus management (WAI-ARIA APG 2026) —
  https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/examples/dialog/
- `prefers-reduced-motion` (WCAG 2.2.2 / 2.3.3) —
  https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-motion
- Shiki in a Web Worker (`codeToHast`) — https://shiki.style/guide/best-performance ;
  https://www.npmjs.com/react-shiki
- Streaming AI chat UI in React (`useTransition`, RSC/Suspense/SSE) —
  https://iocombats.com/blogs/streaming-ai-chat-ui-react-architecture ;
  https://www.prompt-kit.com/ai-chat-ui
