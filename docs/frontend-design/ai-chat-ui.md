# AI Chat / Agent UI Kits — reference for claudius frontend

**Context.** claudius = Kotlin + Spring AI backend (own agent loop, tool calling,
permissions, sub-agents, memory — **NOT** the Vercel AI SDK) fronted by a React SPA that
renders a live agent run: scrolling transcript + reasoning, live tool-call cards (progress
tail + status pill), approve/reject dialogs for mutating calls, sub-agent chips (output tail /
status / stop), a usage bar, code highlighting, inline plan comments. Backend pushes an event
stream over **WebSocket/SSE**.

**The one question that decides every verdict:** is the library a *pure presentational UI you
feed from your own stream*, or *full-stack coupling that pulls in its own runtime/protocol/
backend*? Coupling cost, roughly light → heavy:

| Lock-in type | Example | Cost |
| --- | --- | --- |
| none / primitives | AI Elements, shadcn | copy files, own the data shape |
| framework only | assistant-ui | adopt its React runtime, feed your data |
| runtime/transport | CopilotKit (AG-UI), LangChain UI | re-shape your whole stream to its protocol |
| ecosystem | Vercel AI SDK `useChat` | assumes AI-SDK streaming format |
| full stack | LobeChat, Open WebUI, Chatwoot, Botpress | adopt its app + backend |

---

## 1. assistant-ui  ★ best fit if you want a chat surface
- **What it is:** headless/composable React toolkit for "ChatGPT-grade" chat; state + hooks
  (`useLocalRuntime`, `useExternalStoreRuntime`) plus a UI component layer. MIT, ~7.9k★, YC-backed,
  React/React Native/TUI.
- **2026 status:** active, fast-moving. Multiple **runtime adapters** (LangGraph, LangChain,
  assistant Cloud, `ExternalStoreRuntime`, Data Stream Protocol).
- **+** React-only (matches claudius); you keep your backend and feed it an arbitrary store;
  streaming/interrupt/retry/branching state already solved; rich component set.
- **−** "framework lock-in" — you adopt its runtime + message model; not a shadcn copy-paste;
  opinionated.
- **Use in claudius?** **Yes, strongest option if you'd rather build on a chat framework than
  hand-roll.** `ExternalStoreRuntime`/`LocalRuntime` let the React layer be driven by claudius's
  own WS/SSE events; approve/reject maps to its run controls. Verify the tool-call/approval
  primitives fit "live progress tail + status pill + reject" before committing.

## 2. Vercel AI Elements  ★ best primitive source
- **What it is:** shadcn/ui-style component set built on the Vercel AI SDK: conversation,
  message, reasoning display, tool-use, code block, artifact, agent cards. Installed into your
  codebase via a component registry (like shadcn), **not a runtime dependency**.
- **2026 status:** active, part of the Vercel AI ecosystem (AI SDK v5 → v6 in progress).
- **+** No dependency lock-in; matches claudius's shadcn/Tailwind stack; exactly the primitives
  claudius needs (reasoning, tool cards, code, agent).
- **−** Components expect AI-SDK data shapes — you write an adapter from claudius's event stream;
  no state/runtime, it's dumb view + a little AI-SDK glue.
- **Use in claudius?** **Yes — borrow the primitives.** This is the "copy shadcn primitives"
  path; keep them, swap the data binding to claudius's stream. Don't take the AI SDK runtime.

## 3. Vercel AI SDK (`useChat` / Data Stream Protocol)
- **What it is:** JS SDK for streaming LLM/agent responses; `useChat` hook + streaming protocol
  (`UIMessage` parts over SSE). AI SDK v5 major in 2026 (big `useChat` redesign; Vue/Svelte/Angular
  added), v6 in progress.
- **+** De-facto standard streaming contract; excellent docs; great for prototyping a backend.
- **−** **Ecosystem lock-in:** `useChat` assumes the AI-SDK streaming format and (in practice) an
  AI-SDK-compatible backend. claudius's Kotlin/Spring AI loop is not that. `useChat` is transport+
  state, not just views.
- **Use in claudius?** **No as-is; pattern only.** You can *mimic* its `UIMessage`-part shape on
  your Kotlin side (or an edge adapter) so the frontend can reuse AI Elements, but don't build the
  agent on the AI SDK. Note: AI Elements (item 2) is the part worth taking, `useChat` is not.

## 4. CopilotKit  (powerful, but architecture-coupled)
- **What it is:** "agentic frontend stack": React UI + **AG-UI** protocol + A2UI (agent-generated
  UI, Google partnership) + shared agent/state (`useCoAgent`), human-in-the-loop, multi-platform.
  ~28.6k★, commercial tiers.
- **2026 status:** very active, the most popular full agent-UI framework; growing fast.
- **+** "Any agent backend" via AG-UI; strong human-in-the-loop + shared state + generative UI;
  AG-UI is a genuinely useful *protocol* to learn regardless.
- **−** **Heaviest lock-in (runtime/transport):** you conform your backend to AG-UI events, adopt
  its state model; commercial cloud for full features; large surface to learn.
- **Use in claudius?** **Only if you accept AG-UI coupling** and are willing to shape claudius's
  stream into AG-UI events. Otherwise **no** — it's the opposite of "decoupled presentational UI."
  **Study AG-UI as a protocol option; don't adopt the stack unless it pays for itself.**

## 5. Google A2UI  (watch, not a chat lib)
- **What it is:** declarative, agent-generated-UI format; agents emit a UI spec, renderers
  (Lit/Angular/etc.) draw it.
- **2026 status:** emerging, tied to the CopilotKit/Google generative-UI push.
- **+** Clean separation (agent → UI spec → renderer); good fit for "agent renders tool cards."
- **−** No chat/state/history layer of its own; ecosystem still forming.
- **Use in claudius?** **No now.** Interesting for the *generative* tool/artifact panel later;
  track as a protocol idea.

## 6. TanStack AI  (headless hooks, early)
- **What it is:** framework-agnostic, type-safe streaming hooks for AI (a TanStack-style take on
  `useChat`). No UI.
- **2026 status:** alpha/early; small adoption.
- **+** No vendor UI lock-in; React-agnostic.
- **−** No components (you build all views); immature; less battle-tested than AI SDK/assistant-ui.
- **Use in claudius?** **Maybe later.** Only if you want zero-opinion streaming state without
  assistant-ui's framework. Not worth it pre-alpha-1.

## 7. LangChain Agent Chat UI  (reference only)
- **What it is:** `langchain-ai/agent-chat-ui` — Next.js web app for a **LangGraph** agent: live
  tool viz, time-travel, state forking. ~3.2k★, active.
- **2026 status:** active, but **coupled to LangGraph + LangSmith** (dev mode needs a LangSmith
  key; prod needs its own runtime).
- **+** Great UX reference for tool visualization / debugging / time-travel — exactly claudius's
  "live tool cards + review reasoning" story.
- **−** Cannot point at a Kotlin backend; it's the *client for a LangGraph server*.
- **Use in claudius?** **No (borrow UX ideas only).** Mirror its tool-time-travel & reasoning-review
  patterns; don't adopt the app.

## 8. LobeChat / LobeHub  (whole app, not a lib)
- **What it is:** popular Next.js/React, self-hostable chat *application* with themes, plugins,
  multi-provider.
- **2026 status:** active, large community; it's a product you deploy, not a component library.
- **+** Excellent UX reference; shows polished multi-session/provider UI.
- **−** Full app + own backend/provider model; not embeddable as a UI kit; heavy.
- **Use in claudius?** **No — reference for UX.** Steal layout/theme ideas, don't fork.

## 9. Open WebUI  (note: Python full stack)
- **What it is:** self-hosted OpenAI-compatible chat UI (FastAPI backend + React frontend).
- **2026 status:** very popular, active; **Python backend.**
- **+** Great reference for provider config / model UI / chat UI.
- **−** Python stack; full app, not a React kit; no place for a Kotlin agent loop.
- **Use in claudius?** **No — reference only.**

## 10. Chainlit  (note: Python, maintenance risk)
- **What it is:** Python-first full-stack UI framework for LLM apps; ~11.4k★.
- **2026 status:** **flag:** original team stepped back (~May 2025); now community-maintained —
  verify current maintainers/release cadence before trusting it.
- **−** Python, not React; not a component kit; uncertain maintenance.
- **Use in claudius?** **No.** Python + uncertain maintenance = out of scope.

## 11. Deep Chat  (Web Component, not React)
- **What it is:** single framework-agnostic Web Component chat UI (`OvidijusParsiunas/deep-chat`),
  ~3.3k★, works in any stack.
- **2026 status:** stable but slow-moving; narrow scope.
- **+** Drop-in, no framework lock-in; quick PoC anywhere.
- **−** Web Component, hard to compose with shadcn; limited streaming/state/agent features; too
  rigid for live tool-card UI.
- **Use in claudius?** **No** for the main surface; fine as a throwaway prototype.

## 12. Loquix  (Lit Web Components, very early)
- **What it is:** Lit/Web-Component streaming chat primitives, MIT.
- **2026 status:** **flag:** early-adopter stage, few/no production deployments; author COI
  (vendor building on it).
- **−** Not React; immature; small community.
- **Use in claudius?** **No.** Track only.

## 13. Chatwoot  (support platform, not an agent dev kit)
- **What it is:** open-source **customer-support** platform (Ruby backend, React frontend embed
  widget), ~20k★.
- **2026 status:** active; it's a support/CRM product, an embeddable widget.
- **−** Not a React UI kit for agents; its widget is for *support* chat, not a coding-agent console.
- **Use in claudius?** **No.** Different domain.

## 14. Botpress  (conversational platform, not a UI kit)
- **What it is:** open-source conversational-AI *platform* (Node/JS), bot builder + agent runtime.
- **2026 status:** active; it's a platform you build *bots on*, not a UI component library.
- **−** Owns the agent runtime; not decoupled-presentational; not React-frontend-focused.
- **Use in claudius?** **No.**

## 15. Vapi (UI / voice)  (out of scope unless voice is a goal)
- **What it is:** voice-AI platform; ships a React voice-widget + SDK.
- **2026 status:** active commercial voice agent platform.
- **−** Voice-only; commercial; not relevant to a coding-agent web console.
- **Use in claudius?** **No**, unless claudius adds voice — then embed the widget, not a chat lib.

---

## Recommendation for claudius

**Build a custom chat surface driven by claudius's own WS/SSE stream; don't adopt an agent
framework as the backend.** The Kotlin + Spring AI agent loop is the product; the UI must be a
*view* over its event stream, not the other way round.

Concretely, lightest-correct path:
1. **Views:** borrow **AI Elements** (item 2) — shadcn-based message / reasoning / tool-use /
   code / agent components, copy into the repo like shadcn. They match claudius's shadcn/Tailwind
   stack and give the exact primitives (tool cards, reasoning panel, code highlight). Bind their
   data shape to claudius's stream via a thin adapter; **do not take `useChat`/the AI SDK runtime**
   (item 3) — that's Vercel-ecosystem coupling.
2. **State:** if hand-rolling streaming/interrupt/reject state gets painful, adopt **assistant-ui**
   (item 1) with `ExternalStoreRuntime`/`LocalRuntime` so the React layer is still fed by
   claudius's own events. This is the best "borrow a framework" option and stays decoupled.
3. **Approve/reject + sub-agent stop:** implement as claudius-owned controls over the stream
   (a reject/stop is just a control event back on the socket). No library is required for this.
4. **Protocols to *study, not adopt*:** **AG-UI** (CopilotKit, item 4) and **A2UI** (item 5) are
   the emerging standard event/generative-UI contracts. If claudius ever wants a "standard"
   wire format or agent-rendered panels, design the stream toward AG-UI-compatible event shapes
   *now* so it's forward-compatible — but don't build on CopilotKit unless you accept its coupling.
5. **UX references only:** LangChain Agent Chat UI (tool time-travel/reasoning review), LobeChat
   (polished layout), Open WebUI (provider/model config UI) — borrow patterns, never fork.

**One-line verdict:** presentational primitives (AI Elements/shadcn) + at most assistant-ui for
state, all fed by claudius's own stream. Reject full-stack/framework-coupled options
(CopilotKit stack, Vercel AI SDK runtime, LobeChat, Chatwoot/Botpress/Vapi) unless their coupling
is explicitly accepted.

---

## Sources (retrieved 2026-09-21)
- assistant-ui: https://www.assistant-ui.com/docs/runtimes/langgraph ,
  https://www.assistant-ui.com/docs/runtimes/langchain , SourceForge compare
  https://sourceforge.net/software/compare/CopilotKit-vs-assistant-ui/
- CopilotKit / AG-UI / A2UI: https://copilotkit.ai , https://docs.copilotkit.ai
- Vercel AI SDK & AI Elements: https://sdk.vercel.ai , https://ai-sdk.dev , AI Elements registry
- LangChain Agent Chat UI: https://github.com/langchain-ai/agent-chat-ui ,
  https://docs.langchain.com/oss/python/langchain/ui
- LobeChat/LobeHub: https://lobechat.com , https://github.com/lobehub/lobechat
- Open WebUI: https://github.com/open-webui/open-webui
- Chainlit: https://github.com/Chainlit/chainlit (team-stepback note ~May 2025 — **verify current
  maintainers** before relying on it)
- Deep Chat: https://github.com/OvidijusParsiunas/deep-chat
- Loquix: https://github.com/loquix (verify production status — **flag: early, few deployments**)
- Chatwoot: https://github.com/chatwoot/chatwoot ; Botpress: https://github.com/BotpressHQ/botpress
- Vapi: https://vapi.ai

**Confidence / caveats:** star counts and "team stepped back / early" flags for Chainlit, Loquix,
TanStack AI, and A2UI are directional (2026-09) and should be re-verified before a go/no-go
decision; assistant-ui and CopilotKit status (active, mainstream) is high-confidence.
