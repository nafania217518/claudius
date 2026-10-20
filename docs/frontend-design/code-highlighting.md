# Code Snippet Syntax Highlighting — Options for Claudius

**Date:** 2026-09-22
**Goal:** pick a syntax highlighter for the Claudius React SPA.

## Why this is a constrained problem

Claudius renders **many small code snippets as virtualized list rows**, not a few
big document blocks. Snippets include:

- Markdown chat/reasoning output containing fenced code (streamed token-by-token).
- Live tool-call cards: `BashTool` output, `FileEditTool` diffs, `FileReadTool` source dumps, JSON payloads.
- Live/agent output arrives **partially mid-token** (streaming), so the highlighter must tolerate incomplete code.

Hard constraints from the README user stories + perf:

- **Per-snippet parse cost** matters (virtualization → many re-highlights; a 3 ms parse × 50 rows janks the frame).
- **Streaming partial code** must not crash or flicker.
- **Dark mode** (default) — theme must be swappable.
- **Small bundle** — this is a chat UI, not a docs site; avoid pulling 900 KB of WASM.

Two families exist: **regex-based** (Prism, highlight.js — fast, tiny, weaker accuracy)
vs **TextMate / VS Code engine** (Shiki — editor-grade accuracy, heavier, best streaming story).
Plus **AST/VDOM wrappers**, **tree-sitter/WASM**, and **JSON viewers** for structured data.

---

## Size / speed baseline (chsm.dev, Jan 2025; stars/push from pistack.xyz Aug 2026)

| Engine | Bundle (gzip) | Highlight time | Perf |
| --- | --- | --- | --- |
| **Prism / refractor** | ~11.7 KiB (27 KiB raw) | 0.5–0.7 ms | ~1,400–2,000 /sec |
| **highlight.js / lowlight** | ~15.6 KiB (36 KiB raw) | 1.1–1.4 ms | ~700–900 /sec |
| **Shiki** (full) | ~280 KiB (905 KiB raw, incl. Oniguruma WASM ~231 KiB) | 3.5–5.0 ms | ~200–280 /sec (**~7× slower** than Prism) |

Shiki's cost is almost entirely the **Oniguruma regex WASM engine**; the JS RegExp engine
drops it to ~20 KiB (no WASM) at the cost of some strictness.

---

## Options

### 1. Shiki (shikijs/shiki) — TextMate engine, VS Code parity
- **What:** TextMate-grammar highlighter, "same engine as VS Code"; 1000+ langs, huge theme catalog.
- **2026 status:** v4.4.x, extremely active (published days ago, ~2,100 dependents). Current best-in-class.
- **Plus:** editor-grade accuracy (best TS/JSX/Kotlin), 1000+ themes, fully tree-shakable ESM, runs in browser/Node/edge. First-class streaming via `@shikijs/stream`. Can ship **zero JS** if pre-rendered at build time.
- **Minus:** heaviest client option (full bundle ~280 KiB gzip + WASM); needs explicit `lang` (unset → plain text, no autodetect); returns raw HTML → must sanitize (XSS); per-snippet parse ~7× slower than Prism.
- **Use in claudius?** **Best quality + best streaming.** Only viable client-side if trimmed to needed langs/themes and JS RegExp engine (see §Recommendation). Otherwise pre-render on the Kotlin/Spring backend.

### 2. Prism / PrismJS + refractor — regex engine
- **What:** Prism = fast tiny regex highlighter; refractor = its AST/VDOM wrapper (hast).
- **2026 status:** Prism 1.30.0 (last release Mar 2025 — **maintenance slowed**, 13k★); refractor 5.0.0 (active, wooorm).
- **Plus:** smallest + fastest (~12 KiB gzip, ~0.5 ms); per-snippet/virtualization-friendly; refractor has `kotlin`/`kt`/`kts` **and `diff`** grammars — directly relevant to Claudius (Kotlin backend, `FileEditTool` diffs). ESM, no CSS injection.
- **Minus:** weaker accuracy — TS/JSX only highlights the first extends-base, nested types, generic params, typed vars; JSX attrs parsed as XML; no autodetect. Prism core maintenance slowed.
- **Use in claudius?** **Best perf fit for many virtualized snippets.** Acceptable accuracy for shell/bash/kotlin/diff/json (the common Claudius cases). Strong fallback.

### 3. highlight.js / lowlight — regex engine
- **What:** highlight.js = regex highlighter with autodetect; lowlight = its virtual-DOM/AST wrapper (React-friendly).
- **2026 status:** highlight.js 11.11–11.12 (very active, 25k★, most-used highlighter by site count); lowlight 3.3.0 (active).
- **Plus:** solid accuracy, language **autodetection** (good when `lang` tag is missing on a streamed snippet), ~16 KiB gzip, React-idiomatic via lowlight.
- **Minus:** still ~2.5× slower than Prism; accuracy on TS/JSX weaker than Shiki; full bundle ~1 MB if not trimmed.
- **Use in claudius?** Good middle ground; autodetect is a real plus for untagged streamed snippets. Loses to Prism on size/speed, to Shiki on accuracy.

### 4. refractor (standalone) — Prism → hast
- **What:** Virtual syntax highlighting on top of Prism; import `refractor/core` (0 langs) + register only what you need.
- **2026 status:** 5.0.0, active (wooorm). ESM-only, no CSS injection, no Prism plugin support.
- **Plus:** tree-shakeable (`/core` ~12.7 KiB, default ~40 KiB/36 langs, `/all` ~211 KiB/297 langs); returns VDOM/hast (safe, no `dangerouslySetInnerHTML`); `kotlin` + `diff` grammars.
- **Minus:** inherits Prism's TS/JSX accuracy limits; singleton registration model (register once, global).
- **Use in claudius?** **Best "tiny + React-safe + has Kotlin/diff" pick.** Ideal for virtualized rows where you control the language set.

### 5. prism-react-renderer — Prism → React
- **What:** Small render-props component that tokenizes via a vendored Prism and renders to React (+ RN).
- **2026 status:** v2.4.x, active (Nearform/FormidableLabs). `usePrism`/`<Highlight>` hook API.
- **Plus:** batteries-included React component, vendored slimmed Prism, theming hooks, no global Prism pollution.
- **Minus:** v2 last meaningful release ~Dec 2024; accuracy = Prism; heavier than raw refractor for the same grammar set.
- **Use in claudius?** Convenience over raw refractor, but adds a layer; prefer refractor unless you want the component/hook API.

### 6. react-syntax-highlighter — wrapper (hljs or Prism build)
- **What:** Popular React wrapper over highlight.js **or** refractor, JS-based styles (no external CSS), line numbers, virtualized-renderer option.
- **2026 status:** v15.5.x, active but **heavy by design**; widely flagged as "large footprint". Long-maintained (4.7k★) but aging.
- **Plus:** most batteries (inline JS styles, line numbers, `wrapLines`, custom `renderer` for virtualization, async/light builds for code-splitting).
- **Minus:** large; the "just works" convenience hides that it bundles a full highlighter; accuracy = underlying hljs/Prism; considered a deprecated-prone legacy choice by 2026.
- **Use in claudius?** Only if you want line numbers + inline styles fast and can accept the weight. Prefer refractor/lowlight + a thin custom renderer for a smaller footprint.

### 7. CodeHike / bright — rehype, server-component oriented
- **What:** `bright` = React **Server Component** for highlighting (via `@code-hike/lighter`); `code-hike` = full MDX feature set (annotations, titles, line highlights).
- **2026 status:** active; built around Next.js App Router / RSC.
- **Plus:** great for rich **static/docs** code blocks with annotations.
- **Minus:** server-component model — poor fit for a **client-side streaming SPA**; overkill for chat snippets; not designed for incremental client highlighting.
- **Use in claudius?** **No.** Wrong execution model for a React client streaming UI.

### 8. web-tree-sitter — WASM parser
- **What:** Tree-sitter incremental parser compiled to WASM; most accurate (real CST).
- **2026 status:** 0.25.x, active but **not ready for easy browser use** — `tree-sitter.js` imports node `fs` (bundler breakage), per-language `.wasm` must be fetched/served (ABI churn).
- **Plus:** best possible accuracy; incremental updates suit streaming.
- **Minus:** WASM download + per-language grammar fetches, bundler friction, complex setup; heavy for a chat UI.
- **Use in claudius?** **No** for v1. Overkill; use only if you later need IDE-grade incremental editing of large files.

### 9. @shikijs/vscode-textmate — TextMate engine core
- **What:** Shiki's fork of the VS Code TextMate engine; what Shiki sits on.
- **2026 status:** v10.x (published ~7 mo ago), the engine under Shiki.
- **Plus:** you *can* call it directly to render TextMate without all of Shiki.
- **Minus:** low-level, no theming/streaming convenience, still needs Oniguruma WASM — no reason to skip Shiki to reach it.
- **Use in claudius?** **No direct use** — reach TextMate *through* Shiki instead.

### 10. Chroma — server-side only (NOT a JS lib)
- **What:** Go highlighter (TextMate/Pygments-compatible), huge grammar+theme set.
- **2026 status:** mature but **Go-only** — no browser build.
- **Plus:** best-in-class on the **Kotlin/Spring backend** if you pre-render code to HTML server-side.
- **Minus:** not client-side; would move highlighting to the backend and ship HTML to the SPA (no streaming highlight, must sanitize).
- **Use in claudius?** **Backend-only option.** Viable if Claudius pre-renders non-streaming code (e.g. `FileReadTool` dumps) server-side in Kotlin. Not a frontend lib.

### 11. JSON viewers — react-json-view / @uiw/react-json-view / react18-json-view
- **What:** Collapsible JSON tree components (not syntax *highlighters* per se).
- **2026 status:** `react-json-view` (uiwjs) v2 redesign active; `react18-json-view` (YYsuni) 0.2.10 active, `dark.css` built in; old `@microlink/react-json-view` stale (2020).
- **Plus:** pretty collapsible JSON for `BashTool`/API payloads and settings; dark themes available.
- **Minus:** a second dependency + rendering path; heavy for large trees; edit mode in some.
- **Use in claudius?** **Optional add-on** for structured JSON payloads — but a highlighted `<pre>` is usually enough for v1. Add when JSON inspection UX matters.

### 12. @shikijs/stream (add-on, not a standalone engine)
- **What:** Streaming colorizer for Shiki — `CodeToTokenTransformStream` + `<ShikiStreamRenderer>` (react/vue/solid/svelte); `ShikiCachedRenderer` (experimental) for an incrementally-updated code string.
- **2026 status:** current, first-class part of the Shiki org.
- **Plus:** purpose-built for **LLM output streams** — highlights each chunk as it lands, `allowRecalls` re-evaluates only changed tokens, React renderer commits incremental DOM (low churn — ideal for virtualized streamed rows).
- **Minus:** requires a Shiki highlighter instance (inherits Shiki's size/engine trade-off); `ShikiCachedRenderer` experimental.
- **Use in claudius?** **Yes — this is the mechanism** if Shiki is chosen for streamed reasoning/markdown.

---

## Recommendation for claudius

**Primary: Shiki for streamed prose/markdown code, Prism/refractor for high-volume virtualized rows.**

Rationale (perf + bundle + streaming + dark mode):

- **Streamed reasoning/markdown (fewer, larger, quality-sensitive blocks):** use **Shiki + `@shikijs/stream`**
  (`<ShikiStreamRenderer>` / `ShikiCachedRenderer`). It is built exactly for LLM token streams
  (incremental DOM, `allowRecalls`), gives editor-grade accuracy and 1000+ dark themes, and the
  "few big blocks" shape tolerates Shiki's ~7× parse cost.
  - **Trim hard:** `createHighlighter({ langs: [needed], themes: [darkTheme] })` with
    **`createJavaScriptRegexEngine()`** (~20 KiB, **no Oniguruma WASM**) instead of the default WASM engine.
    Or use `react-shiki/core` (~4 KiB + your grammars) / `react-shiki/web` (~700 KiB, common langs) rather than the full ~1.2 MB build.
  - Multi-theme via `light-dark()` / CSS vars; default a dark theme (e.g. `github-dark`).
- **High-volume virtualized rows** (Bash output, diffs, source dumps — many small snippets where per-row
  parse cost dominates): use **refractor** (`/core` + register `bash`, `kotlin`/`kt`, `diff`, `json`,
  plus a couple common langs). ~12 KiB, ~0.5 ms/parse, returns safe VDOM (no `dangerouslySetInnerHTML`),
  and ships `kotlin` + `diff` grammars Claudius actually needs. Accuracy on TS/JSX is the only gap — acceptable
  for the dominant Claudius cases (shell, Kotlin, diff, JSON).
- **Simpler single-engine fallback (fewer moving parts):** if two engines is too much for v1, pick **one** —
  **Shiki trimmed to a dark theme set with the JS RegExp engine + `@shikijs/stream`**. It covers streaming,
  accuracy, and Kotlin in one dependency; accept the larger bundle. Drop to **refractor/Prism** only if the
  measured bundle/jank is unacceptable.
- **JSON payloads:** start with a highlighted `<pre>`; add `react18-json-view`/`@uiw/react-json-view`
  (has dark theme) only if collapsible inspection becomes a real need.
- **Not for v1:** web-tree-sitter (WASM/bundler friction), bright/CodeHike (RSC, wrong model),
  react-syntax-highlighter (heavy legacy wrapper), Chroma (Go — backend-only; revisit if the Kotlin backend
  ever pre-renders `FileReadTool` output to HTML server-side).

**Security note:** any highlighter that returns HTML (Shiki default output, highlight.js `value`) must be
**sanitized** before injection. Prefer AST/VDOM outputs (refractor, lowlight, Shiki HAST) to avoid
`dangerouslySetInnerHTML` entirely — safest for a UI rendering model-generated code.

---

## Sources

- chsm.dev — Syntax Highlighter comparison (bundle sizes + speed benchmarks, Jan 2025): https://chsm.dev/
- pistack.xyz — 2026 decision matrix, stars/last-push for highlight.js / Prism / Shiki: https://pistack.xyz
- Shiki docs — `@shikijs/stream` (streaming, `CodeToTokenTransformStream`, `ShikiStreamRenderer`, `ShikiCachedRenderer`): https://shiki.style/packages/stream
- Shiki homepage / install guide — engine + `createHighlighter`: https://shiki.style/ , https://shiki.style/guide/install
- shiki npm — v4.4.x, ~2,100 dependents: https://www.npmjs.com/package/shiki
- @shikijs/vscode-textmate npm — v10.x engine core: https://www.npmjs.com/package/@shikijs/vscode-textmate
- react-shiki npm — bundle tiers (`/web` ~700 KB, `/core` ~4 KB), JS engines, streaming: https://www.npmjs.com/package/react-shiki
- refractor GitHub + npm — Prism→hast, entry sizes, `kotlin`/`diff` grammars, v5.0.0: https://github.com/wooorm/refractor , https://www.npmjs.com/package/refractor
- prism-react-renderer npm/GitHub — v2.4.x React wrapper: https://www.npmjs.com/package/prism-react-renderer
- react-syntax-highlighter GitHub/DeepWiki — v15.5, "large footprint": https://github.com/react-syntax-highlighter/react-syntax-highlighter
- lowlight npm — v3.3.0 virtual-DOM hljs wrapper: https://www.npmjs.com/package/lowlight
- highlight.js releases/news — v11.11–11.12, active: https://github.com/highlightjs/highlight.js/releases
- CodeHike / bright GitHub — RSC highlighter: https://github.com/code-hike/bright
- rehype-pretty-code — build-time + client highlight: https://rehype-pretty.pages.dev/
- web-tree-sitter npm — 0.25.x, `fs` bundler issue, WASM: https://www.npmjs.com/package/web-tree-sitter
- react-json-view / react18-json-view npm — JSON tree viewers, dark themes: https://github.com/uiwjs/react-json-view , https://www.npmjs.com/package/react18-json-view
