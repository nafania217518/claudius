# React Performance / List Virtualization / Streaming Render

Category research for **claudius** frontend (React SPA, Vite + Tailwind). The core
problem: a live-growing transcript — thousands of rows (messages, code blocks,
tool-call cards, sub-agent output tails) updated at high frequency by streaming
tokens — that must auto-scroll to bottom, keep variable-height rows, and NOT
re-render the whole list on every token.

> **Verified 2026-09-21 via `mcp-browser`** (live web). All versions/status flags
> below were confirmed against live `mcp-browser` search snippets this run, not
> training memory. Main corrections to the earlier training-knowledge draft:
> **react-window is now v2.3.x and actively maintained** (old draft wrongly called
> it "maintenance mode / do not use"); **Vite 8 / Rolldown is stable (Mar 12 2026)**,
> replacing the old "esbuild dev + Rollup prod" model; **React Compiler 1.0 went
> stable Oct 7 2025**, a week *after* React 19.2 (Oct 1). Both flips are noted inline.

**Verdict in one line:** use **react-virtuoso** for the transcript (built-in
auto-scroll + variable heights + live-stream fit), fall back to
**@tanstack/react-virtual** where you want a headless primitive, layer
**memoization** (React Compiler over `React.memo`) so off-screen rows never
re-render on a token, and build with **Vite 8 (Rolldown)**.

---

## A. Virtualization libraries

### 1. react-virtuoso (`petyosi/react-virtuoso`, `virtuoso.dev`) — ⭐ primary pick
- **What:** purpose-built list/chat virtualizer. `Virtuoso` (plus
   `GroupedVirtuoso`, `VirtuosoGrid`, `TableVirtuoso`); `itemContent`, `count`,
   measured variable heights, `followOutput`, `atBottomStateChange`,
   `rangeChanged`, `initialTopMostItemIndex`, `startReached`/`endReached`, and an
   imperative API to prepend/append older & new messages and to re-pin to the
   live bottom on user submit.
- **Status:** actively maintained — **4.18.10 on npm (published days before
   2026-09-21)**, React 18/19 compatible.
- **+** Auto-scroll-to-bottom is built in and handles the "user scrolled up vs
   stay pinned to live bottom" UX — exactly the streaming-chat case. Variable row
   heights out of the box. Only the visible range mounts, so a new token does not
   re-render historical rows. Great chat docs.
- **−** More opinionized than a headless lib (owns the scroll container);
   custom scroll gestures / multi-list coordination take more discipline.
   NOTE: `@virtuoso.dev/message-list` is a **commercial (paid)** chat component —
   not the open-source `react-virtuoso`; don't confuse them for an OSS build.
- **Use in claudius?** **YES — primary.** Lowest-effort fit for a fast-growing,
   variable-height, auto-scrolling transcript. One `<Virtuoso>` for the main
   transcript; a second small instance per sub-agent output tail if they scroll
   independently.

### 2. TanStack Virtual / `@tanstack/react-virtual` (TanStack) — ⭐ alternative
- **What:** headless, framework-agnostic virtualizer. You supply a scroll element
   + `useVirtualizer({ count, getScrollElement, estimateSize, overscan,
   measureElement })`; it returns measured virtual items. Also ships grid/window
   virtualization and non-React variants (Solid, Svelte, Vue…).
- **Status:** very active — **3.14.9 on npm (published days before 2026-09-21)**,
   ~1,521 dependents in the npm registry, React 18/19.
- **+** Zero opinions — you control scroll container, DOM structure, and
   auto-scroll. Best when you virtualize *many different shapes* or need full
   control.
- **−** You implement auto-scroll + variable-height measurement yourself; more
   code than virtuoso for the common chat case.
- **Use in claudius?** **YES — alternative / escape hatch.** Reach for it if
   virtuoso's container ownership fights the layout, or when you want a fully
   controlled primitive. Both meet the core requirements; virtuoso is less work.

### 3. react-window (`bvaughn`, Bryan Vaughn) — **v2.3.x, actively maintained** ✅
- **What (v2):** a maintained v2 API: `List` (`rowComponent`, `rowCount`, dynamic
   heights via `useDynamicRowHeight`), `Grid` (`cellComponent`), `overscanCount`,
   `onRowsRendered`. 0 runtime deps, TypeScript-native.
- **Status:** **CORRECTED this run.** v1 was the abandoned line, but the v2
   rewrite is **actively maintained (~17k★, Snyk rates maintenance "Healthy",
   v2.3.x current — 2.3.1 seen; 2.2.7 was Feb 2026)**. v1 docs:
   `react-window-v1.vercel.app`; v2 docs: `react-window.vercel.app`. The old
   draft's "maintenance mode / do not use" claim is now **wrong** — v2 is a valid
   general-purpose virtualizer.
- **+** Small, fast, well-understood, maintained + TS-native.
- **−** **Still no built-in auto-scroll** — you must implement the pin-to-live
   bottom yourself, which is exactly the streaming-transcript pain point.
- **Use in claudius?** **OK for generic lists, but NOT for the transcript.**
   For the live-streaming transcript, **react-virtuoso / TanStack Virtual still
   win** (built-in auto-scroll + live-stream fit). Reach for react-window only
   for a plain non-streaming list.

### 4. react-virtualized-auto-sizer — NOT needed
- **What:** measured container width/height and passed them to a fixed-size list.
- **Status:** its own README now states **"More recent versions of react-window
   use ResizeObserver natively and do not require this package"**; and both
   virtuoso and TanStack size to their container on their own.
- **Use in claudius?** **NO.** Not needed with any of the picks above.

### 5. react-virtualized (legacy `bvaughn`) — maintenance / deprecated
- **What:** the older mega-list library (`List`, `Grid`, `WindowedScroller`).
- **Status:** **in maintenance mode since early 2025; deprecated** (confirmed by
   a 2026 comparison: "avoid for new work — its API is class-component era").
   Its own README points people to `react-window` as the lightweight replacement.
- **Use in claudius?** **NO.** Do not adopt.

### 6. useVirtualList / react-virtual-list (various forks, e.g. `@dedalik/use-react`) — legacy
- **What:** small windowed-list hook(s).
- **Status:** **legacy / effectively unmaintained**, no React 19 story; a few
   forks exist as learning/reference projects. Not worth adopting.
- **Use in claudius?** **NO.** Listed for completeness; ignore.

---

## B. Memoization & render performance

### 7. React.memo + react-fast-compare — use memo, but the dep is stale ⚠️
- **What:** `React.memo` skips re-render when props are shallow-equal;
   **react-fast-compare** was a fast deep-equal for a custom comparator.
- **Status:** `React.memo` is stable stdlib. **CORRECTED:** `react-fast-compare`
   is at **3.2.2 — effectively stale** (devcheck health ~33/100, <1 release/mo as
   of 2026; original FormidableLabs repo last touched 2020). Prefer
   hand-written comparator logic or **React Compiler** (below) over this dep.
- **+** Stops historical rows + unchanged tool cards from re-rendering when a new
   token arrives. **−** Must pass stable `itemContent`/render fns and stable keys;
   over-memoizing defeats it; the deep-compare dep is stale.
- **Use in claudius?** **YES for `React.memo`** on row/card components so only the
   *last* (streaming) row updates per token; **skip react-fast-compare** — use
   `React.memo` + stable props/keys and let React Compiler handle the heavy
   deep-memoization.

### 8. `useTransition` (React 18/19)
- **What:** marks render work non-urgent; keeps the UI responsive during heavy work.
- **Status:** stable, part of React core (shipped React 18; current in React 19.2).
- **+** Good for deferring "jump to message / filter / re-highlight" so token
   streaming never blocks input. **−** Adds `isPending` plumbing; not for the hot
   streaming path itself.
- **Use in claudius?** **YES — secondary.** For non-critical work (search,
   scroll-to, plan diffing), not for the per-token render.

### 9. `useDeferredValue`
- **What:** defers a value so cheap renders win over expensive ones.
- **Status:** stable, React core.
- **+** Ideal for keeping the input/composer snappy while the transcript updates,
   and for a debounced "filter transcript" input. **−** Deferred view can lag the
   live view (expected).
- **Use in claudius?** **YES — use for the transcript filter / search over
   history**, and to isolate expensive derived views from the streaming path.

### 10. React Compiler (`babel-plugin-react-compiler`)
- **What:** auto-memoizes components (stable references, skipped re-renders) —
   removes hand-written `useMemo`/`React.memo`/`useCallback`.
- **Status:** **CORRECTED:** **React Compiler 1.0 "stable" released Oct 7 2025**
   (one week *after* React 19.2 on Oct 1), back-compat down to React 17; battle-
   tested at Meta, production-ready. For Vite 8 it is wired through the React
   plugin's Babel option (`babel-plugin-react-compiler`) or the SWC/oxc path —
   not through the deprecated esbuild path.
- **+** Big, low-touch render-perf win for a tree full of re-renders.
   **−** Adds a build/transform step; has documented caveats (escape hatches).
- **Use in claudius?** **YES — the memo layer.** Prefer it *over* hand-memoizing
   and over the stale `react-fast-compare`; it is the cleanest way to make the
   streaming path cheap.

### 11. React 19 concurrent rendering (core primitives)
- **What:** `useTransition`, `useDeferredValue`, `useOptimistic`, `use()` +
   Suspense, Actions — background rendering, partial/interruptible updates.
- **Status:** React 19.0 (Dec 2024); **React 19.2 (Oct 1 2025)** added `Activity`,
   `useEffectEvent`, and — a week later — React Compiler 1.0 (Oct 7 2025).
   Adopt as baseline.
- **+** Foundation for keeping the streaming UI responsive; `useOptimistic` suits
   a "pending tool approval" UI. **−** Concurrency pitfalls if misused.
- **Use in claudius?** **YES — base version.** Target React 19; build the
   approve/reject tool-call UI with `useOptimistic`, streaming UI on the
   transition/deferred primitives.

---

## C. Native / CSS performance

### 12. CSS `content-visibility: auto` (+ `contain` / `contain-intrinsic-size`)
- **What:** tells the browser a subtree is layout/paint-independent so it skips
   rendering off-screen content (cheap "free" virtualization).
- **Status:** **Baseline Newly available (Sept/Oct 2024)** — widely supported,
   stable across current browsers as of 2026.
- **+** Zero-JS, tiny cost, huge win for large static subtrees (collapsed code
   blocks, sub-agent output tails you don't fully virtualize).
   **−** Scroll-jump if `contain-intrinsic-size` is wrong; not a substitute for
   true list virtualization at thousands of live rows.
- **Use in claudius?** **YES — complement.** Virtualize the main transcript; use
   `content-visibility: auto` on heavy but non-live subtrees (old code blocks,
   sub-agent tails) to avoid virtualizing *everything*.

---

## D. Build & tooling

### 13. Vite — baseline, now **Vite 8 / Rolldown**
- **What:** fast dev server + production build + code-splitting.
- **Status:** **CORRECTED/confirmed:** **Vite 8 stable March 12 2026** (Vite 8.1
   June 23 2026), with **Rolldown as a single unified Rust bundler replacing both
   esbuild (dev) and Rollup (prod)** — "the biggest architectural change since
   Vite 2." Reported build-time cuts up to ~87% (Linear 46s → 6s); 10–30× faster
   in benchmarks; Vite 8.1 already near Vite 7's weekly downloads. The old
   "esbuild dev + Rollup prod split" model is obsolete.
- **+** Fast build/HMR matters for iterating on a perf-sensitive UI; route-based
   + dynamic-import code-splitting keeps the initial bundle small. **−** Watch
   install size (pulls in `lightningcss` + the Rolldown binary).
- **Use in claudius?** **YES — baseline.** Adopt Vite 8 (Rolldown); lazy-load
   heavy panels; keep the transcript shell light.

### 14. esbuild — superseded inside Vite
- **What:** the fast Go transform/bundler that Vite used for dev.
- **Status:** **CORRECTED:** in Vite 8 the dev/prod bundling is done by
   **Rolldown** (and `@vitejs/plugin-react` now uses **Oxc** for the React-
   Refresh transform), not esbuild; the `esbuild` config knobs are the usual
   migration gotchas. esbuild still exists standalone, but it is **no longer
   Vite's path**.
- **Use in claudius?** **No direct action.** Use Vite 8's Rolldown; don't
   reintroduce esbuild config.

### 15. `@vitejs/plugin-react` / `@vitejs/plugin-react-swc` — Oxc is the default now
- **What:** the React JSX/refresh transform for Vite; SWC was the older Rust path.
- **Status:** **`@vitejs/plugin-react` is at v6.x (6.0.5, days before 2026-09-21)**
   and, **as of Vite 8, uses Oxc for core transforms + Fast Refresh by default —
   Babel is no longer a mandatory dependency**. `@vitejs/plugin-react-swc` still
   exists (align its version too if used), and `@vitejs/plugin-react-oxc` is the
   Oxc-first variant. The React Compiler is best wired via Babel/SWC/oxc — not
   esbuild.
- **+** Oxc path is the default and fast; SWC remains an option if already
   present. **−** SWC is now the fallback, not the default.
- **Use in claudius?** **Default to `@vitejs/plugin-react` (Oxc) + React
   Compiler;** use SWC only if a Babel/SWC path is already in place.

---

## E. Considered and rejected

- **Recast** — an AST code-rewriter that *preserves source formatting* (used to
  generate/transform code). It is **not a render-performance or virtualization
  tool**; irrelevant to this category. Skip. (Flagged because it appeared in the
  candidate list.)

---

## Recommendation for claudius

1. **Transcript virtualizer → `react-virtuoso` (4.18.x, petyosi).** Lowest-effort,
   best-fit for *all three* hard constraints at once: **auto-scroll to a live
   bottom** (`followOutput` / `atBottomStateChange` + imperative append/prepend
   handle "user scrolled up vs pinned to live"), **variable-height rows** out of
   box (code blocks, tool cards differ in height), and **high-frequency
   streaming** — only the visible range is mounted, so a new token re-renders the
   *current* row, not thousands of historical ones. A second small `<Virtuoso>`
   per sub-agent output tail keeps tails independently scrollable. (Skip the paid
   `@virtuoso.dev/message-list` — it's a commercial add-on, not OSS.)
2. **Escape hatch → `@tanstack/react-virtual` (3.14.x).** Same requirements,
   headless — pick it if virtuoso's container ownership fights the layout or you
   want full control. **Do NOT** adopt react-virtualized or useVirtualList
   (deprecated/unmaintained). **react-window v2 (v2.3.x) is maintained now**, so
   it is fine for a plain list — but it has **no built-in auto-scroll**, so it
   loses to virtuoso/TanStack for the *streaming* transcript.
3. **Memo layer → React Compiler (1.0, stable Oct 7 2025).** Prefer it over
   hand-memoization and over the stale `react-fast-compare`; keep `React.memo` on
   row/card components so only the streaming row updates per token. `useDeferredValue`
   for a transcript filter; `useTransition` for scroll-to/jump; target **React 19**
   with `useOptimistic` for the pending-tool UI.
4. **Native complement → `content-visibility: auto` (Baseline Newly available
   Sept/Oct 2024)** on heavy non-live subtrees (collapsed code blocks, sub-agent
   tails) so you don't have to virtualize everything.
5. **Build → Vite 8 / Rolldown (stable Mar 12 2026).** Single Rust bundler, up to
   ~87% build-time cut. Use `@vitejs/plugin-react` (Oxc, v6) + React Compiler via
   its Babel/SWC/oxc path; SWC only if already on that path.
6. **Streaming batching (pattern, not a lib):** buffer incoming tokens in a
   mutable `useRef` and flush to state **once per `requestAnimationFrame`** —
   capping renders at ~60 fps while LLMs stream 30–100 tokens/sec (confirmed as
   the "decouple intake from render" fix in 2026 write-ups; a unified
   WebSocket+SSE `useServerStream`-style hook does the same batching behind one
   transport). For >150 tok/s, offload parsing to a Web Worker or draw to canvas.
   Virtuoso's visible-range mounting makes the flush cheap; keep it to the single
   streaming row.

**Do NOT** use react-virtualized, useVirtualList, or Recast. **react-window v2 is
fine for plain lists** (it's maintained) but is the wrong choice for the live
transcript because it lacks built-in auto-scroll.

---

## Sources (via `mcp-browser` web_search snippets, 2026-09-21)

Confirmed against live snippets (version/status numbers taken from these):

- `https://www.npmjs.com/package/react-virtuoso` — react-virtuoso **4.18.10**,
   published ~8 days before 2026-09-21.
- `https://www.npmjs.com/package/@tanstack/react-virtual` — **3.14.9**, ~1,521
   dependents, published ~10 days before 2026-09-21.
- `https://github.com/bvaughn/react-window` + `https://socket.dev/npm/package/react-window/versions/2.3.1`
   + Snyk — v2 maintained (v2.3.x; 2.2.7 Feb 2026), Snyk "Healthy".
- `https://github.com/bvaughn/react-virtualized` — maintenance since early 2025;
   README points to react-window (corroborated by a 2026 comparison, pistack.xyz).
- `https://github.com/bvaughn/react-virtualized-auto-sizer` — "recent react-window
   uses ResizeObserver natively and does not require this package."
- `https://www.npmjs.com/package/react-fast-compare` + `https://devcheck.dev/package/react-fast-compare`
   — **3.2.2**, health ~33/100, <1 release/mo (stale).
- `https://react.dev/blog/2025/10/01/react-19-2` — React 19.2 (Oct 1 2025;
   Activity, useEffectEvent).
- `https://react.dev/blog/2025/10/07/react-compiler-1` — **React Compiler 1.0
   stable Oct 7 2025**, back-compat to React 17.
- `https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/content-visibility`
   + `https://web.dev/blog/css-content-visibility-baseline` — Baseline Newly
   available Sept/Oct 2024; pair with `contain-intrinsic-size`.
- `https://vite.dev/blog/announcing-vite8` + `https://vite.dev/blog/announcing-vite8-1`
   — Vite 8 stable Mar 12 2026 (Rolldown unified Rust bundler, ~87% / 10–30× build
   cut), Vite 8.1 Jun 23 2026.
- `https://www.npmjs.com/package/@vitejs/plugin-react` — v6.0.5; Vite 8 uses Oxc
   by default, Babel no longer mandatory (corroborated by deepwiki + sitepoint).
- `https://dev.to/nainikmehta/how-to-fix-llm-streaming-lag-and-react-render-storms-13ee`
   + `https://www.sitepoint.com/streaming-backends-react-controlling-re-render-chaos/`
   — `useRef` + `requestAnimationFrame` token-batching; unified WS+SSE `useServerStream`
   hook.

Cross-checked via `mcp-browser` searches: TanStack Virtual vs react-virtuoso 2026,
virtualize long list React 2026, Vite 8 + Rolldown migration 2026, React 19.2 /
React Compiler 1.0 stable, LLM streaming render-storm fixes.
