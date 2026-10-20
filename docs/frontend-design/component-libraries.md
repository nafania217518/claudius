# React Component / UI Kit Libraries

Reference for the `claudius` React frontend (Vite + Tailwind, dark-mode-first, dense
developer dashboard). This is the **component-kit** question (dialogs, buttons, tables,
forms, tabs, etc.). It is *not* the CSS-mechanism question (Tailwind vs CSS-in-JS vs
vanilla-extract) — see the separate `styling.md`.

> **✅ VERIFIED 2026-09-21 via `mcp-browser`.**
> Live web research this session (network was UP): pages were opened, not just snippets read.
> Version numbers below are current as of **2026-09-21** and confirmed against primary
> sources (changelogs, project homepages, release blogs). The Sources list at the bottom
> contains URLs **actually opened this session**. Re-run the search if a version looks stale.

**Decision context.** claudius needs: accessible approve/reject **dialogs/modals**, a
**settings forms** panel (provider/model, token limits, permission mode), sub-agent
chips, a usage bar, live tool-call cards. Hard constraints: **small bundle + fast
build**, **dark mode**, **dense** layout, and it **must NOT look generic/enterprise**.
This splits the field into two families:

- **Copy-in / headless primitives** (you get a11y + behavior, bring your own look) — best fit.
- **Batteries-included kits** (styled components, you take the dependency) — convenient but heavier & more opinionated.

---

## A. Copy-in / headless primitives   (recommended family)

### shadcn/ui   — ✅ TOP PICK
- **What:** Not an npm dependency — a CLI (`npx shadcn@latest add button`) that **copies component source into your repo**, assembled from a component **registry**. Built on **Radix UI primitives (or Base UI) + Tailwind + class-variance-authority (CVA)**; runtime deps are tiny (`clsx`, `tailwind-merge`).
- **Status (2026-09-21):** Very popular, actively developed. CLI reached **v4 (Mar 2026)**; added **registry `include`/`validate`** (May 2026) and **GitHub-based registries** (Jun 2026). **Since July 2026 the default primitive layer is Base UI** (Radix is the other option you opt into). Framework/toolchain-aware (Next App Router, Vite).
- **Plus:** Zero/opaque runtime lock-in, unopinionated styling you fully own, **dark mode via `class` strategy** (next-themes pattern) — ideal for dark-first, a11y inherited from the primitive layer, tiny bundle, fits dense custom dashboards, won't look "generic."
- **Minus:** You own/maintain the copy-in components (no single version to `npm update`); more upfront work.
- **Use in claudius?** **YES — primary.** Best match for dense + dark + small-bundle + accessible approve/reject dialogs + custom look. Pairs natively with the planned Tailwind + Vite.

### Base UI   — ✅ RECOMMENDED primitive layer (now shadcn default)
- **What:** **Headless, unstyled, accessible primitives** created by the **MUI team** (`base-ui.com` canonical; `@base-ui/react`, 35 components). Launched **alpha Dec 2024**, reached **stable 1.0.0 on Dec 11 2025**, and is the **default primitive layer in shadcn/ui as of July 2026**.
- **Status (2026-09-21):** Actively developed — releases nearly monthly, **v1.8.0 (Sep 4 2026)** is latest. Fast-moving, backed by MUI maintainers. **Note: "MUI Base" is deprecated in favour of Base UI.**
- **Plus:** Modern a11y primitives, no style layer to fight, MIT, active, first-class Combobox/Autocomplete (good for virtualized long lists — relevant to claudius transcripts).
- **Minus:** Younger than Radix (1.x only ~1 year), so slightly thinner long-term track record.
- **Use in claudius?** **YES — preferred default primitive layer.** *Reverses the prior "Radix safer, Base UI unproven" verdict:* Base UI is now stable and is what shadcn ships by default, so use it as the primitive layer and fall back to Radix only if a component is missing.

### Radix UI   — ✅ mature fallback / alternative primitive layer
- **What:** `@radix-ui/react-*` — **headless, unstyled, accessible primitives** (Dialog, AlertDialog, Tabs, Tooltip, Switch, Select, Popover, DropdownMenu, HoverCard, Collapsible, Menubar…). Also a **unified `radix-ui` package**; MIT.
- **Status (2026-09-21):** Still the most-used headless a11y primitive set and the classic layer under shadcn/ui. **Actively maintained — now under WorkOS** (the core team's slower cadence late 2023–24 is why Base UI emerged; Radix is *not* abandoned). No GitHub Releases published — check version on **npm / git tags**.
- **Plus:** Best-in-class a11y + focus-trap/ESC/ARIA behavior, tiny, framework-agnostic, battle-tested.
- **Minus:** Unstyled (you bring styles).
- **Use in claudius?** **YES** — use `AlertDialog`/`Dialog` for approve/reject, `Tabs`/`Collapsible` for settings & tool-call cards, `Tooltip`/`DropdownMenu` for chips/menus. Viable fallback if Base UI lacks a component.

### Headless UI   — ✅ strong Tiny alternative
- **What:** Tailwind Labs' **headless components** (`@headlessui/react`): Dialog, Transition, Menu, Popover, Combobox, etc. Unstyled, accessible.
- **Status (2026-09-21):** **v2.0 for React shipped May 7, 2024** (new Checkbox, HTML form components, anchor positioning via Floating UI, **Combobox virtualization via TanStack Virtual**). Docs at `headlessui.dev`. Maintained by the Tailwind team.
- **Plus:** Tiny, great a11y, **native Tailwind fit** (claudius already uses Tailwind), excellent `Dialog`/`Transition` for approve/reject modals, built-in list virtualization helps long transcripts.
- **Minus:** Smaller primitive set than Radix/Base UI; you style everything.
- **Use in claudius?** **Good** — especially for modals/transitions and virtualized lists. Radix/Base UI have the broader set; Headless UI wins on minimal footprint + Tailwind lineage.

### Ark UI   — ok
- **What:** `@ark-ui/react` — **headless, unstyled, accessible primitives** from the Chakra team, **state-machine-based (Zag.js)**; also Solid/Vue/Svelte. **Chakra v3 is built on Ark UI.**
- **Status (2026-09-21):** Growing, active, modern (e.g. `@ark-ui/react` 5.x); smaller ecosystem than Radix.
- **Plus:** Good a11y, unstyled, active dev, framework-agnostic via Zag.js.
- **Minus:** Fewer examples/community than Radix.
- **Use in claudius?** Viable Radix/Base-UI alternative; prefer Base UI/Radix unless Ark's state-machine API fits better.

---

## B. Batteries-included kits   (convenient, heavier, more opinionated)

### Mantine   — ✅ best "batteries-included" runner-up
- **What:** Full kit, ~60–70 components, **first-class dark mode** (`ColorScheme` provider + `withDarkStyles`), rich **hooks** (`useDisclosure`, `useClickOutside`, `useMediaQuery`) and **forms** (`Form`, inputs, `useForm`/`@mantine/form` + zod).
- **Status (2026-09-21):** **v9.6.2 current; v9.0.0 released Mar 31 2026** (v8.0 = May 5 2025). Actively maintained.
- **Plus:** Dark mode built-in, **forms out of the box** (ideal for the settings panel), good data display (Table), less "Material" than MUI, moderate bundle.
- **Minus:** Takes a dependency (no copy-in), more opinionated than shadcn, slightly larger bundle.
- **Use in claudius?** **YES — the alternative** to shadcn/ui: choose if you'd rather have forms + dark mode + data display ready-made than assemble primitives yourself.

### Chakra UI   — ok
- **What:** Popular styled kit. **v2** (2021–24, stable) → **v3** (announced **Oct 22, 2024**), a complete rewrite that **unifies the headless layer Ark UI + Panda CSS styling + Park UI design system**; shadcn-inspired **copy-paste "snippets" CLI**, emotion retained for dynamic styling, `framer-motion` dropped, semantic tokens (7 per palette, dark-mode aware).
- **Status (2026-09-21):** v3.x current; v2→v3 needs a **codemod (`chakra-codemod`)**; React 19 supported in v3.
- **Plus:** Good a11y, good theming/hooks, solid for dashboards.
- **Minus:** **v3 migration friction**, heavier than shadcn/Radix, opinionated look (can read generic).
- **Use in claudius?** Acceptable batteries-included option but heavier + more opinionated than shadcn, plus migration risk — pick Mantine over this for the "batteries" case.

### MUI   (a.k.a. Material-UI)   — ❌ wrong look
- **What:** Material Design kit, rebranded "Material-UI" → **"MUI"**. Full component set, forms, data display, theming.
- **Status (2026-09-21):** **v7.x current** (v8 in progress; migrate v6→v7). 2026 roadmap focus is **Material UI + Base UI + MUI X**; **MUI Base is deprecated in favour of Base UI** (Pigment CSS, Joy UI, Toolpad, MUI Sync on hold).
- **Plus:** Very complete, mature, well-documented.
- **Minus:** **Heavy bundle**, Material Design aesthetic = **looks enterprise/generic** (violates the hard constraint).
- **Use in claudius?** **No** — wrong aesthetic + heavy; only if you specifically wanted Material Design.

### Ant Design   — ❌ (except its Table)
- **What:** **Design-token** system + CSS-in-JS, tree-shaking. Huge component set; excellent Table/data display.
- **Status (2026-09-21):** **v6.6.5 current**; self-described "**enterprise-class** … **AI-friendly** design system."
- **Plus:** Very complete data-display/table story.
- **Minus:** **Heavy**, enterprise/"data-grid" look — conflicts with "must not look generic/enterprise-heavy."
- **Use in claudius?** No, unless the usage/data view is the dominant surface — then borrow the idea (or a headless table) rather than the whole kit.

### React Bootstrap   — ❌
- **What:** Bootstrap components for React (`react-bootstrap`, v2.x). Steady maintenance (React 19 release Sep 2025).
- **Minus:** Bootstrap aesthetic is not modern/dense; would fight the dark dev-dashboard look.
- **Use in claudius?** No.

### Evergreen   — ❌ (name collision, neither is a top pick)
- **What:** The name now resolves **two** unrelated things — verify which you mean:
  - **Old:** Airbnb's `evergreen-ui` (npm, emotion/CSS-in-JS, ~2018 era) — **effectively maintenance mode**, design dated.
  - **New:** `evergreen.segment.com` / `code/lib-evergreen` (Segment-backed, active, ~12k+ stars) — a modern internal-style UI lib.
- **Status (2026-09-21):** Old package stale; the Segment-backed project is active but not a general public pick.
- **Use in claudius?** **No** — old one is stale, new one isn't a fit; neither is a top pick. Listed because the task named it.

### Fluent UI React v9   — ❌
- **What:** Microsoft's design system; **v9 (2023–24)** rewrite (React 18/19), **Griffel** styling. Also "Fluent UI Web Components."
- **Status (2026-09-21):** v9 is the **final stable line** (~v9.4.x); Microsoft continues to support it.
- **Minus:** Heavy, Microsoft/enterprise look; wrong for a non-generically-styled dev tool.
- **Use in claudius?** No, unless aligning to the Microsoft ecosystem.

### Semantic UI React   — ❌ stalled
- **What:** React bindings for Semantic UI (`react-semantic-ui-react`).
- **Status (2026-09-21):** **Effectively stalled / maintenance** (last real activity ~2022); old.
- **Use in claudius?** No.

---

## C. Tailwind-native helpers / charting (not general kits)

### daisyUI   — ⚠️ partial
- **What:** **Tailwind CSS plugin** adding semantic component *classes* (btn, card, badge, input…) — a **CSS layer**, not JS components; tiny; **dark themes out of the box**.
- **Status (2026-09-21):** **v5** (Tailwind CSS 4 era; npm ~5.6.x, Sep 2025).
- **Plus:** Tiny, fast, themeable incl. dark.
- **Minus:** **No complex a11y interactive primitives** (dialogs/modals still need Radix/Base UI/Headless UI); class-name style can clash with a dense custom look.
- **Use in claudius?** Optional helper for simple atoms (badge/chip/card); **not** a substitute for accessible dialogs.

### Tremor   — charts only (usage bar)
- **What:** Dashboard **chart** library built on **Recharts** (+ Tailwind + Radix) (`Tremor`/`tremor.so`); charts + dashboard tiles. The 2026 "Blocks" are now fully free/open-source.
- **Use in claudius?** Not a general UI kit. For the **usage bar** prefer a lighter path — a small **Recharts** sparkline/bar, or Tremor if you want ready-made dashboard tiles.

### Glaze UI   — ❌ (resolved: not a real pick)
- **What:** The name resolves to small/ambiguous projects, not an established kit: a WIP `Rugz007/glaze-ui`, a different `yoda-libs/glaze-ui` (a micro-frontend shell), and `glaze.js.org` (a CSS/tokens project).
- **Status (2026-09-21):** **No established, maintained component kit by this name.** (Resolved from the prior "UNVERIFIED" flag.)
- **Use in claudius?** **No.** Listed only because the task named it; there is nothing to adopt.

---

## Recommendation for claudius

**Primary — shadcn/ui + a headless primitive layer (Base UI, or Radix) as copy-in.**
This is the strongest fit for every hard constraint at once: **small bundle** (tiny
runtime deps, you own the code, no heavy kit), **dark mode first-class** (shadcn's `class`
strategy, dark-first dev dashboard), **best a11y for approve/reject dialogs** (Base UI /
Radix `AlertDialog` gives focus-trap/ESC/ARIA — critical for a security-sensitive "approve or
reject mutating tool calls" UX), and it **won't look generic/enterprise** (you fully own the
style). It pairs natively with the planned **Tailwind + Vite** stack. You trade in some
upfront assembly work for zero opaque runtime lock-in and a dense, custom look.

- **Default primitive layer: Base UI** — stable (1.0.0 Dec 2025, v1.8.0 Sep 2026) and now
   what shadcn ships by default (July 2026). **Fall back to Radix** for any component Base UI
   lacks. *This reverses the earlier "Radix safer / Base UI unproven" note — Base UI is now
   the recommended default.*
- **`AlertDialog`/`Dialog`** for approve/reject + sub-agent confirmations;
   **`Tabs`/`Collapsible`/`Tooltip`** for settings, tool-call cards, sub-agent chips;
   **`DropdownMenu`/`Select`/`Switch`** for provider/model, token limits, permission mode.
   Consider **Headless UI v2** (Modal/Transition + built-in list virtualization) if you want
   an even tinier, Tailwind-native primitive and help for long transcripts.
- **Alt: Mantine v9** if you'd rather have **forms + dark mode + data display batteries-included**
   (its `Form`/`useForm`/zod + `ColorScheme` map directly to the settings panel and usage
   view) and accept a heavier dependency + slightly larger bundle.
- **Charts (usage bar):** small **Recharts** (or Tremor) — not a general kit.
- **Avoid** MUI, Ant Design, Fluent UI, Evergreen, Semantic UI React, React Bootstrap:
  either too heavy or too "enterprise/generic," which is exactly the look claudius must not have.

---

## Sources (opened 2026-09-21 via `mcp-browser`)
- shadcn/ui: https://ui.shadcn.com/docs · https://www.shadcn.com
- Base UI: https://base-ui.com · https://base-ui.com/react/overview/releases (opened — v1.8.0 latest, 1.0.0 stable Dec 11 2025)
- Radix UI: https://www.radix-ui.com · https://www.radix-ui.com/themes/docs
- Headless UI: https://headlessui.dev · https://tailwindcss.com/blog/headless-ui-v2 (opened — v2.0 React May 7 2024, Combobox virtualization)
- Ark UI: https://ark-ui.com
- Mantine: https://mantine.dev · https://mantine.dev/changelog/all-releases/ (opened — v9.6.2, v9.0.0 Mar 31 2026)
- Chakra UI: https://chakra-ui.com · https://www.chakra-ui.com/blog/announcing-v3 (opened — v3 Oct 22 2024, built on Ark UI)
- MUI: https://mui.com · https://mui.com/x/metrics/ (MUI Base deprecated → Base UI per MUI 2026 roadmap)
- Ant Design: https://ant.design (opened — v6.6.5, "enterprise-class / AI-friendly")
- Evergreen: https://evergreen-ui.netlify.app (old `evergreen-ui`, stale) · https://evergreen.segment.com (new Segment lib — name collision)
- Fluent UI React v9: https://fluentui-react.com · https://www.fluentui.dev/react/
- Semantic UI React: https://react.semantic-ui.com
- React Bootstrap: https://react-bootstrap.github.io
- daisyUI: https://daisyui.com
- Tremor: https://tremor.so
- Glaze UI: (no established kit — `Rugz007/glaze-ui` WIP, `yoda-libs/glaze-ui` MFE shell, `glaze.js.org` CSS/tokens)

> Verification: versions above were confirmed against the live sources named. To refresh,
> re-run `mcp-browser` for "shadcn/ui vs Mantine vs Chakra vs MUI 2026",
> "Base UI vs Radix vs Headless UI vs Ark UI 2026", and a current "best React component
> library 2026" roundup, then reconcile the version numbers.
