# Spring AI ChatClient — reference notes (v2.0.1)

Terse API notes for `claudius` backend. Source: `docs.spring.io/spring-ai/reference/api/chatclient.html`.
Spring AI **2.0.1**. This is a reference, not a copy of the docs.

> Note: `ChatClient` is the high-level fluent API. `ChatModel` is the low-level model interface
> (`call(Prompt)/stream(Prompt) → Flux<ChatResponse>`). `claudius` uses `ChatClient` as the
> injected `callModel` seam (Architecture §2.8). The auto tool loop is disabled — see
> `tool-calling.md`.

## Building a ChatClient

```kotlin
// from an existing ChatModel bean
val client = ChatClient.builder(chatModel, observationRegistry, resource, toolCallingAdvisor).build()
// simplest:
ChatClient.create(chatModel)
ChatClient.builder(chatModel).defaultSystem("...").defaultTools(...).build()
```

- Autoconfigured Boot app exposes a **prototype** `ChatClient.Builder` bean; inject it and call
  `.build()`. No autoconfigured `ChatClient` bean (prototype can't be injected directly).
- Multiple providers → multiple `ChatModel` beans; auto-config registers a `ChatClient.Builder`
  per model. Make one primary with `@Primary`, or implement `ChatClientBuilderConfigurer`
  (one `configure(ChatClient.Builder, ChatModel)` per model → keyed builders by model name).
- Per-client defaults via `Builder`: `defaultSystem`, `defaultUser`, `defaultToolContext`,
  `defaultAdvisors`, `defaultOptions`.

## `.prompt()` API

```kotlin
client.prompt("...").call().content()                    // String
client.prompt("...").call().chatResponse()               // ChatResponse
client.prompt().user(u).system(s).call().content()
client.prompt().messages(List<Message>).call()
```

- `PromptRequestSpec` fluent builder: `.user(String)/.user(Resource)/.user(Message)`,
  `.system(String)/.system(Resource)/.system(Message)`, `.messages(Message...)`, `.param(...)`,
  `.params(Map)`.
- `CallResponseSpec`: `.content()` → String · `.chatResponse()` → `ChatResponse` ·
  `.entity(Class)` / `.entity(ParameterizedTypeReference)` → structured ·
  `.advisors(AdvisorSpec, vararg Advisor)` · `.advisors(Consumer<AdvisorSpec>)`.
- `ResponseEntity<T>` wraps `content()`; add `.responseEntity()` to a prompt to get it.

## Streaming

Reactive only (needs a reactive stack; non-streaming works on servlet stack).

```kotlin
val fluxString: Flux<String> = client.prompt("...").stream().content()          // text chunks
val fluxResp:   Flux<ChatResponse> = client.prompt("...").stream().chatResponse() // token-level
```

- `client.prompt("...").stream().` returns `StreamResponseSpec`; `.content()` and
  `.chatResponse()` are its two terminals.
- `chatResponse()` exposes usage + metadata; `content()` is a convenience over `chatResponse()`.
- **Streaming is NOT supported for a `ToolCallback` that has a non-trivial `ToolMetadata`.**
  (`FunctionToolCallback` / `MethodToolCallback` metadata is trivial; custom impls may not be.)

## Advisors (the cross-cutting seam)

Advisors wrap the request/response chain — the place to hook compaction, usage, memory.

- Chain types: `CallAdvisorChain` (blocking, `nextCall(request)`) / `StreamAdvisorChain`
  (reactive, `nextStream(request) → Flux<ChatResponse>`).
- Per-call `AdvisorSpec`: `.param(key,val)` · `.params(Map)` · `.advisors(Advisor...)`;
  plus `.advisors(Consumer<AdvisorSpec>)`.
- Common advisors: `SimpleLoggerAdvisor` (order `HIGHEST_PRECEDENCE`),
  `MessageChatMemoryAdvisor`, `QuestionAnswerAdvisor`.
- `MessageChatMemoryAdvisor` requires `ChatMemory.CONVERSATION_ID` param or it short-circuits
  (`throwShortCircuit=true` → exception). Set via `.param(ChatMemory.CONVERSATION_ID, id)`.
- `ChatMemory` impls: `MessageWindowChatMemory` (default), `ChatMemoryRepository`-backed
  (JDBC/Redis/Neo4j/InMemory), `VectorStoreChatMemory`.
- Tool loop lives in a `ToolCallingAdvisor` (see `tool-calling.md`) — **disable it** for
  `claudius`; do not let the framework drive the loop.

## Passing messages + tools

```kotlin
client.prompt().messages(List<Message>).call()
client.prompt().user("...").tools(ToolCallback...).call()   // per-call
ChatClient.builder(chatModel).defaultTools(...).build()     // client-wide default
client.prompt().toolContext(Map.of("tenant","acme")).call()
```

- Message types: `UserMessage`, `SystemMessage`, `AssistantMessage`, `ToolResponseMessage`
  (impls of `org.springframework.ai.chat.messages.Message`).
- Tools are `ToolCallback` / `@Tool` POJOs / `ToolCallbackProvider` — see `tool-calling.md`.
- `ToolContext` data reaches the tool, never the model.
- `options(ChatOptions)` sets per-call provider options (model, temperature, topP, **maxTokens**).
  `maxTokens` is where the reference's output-token limit / escalation lives — see below.

## Structured output

```kotlin
// class/POJO (Jackson)
val order = client.prompt("...").call().entity(OrderSummary::class.java)
// generic
val pair = client.prompt("...").call().entity(object : ParameterizedTypeReference<Pair<String,Int>>() {})
// with schema validation + provider-side structured output
client.prompt("...").call().entity(spec -> spec.validateSchema(true).useProviderStructuredOutput(true), MyType::class.java)
// ResponseEntity variant
client.prompt("...").call().responseEntity(MyType::class.java)
```

- `EntityParamSpec` flags: `validateSchema(boolean)`, `useProviderStructuredOutput(boolean)`.
- `BeanOutputConverter<T>` / `StructuredOutputConverter` produce/parse JSON schema.
- **Caveat (from docs):** structured output on OpenAI/Bedrock/Vertex forces
  `parallelToolCalls=false` and skips `ToolCallingAdvisor`. Anthropic has no schema-validation
  param. For `claudius` the agent loop is free-form tool use, not structured output — so this is
  mostly irrelevant to the main path (only relevant if a sub-agent emits a typed result).

## Token-limit / max-tokens config

- `maxTokens` (and `temperature`, `topP`) are set on the provider `ChatOptions` via
  `.options(...)` or `defaultOptions(...)`. This is how the reference's
  `max_output_tokens` escalation (8k→64k, Architecture §2.1 step 4) would be applied
  **per iteration** of the loop.
- `spring.ai.<provider>.max-tokens` sets a default; `.maxTokens(n)` on the request overrides.
- Token *limits as a guard* (abort a run over budget) are not a ChatClient feature — that is
  `claudius`'s `UsageService` / loop concern, not the model's.

## claudius mapping

- **`ChatClient` = the injected `callModel`** (Architecture §2.8, §6.7). Build one per provider
  from the `ChatModel` bean; inject it into `AgentLoop` as the loop's model-call function so the
  loop is unit-testable with a **fake `ChatModel` = VCR** (§2.1: "a fake callModel is a VCR").
- **Streaming feeds the loop:** each model-call step is `client.prompt(messages).stream()
  .chatResponse()` → `Flux<ChatResponse>`. The loop forwards raw chunks to the frontend WS/SSE
  (live reasoning + tool-call cards, §2.1 step 2 / §2.10) while aggregating a copy
  (`ChatClientMessageAggregator`) to detect `tool_use` blocks and finish reason. Use
  `chatResponse()`, not `content()`, so usage/metadata (→ `UsageService`, §2.8) is visible.
- **Disable the framework tool loop.** `claudius` runs its own loop (§2.1): set
  `AdvisorParams.toolCallingAdvisorAutoRegister(false)` per call (or
  `spring.ai.chat.client.tool-calling.enabled=false` globally) and drive `ToolCallbacks`
  manually — see `tool-calling.md`.
- **Advisors for cross-cutting:** put compaction / usage / memory as advisors *outside* the
  manual loop (or as explicit steps), matching §2.6/§2.7. Don't rely on `MessageChatMemoryAdvisor`
  for session history — the loop owns `QueryEngine` state (§2.2), so keep history in the
  `QueryEngine`, not a `ChatMemory` bean.
- **Contradiction to flag:** Architecture §2.8 says keep "one `cache_control` marker per
  request (5m/1h TTL)". `ChatClient` does not expose `cache_control` directly — that is a
  provider option on `AnthropicChatOptions` / request metadata, not a ChatClient API surface.
  Confirm the exact v2.0.1 option before wiring; the invariant to keep is "don't mutate the
  cached prefix" (§6), not the marker plumbing.
- **Contradiction to flag:** Architecture §2.8 mentions retry = exponential backoff + 25%
  jitter, `MAX_529_RETRIES=3` → fallback model. These are the *reference* (claude-code) numbers;
  Spring AI retry is a separate concern (retry template / `spring-ai` retry), not part of
  `ChatClient`. Implement the 529/429 backoff + fallback in `claudius`'s gateway, not via a
  built-in ChatClient feature.
