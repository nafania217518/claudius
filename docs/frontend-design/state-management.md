# React State Management — Research Reference

For the `claudius` frontend: a React SPA that live-streams agent state
(plan / current tool call / reasoning / per-tool status / settings panel) from a
Kotlin + Spring AI backend over **WebSocket / SSE**.

Key constraint that shapes every verdict below: **high-frequency, per-item streamed
updates** (LLM tokens, tool events) + **per-item live status** + a **settings panel**.
In 2026 the consensus is "different types of state get different tools — no single
library owns all state." The hot stream should live **outside** the render path; the
chosen library handles shared UI + settings, not the raw token firehose.

Research date: **21 September 2026**.

---

## 2026 mental model (read this first)

State categories, each with its own tool (from the 2026 consensus — do not collapse
them into one store):

| State type | Examples in claudius | Tool |
| --- | --- | --- |
| Server / async state | session history, saved memory, provider config from API | **TanStack Query** (or SWR) |
| Global client state | selected session, active tool, sidebar, settings panel | **Zustand** (default) / Jotai / RTK |
| Local UI state | modal open, tab, hover | `useState` / `useReducer` |
| Form state | the settings panel, provider config form | **React Hook Form** / TanStack Form |
| Workflow / machine state | agent run lifecycle (idle→running→awaiting-approval→done) | **XState** or `useReducer` |
| URL / router state | which session/pane is open | router (TanStack Router / react-router) |
| Realtime stream | the live token/event firehose | **external store + `useSyncExternalStore`**, buffered, RAF-flushed |

**Anti-pattern to avoid:** storing the streaming token firehose *inside* Zustand/Redux
as `SET_TOKEN` actions. That reproduces the exact "RENDER STORM" the 2026 streaming
guides warn about. Buffer in a `useRef` outside React, flush a coalesced snapshot at a
display-friendly cadence, then hand the snapshot to the store/components.

---

## The streaming pattern (the part that actually matters for claudius)

From 2025–2026 streaming/LLM guides — this is the load-bearing design, independent of
which library you pick for UI state:

- **Never `setState` per incoming message.** At ~30 tokens/sec each update schedules a
  full reconcile; jank starts ~50ms. (SitePoint 2026)
- **Buffer → flush → coalesce.** `Transport (WS/SSE) → mutable buffer in `useRef`
  (outside React) → `requestAnimationFrame` tick → one `setState`/one snapshot per frame
  → render.` React sees ≤1 update/frame regardless of message rate.
- **`useSyncExternalStore`** is React's official primitive for subscribing to an external
  mutable store with correct concurrent-mode semantics. Use it when the stream buffer
  logic is shared across components. For simple cases React docs still recommend
  `useState`/`useReducer` over it.
- **Coalesce to a display cadence** (throttle to ~20–60fps); don't render every token.
- **`React.memo` + custom comparators** on hot child rows; **virtualize** long logs/
  history with `@tanstack/react-virtual` so DOM node count stays constant.
- **Escape hatch:** for a single ultra-hot value (e.g. a live timer) write to the DOM
  via a ref, bypassing React entirely.
- **Backpressure:** cap buffer/state length (`maxBufferSize`) to avoid unbounded growth
  on indefinite streams; close WS/EventSource + cancel RAF on unmount (the #1 leak bug).
- **`startTransition`/`useTransition`** marks the stream update non-urgent so input stays
  responsive; it reduces jank but does not fix render count.

Verdict: this is the core of claudius' live view. Build it once as a reusable hook
(`useAgentStream`) and feed coalesced snapshots into whatever UI store you choose.

---

## Options

### 1. `useState` / `useReducer` (React built-in)
- **What:** React's native local-state primitives. `useReducer` = Redux pattern at
  component level, no library.
- **2026 status:** stable, built-in, zero bytes.
- **Plus:** zero dependency, best TS inference, no provider/hydration issues.
- **Minus:** scales badly past 3 levels of prop drilling; no cross-tree sharing.
- **Use in claudius?** Yes — for local UI (modal/tab), and `useReducer` for a
  single-component agent-run state machine. Not for global/session state.

### 2. Context API (React built-in)
- **What:** built-in tree-wide data transport. Not a state manager — no selectors or
  subscription scoping.
- **2026 status:** stable, built-in.
- **Plus:** zero dependency; fine for low-frequency global state (theme, i18n, DI,
  read-only auth).
- **Minus:** *every* consumer re-renders on any value change; wrong for high-churn /
  frequent updates.
- **Use in claudius?** Sparingly — theme/provider-config context only. **Not** for the
  streamed agent state (would re-render the whole tree per token).

### 3. Zustand ⭐ (recommended default)
- **What:** tiny external store, flux-inspired, no boilerplate. No Provider.
- **2026 status:** ~1 KB, ~58k★, the modern default for global client state in new
  React apps. React 18/19 concurrent-safe.
- **Plus:** granular per-selector subscriptions (only re-render on the slice you read),
  middleware (immer, devtools, `persist` for settings), great TS inference, works with
  `useSyncExternalStore` semantics.
- **Minus:** no built-in DevTools (middleware), no enforced structure (discipline),
  SSR hydration needs care (not an issue in a pure SPA).
- **Use in claudius?** **Yes — primary choice.** UI/session state, active tool, selected
  session, settings panel (with `persist`). Keep the raw stream out of it (see streaming
  pattern). Split by domain: `useUIStore`, `useSessionStore`, `useSettingsStore`.

### 4. Jotai
- **What:** atomic model — many independent "atoms," composable/derived; granular
  per-atom subscription.
- **2026 status:** ~3 KB, ~21k★, growing; the de-facto **Recoil replacement**, actively
  maintained, good RSC/SSR.
- **Plus:** finest-grained reactivity in React, clean derived/async atoms,
  `atomWithStorage`, `atomWithQuery` (Jotai + TanStack Query bridge).
- **Minus:** more conceptual overhead than Zustand for simple global state; ecosystem
  smaller.
- **Use in claudius?** Good alternative if the UI has many independent fine-grained live
  widgets / lots of derived state. Either Zustand *or* Jotai; don't mix.

### 5. TanStack Query (formerly React Query)
- **What:** server-state manager — caching, dedupe, background refetch, stale-while-
  revalidate, mutations + optimistic updates.
- **2026 status:** ~12 KB, 45M+ weekly downloads, now **v5**, framework-agnostic, the
  dominant server-state tool.
- **Plus:** eliminates the loading/error/stale/refetch boilerplate; the #1 reason to
  avoid over-using a global store.
- **Minus:** not for client-owned state or the realtime firehose; some learning curve.
- **Use in claudius?** **Yes — for server/async state:** fetch session history, saved
  memory, provider configs. **Not** the live WS/SSE stream (that's the streaming
  pattern). Rule: don't copy Query results into Zustand.

### 6. Redux Toolkit (RTK) + RTK Query
- **What:** opinionated, modern Redux — `createSlice` (Immer under the hood),
  single immutable store, serializable actions, time-travel DevTools; RTK Query bundles
  server-state fetching.
- **2026 status:** ~11 KB, ~61k★, stable/entrenched in enterprise; no longer the default
  for new small apps.
- **Plus:** strict predictable flow, superb DevTools + audit trail, RTK Query for server
  state in one dependency.
- **Minus:** ~10x more boilerplate than Zustand; overkill for a lean SPA.
- **Use in claudius?** Only if auditability/time-travel/debuggability of the full event
  log is a hard requirement. Otherwise Zustand is lighter and sufficient.

### 7. Redux (classic, no RTK)
- **What:** original hand-rolled reducer + action-creator pattern.
- **2026 status:** **legacy / declining** — RTK is the only sanctioned way to write
  Redux now.
- **Use in claudius?** **No.** If using Redux at all, use RTK.

### 8. MobX
- **What:** transparent reactivity — observables/computed/actions, class-friendly,
  auto fine-grained subscriptions.
- **2026 status:** ~16 KB, ~28k★, capable but declining in new adoption; superseded by
  Jotai/Zustand for most cases; RSC compatibility limited.
- **Use in claudius?** No unless the team is already MobX-fluent. Not for a greenfield
  React 19 SPA.

### 9. Valtio
- **What:** proxy-based mutable-feel state (you mutate directly; Proxy snapshots handle
  diffing).
- **2026 status:** ~2.8 KB, actively maintained by pmndrs, **React 19 `use()`
  compatible**; niche but healthy.
- **Plus:** excellent DX for direct mutation, good render optimization.
- **Minus:** no enforced patterns (mutation discipline), weaker SSR story.
- **Use in claudius?** Optional — nice for a self-contained mutable settings/session
  module. Prefer Zustand for consistency unless you specifically want proxy mutation.

### 10. Recoil
- **What:** Meta's atomic global state (ancestors: `RecoilRoot`, atoms, selectors).
- **2026 status:** **effectively unmaintained / superseded.** Last major release
  (0.7.x, 2022); community moved to Jotai.
- **Use in claudius?** **No** — do not use in a new project. (Jotai is its active
  successor.)

### 11. XState
- **What:** finite-state machines / statecharts (v5: actor model, event-driven, strong
  TS inference).
- **2026 status:** ~8 KB, v5 mature, stable niche for complex workflows.
- **Plus:** models the agent-run lifecycle cleanly (idle → running → awaiting-approval →
  done/error), prevents impossible states, visualizes/communicates the flow.
- **Minus:** heavy for simple booleans; learning curve.
- **Use in claudius?** **Maybe — for the agent run/permission flow only.** If the
  approve/reject mutating-tool-call lifecycle gets multi-step, an XState machine (or a
  `useReducer` discriminator) models it well. Skip it for the streamed data itself.

### 12. Signals (Preact `@preact/signals-react` / SolidJS; TC39 proposal)
- **What:** reactive primitive — reads/writes auto-tracked, updates only affected DOM
  nodes, bypasses the reconciler; TC39 Signals proposal progressing.
- **2026 status:** growing/early-adoption, ~1 KB. Not yet native to React (needs adapter).
- **Plus:** exceptional for high-frequency updates; the likely future primitive.
- **Minus:** integration with React's model needs care; not the mainstream React choice.
- **Use in claudius?** Interesting for the hot live-tick path, but **watch, don't
  adopt yet** — the `useSyncExternalStore` + RAF-buffer approach covers the same need
  with mainstream React tooling.

### 13. nanostores
- **What:** tiny (~265 B) framework-agnostic reactive atoms (Astro/multi-framework
  islands).
- **2026 status:** niche; the smallest option, but React integration is thinner than
  Zustand/Jotai.
- **Use in claudius?** No — for a pure React SPA, Zustand/Jotai are strictly better
  integrated.

### 14. Pinia
- **What:** Vue's official store (replaced Vuex).
- **2026 status:** ~14k★, healthy — but **Vue only**.
- **Use in claudius?** **N/A** — claudius is React.

---

## Names from the task list that are not applicable

- **Koa** — a Node HTTP framework, not a React state library. Skip.
- **Akita** — old D3/Redux-flavored data-flow lib; **unmaintained/dead.** Skip.
- **Unstated** — early "lightweight Redux" via containers; **unmaintained/dead.** Skip.
- **MobX-Atom** — not a real standalone package; MobX has `@mobx/atom`-style
  composition but it's part of MobX. Covered under #8.

---

## Recommendation for claudius

**Stack: Zustand + TanStack Query + `useSyncExternalStore` streaming hook, with
`useReducer`/optional XState for the agent lifecycle.**

- **UI + session + settings panel → Zustand** (default 2026 global-client store).
  Split into domain slices (`useUIStore`, `useSessionStore`, `useSettingsStore`); use
  `persist` middleware for settings so they survive reloads.
- **Server/async state (history, memory, provider config fetches) → TanStack Query.**
  Never mirror Query results into Zustand.
- **The live agent stream (tokens / tool events / per-item status) → a dedicated
  `useAgentStream` hook, NOT a store.** Buffer WS/SSE messages in a `useRef` outside
  React, coalesce to a ~30–60 fps snapshot on `requestAnimationFrame`, and expose it via
  `useSyncExternalStore` (or `startTransition`). Virtualize the log/history
  (`@tanstack/react-virtual`) and `React.memo` the hot rows. Cap buffer length + clean
  up connections on unmount.
- **Settings panel form → React Hook Form** (keep form state out of the global store).
- **Agent run/permission lifecycle → `useReducer` now; upgrade to XState** only if the
  approve/reject flow grows into a real multi-step machine.
- **Do NOT pick:** Recoil / classic Redux / MobX / Akita / Unstated (dead or superseded),
  Pinia (wrong framework), nanostores/Signals (not mainstream enough yet).

**Why not Redux Toolkit:** it's viable (best DevTools/time-travel) but ~10x more
ceremony for a lean live-view SPA — choose it only if the full event-log audit trail
becomes a hard requirement.

---

## Sources
- mohajer.com "React State Management in 2026" — 2026 landscape, per-library status,
  state-type taxonomy, comparison matrix (primary).
- nabraj.com "The Real Reason You're Choosing the Wrong State Management Library" —
  2026 comparison; Zustand ~58k★/~2KB, Jotai ~21k★/~3KB, RTK ~61k★/~11KB, MobX
  ~28k★/~16KB, Pinia ~14k★.
- blog.rajpoot.dev "Choosing State Management Library (React 19 + Next 15)" — atomic vs
  reactive vs Flux, "server state ≠ client state," "no single library," Recoil→Jotai.
- pistack.xyz "Best React State Management Libraries (2026)" — Recoil superseded by
  Jotai; XState for complex flows.
- react.dev — `useSyncExternalStore` (integrating non-React code; prefer
  useState/useReducer when possible); streaming data guidance.
- sitepoint.com "Streaming Backends & React: Controlling the Re-render Chaos" (Feb 2026)
  — buffer-in-ref → RAF coalesce → single setState, `useSyncExternalStore`,
  `React.memo`, `@tanstack/react-virtual`, `startTransition`, backpressure/cleanup.
- npmjs.com / github.com (pmndrs/valtio) — Valtio React 19 `use()` compat, proxy-state,
  actively maintained.
- TanStack Query docs / npm — v5, 45M+ weekly downloads, dominant server-state tool.
