# Spring AI Observability — reference notes (v2.0.1)

Terse API notes for `claudius` backend. Source: `docs.spring.io/spring-ai/reference/observability/index.html`.
Spring AI **2.0.1**. This is a reference, not a copy of the docs.

> Built on **Micrometer** (Spring Boot metrics + tracing, actuator). Instrumented
> components: `ChatClient` (+ advisors), `ChatModel`, `EmbeddingModel`, `ImageModel`,
> `VectorStore`. Low-cardinality keys → metrics *and* traces; high-cardinality → **traces
> only**.

## Observations (spans)

| Observation | When | Notable low-cardinality keys |
| --- | --- | --- |
| `spring.ai.chat.client` | `ChatClient` `call()`/`stream()` | `gen_ai.operation.name`=framework, `gen_ai.system`=spring_ai, `spring.ai.chat.client.stream`, `spring.ai.kind`=chat_client |
| `spring.ai.advisor` | each advisor in chain | `spring.ai.advisor.name` (+ high-card `spring.ai.advisor.order`) |
| `gen_ai.client.operation` | `ChatModel` `call`/`stream` | `gen_ai.request.model`, `gen_ai.response.model` |
| `spring.ai.tool` | tool execution | `gen_ai.operation.name`=execute_tool, `spring.ai.kind`=tool_call, `spring.ai.tool.definition.name` |
| `db.vector.client.operation` | vector store add/delete/query | `db.operation.name`, `db.system` |

High-cardinality (trace-only) on `spring.ai.chat.client`: `spring.ai.chat.client.advisors`,
`spring.ai.chat.client.conversation.id` (only when a chat-memory advisor is in use),
`spring.ai.chat.client.tool.names`.

Prompt/completion content **not exported by default** (sensitive):
`spring.ai.chat.observations.log-prompt` / `log-completion` (ChatModel layer) and
`spring.ai.chat.client.observations.log-prompt` / `log-completion` (ChatClient layer),
all default `false`.

## Token usage — the numbers

**Per-call, read from the response (not from metrics):**

```kotlin
val usage = chatResponse.metadata?.usage   // ChatResponseMetadata.getUsage(): Usage?
usage?.promptTokens      // input
usage?.generationTokens  // output
usage?.totalTokens
```

`Usage` is the per-call truth. (Cache detail — `cacheCreationInputTokens` /
`cacheReadInputTokens` — lives in provider-native usage objects; confirm 2.0.1's `Usage`
shape before coding. The observability docs don't cover the getter.)

**As a metric** — one counter, no per-session dimension:

- `gen_ai.client.token.usage` → Prometheus `gen_ai_client_token_usage_total`
   (Counter, unit tokens), label `gen_ai_token_type` ∈ `input` | `output` | `total`.
- Timers: `gen_ai.client.operation` → `gen_ai_client_operation_seconds_{count,sum,max}`
  + `gen_ai_client_operation_active_count` (in-flight gauge); same family for
  `gen_ai.chat.client.operation` and `db.vector.client.operation`.

**As trace attributes** (high-cardinality, `gen_ai.client.operation` span):

- `gen_ai.usage.input_tokens` / `.output_tokens` / `.total_tokens`
- `gen_ai.usage.cache_creation.input_tokens` — tokens written to a provider-managed cache
- `gen_ai.usage.cache_read.input_tokens` — tokens served from it

## What Spring AI does NOT provide

- **No cost metric anywhere** (no `gen_ai.client.cost`, no currency label) — cost is not
  in v2.0.1.
- **No usage stream / per-session usage aggregate** — observations are per-call and emitted
  to Micrometer; nothing pushes a running total to a client.
- No per-session tag on any usage observation out of the box
  (`conversation.id` is trace-only *and* requires a chat-memory advisor, which §2.2 bypasses).

Streaming caveat: for `ChatClient.stream()`, the HTTP-layer span is **not** parented under
the chat-model span (SDK hops to `ForkJoinPool.commonPool()`, dropping observation
context). Affects trace nesting, not token numbers.

## claudius mapping

- **§2.8 `UsageService` = per-call aggregation over `ChatResponse.getMetadata().getUsage()`**,
  accumulated in the session's `QueryEngine` (total usage is already §2.2 state). The
  Micrometer counter is process-global and unusable per session — don't scrape it for the
  frontend; it's for ops dashboards only.
- **Usage status stream (README story 3 / §2.8):** Spring AI has no built-in; claudius
  emits one — a `Flux<UsageStatus>`/SSE derived from per-call deltas as the agent loop
  consumes `Flux<ChatResponse>` from `callModel` (§2.8 keeps `ChatClient.prompt().stream()`
  as the seam, so every delta is in hand at the aggregation point).
- **§2.8 "Usage is aggregated (`updateUsage`)" holds** — aggregation is claudius-side
  exactly as the reference does; nothing to correct in Architecture.md.
- **Prompt cache (§2.8's single `cache_control` marker):** visible to claudius as
  `gen_ai.usage.cache_creation/cache_read.input_tokens` on the trace and (provider-dependent)
  in native usage — surface cache-read tokens in the usage stream when available, but don't
  depend on it before confirming 2.0.1 `Usage` carries it.
- **Cost:** claim "cost" (README story 3) is claudius-computed: `tokens × provider price
  table` in `UsageService`, since no cost metric exists. Flag for Architecture.md: §2.8 says
  "usage" not "cost", so no correction — but do not add a "read cost from Micrometer"
  anywhere.
