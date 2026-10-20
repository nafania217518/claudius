# Routing + Data / Real-Time Transport — options for claudius

Research date: 2026-09-21. claudius = React SPA (Vite + Tailwind) frontend, Kotlin + Spring
AI backend. UI must be LIVE: stream agent output, tool-call progress, sub-agent tails, token
usage; ordinary REST for history / settings / sessions / memory; JSON validates against Kotlin DTOs.

---

## ROUTING

### React Router v7
- **What:** canonical React router; v7 (late 2024) merged `react-router` + `react-router-dom`
  into one package and unified v6 + Remix into two modes: "library" (classic SPA, explicit
  `createBrowserRouter`) and "framework" (Remix-style SSR / loaders / actions, file-based
  `app/routes/`). ~14 KB gzip. 56.5k GitHub stars, updated 2026-08.
- **2026 status:** most-used router in React by far. Safe default. Partial type safety via
  optional codegen; typed links work out of the box.
- **Plus:** huge ecosystem, tutorials, job familiarity; `RouterProvider` data APIs; works on
  plain Vite in library mode; React 18/19.
- **Minus:** route paths are strings unless you opt into codegen; mixing old `useRoutes` style
  and new data APIs is an anti-pattern.
- **Use in claudius?** Yes, as the conservative default: SPA with ~3-5 views (chat, session
  history, settings, memory). Library mode only — no SSR need.

### TanStack Router
- **What:** router built around end-to-end TypeScript: every path, param, and search param is
  compile-time checked from the route tree. File-based routing built in, Vite plugin codegen,
  dedicated devtools. ~15 KB gzip. 14.9k stars, updated 2026-08; 1.0 stable since early 2024
  (used in production by Vercel's dashboard).
- **2026 status:** the type-safety maximalist; one of the most active routers. No official
  React Native support; SSR story via the younger TanStack Start.
- **Plus:** full type safety (broken links fail the build); typed search params
  (`validateSearch`) — good for deep links like `/sessions/:id?tab=tools`; first-class
  integration with TanStack Query; built-in devtools.
- **Minus:** steeper learning curve; codegen output must be committed/regenerated; no RN.
- **Use in claudius?** Strong option. The session deep-link + query-string filtering case is
  exactly its sweet spot, and its TanStack Query integration pairs cleanly with REST.
  Overkill if claudius stays at 2-3 routes; worth it once history/settings/memory grow.

### Wouter
- **What:** 2.2 KB gzipped, zero-dependency router; hook API (`useRoute`, `useLocation`,
  `Switch`, `Link`); history or hash mode; no loaders, no SSR, no codegen. 7.9k stars,
  updated 2026-08.
- **2026 status:** actively maintained; the "tiny router" reference for widgets/extensions.
- **Plus:** negligible bundle cost; one file of docs; hash mode needs no server config.
- **Minus:** flat path matching only; nested layouts must be composed by hand; runtime string
  matching (params typed, matching not).
- **Use in claudius?** Tempting for a 3-view SPA, but hash mode hurts deep-link previews and
  string-typed routes get wrong as the app grows. Pick it only if bundle size is a real
  constraint; otherwise React Router v7 / TanStack Router.

### Reach Router
- **What:** lightweight router that shipped the modern history API to early React.
- **2026 status:** EOL/maintenance — superseded by React Router v5+ (which is where its API
  lineage went). Do not adopt new.
- **Use in claudius?** No. Historical note only.

### Remix (standalone)
- **What:** full-stack React framework (loaders/actions/SSR); the framework half of React
  Router v7.
- **2026 status:** as a standalone library it is EOL — merged into React Router v7 "framework
  mode". New code uses `react-router` v7, not `remix`.
- **Use in claudius?** No — claudius is a pure SPA with its own Kotlin backend; no SSR. If
  ever wanted loaders-style data APIs, that's React Router v7 library mode, not Remix.

### Next.js `<Link>` / built-in routing
- **What:** Next.js's router primitives (`<Link>`, `useRouter`, app-router routes).
- **2026 status:** current and dominant inside Next.js apps.
- **Use in claudius?** N/A — meta-framework question. claudius is explicitly Vite + React SPA;
  adopting Next means rewriting the build. Listed only so the decision is on record: do not
  move to Next.

### Solid Router (SolidJS)
- **What:** official router for the SolidJS framework.
- **2026 status:** maintained, but tied to Solid (a different reactive library from React).
- **Use in claudius?** No — wrong framework. claudius is React.

---

## DATA FETCHING (REST)

### TanStack Query
- **What:** server-state manager: query keys + `queryFn`, caching, dedupe, retries,
  `useMutation`, `useInfiniteQuery`, `staleTime`/`gcTime`, devtools extension. ~13 KB
  (React pkg). 50k+ stars, updated 2026-08.
- **2026 status:** the default choice for most new 2026 React apps with non-trivial server
  state. Supports React/Vue/Svelte/Solid/Angular.
- **Plus:** handles the entire "fetch history/sessions/settings, cache, invalidate on
  mutation" story; devtools; pairs with TanStack Router; multi-framework.
- **Minus:** ~13 KB; `staleTime: 0` default means refetch-on-every-mount unless tuned; key
  discipline required.
- **Use in claudius?** Yes — for all REST: session list/history, settings, memory list,
  provider/model config. Mutations (create session, delete, save settings) invalidate query
  keys; live-stream events (session finished) invalidate `['sessions']`.

### React Query
- **Note:** same project as TanStack Query — renamed when it expanded beyond React. Package is
  `@tanstack/react-query`; "React Query" and "TanStack Query" are one and the same. Not a
  separate option.

### SWR
- **What:** stale-while-revalidate hook from Vercel: `useSWR(key, fetcher)` returns
  `{data, error, isLoading}`, auto-dedupe, refetch on focus/reconnect. ~4.3 KB. 32.5k stars,
  updated 2026-08.
- **2026 status:** actively maintained; minimal-app favorite; tightest Next.js integration.
  Mutations are manual `mutate` + rollback (no dedicated primitive).
- **Plus:** tiny; one-hook API; great when the app is mostly GET-heavy reads.
- **Minus:** beyond simple reads you compose everything by hand; Next.js-flavored defaults.
- **Use in claudius?** Only if we go dependency-minimal; TanStack Query wins for claudius's
  mutation + invalidation-on-event needs. Rule: never mix two server-state libraries.

### RTK Query (Redux Toolkit)
- **What:** data layer inside the Redux store: `createApi` + typed generated hooks, tag-based
  invalidation, cache lifecycle in one store. Part of redux-toolkit (11.2k stars).
- **2026 status:** maintained; chosen only by teams already on Redux.
- **Use in claudius?** No — claudius should not adopt Redux for a SPA of this size. Listed for
  completeness.

### Native `fetch` + `AbortController`
- **What:** browser platform fetch, no library; `AbortController` cancels in-flight requests.
- **2026 status:** baseline everyone uses under the hood; still the right tool for one-off,
  fire-and-forget calls.
- **Plus:** zero deps, zero KB.
- **Minus:** no caching/dedupe/invalidation; hand-rolled `useEffect` fetching duplicates
  requests and causes spinner bugs at scale.
- **Use in claudius?** Yes, as the transport *inside* a TanStack Query `queryFn`, and for the
  single-shot calls that don't belong in the cache. Not as the whole data layer.

---

## REAL-TIME TRANSPORT (agent firehose)

### Native WebSocket (`WebSocket` API, RFC 6455)
- **What:** full-duplex persistent connection; both sides send messages any time; manual
  reconnection, manual protocol framing; on Spring side: `WebSocketHandler`/STOMP over
  `@ServerEndpoint`.
- **2026 status:** universal browser support; the protocol under everything below.
- **Plus:** bidirectional — approvals, "stop", and steering go down the *same* connection as
  the stream; low latency; one connection per active session.
- **Minus:** auth is fiddly (no headers — token in first message / `Sec-WebSocket-Protocol` /
  query param); reconnection + backoff you write; horizontal scale needs sticky LB + Redis
  pub/sub backplane (irrelevant for single-user).
- **Use in claudius?** **Yes — primary recommendation for the live agent stream.** The UI
  needs server→client (text deltas, tool-call progress, sub-agent tails, usage) *and*
  client→server mid-stream (approve/reject mutating tool calls, stop/cancel). That is the
  2026 decision rule: "single-turn streaming? SSE. Multi-turn with tool-call approvals,
  mid-stream steering, agent control? WebSockets" (rajpoot.dev). A single WS per active agent
  run, JSON envelopes `{type: "text"|"tool_call"|"subagent"|"usage"|"status"}`, client sends
  `{action: "approve"|"reject"|"stop"}`. Validate server→client JSON against the same Kotlin
  DTOs. Skip Socket.IO/Pusher — rooms, presence, and fan-out are for multi-user, which
  claudius is not.

### Native SSE (`EventSource`, Server-Sent Events)
- **What:** HTTP-based server→client event stream; auto-reconnect with `Last-Event-ID`
  resume; cookies work; proxy/CDN-friendly (needs `Cache-Control: no-cache, no-transform`,
  `X-Accel-Buffering: no` on nginx, ~15-30s heartbeat). On Spring side: `SseEmitter` or
  WebFlux `Flux<ServerSentEvent>`.
- **2026 status:** the default for "just stream to me" (LLM single-turn chat). Well under
  WebSocket's connection ceiling; no infra overhead.
- **Plus:** simplest correct streaming on both ends; auto-reconnect is free; HTTP-native auth
  and CORS; scales cleanly.
- **Minus:** GET-only, server-only — cannot carry approve/reject/stop; those need a separate
  `POST /cancel`-style endpoint; `EventSource` can't send request bodies (start-with-payload
  needs fetch-streaming instead).
- **Use in claudius?** Viable fallback: SSE for the firehose + small `POST /agent/{id}/command`
  for stop/approve. Cheaper backend than WS. Prefer WS instead, because approvals arrive
  mid-stream and race against a separate endpoint.

### Fetch streaming (`resp.body.getReader()` + `TextDecoder`)
- **What:** POST a request, read the response body as a stream of chunks (e.g. NDJSON/SSE
  over `fetch`); `AbortController` to cancel.
- **2026 status:** standard; the bridge when you need POST + a body + streaming response.
- **Plus:** full request body (start a task with a long prompt), POST semantics, abort =
  cancel; one HTTP request, one stream.
- **Minus:** single-shot — reconnect means a new request; no automatic resume; you manage the
  reader loop.
- **Use in claudius?** Good for the *initial* "start task with prompt + context" POST that
  streams the first response; but with a persistent WS the start message is just the first
  client frame, so this only matters in the SSE fallback design.

### Socket.IO client (`socket.io-client`)
- **What:** library over WebSocket: automatic reconnection w/ backoff, HTTP long-polling
  fallback, rooms/namespaces, acks, event-based messaging. Self-hosted server (Node or via
  Spring+Java ports); Redis adapter required past one server instance.
- **2026 status:** mature, widely used; recommended starting point when scale is unknown.
  Alternatives in 2026: Soketi/Laravel Reverb (Pusher-protocol, self-hosted), Centrifugo.
- **Plus:** reconnection and fallback plumbing done for you.
- **Minus:** its value (rooms, presence, fan-out) is multi-user; claudius has one user per
  session — you'd adopt the library's protocol and ops surface for zero benefit. Spring side
  has no first-class Socket.IO support.
- **Use in claudius?** No. Native WebSocket gives the same bidirectional connection without
  the rooms/presence machinery.

### Pusher
- **What:** managed pub/sub realtime service; backend `trigger()`s events, client SDK
  subscribes; presence channels; connection-metered pricing (Sandbox free 100 conns;
  $49-699/mo tiers by connections, 2026).
- **2026 status:** active; zero-ops trade for a per-connection bill and proprietary
  protocol/SDK lock-in (migrate to Socket.IO = rewrite).
- **Use in claudius?** No — claudius is single-user; paying a managed fan-out service to push
  one user's own stream is pure waste.

### Ably
- **What:** managed realtime with strict ordering, delivery guarantees, presence, published
  SLAs, own SDK (not Pusher-compatible).
- **2026 status:** active; the "grown-up managed realtime" choice when reliability is
  load-bearing and SLA matters.
- **Use in claudius?** No — same reason as Pusher: the value props target multi-tenant
  fan-out. Overkill and costly for one user per session.

### Vercel AI SDK — `useChat` / streaming
- **What:** React hook for AI chat UIs (v7, `@ai-sdk/react`): transport-based since 5.0,
  `onToolCall`, `addToolOutput`, `addToolApprovalResponse` (approve/reject with a reason),
  `addToolResult`, `stop()`, `resumeStream()`, WebSocket/HTTP transports.
- **2026 status:** "streaming first", actively developed; supports React/Vue/Svelte/Angular/
  Node/TanStack Start.
- **Plus:** its tool-approval model is basically claudius's feature list; would give the
  streaming chat surface in a few lines.
- **Minus:** it expects the Vercel AI SDK backend shape (its own streaming protocol / UIMessage
  contract) — claudius's backend is Kotlin + Spring AI, so adopting it means conforming the
  server to a JS-originated protocol, and it bakes in Vercel-isms.
- **Use in claudius?** Not the transport itself — but study its `useChat` event model
  (`onToolCall` → approval → `addToolOutput`) and mirror that shape in the WebSocket envelope.
  It's the closest public precedent for exactly this UI.

---

## Recommendation for claudius

- **Routing: React Router v7 (library mode)** — 3-5 views, safest default. Switch to
  **TanStack Router** if/when session deep links + typed search params matter (its
  `validateSearch` fits `/sessions/:id?tab=tools`), then pair it with TanStack Query's
  built-in integration.
- **REST: TanStack Query** for sessions/history/settings/memory/provider config; `fetch`
  inside `queryFn`; live events invalidate query keys.
- **Live agent stream: native WebSocket, one per active agent run.** The firehose looks
  server→client (which would make SSE the pick), but claudius's defining feature is
  *per-tool-call approve/reject + stop mid-stream* — client→server control on the live
  connection. The 2026 decision rule is explicit: tool-call approvals + mid-stream steering →
  WebSocket. Fallback design: SSE (`SseEmitter`/`Flux<ServerSentEvent>`) + `POST /cancel`
  if backend simplicity ever outweighs the race it introduces. JSON envelopes validated
  against the same Kotlin DTOs on both ends.
- **Skip:** Socket.IO, Pusher, Ably (multi-user infra we don't need), SWR/RTK Query
  (TanStack Query covers their ground), Remix/Reach/Next/Solid (wrong framework or EOL).

---

## Sources

- https://www.pistack.xyz/posts/2026-08-13-react-router-vs-tanstack-router-vs-wouter-typescript-comparison/ (routers, Aug 2026)
- https://devtoolbox.blog/tanstack-router-vs-react-router-v7-2026/ (router sizes, modes, migration, 2026)
- https://www.pistack.xyz/posts/2026-08-11-tanstack-query-vs-swr-vs-rtk-query-react-data-fetching-comparison/ (data libs, Aug 2026)
- https://blog.rajpoot.dev/posts/backend/sse-vs-websockets-streaming-ai-2026/ (SSE vs WS for AI/agent streaming, 2026)
- https://novasphereailab.com/blog/socket-io-vs-pusher-vs-websocket-2026 (Socket.IO/Pusher/WS, Jul 2026)
- https://ai-sdk.dev/docs/reference/ai-sdk-ui/use-chat (Vercel AI SDK v7 useChat API, current)
- https://zairalabs.com/ably-vs-pusher-2026/ (Ably vs Pusher, Aug 2026 — pricing/guarantees)
