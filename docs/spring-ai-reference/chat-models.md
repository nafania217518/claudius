# Spring AI v2.0.1 — Chat models / LLM gateway reference

Distilled from the official v2.0.1 docs: `api/chatmodel.html`, `api/chat/anthropic-chat.html`,
`api/chat/comparison.html`, `api/chat/anthropic-migration.html`. For claudius §2.8 (LLM
gateway) + §3 story 3 (configurable provider/model + token limits).

---

## 1. Core API (provider-agnostic)

- `ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel`
  - `String call(String)` (default), `ChatResponse call(Prompt)`.
- `StreamingChatModel extends StreamingModel<Prompt, ChatResponse>`
  - `Flux<String> stream(String)` (default), `Flux<ChatResponse> stream(Prompt)`.
- `Prompt` = `ModelRequest<List<Message>>` → `messages` + `ChatOptions modelOptions`.
- `ChatOptions`: `getModel() / getMaxTokens() / getTemperature() / getTopK() / getTopP() /
  getFrequencyPenalty() / getPresencePenalty() / getStopSequences() / mutate()`.
- `ChatResponse` → `getMetadata(): ChatResponseMetadata` + `getResults(): List<Generation>`.
  - `Generation` → `getOutput(): AssistantMessage` + `getMetadata(): ChatGenerationMetadata`.
- **Options precedence:** a ChatModel's default options are fixed at init; per-request
  `Prompt` options **fully override** (or pass `null` to inherit defaults). `ChatClient` does an
  incremental "delta" merge instead. In 2.0.x `NONE`-ish defaults no longer auto-merge at prompt
  level for Anthropic — use `chatModel.getOptions().mutate().maxTokens(2048).build()`.

---

## 2. Per-provider ChatModel beans (starter → base class)

| Provider | Starter module | `ChatModel` class | Retry | Streaming |
| --- | --- | --- | --- | --- |
| Anthropic | `spring-ai-starter-model-anthropic` | `AnthropicChatModel` | YES (SDK `maxRetries`) | YES |
| OpenAI | `spring-ai-starter-model-openai` | `OpenAiChatModel` | YES | YES |
| Bedrock | `spring-ai-starter-model-bedrock` | `BedrockConverseChatModel` (comparison: "Amazon Bedrock Converse") | YES | YES |
| Vertex/Google | `spring-ai-starter-model-vertexai` | `VertexAiChatModel` (comparison also lists "Google GenAI" as a distinct surface) | YES | YES |

- Each starter auto-configures **one default `ChatModel` bean**. For claudius's per-provider
  registry, build explicit `XChatModel.builder()…build()` beans and map them by provider key.
- **Anthropic is a thin adapter over the official `com.anthropic:anthropic-java` SDK** since
  2.0.0-M3 (was a hand-rolled 2300-line `AnthropicApi`). New transitive dep:
  `com.squareup.okhttp3:okhttp`.
- Public `AnthropicChatModel` constructors were **removed** — use `AnthropicChatModel.builder()`
  (accepts `apiKey, defaultOptions, toolCallingManager, baseUrl, timeout, maxRetries, proxy,
  customHeaders, observationRegistry, observationConvention` — **no `retryTemplate`**).

---

## 3. Config / token limits (`@ConfigurationProperties`)

Anthropic namespace `spring.ai.anthropic.*`:
- `api-key` (env `ANTHROPIC_API_KEY` auto-detected) · `base-url` (`api.anthropic.com`) ·
  `timeout` (60s, construction-time) · `max-retries` (**default 2**) · `custom-headers.*`.
- `chat.model` — starter default `claude-haiku-4-5`; options table default
  `claude-sonnet-4-20250514`. · `chat.max-tokens` (**default 4096**, was 500 pre-2.0) ·
  `chat.temperature` · `chat.top-p` · `chat.top-k`.
- `chat.http-headers` (per-request) · `chat.inference-geo` (us/eu) · `chat.service-tier`
  (auto/standard_only) · `chat.web-search-tool.*`.

- **max-tokens / token limits = HOLDS in Spring AI:** `spring.ai.anthropic.chat.max-tokens`
  (startup default) + runtime `AnthropicChatOptions.builder().maxTokens(n)`, per-request via
  `Prompt` options. This is the "adjustable token limits" story.

`AnthropicChatOptions.builder()` (runtime): `model`, `maxTokens(4096)`, `temperature(1.0)`,
`topP/topK` (**deprecated for models after Claude Opus 4.6 → HTTP 400**), `stopSequences`,
`apiKey`, `baseUrl`, `timeout`, `maxRetries(2)`, `proxy`, `customHeaders` (client-level),
`httpHeaders` (per-request, via `MessageCreateParams.putAdditionalHeader()`), `cacheOptions`,
`inferenceGeo`, `serviceTier` (`MessageCreateParams.ServiceTier.AUTO/STANDARD_ONLY`).
- `thinking`: `thinkingEnabled(budgetTokens[,display])` / `thinkingAdaptive([display])` /
  `thinkingDisabled()` — budget ≥1024 and < maxTokens, temperature must be 1.0.
- `outputConfig`: `outputSchema(String)` / `effort(Effort)` (requires claude-sonnet-4-6+).
- Tool options: `toolChoice` (AUTO), `toolCallbacks`, `disableParallelToolUse(false)`.

---

## 4. Streaming

- `Flux<ChatResponse> stream = chatModel.stream(new Prompt(...));`
- `stream.subscribe(r -> r.getResult().getOutput().getText());` — text accessor is `getText()` → Kotlin `.text` (NOT `getContent()`; that fails to resolve on Spring AI 2.0.1 `AssistantMessage`).
- All four providers support streaming (comparison table).
- **Anthropic quirk:** rate-limit headers are read once at stream start and attached to the
  chunk carrying final usage. The streaming path consumes the SDK's blocking stream on
  `Schedulers.boundedElastic()` — **a streaming call holds a worker thread for the stream's
  whole duration.** Relevant for claudius's per-session WS/SSE stream sizing.

---

## 5. Prompt caching / `cache_control` — SUPPORTED in 2.0.x ✅

Spring AI 2.0.x DOES expose prompt caching for Anthropic. Types moved to root package
`org.springframework.ai.anthropic`:
- `AnthropicCacheStrategy`: `NONE` (default, no markers) / `SYSTEM_ONLY` (1 bp) /
  `TOOLS_ONLY` (1 bp) / `SYSTEM_AND_TOOLS` (2 bp) / `CONVERSATION_HISTORY` (up to 4 bp).
- `AnthropicCacheTtl`: `FIVE_MINUTES` / `ONE_HOUR` (→ the §2.8 "5m/1h TTL").
- `AnthropicCacheOptions`: `strategy`, `messageTypeTtl(MessageType, AnthropicCacheTtl)`,
  `messageTypeMinContentLength` (default 1), `contentLengthFunction` (default `String::length`),
  `multiBlockSystemCaching` (false), `cacheToolResults` (bp on last tool result; needs
  `CONVERSATION_HISTORY`).
- `CacheBreakpointTracker` enforces the **4-breakpoint/request cap** (silently skips extras,
  one-time WARN). `CacheEligibilityResolver` decides what's cacheable.
- Config: `spring.ai.anthropic.chat.cache-options.strategy` (default `NONE`) and
  `…multi-block-system-caching` (false).

```java
AnthropicChatOptions.builder()
    .model("claude-sonnet-4-20250514")
    .maxTokens(1024)
    .cacheOptions(AnthropicCacheOptions.builder()
        .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
        .build())
    .build();
```

- **Caveats:** no raw single-`cache_control`-marker API — it is **strategy-driven** (the
  strategy picks which blocks get markers). `AnthropicCacheStrategy.NONE` means "unset / fall
  back to model default" (**NOT** "disabled") — to truly disable, build a 2nd
  `AnthropicChatModel` with `AnthropicCacheOptions.disabled()`. Tool cache TTL is taken from
  `MessageType.SYSTEM` regardless of strategy. `AnthropicCacheType` was replaced by SDK
  `com.anthropic.models.messages.CacheControlEphemeral` (only `ephemeral` value).

---

## 6. Retry / backoff + fallback model

### Retry — BUILT-IN, but provider-shaped
- Comparison table: **Retry = YES** for Anthropic, OpenAI, Bedrock Converse, Google GenAI,
  Mistral, Ollama, Groq, NVIDIA, Perplexity, MiniMax.
- **Anthropic (2.0.x): retry is the SDK's `maxRetries` (default 2)** — `spring.ai.anthropic.
  max-retries=5` for example. **NOT Spring Retry `RetryTemplate`.** Backoff + jitter live
  inside the SDK; **`MAX_529_RETRIES=3` is not a Spring AI / SDK concept** — the SDK has a
  generic retry count, no per-status-code (529) cap. Finer control via a custom
  `AnthropicSetup`.
- Other providers historically use Spring Retry `RetryTemplate` + `ExponentialBackOffPolicy`
  (no jitter by default → jitter must be added, e.g. resilience4j `ExponentialRandomBackoff`
  or a custom `BackOffPolicy`).

### Fallback model — NOT BUILT-IN ⚠️
- No "fallback to model X after N retries" anywhere in Spring AI (no fallback column in the
  comparison table). **claudius must wrap it:** a decorator over `ChatModel`, resilience4j
  `Fallback`, or `try/catch` on `com.anthropic.errors.RateLimitException` (thrown after the
  SDK's retries exhaust) → switch to a fallback `ChatModel`/model.

### Usage / rate-limit metadata
- `ChatResponseMetadata.getUsage()` → `Usage` (per-response). For cache breakdown:
  ```java
  var sdkUsage = (com.anthropic.models.messages.Usage)
      response.getMetadata().getUsage().getNativeUsage();
  long cacheCreation = sdkUsage.cacheCreationInputTokens().orElse(0L);
  long cacheRead     = sdkUsage.cacheReadInputTokens().orElse(0L);
  ```
- `response.getMetadata().getRateLimit()` → `RateLimit` (`getRequestsLimit/Remaining/Reset`,
  `getTokensLimit/Remaining/Reset`); `AnthropicRateLimit` adds input/output token buckets;
  otherwise `EmptyRateLimit`.
- **Cross-session usage aggregation is claudius's job** — Spring AI only gives per-response
  `Usage` + rate-limit headers.

---

## 7. Claudius mapping (§2.8 + §3)

**§2.8 claims — verdict:**

| §2.8 claim | Verdict |
| --- | --- |
| "one `cache_control` marker per request (5m/1h TTL)" | **HOLDS** — via `AnthropicCacheOptions`/`Strategy`/`Ttl` (5m/1h = `FIVE_MINUTES`/`ONE_HOUR`). Not a raw single-marker API; strategy-driven, 4-bp cap. `NONE` ≠ disabled. |
| "retry = exponential backoff + 25% jitter, `MAX_529_RETRIES=3`" | **PARTIAL / CORRECT** — retry is built-in, but for Anthropic it's the **SDK `maxRetries` (default 2)**, not Spring Retry. Jitter is SDK-internal; `MAX_529_RETRIES=3` is **not** a Spring AI/SDK concept → implement per-status cap in claudius if needed. |
| "→ fallback model" | **MUST CORRECT** — **NOT built-in** in Spring AI. Wrap with a decorator / resilience4j / `try-catch(RateLimitException)`. |
| "OAuth refresh" | **MUST CORRECT** — NOT built-in. Spring AI uses API key (`api-key`/`ANTHROPIC_API_KEY`). OAuth refresh = claudius code (custom `AnthropicSetup`/`ApiKeyResolver`/headers). |
| "`ClientFactory` bean per provider" | **HOLDS w/ nuance** — Spring AI auto-configures one default `ChatModel`/starter; per-provider registry = build `XChatModel.builder()` beans yourself. |
| "Usage aggregated (`updateUsage`)" → `UsageService` | **CLARIFY** — Spring AI gives per-response `Usage` + `RateLimit`; cross-session aggregation is claudius. |
| "`ChatClient.prompt().stream()` → `Flux<ChatResponse>` per provider" | **HOLDS** — use `chatModel.stream(Prompt)` (or `ChatClient`); injected `callModel` stays unit-testable. |
| "`max-tokens` / token limits" | **HOLDS** — `spring.ai.anthropic.chat.max-tokens` (4096) + `AnthropicChatOptions.maxTokens()`, per-request via `Prompt`. |

**Realization of §2.8 / story 3:**
- **Gateway:** `ChatModel` per provider (Anthropic first via `spring-ai-starter-model-anthropic`
  → `AnthropicChatModel`); a `ClientRegistry`/factory maps provider key → `ChatModel`. Inject
  as `callModel` (VCR-friendly fake for tests).
- **Retry:** rely on SDK `maxRetries` (`spring.ai.anthropic.max-retries`) for 429/529 backoff;
  **add the fallback-model switch + optional per-status cap in claudius** (decorator).
- **Cache:** enable `AnthropicCacheOptions.strategy(SYSTEM_AND_TOOLS or CONVERSATION_HISTORY)`
  with `FIVE_MINUTES`/`ONE_HOUR`; keep the "don't mutate the cached prefix" invariant (Architecture
  §6.3) — the cache pays for it.
- **Usage:** read `ChatResponseMetadata.getUsage()` (+ `RateLimit`) on call/stream; a
  `UsageService` aggregates per-session and feeds the React usage panel (story 3).
- **Config:** `spring.ai.anthropic.*` + `@ConfigurationProperties` = the "configurable
  provider/model + token limits" story; surface model/max-tokens/permission-mode in the §2.10
  settings panel.
- **Auth:** API-key based in Spring AI; implement OAuth refresh (if used) in claudius.
