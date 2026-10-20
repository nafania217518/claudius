# Claude Skills / Plugins / MCP — the frontend-dev ecosystem claudius emulates (2026)

> **Verified 2026-09-21 via mcp-browser** (web live; repo paths, names, and statuses
> checked against GitHub/npm/docs snippets). Corrections vs the earlier training-knowledge
> draft are marked **CORRECTION**.

## The claudius angle in one line

claudius **reimplements the Claude Code CLI as a web GUI**, so it must understand the extension
layer it is emulating: **Skills** (a folder = `SKILL.md` + resources, loaded per-task), **Plugins**
(a bundle of commands/agents/hooks/MCP servers installed from a marketplace), and **MCP servers**
(tools exposed to the agent). Two uses: (1) **reuse** frontend skills/plugins/MCPs to build
claudius's own React frontend faster, and (2) **emulate** the model so claudius supports skill-like
extensibility. Skills/plugins are authorable *in-repo* and cheap; MCP is the bigger architectural
decision (a network protocol + tool surface claudius's own agent loop must speak).

---

## (a) CLAUDE SKILLS relevant to frontend

> A **skill** = a directory with `SKILL.md` (YAML frontmatter: `name`, `description`, then
> instructions) + optional bundled scripts/assets. Anthropic's official skills ship as
> `anthropic-skills:*` (loaded in-session, confirmed) from the public repo
> [`anthropics/skills`](https://github.com/anthropics/skills), which now bundles 17–18 example
> skills and carries its own `.claude-plugin/marketplace.json`, i.e. it *is* a plugin. Loaded
> progressively: metadata is always in context, full body loads on demand.

### 1. `anthropic-skills:frontend-design`    ★ most relevant
- **What:** official Anthropic skill for frontend/UI design — building and styling web UI.
- **From:** Anthropic (`anthropic-skills` namespace / `anthropics/skills`). **Status: LIVE** —
  loaded as a skill in this very session, name confirmed.
- **+** Official, current, exactly the "build UI well" capability; small/progressive-load; the
  canonical example of the skill format claudius would copy.
- **−** Design guidance, not a framework; opinionated on aesthetics.
- **Use in claudius?** **✅ Adopt for its own frontend dev + use as the reference skill format.**
  The single best template for "how a skill is written" that claudius should mirror.

### 2. `web-artifacts-builder`
- **What:** skill for producing rich, self-contained interactive **web artifacts** — confirmed:
  "multi-component claude.ai HTML artifacts using modern frontend web technologies (React,
  Tailwind CSS, shadcn/ui)".
- **From:** `anthropics/skills`. **Status: LIVE**, ~109K installs in directories (very popular).
- **+** Directly matches a "live rendered output" feature; great model for an "artifact" UI.
- **−** Targets HTML artifacts, not a real React app; sandboxed, not full framework.
- **Use in claudius?** **✅ Study.** Mirrors the "agent emits UI you render live" panel; useful
  pattern if claudius shows generated previews/artifacts.

### 3. `webapp-testing`
- **What:** skill for end-to-end testing web apps (Playwright-based: drive the browser, assert,
  screenshot).
- **From:** `anthropics/skills` (the repo README explicitly lists "testing web apps" among its
  skills). **Status: LIVE.**
- **+** Turns "does the frontend work?" into a skill the agent can run; pairs with Playwright MCP.
- **−** Assumes Playwright tooling; test-writing guidance is generic.
- **Use in claudius?** **✅ Adopt for its own frontend dev.** Pairs with item 13 (Playwright MCP).

### 4. `skill-creator`    ★ extensibility meta-skill
- **What:** a skill *for authoring new skills* — confirmed: "Create, test, and iteratively
  improve AI agent skills with structured evaluation and benchmarking".
- **From:** `anthropics/skills`. **Status: LIVE.**
- **+** The bootstrap for a whole extensibility system; shows Anthropic's own skill-authoring loop.
- **−** Only useful if claudius supports user-authored skills.
- **Use in claudius?** **✅ Emulate as the extensibility seed.** If claudius supports skills,
  this is the "author your first skill" entry point.

### 5. `mcp-builder`
- **What:** skill for building **MCP servers** — the `anthropics/skills` README explicitly lists
  "MCP server generation" among its skills.
- **From:** `anthropics/skills`. **Status: LIVE.**
- **+** Bridges skills↔MCP; the on-ramp to exposing new tools to the agent.
- **−** MCP is a bigger commitment than a skill (protocol + transport).
- **Use in claudius?** **✅ Emulate / adopt.** The natural pair to `skill-creator`: "skills add
  instructions, MCP adds tools" — claudius's extensibility story needs both.

### 6. Vercel official skills: `vercel-labs/agent-skills`    ★ new, strong pick
- **What:** Vercel's official skill collection — **110+ skills across ~19–22 repos**, incl.
  `vercel-react-best-practices` (React/Next.js performance rules with before/after examples,
  "codified from what the Vercel team follows internally"), View Transition animations, and more.
- **From:** [`vercel-labs/agent-skills`](https://github.com/vercel-labs/agent-skills) +
  [vercel.com/docs/agent-resources/skills](https://vercel.com/docs/agent-resources/skills).
  **Status: LIVE**, updated 2026; installable via community skills CLIs.
- **+** Real-world, vendor-blessed React knowledge; shows skills can be *packaged at scale*
  (100+ per org) and distributed outside Anthropic's repo.
- **−** React/Next.js-specific; some skills are opinionated to Vercel's stack.
- **Use in claudius?** **✅ Adopt `vercel-react-best-practices` for its own React dev; study the
  bulk-packaging as a marketplace-scale model.** **CORRECTION** vs draft: this replaces the
  unverified "community TS-React toolkit" as the concrete, confirmed example.

### 7. Document skills bundle (`pdf` / `docx` / `pptx` / `xlsx`)
- **What:** official skills that generate/parse Office + PDF docs. **Status: LIVE** —
  `anthropic-skills:pdf/docx/pptx/xlsx` are loaded in this session.
- **+** Real, official, mature; show the "skill = bundled scripts + formats" pattern.
- **−** Not frontend; tangential (only relevant for exporting reports/PR docs).
- **Use in claudius?** **◐ Optional.** Skip for UI dev; worth knowing the multi-file resource
  pattern (scripts + assets) they demonstrate.

---

## (b) CLAUDE PLUGINS / plugin packs for frontend

> A **plugin** bundles slash-commands, sub-agents, hooks, and MCP servers, installed from a
> **marketplace** (Claude Code docs: *discover-plugins*, *plugins-reference*, *Plugin
> marketplaces* — now under [`code.claude.com/docs`](https://code.claude.com/docs/en/discover-plugins)).
> Plugins can ship skills, MCP connectors, subagents, slash commands, or hooks *in one step*; a
> community registry (claude-plugins.dev) indexes public plugins. **CORRECTION**: the canonical
> docs URL moved to `code.claude.com/docs/en/*` (previously docs.claude.com).

### 8. Playwright plugin (browser testing)    ★
- **What:** bundles the Playwright MCP + E2E test commands/agents into one install.
- **From:** Playwright ecosystem (playwright.dev ships official MCP docs + AI test stack).
  **Status: LIVE.**
- **+** One install = "write + run + debug browser tests"; the flagship frontend plugin.
- **−** Assumes Node/Playwright runtime in the dev env.
- **Use in claudius?** **✅ Adopt for its own frontend dev.** Best single frontend plugin.

### 9. GitHub plugin (PR / review / CI)
- **What:** GitHub MCP + PR/review/CI slash-commands (open PR, review, check status).
- **From:** official + community; the official MCP is `github/github-mcp-server` (see #20).
  **Status: LIVE.**
- **+** Closes the "agent edits → opens PR → reads review" loop; useful for claudius's own workflow.
- **−** Needs a GitHub token / remote.
- **Use in claudius?** **✅ Adopt for its own dev.** Standard dev-loop plugin.

### 10. Figma plugin (design → code)
- **What:** wraps Figma's official MCP — pull design tokens/components/spec into the agent;
  Figma also ships **skills** for its MCP (per Figma help docs, clients that support skills get
  agent-level instructions for Figma work).
- **From:** Figma official. **Status: LIVE** (announced 2025-06-04; renamed from "Dev Mode MCP"
  to **"Figma MCP server"**).
- **+** Design-system-in-the-loop; strong for "implement this Figma screen".
- **−** Requires a Figma account + plan/seat requirements (per setup guides); remote vs desktop
  deployment choice.
- **Use in claudius?** **◐ Adopt if claudius builds UI from Figma specs; else skip.**

### 11. Context7 plugin (up-to-date docs)
- **What:** injects current library/API docs into context so the agent doesn't code from stale
  memory. The Upstash **Claude Code plugin bundles the remote MCP (over OAuth) + skills in one
  step**.
- **From:** Upstash (`upstash/context7`; plugin in Upstash docs). **Status: LIVE.**
- **+** Fixes the "agent hallucinates old API" problem; high ROI for any dev.
- **−** Network dependency; extra context tokens.
- **Use in claudius?** **✅ Adopt for its own dev.** Cheap, high-value.

### 12. Vercel / shadcn / stack-toolkit packs (community + official)
- **What:** packs that package a stack's tooling and knowledge for a framework — now with
  **official** examples: Vercel's 110+ skill pack (see #7) and shadcn's official skill + MCP
  (see #21). Older "community frontend toolkit" concept stands, but official packs have won.
- **From:** `vercel-labs/agent-skills`, shadcn, and community marketplaces (claude-plugins.dev).
  **Status: LIVE.**
- **+** Ready-made "wire up my project's commands" packs; good model of a *project-local* plugin.
- **−** Heterogeneous across community entries; vendor-specific; maintenance varies.
- **Use in claudius?** **◐ Study as the extensibility model** (project-scoped command/skill bundle);
  adopt a specific one only if it cleanly matches claudius's toolchain.

### 13. Sentry plugin (error monitoring)    (optional)
- **What:** wraps the Sentry MCP (`getsentry/sentry-mcp`, plus stdio variant) + agent plugin
  that "one command teaches Claude Code how to install and configure Sentry".
- **From:** Sentry official. **Status: LIVE** (2026 docs; Seer root-cause analysis in agent).
- **+** Post-deploy error loop for a web app.
- **−** Needs a Sentry project; tangential pre-launch.
- **Use in claudius?** **◐ Later, if claudius ships monitoring.** Skip early.

---

## (c) MCP SERVERS useful for frontend dev

> **MCP** = the Model Context Protocol: a server exposing tools a client (here, the agent) can call.
> Two transports: **stdio** (local, subprocess) and **HTTP** (remote/hosted). This is the layer
> claudius's *own* agent loop must eventually speak. All repo paths below were
> **live-verified 2026-09-21**.

### 14. Playwright MCP (`microsoft/playwright-mcp`)    ★
- **What:** official browser-automation MCP — navigate, click, fill, assert, screenshot a real
  browser; structured accessibility snapshots instead of screenshots.
- **From:** Microsoft ([`microsoft/playwright-mcp`](https://github.com/microsoft/playwright-mcp),
  docs at playwright.dev). **Status: LIVE, actively maintained through 2026** (Docker image +
  npm package). **Note:** CVE-2025-9611 (CSRF flaw in an earlier version) — use a patched
  release.
- **+** The default "agent drives a browser" tool; testing + QA + screenshot; stable.
- **−** Heavier than a headless HTTP client; needs a browser install.
- **Use in claudius?** **✅ Adopt + likely the primary MCP claudius exposes.** Best test of the
  claudius↔MCP bridge; a coding agent's most useful frontend tool.

### 15. Puppeteer MCP (`xdevplatform/puppeteer-mcp`)
- **What:** earlier Puppeteer-based browser MCP (pre-Playwright).
- **From:** `xdevplatform/puppeteer-mcp`. **Status: ARCHIVED — dead since May 2025, superseded by
  Playwright MCP (live-confirmed).** A fresh rewrite `greenfinity/puppeteer-mcp` (Dec 2025)
  exists for Puppeteer-loyal teams.
- **+** Familiar if a team already uses Puppeteer.
- **−** Superseded by Playwright MCP for most cases; smaller momentum.
- **Use in claudius?** **❌ Skip in favor of Playwright MCP** unless a Puppeteer lock-in exists
  (then `greenfinity/puppeteer-mcp`, not the archived original).

### 16. Chrome DevTools MCP (`ChromeDevTools/chrome-devtools-mcp`)
- **What:** exposes Chrome DevTools Protocol — performance traces, network, memory, console,
  profiling, **Lighthouse audits** — to the agent.
- **From:** Google/Chrome DevTools team, official ([`ChromeDevTools/chrome-devtools-mcp`](https://github.com/ChromeDevTools/chrome-devtools-mcp),
  docs at developer.chrome.com/docs/devtools/agents). **Status: LIVE through 2026** (npm
  `chrome-devtools-mcp`; works with Claude, Cursor, Copilot).
- **+** "why is it slow / what's this error?" debugging is a real agent superpower; official
  backing (unlike the draft's "community" guess).
- **−** Chrome/CDP-specific; more niche than Playwright.
- **Use in claudius?** **◐ Adopt if claudius adds a perf/debug story; otherwise later.**
  **CORRECTION:** this is Google-official, not community.

### 17. Figma MCP server (formerly "Dev Mode MCP")
- **What:** pull Figma designs/tokens/components/spec into the agent (remote + desktop Dev Mode);
  can also write native Figma content back.
- **From:** Figma official ([developers.figma.com/docs/figma-mcp-server](https://developers.figma.com/docs/figma-mcp-server/)).
  **Status: LIVE** since 2025-06-04; renamed "Figma MCP server" in 2026.
- **+** Design-system-in-the-loop; the canonical "design → code" MCP.
- **−** Requires Figma + plan/seat requirements; tightens to Figma as the design source.
- **Use in claudius?** **◐ Adopt if building UI from Figma; else track.**

### 18. Context7 MCP (`upstash/context7`)
- **What:** fetches current, version-pinned library docs and injects them into context.
- **From:** Upstash ([`upstash/context7`](https://github.com/upstash/context7); npm
  `@upstash/context7-mcp`, stdio or HTTP transport). **Status: LIVE.**
- **+** Cures stale-memory coding; works for any framework; remote-hosted.
- **−** Network + token cost; another context sink.
- **Use in claudius?** **✅ Adopt for its own dev.** Cheap, broad, high ROI.

### 19. Sequential-thinking MCP (`modelcontextprotocol/servers`)
- **What:** reference MCP that gives the model a structured "think step by step, revise" tool
  (npm `@modelcontextprotocol/server-sequential-thinking`).
- **From:** official MCP reference servers. **Status: STILL PUBLISHED/MAINTAINED (Jul 2026)** —
  not deprecated, just lower marginal value as models reason natively.
- **+** Cheap, dependency-free, improves planning on hard tasks; good demo of a *pure-logic* MCP.
- **−** Effect is soft/variable; declining marginal value.
- **Use in claudius?** **◐ Optional demo.** Fine as an example "no-external-tool" MCP; low priority.

### 20. GitHub MCP (`github/github-mcp-server`)
- **What:** official GitHub MCP — repos, issues, PRs, CI (remote GitHub-hosted + self-hosted +
  Docker + GitHub Enterprise).
- **From:** GitHub official ([`github/github-mcp-server`](https://github.com/github/github-mcp-server)).
  **Status: LIVE.** **CORRECTION:** it **replaced the community
  `modelcontextprotocol/servers/github` reference in late 2025** — the canonical GitHub MCP is now
  this repo; don't wire the old reference.
- **+** Closes the dev/PR/review loop; official + well-supported.
- **−** Auth + scope management; large tool surface.
- **Use in claudius?** **✅ Adopt for its own dev** (PR/review loop).

### 21. shadcn MCP (official) + component registries
- **What:** **shadcn now ships an official MCP server** — browse, search, and install components
  from any shadcn-compatible registry by natural language (plus shadcn's own skill for project
  context). Community variants exist (e.g. `Rachidhssin/shadcn-registry-mcp`) with dependency
  resolution.
- **From:** shadcn official ([ui.shadcn.com/docs/mcp](https://ui.shadcn.com/docs/mcp)) +
  community. **Status: LIVE 2026.** **CORRECTION** vs draft: no longer "community concept,
  verify" — the official server exists.
- **+** "add a component / pull the design system" becomes an agent action; strong for a
  shadcn/Tailwind stack (which claudius likely uses).
- **−** Community variants heterogeneous; official one assumes a shadcn-registry project.
- **Use in claudius?** **✅ Adopt if claudius uses shadcn; emulate the "registry as tool" idea
  regardless.**

### 22. Browserbase MCP (`browserbase/mcp-server-browserbase`)
- **What:** cloud-hosted headless browsers behind an MCP — no local browser, sessions, scraping;
  now built on **Stagehand** (LLM-friendly browser API).
- **From:** Browserbase ([`browserbase/mcp-server-browserbase`](https://github.com/browserbase/mcp-server-browserbase),
  docs at browserbase.com/mcp). **Status: LIVE.**
- **+** No local browser install; scales; good for "many browsers / CI."
- **−** Third-party SaaS + cost; data leaves your machine.
- **Use in claudius?** **◐ Later, if claudius needs server-side browser runs** (CI, batch). Prefer
  local Playwright MCP for interactive dev.

### 23. Reference MCPs: Filesystem + Fetch + Git (`modelcontextprotocol/servers`)
- **What:** the official reference servers — filesystem access, `fetch` web content, git ops
  (fetched/git packages released as late as Aug 2026).
- **From:** official MCP reference ([`modelcontextprotocol/servers`](https://github.com/modelcontextprotocol/servers)).
  **Status: LIVE as reference, but the github reference is superseded by `github/github-mcp-server`
  (see #20).**
- **+** Canonical, well-tested; the "hello world" of MCP; good bridge to learn the protocol.
- **−** A coding agent already has native file/git tools, so most are redundant for claudius.
- **Use in claudius?** **◐ Reference/learning only.** Use to learn the protocol; don't duplicate
  tools claudius already has natively (file read/edit, git).

---

## Recommendation for claudius

**Two tracks, different urgency.**

**Track 1 — adopt for building claudius's *own* frontend (do now, cheap, high ROI):**
1. **Playwright MCP (#14) + webapp-testing skill (#3)** — the single most valuable frontend
   pairing for a coding agent: "write, run, and verify UI in a real browser." This is also the
   first thing to use to *prove out* claudius's own MCP bridge. Keep the patched release
   (CVE-2025-9611).
2. **Context7 MCP (#18) / its plugin (#11)** — cures stale-API coding; near-zero cost; the
   Upstash plugin bundles MCP + skills in one install, a good demo of plugin packaging.
3. **`anthropic-skills:frontend-design` (#1) + `vercel-react-best-practices` (#6)** — the former
   as both a dev aid *and* the canonical template for claudius's own skill format; the latter for
   concrete React quality.
4. **GitHub MCP/plugin (#9, #20)** — close the edit→PR→review loop for claudius's own workflow
   (use `github/github-mcp-server`, not the old reference server).
5. **shadcn official MCP (#21)** — if claudius's stack includes shadcn (likely, given the
   web-artifacts/webapp patterns); else track it as the "registry as a tool" model.
6. Skip Puppeteer MCP (#15, archived); Filesystem/Fetch reference (#23) are redundant with
   native tools.

**Track 2 — emulate as claudius's own extensibility (architectural, higher priority long-term):**
- **Skills are the cheap first extensibility layer.** claudius should support a `SKILL.md`-style
  folder (frontmatter `name`/`description` + instructions/resources) with **progressive loading**
  (metadata always in context, body on demand). Mirror `frontend-design` (#1); ship
  `skill-creator`-style authoring (#4) as the entry point. Note that Anthropic's own skills repo
  now *is* a plugin (`anthropics/skills` ships `marketplace.json`), so the skill→plugin line is
  already blurred — design for both from the start.
- **MCP is the second layer and the real commitment.** A coding agent that can't speak MCP can't
  expose Playwright/Context7/GitHub to its users. Prioritize the **stdio transport first** (local
  subprocess, simplest) then HTTP/remote — Context7's stdio-or-HTTP dual transport is a good
  reference. Use the reference servers (#23) only to *learn* the protocol, not as tools.
- **Plugins = skills+MCP+commands+hooks+agents, installable from a marketplace** (#8–#13). This is
  the packaging/distribution model claudius should eventually offer; start by *consuming* official
  plugins (Vercel's 110-skill pack shows scale), later *hosting* a marketplace. `mcp-builder` (#5)
  + `skill-creator` (#4) are the two seeds of that system.
- **Order:** (1) skill-loading with progressive metadata → (2) stdio MCP client → (3) plugin
  bundle + marketplace. Don't build the marketplace before the single-skill + single-MCP paths
  work.

**One-line verdict:** adopt **Playwright MCP + Context7 + frontend-design +
vercel-react-best-practices + GitHub MCP** for claudius's own frontend dev now; and architect
claudius to **load `SKILL.md` skills (progressive) → speak stdio MCP → bundle into plugins/
marketplace** in that order, with `skill-creator` and `mcp-builder` as the extensibility seeds.

---

## Sources (live-verified 2026-09-21 via mcp-browser snippets)

- [anthropics/skills](https://github.com/anthropics/skills) — official example skills (17–18,
  incl. web-artifacts-builder, webapp-testing, skill-creator, mcp-builder, doc skills); ships
  `.claude-plugin/marketplace.json`
- [vercel-labs/agent-skills](https://github.com/vercel-labs/agent-skills) +
  [Vercel agent skills docs](https://vercel.com/docs/agent-resources/skills) — 110+ official
  Vercel skills incl. `vercel-react-best-practices`
- [Claude Code plugins / marketplace docs](https://code.claude.com/docs/en/discover-plugins),
  [plugins reference](https://code.claude.com/docs/en/plugins-reference) — plugin bundles
  (skills/agents/commands/hooks/MCP), marketplace install (**CORRECTION**: moved from
  docs.claude.com to code.claude.com)
- [claude-plugins.dev](https://claude-plugins.dev/) — community plugin/skill registry
- [Playwright MCP](https://github.com/microsoft/playwright-mcp) +
  [docs](https://playwright.dev/docs/getting-started-mcp) — official, live; note CVE-2025-9611
- [Puppeteer MCP](https://github.com/xdevplatform/puppeteer-mcp) — **archived since May 2025**;
  successor [`greenfinity/puppeteer-mcp`](https://github.com/greenfinity/puppeteer-mcp)
- [Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp) +
  [Chrome docs](https://developer.chrome.com/docs/devtools/agents) — Google-official, live
- [Figma MCP server](https://developers.figma.com/docs/figma-mcp-server/) +
  [announcement](https://www.figma.com/blog/introducing-figma-mcp-server/) — renamed from Dev
  Mode MCP, 2025-06
- [Context7 MCP](https://github.com/upstash/context7) (`@upstash/context7-mcp`) — live, stdio/HTTP
- [MCP reference servers](https://github.com/modelcontextprotocol/servers) — sequential-thinking
  still published; fetch/git maintained; **github reference superseded by github-mcp-server**
- [GitHub MCP](https://github.com/github/github-mcp-server) — official, remote/self-hosted/
  Enterprise, live
- [shadcn MCP](https://ui.shadcn.com/docs/mcp) +
  [Rachidhssin/shadcn-registry-mcp](https://github.com/Rachidhssin/shadcn-registry-mcp) —
  official shadcn server now exists
- [Browserbase MCP](https://github.com/browserbase/mcp-server-browserbase) — live, Stagehand-based
- [Sentry MCP](https://github.com/getsentry/sentry-mcp) (+ sentry-mcp-stdio) — live, has agent
  plugin
- [Model Context Protocol spec](https://modelcontextprotocol.io) — protocol + transports
  (stdio/HTTP)

**Corrections vs the training-knowledge draft:** (1) Figma "Dev Mode MCP" → "Figma MCP server";
(2) Chrome DevTools MCP is Google-official, not community; (3) shadcn MCP is now official;
(4) `github/github-mcp-server` superseded the MCP reference github server (late 2025);
(5) xdevplatform puppeteer-mcp confirmed archived May 2025 (successor exists);
(6) plugin docs moved to `code.claude.com/docs`; (7) added Vercel's official 110+ skill pack as a
confirmed entry; (8) sequential-thinking reference is *still maintained*, just lower-value;
(9) CVE-2025-9611 on Playwright MCP — pin a patched version.
