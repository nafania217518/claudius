# Frontend design & technology research — index

Research for **claudius-frontend**: a React SPA (Vite + Tailwind, dark mode) that
live-streams a coding-agent GUI over WebSocket/SSE from the Kotlin + Spring AI
backend — a long growing chat transcript, live tool-call cards, approve/reject
dialogs, a settings panel, sub-agent chips, and a usage bar; it highlights code
and shows inline "plan" comments.

Each file surveys a category with **≥10 options**, each with a one-liner, 2026
status, plus/minus, and a **"use in claudius?"** verdict, ending in a per-file
**Recommendation**. Compiled 2026-09-21, favouring current 2025–2026 tooling and
flagging anything deprecated/archived.

## Verification status

- **Live-verified** (web searched via `mcp-browser`, 2026-09-21): **all 13 files.**
   `meta-frameworks`, `state-management`, `styling`, `code-highlighting`,
   `markdown-editors`, `ai-chat-ui`, `routing-data-fetching` were verified in the first
  pass; `component-libraries`, `testing`, `type-safety-schema`,
   `performance-virtualization`, `best-practices-antipatterns-2026`, and
   `claude-skills-frontend` were re-verified in a second pass once the sandbox's
   `mcp-browser` network recovered — their "not live-verified" banners are now
  replaced with a "Verified 2026-09-21" stamp and real opened Sources.
- **Caveat:** `claude-skills-frontend`'s plugin/MCP *repo paths* are the one remaining
  soft spot (official `anthropic-skills:*` names are confirmed; exact plugin/marketplace
  install paths should be re-checked before pinning).

## Files

| File | Covers | Recommendation for claudius |
| --- | --- | --- |
| [meta-frameworks.md](meta-frameworks.md) | React meta-frameworks & build tools | **Vite + React SPA + React Router 7**; reject RSC/SSR meta-frameworks (Gatsby/Remix EOL, Qwik/Blitz/Redwood flagged). |
| [state-management.md](state-management.md) | State management | **Zustand** (UI/session/settings, split slices + `persist`) + **TanStack Query** (server state only) + a dedicated `useAgentStream` hook for the WS/SSE firehose (kept out of the store); `useReducer`→XState for approve/reject; avoid Recoil / classic Redux / MobX / Pinia / nanostores. |
| [styling.md](styling.md) | CSS mechanism | **Tailwind CSS v4** via `@tailwindcss/vite` (zero runtime, CSS-var dark mode) + Radix/shadcn primitives; CSS Modules as escape hatch; reject styled-components (maintenance) / Emotion (per-render cost) / Panda / UnoCSS. |
| [code-highlighting.md](code-highlighting.md) | Syntax highlighting | **Shiki + `@shikijs/stream`** for streamed reasoning/markdown (trimmed langs/themes, JS engine, no WASM) + **refractor** for high-volume virtualized rows; sanitize any HTML path. |
| [markdown-editors.md](markdown-editors.md) | Markdown render + rich/inline editing | **Streamdown** for streaming messages (partial-block tolerant, built-in `rehype-harden` XSS) + **custom read-only gutter annotations** for plan comments + **`<textarea>` + preview** composer; TipTap/CodeMirror as upgrades. |
| [ai-chat-ui.md](ai-chat-ui.md) | AI chat / agent UI kits | **Build a custom surface on claudius's own stream**; borrow **AI Elements / shadcn** primitives; reject Vercel `useChat`, CopilotKit, LobeChat, Chatwoot/Botpress/Vapi (backend coupling); study AG-UI/A2UI as protocols. |
| [routing-data-fetching.md](routing-data-fetching.md) | Routing + data/real-time transport | **React Router 7** (library mode) + **TanStack Query** (REST) + **native WebSocket per active run** (client→server needs for approve/reject + stop); SSE + `POST /cancel` as fallback; reject Socket.IO/Pusher/Ably. |
| [component-libraries.md](component-libraries.md) | Component / UI kits | **shadcn/ui + Radix/Base UI** (copy-in, dark-first, tiny, best a11y approve/reject dialogs); **Mantine v9.6.2** runner-up; avoid MUI/Ant/Fluent/Evergreen/Semantic. |
| [testing.md](testing.md) | Testing tools | **Vitest + React Testing Library + jsdom** (unit/integration of the reducer/formatters/dialogs) + **Playwright** (E2E run→see tool calls→approve) + **MSW** (mock WS/SSE; test the reducer with hand-built event arrays); avoid Jest/Enzyme/Karma/Cypress/Puppeteer/Nightwatch. |
| [type-safety-schema.md](type-safety-schema.md) | Type safety / schema / DTO validation | **Zod v4** (or Valibot) as the single runtime validator for WS/SSE + REST envelopes with `z.infer` + **OpenAPI + springdoc-openapi** for the REST contract; **skip Kotlin↔JS schema sharing** (use kotlinx.serialization DTOs + shape-pinning tests, JSON Schema as one-way doc); reject tRPC (Kotlin backend). |
| [performance-virtualization.md](performance-virtualization.md) | Performance / list virtualization | **react-virtuoso** (built-in auto-scroll + variable heights + streaming fit) + **TanStack Virtual** as headless escape + **React Compiler** for memoization + per-frame streaming batching; `content-visibility` for heavy non-live subtrees. |
| [claude-skills-frontend.md](claude-skills-frontend.md) | Claude skills / plugins / MCP for frontend dev | Adopt **Playwright MCP + Context7 + `frontend-design` skill + GitHub MCP** for claudius's own dev; emulate extensibility as **skill-loading → stdio MCP → plugin/marketplace** in that order. |
| [best-practices-antipatterns-2026.md](best-practices-antipatterns-2026.md) | Best practices & anti-patterns (2026) | ~18 best + ~19 anti + streaming-specific + a claudius checklist: coalesce token updates, keep the stream out of the global store, virtualize the transcript, sanitize agent markdown, focus-trapped approve/reject modals, `role="log"` transcript, React Compiler. |

## Stitched recommended stack

- **Build / framework:** Vite + React 19 + React Router 7 (SPA, no RSC/SSR).
- **Style:** Tailwind CSS v4 (`@tailwindcss/vite`, CSS-var dark mode).
- **Components:** shadcn/ui + Radix/Base UI primitives (Mantine v9.6.2 if you want batteries-included).
- **State:** Zustand (UI/session/settings) + TanStack Query (REST/server) + a dedicated `useAgentStream` hook for the WS firehose (out of the store).
- **Transport:** native **WebSocket per active agent run** (carries client→server approve/reject + stop); SSE + `POST /cancel` as a fallback; TanStack Query for ordinary REST.
- **Rendering:** virtualize the transcript with **react-virtuoso** (auto-scroll + variable heights); **Streamdown** for streaming markdown (built-in `rehype-harden` sanitization); **Shiki + `@shikijs/stream`** for code (refractor for high-volume rows).
- **Plan comments:** custom read-only gutter annotations (v1); TipTap/CodeMirror as an upgrade.
- **Composer:** `<textarea>` + preview; Lexical only if mentions/inline formatting land.
- **Schema:** Zod v4 (or Valibot) runtime validation of every wire envelope + `z.infer` types; OpenAPI/springdoc-openapi for REST; no Kotlin↔JS schema sharing.
- **Testing:** Vitest + RTL + jsdom (unit/integration) + Playwright (E2E) + MSW (mock WS/SSE).
- **Own dev tooling:** Playwright MCP + Context7 + GitHub MCP + the `frontend-design` skill.
- **Cross-cutting rules:** coalesce streaming token updates, keep the stream out of the global store, memoize with the React Compiler, focus-trap approve/reject modals, mark the transcript `role="log"`/`aria-live`, and **sanitize all agent-produced HTML/markdown**.

## Notes

- **All 13 files are now live-verified via `mcp-browser`** (2026-09-21). The first
  pass covered categories 1–7; categories 8–13 were written from training knowledge
  after the sandbox's `mcp-browser` network dropped, then **re-verified in a second
  pass** once the network recovered — every "not live-verified" banner is now a
  "Verified 2026-09-21" stamp with real opened Sources.
- Notable corrections from the re-verification: **Mantine v9.6.2** (not v7),
  **Ant Design v6.6.5**, **Base UI is now shadcn's default primitive layer**,
  **React 19.2**, **React Compiler 1.0 stable** (Oct 7 2025), **kotlinx-schema is
  first-party JetBrains** (Kotlin JSON-Schema is not a dead end), **Nightwatch is
  active (v3, BrowserStack-backed), not superseded**, **Karma is deprecated**
  (Feb 2026), **Vitest 5.0**, **MSW has first-class `ws` + `sse` namespaces**,
  and a **Playwright MCP CVE-2025-9611 pin note**.
- Every file is written to be skimmable: per-entry heading, plus/minus bullets,
  and a claudius verdict, plus a Sources section.
