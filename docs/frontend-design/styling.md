# React Styling — claudius frontend

Context: dense developer dashboard (live agent transcript stream via WebSocket/SSE, tool-call
cards, approve/reject dialogs, settings panel, sub-agent chips, usage bar, code highlighting).
Long virtualized transcripts ⇒ performance matters. Small bundle, fast build, dark mode.
Date of research: 2026-09-21.

Big picture: the field split in 2025–2026 into **build-time** (Tailwind, CSS Modules,
Vanilla Extract, Panda, UnoCSS — output static CSS, zero runtime) vs **runtime CSS-in-JS**
(styled-components, Emotion — inject styles at render, larger bundle, RSC friction).
Runtime CSS-in-JS is in decline: Mantine 7.0 moved off Emotion to CSS Modules; styled-components
is in maintenance mode. (codecudos 2026, Mantine docs)

---

## Tailwind CSS v4 — recommended primary

- **What:** utility-first CSS framework; classes in markup, only used utilities compiled into a static CSS file.
- **Status (2026):** v4.0 released Jan 22, 2025 (official blog; a secondary source says "late 2025" — official date is authoritative). Current line v4.3 per site nav. Actively developed.
- **Plus:** Rust "Oide" engine — full build 3.78× faster, no-change rebuild 182× (35ms→192µs); native cascade layers, `color-mix()`, `@property`; zero runtime; `@import "tailwindcss"` one-line install; `@tailwindcss/vite` first-party Vite plugin; CSS-first `@theme` config with tokens as CSS custom properties (dark mode = CSS var swap); dynamic utilities; oklch P3 palette; container queries.
- **Minus:** utility classes in markup can read busy at high density; v3→v4 is a breaking change (irrelevant for a new project).
- **Use in claudius?** **Yes — primary.** Zero runtime, small output bundle, CSS-var tokens for dark mode, fast builds, and the whole ecosystem (shadcn/ui, Radix recipes) is built on it.

## CSS Modules

- **What:** scoped per-file `.module.css`; class names hashed at build.
- **Status (2026):** native Vite/RSPack support, zero deps, zero runtime — the 2026 default when you don't want a framework. Mantine 7.0 moved its own internals from Emotion to CSS Modules, a telling trend.
- **Plus:** zero runtime, zero config, no class-name collisions, easy dark mode via CSS vars, trivial bundle impact.
- **Minus:** no shared design-system vocabulary without extra work; theming is manual var plumbing; no utility ergonomics for fast dense UI.
- **Use in claudius?** **Yes — escape hatch.** Use for complex bespoke widgets (code-block panes, diff views) where utilities get painful. Pairs cleanly with Tailwind.

## Vanilla Extract

- **What:** write `.css.ts` stylesheets in TypeScript; compiled to static CSS at build.
- **Status (2026):** active (Vanilla/Stripe), but smaller community. 2026 verdict: the pick for type-safety-first teams wanting scoped CSS without a new utility dialect.
- **Plus:** fully type-safe, scoped by default, typed theme contracts (dark mode = theme contract), zero runtime, closest to "real CSS".
- **Minus:** more ceremony (separate stylesheet files, extra imports); fewer recipes/templates; ecosystem far smaller than Tailwind.
- **Use in claudius?** No. Overkill for a solo/small project; YAGNI when Tailwind + CSS Modules cover the same ground.

## Panda CSS

- **What:** typed style objects + recipes + semantic tokens; extracted to static CSS at build.
- **Status (2026):** active (Chakra team); now the styling engine folded into the Chakra v3 ecosystem. Strong DX for a governed design system.
- **Plus:** CSS-in-JS ergonomics with zero runtime; semantic tokens adapt by theme; recipes = typed component variants.
- **Minus:** build step + generated `styled-system` folder; learning curve; more machinery than a small app needs.
- **Use in claudius?** No. Right tool for a product with a big design system; not for this one.

## UnoCSS

- **What:** configurable atomic-CSS engine — Tailwind-like utilities, but the engine itself (rules, shortcuts, presets) is yours.
- **Status (2026):** active, happy in the Vite/Vue world; community presets exist for daisyUI and shadcn (`unocss-preset-shadcn`).
- **Plus:** zero runtime, on-demand generation is extremely fast, fully customizable vocabulary.
- **Minus:** utility-in-markup (same density cost as Tailwind) plus a smaller ecosystem; if you're content with Tailwind, no reason to switch.
- **Use in claudius?** No. Tailwind covers the same zero-runtime utility model with a 10× bigger ecosystem.

## daisyUI

- **What:** component class names layered on top of Tailwind (`btn`, `card`, …) instead of primitives.
- **Status (2026):** v5 supports Tailwind v4; still maintained. But migrating off it means rewriting UI — you're dependent on its class vocabulary.
- **Plus:** fast prototyping, batteries-included components, dark mode built in.
- **Minus:** styling and components coupled through its classes; hard to leave; opinionated look.
- **Use in claudius?** No. For a distinctive dense dashboard, daisyUI's stock look and class lock-in fight us. Prefer Radix + own CSS.

## shadcn/ui + Tailwind

- **What:** not a dependency — CLI copies Radix + Tailwind + `class-variance-authority` components into your repo; you own the code.
- **Status (2026):** the de facto default component layer; assumes Tailwind; very active.
- **Plus:** full control, no version lock-in, accessible Radix primitives, dark mode via CSS vars, small per-component footprint.
- **Minus:** assumes Tailwind (fine — we're using it); you maintain the components; not a "styling mechanism" alone, so mostly out of scope for this file.
- **Use in claudius?** **Yes, for components** — see the components file. It's the correct companion to Tailwind here.

## Radix UI + own CSS

- **What:** headless accessible primitives (Dialog, Popover, Tooltip…); you supply all styling.
- **Status (2026):** active; the underlay of shadcn and Chakra v3 (Ark).
- **Plus:** a11y for free, styling decoupled (works with any mechanism in this file), tree-shakeable.
- **Minus:** you write every pixel; more code for a dense UI with many controls.
- **Use in claudius?** **Yes — via shadcn** for the interactive primitives (approve/reject dialog, settings popover, sub-agent chips).

## Mantine

- **What:** full component library; v7 rewrote styling on **CSS Modules** (Emotion moved to opt-in `@mantine/emotion`, available since 7.9).
- **Status (2026):** v7 active, mature, no runtime by default now.
- **Plus:** huge component set, zero-runtime by default, good a11y.
- **Minus:** large surface area, opinionated look, heavier bundle for a dashboard that mostly needs a few custom widgets; the runtime path (Emotion, `sx`, `createStyles`) still exists if you use it.
- **Use in claudius?** No. A component library, out of scope; and its own v7 move to CSS Modules confirms the trend this file recommends.

## Chakra UI v3

- **What:** full component library; v3 unifies headless Ark UI + Panda-style theming + Park UI design system, with copy-paste "snippets".
- **Status (2026):** v3 released Oct 22, 2024, active. **Note: still uses Emotion / runtime CSS-in-JS internally** (deliberate, per official FAQ) — the one major library not on the zero-runtime trend. v2 is legacy.
- **Plus:** polished design system, semantic tokens, dark-mode without manual work, active.
- **Minus:** runtime styling (bundle + render cost in a streaming UI), heavy for a few custom widgets, styling+components locked together.
- **Use in claudius?** No. A component library, out of scope; its runtime CSS-in-JS is exactly what we're avoiding.

## styled-components

- **What:** runtime CSS-in-JS via JSX `styled`/`css`.
- **Status (2026):** **maintenance mode since March 2025** (confirmed by sanity, GitHub discussion #5568, multiple 2025–2026 articles). Fine to maintain existing code; not for new bets.
- **Plus:** component co-located styles, dynamic theming.
- **Minus:** runtime cost (serializes + injects styles per render), larger bundle, RSC friction.
- **Use in claudius?** **No.** Runtime cost per render is the wrong fit for a UI that continuously streams and re-renders the transcript.

## Emotion

- **What:** runtime CSS-in-JS (atomic, SSR-aware); underpinned Mantine ≤6.9 and still inside Chakra v3.
- **Status (2026):** active but same decline as styled-components; the field's 2026 trend is moving off it (Mantine 7.0 → CSS Modules).
- **Plus:** mature, SSR-ready, dynamic theming.
- **Minus:** runtime cost, RSC friction; momentum is against it.
- **Use in claudius?** No. For the same reason as styled-components.

## Lightning CSS / PostCSS / Vite CSS tooling

- **What:** Lightning CSS = Rust transpiler+minifier (Tailwind v4 uses it under the hood). PostCSS = JS plugin pipeline. In a Vite SPA both are largely already there.
- **Status (2026):** both active; Lightning CSS increasingly the default fast path.
- **Plus:** fast, modern, no extra decision to make with Vite.
- **Minus:** not a styling approach per se.
- **Use in claudius?** **Incidentally, yes** — whatever Vite ships; no separate adoption needed. Lightning CSS is preferred where a choice exists.

## Plain CSS / native

- **What:** hand-written global CSS, CSS custom properties, `:root`/`[data-theme]` dark mode.
- **Status (2026):** evergreen. `color-scheme`, cascade layers, `@container`, `color-mix()` are all in browsers by 2026.
- **Plus:** zero deps, zero runtime, total control.
- **Minus:** no scoping (collision risk), no token/variant ergonomics, slow to build a dense UI.
- **Use in claudius?** **Yes, as tokens layer** — a small global `tokens.css` (CSS vars for colors/spacing/dark theme) sits underneath whatever mechanism; Tailwind v4's `@theme` is this mechanism anyway.

---

## Recommendation for claudius

**Tailwind CSS v4 via `@tailwindcss/vite`, CSS Modules as escape hatch, Radix via shadcn for
interactive primitives — no runtime CSS-in-JS.**

- **Tailwind v4** is the mechanism: zero runtime and only-used-utilities bundle matter most for
  a streaming, continuously-re-rendering UI; CSS-var `@theme` tokens give dark mode and a
  consistent dense look with almost no ceremony; the Rust engine makes fast builds trivial.
- **CSS Modules** for the few bespoke widgets utilities fight (code/diff panes). Same static-CSS
  contract, so the two coexist cleanly.
- **shadcn/ui + Radix** (a components-file topic) inherits Tailwind + CSS-var dark mode for free.
- **Rejected:** styled-components and Emotion (runtime cost per render — the wrong direction for
  this app; styled-components in maintenance mode, Mantine itself moved off Emotion in v7);
  Panda / UnoCSS / Vanilla Extract (more machinery and smaller ecosystems for a project this size —
  revisit only if it grows into a governed design system); Mantine / Chakra / daisyUI as full kits
  (covered in the components file).
- **One note:** if the frontend ever adds React Server Components, the build-time choices above
  keep working untouched; the runtime-CSS-in-JS ones don't — another reason to avoid them here.

---

## Sources

- Tailwind CSS v4 official announcement (benchmarks, features, Jan 22 2025): https://tailwindcss.com/blog/tailwindcss-v4
- Tailwind vs Vanilla Extract vs Panda CSS, 2026: https://briandetering.net/2026/06/16/tailwind-vs-vanilla-extract-vs-panda-css-2026/
- Tailwind vs CSS Modules vs styled-components, 2026: https://codecudos.com/blog/tailwind-css-vs-css-modules-vs-styled-components-2026
- UnoCSS vs Panda CSS vs Vanilla Extract, 2026: https://codecudos.com/blog/unocss-vs-panda-css-vs-vanilla-extract-2026
- Mantine docs — Emotion replaced by CSS Modules in v7.0: https://mantine.dev/styles/emotion/
- Chakra UI v3 announcement (Oct 22 2024; retains runtime CSS-in-JS per FAQ): https://www.chakra-ui.com/blog/announcing-v3
- styled-components maintenance mode (cross-checked): https://www.sanity.io/blog/2025/09/11/styled-components-maintenance-mode , https://github.com/styled-components/styled-components/discussions/5568 , https://buildmvpfast.com/articles/styled-components-alternatives-2026
- shadcn preset for UnoCSS (context): https://github.com/unocss-community/unocss-preset-shadcn
