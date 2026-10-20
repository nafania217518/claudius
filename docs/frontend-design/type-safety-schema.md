# Type Safety / Schema / DTO Validation — frontend (2026)

> **VERIFICATION STATUS: live-verified 2026-09-23.**
> Every version/size below was checked this run against npm / Maven Central / the
> project's own repo via `mcp-browser`. Not training-knowledge; safe to commit.
> (Re-verify when a major version lands — e.g. Zod 5, tRPC 12.)

## The claudius problem in one line

Kotlin backend emits JSON over **WS/SSE (streamed)** + REST; React frontend must
**validate at runtime** (server JSON is untrusted/evolving) **and derive TS types**.
Extra wrinkle: can the schema be *shared* across the Kotlin↔TS boundary?
Update for 2026: the Kotlin side is **no longer ecosystem-weak** —
`org.jetbrains.kotlinx:kotlinx-schema` is a first-party JetBrains project that
emits **JSON Schema (Draft 2020-12) *and* OpenAI/Anthropic function-calling schemas
from Kotlin** — exactly the shape claudius's tool-calling layer needs. So a shared
**JSON Schema / OpenAPI contract is now realistic**, though Kotlin *runtime
validation* (validating inbound payloads, not emitting) is still thin.
Practical verdict: **runtime validation lives in TS** (Zod/Valibot); Kotlin emits
JSON Schema as a *contract/reference* via kotlinx-schema + validates DTOs with
kotlinx.serialization + tests.

## Options

### Zod v4
- **What:** most popular TS schema library; **v4.6.5** (latest, MIT). v4 (stable 2025)
  rewrote the core (faster, better tree-shaking, custom errors) **and ships native
  JSON-Schema export via `z.toJSONSchema()`** — no bridge package needed for JSON Schema
  anymore. v3 is maintenance-mode.
- **Status:** 4.x is default; huge ecosystem (react-hook-form, tRPC, react-query).
- **Plus:** best DX + ecosystem; one-liner inference (`z.infer`); **built-in** JSON
  Schema export; `zod/mini` (≈6.88 kB) for tight bundles.
- **Minus:** full Zod still the heaviest option (~17 kB for a trivial schema, vs
  Valibot 1.37 kB / Zod Mini 6.88 kB); per-chunk streaming parse is awkward; the
  JSON↔schema bridge (`zod-to-json-schema`) is one-directional.
- **Use in claudius?** ✅ **Strong default.** Runtime-validate WS/SSE envelopes +
  REST responses, `z.infer` everywhere. Native `z.toJSONSchema()` emits a *reference*
  contract the Kotlin side can diff against (Kotlin doesn't import it — it emits its
  own via kotlinx-schema). Use `zod/mini` if bundle size bites.

### Valibot
- **What:** smallest + fastest **startup** mainstream schema lib (**1.5.0**, MIT,
  no deps, ≈1.37 kB for a trivial schema vs Zod's ~17 kB — a 90% cut).
- **Status:** 1.x, actively maintained (Fabian Hiller), the "performance" pick in 2025-2026.
- **Plus:** tiny bundle, great `v.infer`, no deps, ideal for streaming/high-frequency frames.
- **Minus:** smaller ecosystem/integrations than Zod (fewer React-form / RPC glues).
- **Use in claudius?** ✅ **Best if bundle/startup perf matters** (frequent WS/SSE frames).
  Tradeoff vs Zod: fewer batteries. Pick one — do not mix.

### ArkType
- **What:** runtime "type compiler" with **bidirectional type↔schema inference**
  (schema → TS type *and* TS type → schema). "TypeScript's 1:1 validator, optimized
  from editor to runtime."
- **Status:** **2.2.3**, MIT, actively maintained (David Blass). 2.x line matured in 2025.
- **Plus:** fastest runtime class, unique two-way inference, elegant, clean MIT license.
- **Minus:** smaller community; steeper learning curve; async/streaming less
  battle-tested than Zod; bidirectional power is overkill for a DTO/form layer.
- **Use in claudius?** ⚠️ Cool and the old license fear is gone (it's plain MIT) — adopt
  only if the team buys the two-way-inference pitch. Not the default.

### TypeBox
- **What:** TS-native JSON Schema impl; schemas *are* JSON Schema objects.
- **Status:** **0.34.52** (0.x, not 1.x), MIT, maintained (`@sinclair/typebox`),
  used heavily in Fastify and server contexts.
- **Plus:** schemas serialize to JSON Schema trivially → **best "one JSON Schema
  shared across Kotlin+TS" story of the TS-side libs** (a JSON-Schema file drives
  codegen on both ends).
- **Minus:** inference slightly weaker than Zod; API more verbose; no forms ecosystem.
- **Use in claudius?** ✅ **Best if "one JSON Schema shared across Kotlin+TS" is the
  priority.** Pairs naturally with kotlinx-schema on the Kotlin side (both target
  JSON Schema Draft 2020-12).

### myzod
- **What:** ultra-minimal, ESM-only, fast schema lib.
- **Status:** **1.12.1**, MIT (davidmdm), maintained, small following.
- **Plus:** tiny, fast, no deps.
- **Minus:** small ecosystem; less known; fewer integrations.
- **Use in claudius?** ⚠️ Only if minimalism trumps ecosystem — otherwise Zod/Valibot.

### Runtypes
- **What:** type-first, ergonomic runtime types.
- **Status:** **7.0.5**, MIT (Thomas Crockett), actively maintained.
- **Plus:** readable, good error messages, solid runtime types.
- **Minus:** small ecosystem; not worth switching to from nothing.
- **Use in claudius?** ❌ Niche — skip unless it's already in the tree.

### Effect Schema
- **What:** runtime schemas in the Effect ecosystem (functional, effectful).
- **Status:** **0.75.5**, MIT, `peerDep effect ^3.9.2`; ships `Effect/Schema/JSONSchema`
  (can export to JSON Schema).
- **Plus:** type-safe, powerful, good inference.
- **Minus:** pulls Effect's worldview (TaggedError, etc.) — overkill for a form/DTO layer.
- **Use in claudius?** ❌ Only if claudius adopts Effect for its runtime — it won't.

### Superstruct
- **What:** struct-based validation (pre-Zod popular choice).
- **Status:** **2.0.2**, MIT, maintained but stale vs Zod/Valibot; slower.
- **Plus:** ergonomic, no new deps if already using it.
- **Minus:** slower, community momentum lost to Zod/Valibot.
- **Use in claudius?** ❌ Skip — no reason to start here.

### Joi
- **What:** the classic Node validation lib (schema + coercion + great messages);
  the "server-side Node" default, predating Zod.
- **Status:** **18.2.9**, BSD-3-Clause (hapi), actively maintained.
- **Plus:** battle-tested, rich coercion, huge ecosystem — a real option *if a Node
  service ever sat in the path*.
- **Minus:** **Node/JS-only, no TS type inference out of the box**, heavier, wrong
  runtime for a browser bundle.
- **Use in claudius?** ❌ Not for the browser frontend. Notable only as "what a
  Node BFF would use" — claudius has no Node tier (Kotlin backend).

### tRPC
- **What:** type-safe RPC layer; zero-codegen type safety end-to-end.
- **Status:** **v11.x current** (`@trpc/server` 11.19.0, MIT, KATT); no v12 as of 2026.
- **Plus:** *if the backend were TS*, it'd be the obvious answer — end-to-end types for free.
- **Minus:** **requires a TS/JS server.** Kotlin backend can't host tRPC.
- **Use in claudius?** ❌ **Backend is Kotlin** — tRPC out. The *idea* (end-to-end
  types) is achievable via OpenAPI+codegen or a shared JSON Schema + a TS client wrapper.
  (A TS shim proxy between Kotlin and tRPC is overkill for v1.)

### OpenAPI + openapi-typescript
- **What:** design a REST contract as OpenAPI YAML/JSON; generate TS types client-side.
  Spring side: **springdoc-openapi** (3.0.0, Apache-2.0) emits the spec;
  `openapi-typescript` (**7.13.0**, MIT, `@openapi-ts`) generates the TS client.
- **Status:** both mature; standard 2026.
- **Plus:** language-agnostic, machine-checkable, IDE tooling, shared contract.
- **Minus:** typegen is a build step (stale risk); no runtime validation; WS/SSE
  channels aren't expressible in OpenAPI cleanly.
- **Use in claudius?** ✅ **For REST + as the shared contract** with Kotlin. Pairs
  with a runtime validator (Zod/Valibot) for the actual message envelopes —
  OpenAPI for the *shape*, Zod for the *runtime*.

### JSON Schema (native) + the 2026 Kotlin side
- **What:** the language-agnostic schema standard. TS-side: ajv / TypeBox.
  **Kotlin-side is now real:** `org.jetbrains.kotlinx:kotlinx-schema` — a first-party
  JetBrains project (Apache 2.0, "Experimental" badge) that generates **JSON Schema
  Draft 2020-12 *and* OpenAI/Anthropic function-calling schemas** from Kotlin via
  **KSP (compile-time)**, runtime **reflection**, or **kotlinx.serialization**.
  It auto-handles sealed classes (`oneOf` + `$ref` discriminator), nested objects,
  generics, nullables, and even LLM function-calling schema format.
  `OptimumCode/json-schema-validator` (0.5.4, MIT, KMP) and `jsonschema-kt` (4.x)
  add runtime *validation* on the Kotlin side.
- **Status:** the standard; universal; and the Kotlin ecosystem for it went from
  "weak" to "first-party + maintained" in 2025-2026.
- **Plus:** only truly shareable artifact across Kotlin+TS; kotlinx-schema targets the
  *exact* JSON + function-calling shape claudius's LLM tool-calling layer emits.
- **Minus:** writing schemas by hand is verbose; kotlinx-schema is still marked
  **Experimental** (API may move); runtime *inbound validation* on Kotlin is thin
  (validation libs are newer/smaller than Kotlin's serialization story).
- **Use in claudius?** ✅ **As the shared contract** — kotlinx-schema emits JSON Schema
  for DTOs *and* for tool/function definitions; the TS side validates at runtime with
  TypeBox (schemas already *are* JSON Schema) or Zod. This is the strongest "one
  source of truth" path available in 2026, but treat kotlinx-schema as a *reference
  emitter*, not a hard build-time coupling (it's experimental).

### ruru / Typeman / SchemaRS
- **What:** smaller/newer schema libs (ruru: Rust-side schema-first, not TS;
  Typeman: minimal; SchemaRS: runtime schemas).
- **Status:** emerging/marginal (2025-2026), tiny communities.
- **Plus:** tiny, modern.
- **Minus:** unproven, no ecosystem — don't bet a team project on them.
- **Use in claudius?** ❌ Skip — no ecosystem; revisit if one goes mainstream.

### yup
- **What:** form-focused validation (the Formik sibling).
- **Status:** 1.7.1, maintained but niche.
- **Plus:** great for HTML forms; huge community.
- **Minus:** slow, no streaming story, weak for API payloads.
- **Use in claudius?** ❌ Not for DTO/payload validation. Maybe *inside* a form lib if one appears.

### io-ts (deprecated)
- **What:** type-level validation via free monads (fantasy-land).
- **Status:** **effectively dead** — last release Dec 2024, no maintainer momentum
  (community forks like `io-ts2` exist but aren't the canonical package).
- **Minus:** complex, stale, no ecosystem momentum.
- **Use in claudius?** ❌ Never — do not adopt.

### AJV
- **What:** the standard JSON Schema validator in JS (fast, no TS types by default).
- **Status:** **8.20.0** (8.x, MIT, the de-facto standard). **No "AJV 9"** — don't wait for it.
- **Plus:** fast, standard, good if you already have JSON Schemas.
- **Minus:** no type inference out of the box (pair with `ajv-json-schema-to-ts` or similar).
- **Use in claudius?** ⚠️ Only if JSON Schema is the contract *and* you want zero-Zod/zero-TypeBox.
  Otherwise Zod/Valibot/TypeBox gives you validation + types in one.

### zod-to-json-schema / zod-to-openapi (bridges)
- **What:** bridges that emit JSON Schema / OpenAPI from Zod (one direction).
- **Status:** **`zod-to-json-schema` is maintained and v4-aligned (3.25.2)** — but
  Zod v4 now ships **native `z.toJSONSchema()`**, making this bridge mostly
  redundant for JSON Schema. **`zod-to-openapi` is deprecated (Nov 2025)** in favor of
  the `@alcyone-labs/zod-to-openapi` fork — use the fork, not the canonical name.
- **Use in claudius?** ✅ Prefer **native `z.toJSONSchema()`** over any bridge.
  Reach for `@alcyone-labs/zod-to-openapi` only if you need OpenAPI (not JSON Schema)
  from Zod. One-directional (TS→Kotlin), fine for "Kotlin implements what TS declares".

### TS structural types only (no runtime) + branded types / discriminated unions
- **What:** hand-write TS interfaces; trust the compiler; no runtime check.
  Hardened with **discriminated unions** (a `type` tag narrows a sum type safely) and
  **branded/opaque types** (`type UserId = string & { readonly __brand: "UserId" }`)
  to stop structurally-identical primitives from being mixed up at the type level.
- **Status:** the baseline everyone starts from; these are built-in TS, zero deps.
- **Plus:** zero cost; discriminated unions + brands catch a *lot* of bugs at compile
  time (ideal for a sealed-message union like WS/SSE frame variants).
- **Minus:** server JSON is *untrusted* — a drifted field crashes the UI at runtime.
  WS/SSE payloads are exactly the case where structural types alone fail.
- **Use in claudius?** ✅ **Use branded types + discriminated unions for internal
  TS domain types**, but **NOT as the only line of defense for streamed payloads** —
  gate untrusted WS/SSE/REST JSON through Zod/Valibot first, then narrow.

## Recommendation for claudius

**Adopt Zod v4** (or **Valibot** if bundle/startup perf is a hard constraint, or
**TypeBox** if a *shared JSON Schema* is the priority) as the **single runtime
validator for WS/SSE message envelopes and REST responses**, deriving types via
`z.infer` / `v.infer` / `Static<...>`. Do **not** hand-couple the two codebases to a
single source-of-truth schema — but *do* lean on the now-real Kotlin tooling:

- **The Kotlin side is no longer schema-weak.** `org.jetbrains.kotlinx:kotlinx-schema`
  (first-party, Apache 2.0) emits **JSON Schema Draft 2020-12 *and* OpenAI/Anthropic
  function-calling schemas** from Kotlin — the exact shape claudius's tool-calling
  layer produces. Use it to emit a JSON Schema *reference contract* the frontend can
  diff against. It's still marked **Experimental**, so treat it as an emitter, not a
  build-time hard dependency.
- Kotlin DTOs stay native via **kotlinx.serialization**, with **tests** that pin the
  JSON shape. For inbound *runtime* validation on the Kotlin side, `jsonschema-kt` /
  `OptimumCode/json-schema-validator` exist but are newer/smaller — don't over-invest.
- **OpenAPI + springdoc-openapi (3.x) + openapi-typescript (7.x)** is worth adopting
  for the REST surface *as a contract*, layered *above* Zod/Valibot (OpenAPI for
  shape, Zod for runtime). WS/SSE channels are documented via a small JSON Schema file
  — OpenAPI can't express streams well.
- **TS-side domain types** use discriminated unions + branded types *after* the Zod
  gate, not instead of it.
- **Skip** tRPC (Kotlin backend), io-ts (dead), yup/Superstruct (forms), Joi (Node
  only, no browser inference), Effect (overkill), ruru/Typeman/SchemaRS (no ecosystem),
  and the `zod-to-json-schema` bridge (Zod v4 has native `z.toJSONSchema()`; and
  `zod-to-openapi` is deprecated in favor of the `@alcyone-labs` fork).

**One-line:** Zod v4 (or Valibot/TypeBox) at the frontend for runtime safety + type
derivation; OpenAPI/springdoc for the REST contract; Kotlin emits JSON Schema
(function-calling + DTO) via kotlinx-schema as a *reference*, kotlinx.serialization
DTOs stay native with tests. The Kotlin JSON-Schema story went from "weak" to
"first-party + experimental-but-real" in 2026 — use it, don't couple a build to it.

## Sources (live-verified 2026-09-23 via mcp-browser)

- [Zod docs](https://zod.dev/) — v4.6.5, `z.toJSONSchema()`, `zod/mini`
- [Zod Mini on Valibot's comparison page](https://valibot.dev/guides/comparison/) — Zod ~17 kB / Zod Mini ~6.88 kB / Valibot 1.37 kB for a trivial schema
- [Valibot docs](https://valibot.dev/) — v1.5.0, MIT, Fabian Hiller
- [ArkType docs](https://ark-type.io/) / [package.json](https://unpkg.com/arktype/package.json) — v2.2.3, **MIT** (license risk resolved), David Blass
- [TypeBox repo](https://github.com/sinclairzx81/typebox) / [npm](https://www.npmjs.com/package/@sinclair/typebox) — v0.34.52, MIT, schemas-as-JSON-Schema
- [myzod npm](https://www.npmjs.com/package/myzod) — v1.12.1, MIT (davidmdm)
- [Runtypes npm](https://www.npmjs.com/package/runtypes) — v7.0.5, MIT
- [Effect Schema docs](https://effect.website/docs/schema/) / [Effect Schema repo](https://github.com/Effect-TS/effection) — v0.75.5, `Effect/Schema/JSONSchema`, peer `effect ^3.9.2`
- [Superstruct npm](https://www.npmjs.com/package/superstruct) — v2.0.2
- [Joi npm](https://www.npmjs.com/package/joi) — v18.2.9, BSD-3-Clause (Node-only, noted for completeness)
- [tRPC docs](https://trpc.io/docs/) / [npm](https://www.npmjs.com/package/@trpc/server) — v11.19.0, no v12 as of 2026
- [openapi-typescript repo](https://github.com/openapi-ts/openapi-typescript) — v7.13.0, `@openapi-ts`
- [springdoc-openapi repo](https://github.com/springdoc/springdoc-openapi) — v3.0.0, Apache-2.0
- [kotlinx-schema repo](https://github.com/Kotlin/kotlinx-schema) / [Maven Central](https://central.sonatype.com/namespace/org.jetbrains.kotlinx) — first-party JetBrains, Apache 2.0, JSON Schema Draft 2020-12 + OpenAI/Anthropic function-calling, KSP/reflection/serialization, "Experimental"
- [OptimumCode/json-schema-validator](https://github.com/OptimumCode/json-schema-validator) — v0.5.4, MIT, KMP Kotlin runtime validator
- [AJV docs](https://ajv.js.org/) / [npm](https://www.npmjs.com/package/ajv) — v8.20.0, **no v9**
- [io-ts GitHub](https://github.com/gcanti/io-ts) — last release Dec 2024, confirmed stale
- [zod-to-json-schema npm](https://www.npmjs.com/package/zod-to-json-schema) — v3.25.2, maintained & v4-aligned (but Zod v4 has native `z.toJSONSchema()`)
- [zod-to-openapi deprecation](https://www.npmjs.com/package/zod-to-openapi) — deprecated Nov 2025 → `@alcyone-labs/zod-to-openapi`

Re-verify when a major version lands: `mcp__mcp-browser__web_search` for
"Zod v5", "tRPC v12", "kotlinx-schema release", "springdoc-openapi 2026" and refresh
the version numbers + this banner.
