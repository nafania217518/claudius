# Markdown Rendering + Rich/Inline Text Editing — Landscape (researched 2026-09-21)

Category for **claudius** (React SPA, Vite + Tailwind): streaming markdown chat transcript,
inline "plan" comments (planning mode), and an input composer.
Constraints that matter: streaming/partial markdown, XSS (LLM output = prompt-injection risk),
per-row render cost (long transcripts → virtualization), inline annotations, lightweight.

Status legend: ✅ active / ⚠️ maintenance mode / ⛔ deprecated.

---

## (a) Markdown rendering

### 1. react-markdown ✅
- React component wrapping the unified remark (mdast) → rehype (hast) → React pipeline; no build step, plugins via remark/rehype, custom renderers via `components` map.
- Status: default React markdown renderer in 2026; GFM via `remark-gfm`; by default it does NOT execute raw HTML (XSS-safe), `rehype-raw` reopens that hole.
- + huge ecosystem, tree-shakeable, SSR-friendly, component overrides (swap `<code>` for a highlighter component).
- − re-parses the full string on every token unless memoized; no built-in handling for *incomplete* streaming blocks (flickering fences).
- **Claudius:** candidate for non-streaming history messages; for streaming rows it's what Streamdown replaces (see #4).

### 2. remark + rehype (unified pipeline, standalone) ✅
- The plugin layer under react-markdown: `remark` transforms markdown AST, `rehype` transforms HTML AST. Used standalone to hand-roll rendering (e.g. custom React hook producing React nodes without `dangerouslySetInnerHTML`).
- Status: ✅ actively maintained foundation of the ecosystem.
- + total control; same plugin set as react-markdown (gfm, sanitize, raw, highlight).
- − you build the renderer yourself; only worth it for special cases.
- **Claudius:** use indirectly through react-markdown/Streamdown; not directly.

### 3. MDX ✅
- Markdown + inline JSX; normally compiled at **build time** (`@mdx-js/*`).
- Status: active but aimed at authored content (docs, marketing), not runtime LLM output.
- + embed components in docs; + type-checkable.
- − build-time compilation, security model assumes trusted authors; wrong shape for streaming agent output.
- **Claudius:** skip for chat. Fine later if we ship authored help/docs pages.

### 4. Streamdown (vercel/streamdown) ✅ — best fit for streaming
- "Drop-in replacement for react-markdown, designed for AI streaming": unterminated-block tolerance (via `remend`), GFM, Shiki highlighting, KaTeX, Mermaid, memoized rendering, built-in `rehype-harden` security. Powers Vercel AI Elements.
- Status: ✅ v2.x, shipped 2025, ~5.6k GitHub stars, actively maintained (Vercel), widely adopted in AI chat UIs (2026).
- + solves the exact streaming-flicker problem; security hardening aimed at LLM prompt-injection (URL allow-lists, protocol checks).
- − opinionated styling (shadcn/ui CSS variables — needs a small `:root` token set even with Tailwind); heavier than plain react-markdown; Tailwind `@source` wiring.
- **Claudius:** primary recommendation for streaming agent messages.

### 5. react-stream-markdown (legacy) ⚠️
- Small older package (xiaoye1997) doing the same "render partial markdown" job.
- Status: ⚠️ effectively superseded by Streamdown (2025) and Vercel's `remend` preprocessor (Dec 2025); low adoption, minimal activity.
- **Claudius:** do not adopt; listed only because older 2024-era tutorials still point at it.

### 6. marked ✅
- Fast markdown → HTML string renderer (not a React component); ~300k+ weekly downloads.
- Status: ✅ active, very fast, string-based.
- − output is an HTML string → must be sanitized before `dangerouslySetInnerHTML`; no component overrides for code highlighting; no React integration.
- **Claudius:** skip — string output collides with per-row React rendering and XSS handling.

### 7. micromark ✅/⚠️
- Low-level markdown tokenizer/lexer that remark is built on; used for character-level stream processing.
- Status: ✅ maintained by the remark team, but a building block, not a renderer.
- **Claudius:** relevant only if we build a custom streaming preprocessor — Streamdown already ships one (`remend`). Skip.

### 8. Sanitizers: rehype-sanitize vs rehype-harden (security layer, not a renderer)
- `rehype-sanitize` — classic HTML-AST sanitizer (default schema, strips script/event attrs); "use it after the last unsafe thing". `rehype-harden` (vercel-labs, 2025) — LLM-focused: blocks unexpected link/image origins and protocols, the right tool against prompt-injection from agent output.
- Status: ✅ both active; rehype-harden is the AI-era recommendation.
- **Claudius:** mandatory. react-markdown already omits raw HTML by default; add rehype-harden (or Streamdown's built-in one) for LLM-originated content. Do NOT enable `rehype-raw` on untrusted markdown.

---

## (b) Rich / inline editing — plans, comments, composer

### 9. CodeMirror 6 ✅ — best for plan annotations on code
- Modular editor core (`@codemirror/state/view/language` + per-language Lezer parsers); gutter and decorations are first-class extensions; React wrapper `@uiw/react-codemirror`.
- Status: ✅ actively maintained (last push 2026-04), the 2026 default for embedding code editors; no web workers, touch support.
- + gutter decorations = natural home for inline plan comments on code; `EditorState.readOnly` for read-only view; small per-language bundles.
- − you assemble extensions yourself; community language package quality varies.
- **Claudius:** yes — code view in planning mode + plan-comment gutters on code. Not for prose.

### 10. Monaco ✅ (heavy)
- The VS Code editor in the browser; completion, diff, 40+ languages; React wrapper `@monaco-editor/react`.
- Status: ✅ maintained by Microsoft, but ~2.9MB full / ~800kB core, worker setup required, no mobile.
- + IDE-grade UX, diff views (nice for tool-call diffs).
- − huge bundle, worker plumbing, overkill for a chat GUI.
- **Claudius:** skip for chat UI. Reconsider only if a "code playground" screen appears.

### 11. TipTap (ProseMirror) ✅ — best for plan docs + inline comments
- Headless ProseMirror wrapper; ~1.8M npm downloads/mo in 2026; 100+ extensions incl. a **Comment** extension (collaboration-style margin annotations), mentions, code blocks; strong React support; React 19 compatible.
- + the comment/gutter-annotation story out of the box; model is a clean JSON doc (persist plan easily to the Kotlin backend).
- − more moving parts than a textarea; commercial premium tier for some collab features (Yjs collab itself is open).
- **Claudius:** strong candidate for the planning-mode editor if plan = a structured editable doc with inline comments. For a *chat GUI*, likely overkill vs. a read-only render + lightweight annotations — see Recommendation.

### 12. Lexical (Meta) ✅
- Headless framework powering Messenger/WhatsApp web; minimal core + plugins; official React bindings; immutable state, fast on large docs; React 19 compatible.
- + best performance characteristics, good a11y, active.
- − fewer ready-made extensions than TipTap (comments/annotations are community builds), more DIY for the plan-comment UI.
- **Claudius:** top pick for the **input composer** (multi-line, paste cleanup, mentions); viable plan editor if we want speed over batteries.

### 13. Slate ⚠️
- React-native editable primitive (schema-less, DOM-like model); Plate.js is its plugin layer.
- Status: perpetually "in beta"-ish, contributor-maintained (no commercial backer), API churn across majors; widely used but the 2026 consensus (TipTap/Lexical comparisons) rates it high-effort.
- + ultimate customization; − you implement most editing behavior yourself.
- **Claudius:** skip unless a bespoke editor requirement emerges.

### 14. Plate.js ⚠️
- ~30+ plugins on top of Slate (headings, mentions, tables, code, comments-style blocks).
- Status: maintained but inherits Slate's churn; niche adoption.
- **Claudius:** skip — Plate = TipTap-without-the-ecosystem.

### 15. Draft.js ⛔
- Meta's former editor framework; unmaintained since ~2022, superseded by Lexical.
- **Claudius:** do not use.

### 16. react-quill / Quill ⚠️ → ⛔ in React
- Quill core (Slab, ~1.5M weekly downloads) is fine; the `react-quill` wrapper is effectively dead on React 18+ Strict Mode / React 19 (`findDOMNode` removal broke it — 2026 migration guides point everyone to TipTap). Successor wrappers (`react-quill-new`) are community-run.
- **Claudius:** skip — no viable React story.

### 17. Plain `<textarea>` (baseline) ✅
- Free, zero deps, native paste/IME/a11y, trivially virtualization-adjacent.
- − no inline formatting, no annotations; markdown is just text in.
- **Claudius:** the composer's starting point (and fallback). A textarea with a markdown-preview toggle covers 90% of an "input composer" need; upgrade to Lexical only when mentions/inline code formatting become required.

### 18. Ace ⛔
- Legacy Cloud9-era editor; still runs but 120+ regex modes largely stale, no tree-shaking, no modern React story.
- **Claudius:** skip — CodeMirror 6 dominates its niche.

### Inline plan comments — approach, not a library
- Options: TipTap/ProseMirror Comment extension; CodeMirror gutter decorations; generic `react-annotation`-style absolute-positioned overlays; Velt-style comment layers (commercial collab SDK, overkill here).
- **Claudius:** since plans come *from the agent* and the user reacts (approve/reject per step), plan comments are mostly **read-only annotations on rendered output** — a custom gutter component over the rendered plan (line index → note) beats an editor library for v1. Editable plan feedback = the composer, not per-line editing.

---

## Recommendation for claudius

- **Streaming agent messages:** `streamdown` (Vercel) — built for exactly this: partial-block tolerant, Shiki code highlighting (pairs with the separate highlighter file), built-in `rehype-harden` for the XSS/prompt-injection risk of LLM output. Non-streaming history rows: `react-markdown` + `remark-gfm` + `rehype-harden` (or keep Streamdown everywhere for one code path — simpler).
- **Never** `dangerouslySetInnerHTML` with raw LLM markdown; never enable `rehype-raw` on untrusted input; keep raw HTML disabled by default.
- **Long transcripts:** virtualize the row list (TanStack Virtual / react-virtual); keep each row cheap (memoize, parse once on stream end). Both Streamdown and editors virtualize internally, but the *list* of messages is ours.
- **Plan comments (planning mode):** v1 = custom read-only gutter annotations over the rendered plan/code (line index → comment, approve/reject buttons). If plans become user-editable docs, `TipTap` (Comment extension, JSON model for backend persistence) is the upgrade path; for code-anchored comments use `CodeMirror 6` gutter decorations.
- **Input composer:** start with `<textarea>` + markdown preview toggle; upgrade to `Lexical` only when mentions / inline formatting / paste cleanup are real requirements.
- **Skip:** MDX, react-stream-markdown, react-quill, Draft.js, Plate, Ace, Monaco — each loses to something lighter or is dead.

## Sources
- https://github.com/vercel/streamdown — Streamdown (read in full, 2026)
- https://velt.dev/blog/best-rich-text-editors-react-2026 — Top 10 rich text editors 2026 (read in full)
- https://www.pistack.xyz/posts/2026-08-22-browser-code-editors-monaco-codemirror-ace-comparison/ — Monaco vs CodeMirror vs Ace, Aug 2026 (read in full)
- https://groundy.com/articles/vercels-remend-turns-streaming-markdown-repair-into-a-dependency/ — Streamdown/Remend history
- https://github.com/vercel-labs/markdown-sanitizers — rehype-harden for LLM prompt-injection
- https://www.npmjs.com/package/rehype-sanitize — sanitizer ordering note
- https://medium.com/@kalaivani7m/choosing-the-right-markdown-renderer-for-react-84055973120b — react-markdown raw-HTML/XSS note
- https://macmdviewer.com/blog/react-markdown-guide — react-markdown pipeline (remark→rehype)
- https://codecudos.com/blog/tiptap-vs-lexical-vs-slate-2026 and https://medium.com/@support_56991/lexical-vs-tiptap-2026-which-react-editor-framework-wins-b269441cfc2d — Lexical/TipTap 2026
- https://unanswered.io/guide/draft-js-status-deprecated-free-vs-editor-js + https://blog.sticobytes.com/why-react-quill-broke-my-react-19-app-and-how-tiptap-saved-it — Draft.js / react-quill deprecation status
- https://reactscript.com/render-streaming-ai-markdown/ — Streamdown adoption context
