# Meta-frameworks & build tooling — claudius frontend (as of 2026-09-21)

Context: claudius frontend is a **React SPA** that live-streams a Kotlin/Spring-AI
agent over **WebSocket/SSE** — transcript + reasoning, live tool-call cards,
approve/reject dialogs, sub-agent chips, settings, usage bar. **Single user at a
time, no SEO/SSR need.** That one fact drives every verdict below: client-first,
fast-HMR, no server-rendering tax, no platform lock-in. Wire JSON must validate
against the backend's Kotlin DTOs. Long transcripts → perf matters.

Legend: status is as of Sept 2026. "✓" = active/maintained. "⚠" = maintenance/declining.
"✗" = deprecated/archived/abandoned.

## TL;DR verdicts

| Tool | Status | Use in claudius? |
| --- | --- | --- |
| **Vite** | ✓ default for new projects | **YES — primary build tool** |
| **React Router 7** | ✓ (Remix merged in) | **YES — as the router, data/declarative mode** |
| TanStack Query | ✓ | OPTIONAL — for REST server-state (optional) |
| Next.js | ✓ dominant | NO — RSC/SSR overkill + Vercel lock-in + RSC CVEs |
| Remix (v2) | ✗ EOL | NO — dead; superseded by React Router 7 |
| TanStack Start | ⚠ v1 RC | NO — too young, RSC experimental |
| Astro | ✓ | NO — content/islands, wrong shape |
| Qwik | ✓ v2 beta | NO — resumability unneeded, beta + RCE history |
| SolidJS | ✓ | NO — not React |
| Preact | ✓ | NO — stick with React ecosystem |
| Svelte / SvelteKit | ✓ | NO — not React |
| Turbopack | ✓ stable | NO standalone — Next-coupled |
| Webpack | ✓ legacy | NO — slow; only if module federation (not needed) |
| esbuild | ✓ | YES implicitly — it's inside Vite |
| Rspack | ✓ 2.0 | NO — webpack-compat path we don't need |
| Rollup | ✓ | YES implicitly — Vite's prod + lib bundler |
| Blitz.js | ⚠ pivoted to Next toolkit | NO |
| Gatsby | ✗ effectively abandoned | NO |
| RedwoodJS | ⚠ winding down | NO — wrong stack (GraphQL fullstack) |
| Wasp | ⚠ | NO — Ruby-based fullstack, wrong stack |

---

## Build tools

### Vite — use as primary build tool
- **What:** dev server + bundler; esbuild for dev pre-bundling/HMR, Rollup for prod builds.
- **Status:** ✓ the default for new React/TS projects in 2026. Sub-second cold start, near-instant HMR.
- **Plus:** best-in-class dev loop; TS-native; huge plugin base; "just works" for an SPA.
- **Minus:** dev/prod use different bundlers (edge cases surface one env only); modern-browser assumption.
- **Claudius:** **YES.** Fast HMR is exactly what the fast-moving tool-call-card / streaming UI needs; SPA-native so no SSR tax; no lock-in. This is the default.
- src: reintech.io/javascript-build-tools-comparison-2026; byteledger.vizleo.com/blog/best-bundlers-2026

### esbuild — used implicitly via Vite
- **What:** Go-based bundler/transformer; the raw-speed benchmark; powers Vite's dev pre-bundling.
- **Status:** ✓ active. No type-checking, basic code-splitting, smaller plugin set by design.
- **Claudius:** **YES implicitly** — it's already inside Vite; use it only if you ever ship a standalone lib. Not a top-level choice.
- src: reintech.io/javascript-build-tools-comparison-2026

### Rollup — used implicitly via Vite
- **What:** mature production/library bundler; what Vite uses for the production build.
- **Status:** ✓ active; still the standard for library output.
- **Claudius:** **YES implicitly.** Not a top-level decision — Vite wraps it.
- src: reintech.io/javascript-build-tools-comparison-2026

### Turbopack — NO standalone
- **What:** Rust incremental bundler by Vercel, designed for Next.js.
- **Status:** ✓ stable in 2026; **default bundler for dev+build in Next.js 16.**
- **Minus:** tightly coupled to Next; not a general-purpose tool.
- **Claudius:** **NO.** Only relevant if you adopt Next (you won't).
- src: nextjs.org/docs/.../version-16; ishu.dev/post/turbopack-nextjs-16-migration-guide-2026-04-27

### Webpack — NO
- **What:** the mature JS bundler; unmatched plugin ecosystem; module federation.
- **Status:** ✓ v5, still runs millions of apps, but "optional" for new projects; 5–10s cold start even optimized.
- **Claudius:** **NO.** Slow HMR kills the streaming-UI dev loop; module federation isn't needed for a single-user SPA.
- src: reintech.io/javascript-build-tools-comparison-2026; byteledger.vizleo.com/blog/best-bundlers-2026

### Rspack — NO
- **What:** Rust, webpack-API-compatible bundler by ByteDance.
- **Status:** ✓ active; 2.0 (2026) goal is "not just a faster webpack"; 85%+ of top-50 webpack plugins work.
- **Claudius:** **NO.** It's the "stay on webpack, get speed" path — claudius shouldn't start on webpack at all.
- src: rspack.rs/guide/start/introduction; ecorpit.com/rspack-vs-rolldown-webpack-migration-decision-2026

---

## React meta-frameworks

### Next.js — NO (for claudius)
- **What:** RSC/SSR/SSG/ISR meta-framework; largest ecosystem + hiring pool.
- **Status:** ✓ dominant, Next.js **16 / 16.2**, React **19.2**, Turbopack default, React Compiler stable, Cache Components.
- **Plus:** deepest ecosystem, most jobs, streaming SSR.
- **Minus:** RSC + caching-model churn is a recurring headache; **Vercel lock-in** (image opt / ISR cache / edge assume vendor infra off-platform); RSC security surface — **CVE-2026-23864** (RSC DoS, CVSS 7.5), plus prior RCEs (CVE-2025-55182 / -66478).
- **Claudius:** **NO.** It's an interactive single-user SPA driven by WS/SSE from a **Kotlin** backend — RSC/SSR is dead weight, and RSC would put a Node process between the UI and the Kotlin agent for no benefit. Skip the streaming-SSR and just push events over the socket.
- src: nextjs.org/blog/next-16; botmonster.com/web-dev/best-react-frameworks-2026; netlify.com/changelog/2026-01-26-react-nextjs-dos-vulnerability

### React Router 7 (the merged Remix) — YES as the router
- **What:** the router React apps already use; **Remix merged into it in Dec 2024** (loaders/actions/nested routing/SSR folded in).
- **Status:** ✓. Two modes — *declarative/data* (drop-in router for existing SPAs) and *framework* (full-stack Remix-style + SSR). RSC is preview-only.
- **Note:** "Remix 3" is a **separate React-free Preact project** (beta Apr 30 2026, no v2 migration) — not this.
- **Claudius:** **YES, but in data/declarative mode, not framework mode.** Use it for transcript/settings routing and (optionally) route-level data loaders; don't pull in its SSR/framework mode.
- src: botmonster.com/web-dev/best-react-frameworks-2026; reactrouter.com; blog.logrocket.com/react-router-v7-modes

### Remix v2 — ✗ DO NOT USE (EOL)
- **Status:** **EOL.** As of **June 17 2026**, Remix v2 + React Router v6 stopped receiving security updates. Superseded by React Router 7.
- **Claudius:** **NO.** New apps never start here.
- src: markaicode.com/integrate/remix-with-nextjs/; botmonster.com/web-dev/best-react-frameworks-2026

### TanStack Start — NO (too young)
- **What:** full-stack React, type-first; TanStack Router + **Vite + Nitro** server.
- **Status:** ⚠ **v1 release-candidate (early 2026)**, not stable; RSC still experimental.
- **Claudius:** **NO now.** Interesting (client-first, type-safe, Vite-based) but a young RC for a conservative agent UI. Revisit when v1 stabilizes.
- src: botmonster.com/web-dev/best-react-frameworks-2026; tanstack.com/blog/announcing-tanstack-start-v1

### Astro — NO
- **What:** islands, SSG-first, near-zero JS; React is just one island framework.
- **Status:** ✓ Astro **6.x** (Node 22+, Cloudflare focus).
- **Claudius:** **NO.** Islands fight a heavy interactive SPA; its zero-JS static sweet spot (docs/blogs/marketing) doesn't apply to a live agent UI.
- src: botmonster.com/web-dev/best-react-frameworks-2026

### Qwik — NO
- **What:** resumable framework, ~1kb boot regardless of app size; JSX/React-like DX.
- **Status:** ✓ active but **v2 still in beta** (Sept 2026); had **CVE-2026-27971 RCE** (unsafe deserialization, Mar 2026).
- **Claudius:** **NO.** Resumability optimizes first-load of huge pages — claudius is a streaming SPA, not a giant static page. Beta + RCE history add risk for no upside.
- src: qwik.dev; github.com/QwikDev/qwik/pulse; sentinelone.com/vulnerability-database/cve-2026-27971

---

## React alternatives (not React → not for claudius, noted for completeness)

### SolidJS — NO
- **What:** fine-grained reactivity, tiny bundle (often cited ~15x smaller than React), similar API.
- **Status:** ✓ active; a leading "fix React's mistakes" candidate.
- **Claudius:** **NO** — claudius is a React app; switching frameworks to save bundle bytes isn't worth it.

### Preact — NO
- **What:** tiny (~3kb) React-compatible library.
- **Status:** ✓ active.
- **Claudius:** **NO.** Ecosystem (code highlighting, editors, data libs) is React; stay on React. (Preact is also what the offshoot Remix 3 chose.)

### Svelte / SvelteKit — NO
- **What:** compiler framework; SvelteKit = its meta-framework.
- **Status:** ✓ active (Svelte 5 line).
- **Claudius:** **NO** — not React.
- src: birjob.com/blog/svelte-solid-qwik-react-2026; syncfusion.com/blogs/post/5-react-alternatives-frontend-dev-2026

### Angular / Blazor (WASM) — NO
- **Status:** ✓ active, but non-React.
- **Claudius:** **NO** — wrong language/ecosystem; the task is React-centric.

---

## Fullstack / legacy frameworks — all NO for claudius

### Blitz.js — NO
- **Status:** ⚠ Pivoted from a standalone fullstack framework to **"The Missing Fullstack Toolkit for Next.js"** (a layer *on top of* Next, not a framework of its own). Last PR ~Dec 2025.
- **Claudius:** **NO.** It now depends on Next.js, which claudius is already rejecting.
- src: github.com/blitz-js/blitz; blitzjs.com

### Gatsby — NO (effectively abandoned)
- **Status:** ✗ **Effectively dead.** Not formally discontinued, but Netlify's 2023 acquisition ended active development; single-maintainer compatibility patches only, plugin ecosystem largely unmaintained. Widespread migration **to Astro** in 2025–26.
- **Claudius:** **NO.** Don't touch a sunset framework.
- src: vardot.com/en/ideas/blog/gatsby-abandoned-drupal-migration; docs.astro.build/en/guides/migrate-to-astro/from-gatsby

### RedwoodJS — NO
- **Status:** ⚠ **Winding down** (per Wasp, Sept 2026). Legacy GraphQL full-stack (React + GraphQL + Prisma). Successor candidates: CedarJS, RedwoodSDK, Wasp.
- **Claudius:** **NO.** Wrong shape (GraphQL fullstack) and wrong stack (backend is Kotlin, not GraphQL/Node).
- src: wasp.sh/resources/2026/09/09/redwoodjs-alternatives-2026; docs.redwoodjs.com

### Wasp — NO
- **Status:** ⚠ Active but niche; **Ruby-based** fullstack (React front + Ruby back).
- **Claudius:** **NO.** Hard-fails the Kotlin-backend requirement.
- src: wasp.sh; trybuildpilot.com/682-wasp-vs-redwood-vs-blitz-2026

---

## Recommendation for claudius

**Vite + React SPA, routed by React Router 7 (data/declarative mode).**

- **Vite** is the primary build tool: near-instant HMR is what the live tool-call-card /
  streaming UI needs, SPA-native so no SSR/RSC tax, no platform lock-in, and it already
  wraps esbuild (dev) + Rollup (prod) — so those two are covered for free.
- **React Router 7** handles transcript/settings routing and optional route-level data
  loaders in data mode; do **not** adopt its framework/SSR mode.
- **Optionally** add TanStack Query if the UI pulls server state over REST, but the
  primary data path is **WS/SSE from the Kotlin/Spring-AI backend**, so query-state may
  not be needed — skip it until a real cache/dedup need appears.
- **Reject** every RSC/SSR meta-framework (Next.js, TanStack Start, Astro, Qwik): this is
  a single-user interactive agent GUI, not a content/SEO site, and an RSC layer would insert
  a Node process between the UI and the Kotlin agent for zero benefit while adding RSC
  CVE exposure (CVE-2026-23864 DoS; CVE-2025-55182/66478 RCE).
- **JSON validation:** generate TypeScript types from the backend's Kotlin DTOs (e.g. a
  schema → TS step) so wire payloads typecheck against `PermissionResult` / tool-call /
  message shapes — keeps the frontend in sync with the backend contract.
- **Perf (long transcripts):** virtualize the transcript list; keep streaming tool-call
  cards in a fixed tail rather than growing the DOM unbounded.

**Bottom line:** the fastest path to "done" is a plain Vite React SPA. Every "meta"
framework here exists to solve SSR/SEO/hydration/edge problems that claudius does not have.
Adopt the router and the bundler; ignore the rest. Revisit TanStack Start when it hits v1 stable.

---

## Sources

- Best React frameworks 2026 (Next / React Router 7 / TanStack Start / Astro): https://botmonster.com/web-dev/best-react-frameworks-2026/
- JS build tools 2026 (Vite / esbuild / Turbopack / Webpack / Rollup): https://reintech.io/blog/javascript-build-tools-comparison-2026
- Best JS bundlers 2026 (Vite / Turbopack / Rspack / esbuild): https://byteledger.vizleo.com/blog/best-bundlers-2026
- Turbopack default in Next.js 16: https://ishu.dev/post/turbopack-nextjs-16-migration-guide-2026-04-27 ; https://nextjs.org/docs/app/guides/upgrading/version-16
- Next.js 16 + React 19.2: https://www.webhani.com/blog/nextjs-16-react-compiler-turbopack_2026
- RSC DoS vulnerability (CVE-2026-23864): https://www.netlify.com/changelog/2026-01-26-react-nextjs-dos-vulnerability/
- RSC RCE (CVE-2025-55182 / -66478): https://www.slcyber.io/research/high-fidelity-detection-mechanism-for-rsc-next-js-rce-cve-2025-55182-cve-2025-66478
- Remix → React Router 7 / Remix 3 status: https://botmonster.com/web-dev/best-react-frameworks-2026/ ; https://markaicode.com/integrate/remix-with-nextjs/
- React Router 7 modes: https://blog.logrocket.com/react-router-v7-modes/
- TanStack Start v1 RC: https://tanstack.com/blog/announcing-tanstack-start-v1
- Qwik (active, v2 beta, RCE CVE-2026-27971): https://qwik.dev/ ; https://github.com/QwikDev/qwik/pulse ; https://www.sentinelone.com/vulnerability-database/cve-2026-27971/
- React alternatives (Solid/Preact/Svelte): https://www.birjob.com/blog/svelte-solid-qwik-react-2026 ; https://www.syncfusion.com/blogs/post/5-react-alternatives-frontend-dev-2026
- Rspack 2.0: https://rspack.rs/guide/start/introduction ; https://ecorpit.com/rspack-vs-rolldown-webpack-migration-decision-2026/
- Blitz.js (pivoted to Next toolkit): https://github.com/blitz-js/blitz ; https://blitzjs.com/
- Gatsby (effectively abandoned): https://www.vardot.com/en/ideas/blog/gatsby-abandoned-drupal-migration ; https://docs.astro.build/en/guides/migrate-to-astro/from-gatsby/
- RedwoodJS (winding down) / Wasp: https://wasp.sh/resources/2026/09/09/redwoodjs-alternatives-2026 ; https://trybuildpilot.com/682-wasp-vs-redwood-vs-blitz-2026
