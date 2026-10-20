# Spring AI Tool Calling — reference notes (v2.0.1)

Terse API notes for `claudius` backend. Sources: `tools.html` + `tool-calling-advisor.html`
(Spring AI **2.0.1**). Reference, not a copy of the docs.

> Big decision: `claudius` wants its **own** agent loop (Architecture §2.1), not Spring AI's
> auto tool loop. → **Bypass `ToolCallingAdvisor`** and drive `ToolCallback`s manually. See
> the `claudius mapping` at the bottom.

## `ToolCallback` interface (the tool contract)

```java
public interface ToolCallback {
    ToolDefinition getToolDefinition();         // name, description, JSON inputSchema
    ToolMetadata getToolMetadata();             // e.g. returnDirect
    String call(String toolInput);
    String call(String toolInput, ToolContext toolContext);
}
```

- `ToolDefinition`: `name()` · `description()` · `inputSchema()` (JSON string). Build via
  `ToolDefinition.builder().name().description().inputSchema()`, or
  `ToolDefinitions.from(method)` to generate from a signature.
- `ToolMetadata`: `returnDirect(true)` → tool result bypasses the model, returned to caller.
  Only honored if **all** tools in a multi-call round are `returnDirect`.
- `ToolContext`: non-model data (tenant/user/scope); passed to the tool, **never** sent to the
  model. Read in tool via a `ToolContext` param.
- `ToolCallbacks.from(obj...)` → `ToolCallback[]`; `ToolCallbacks.fromToolCallbacks(List)`.
- **`ToolCallback` has NO read-only / concurrency-safe / destructive flags.** Only `returnDirect`.
  `claudius`'s `isReadOnly`/`isConcurrencySafe`/`isDestructive` (§2.3) must live on claudius's
  **own** `Tool` interface, layered on top of `ToolCallback`.

## Defining tools (3 styles, all → `ToolCallback`)

```java
// 1. Declarative
@Tool(description = "Get the weather for a city")
public String getWeather(String city) { ... }
@ToolParam(description="City", required=false)  // @Nullable / @JsonProperty(required=false) also work
```

```java
// 2. Method (need runtime build / don't own the class)
MethodToolCallback.builder()
    .toolDefinition(ToolDefinitions.builder(method).description("...").build())
    .toolMethod(method).toolObject(obj)   // toolObject required for instance methods
    .build();
```

```java
// 3. Function/lambda/method-ref
FunctionToolCallback.builder("currentWeather", svc::getWeather)
    .description("...").inputType(WeatherRequest.class).build();
```

- `@Tool`: `name` (defaults to method name, must be unique), `description` (recommended),
  `returnDirect`, `resultConverter`. `@ToolParam` for per-param description/required.
- **Method-tool unsupported types:** `Optional`, `CompletableFuture`/`Future`,
  `Flow`/`Mono`/`Flux`, `Function`/`Supplier`/`Consumer` (not serializable → no schema).
- **AOT/GraalVM:** `@Tool` class must be a Spring bean (`@Component`) or carry
  `@RegisterReflection(...INVOKE_DECLARED_METHODS)`.

## Registry (the fail-closed default lives in claudius, not Spring AI)

- Spring AI **auto-discovers `ToolCallback` beans** → `StaticToolCallbackResolver` (name lookup).
  Inject the beans and pass to `ChatClient` via `.tools(...)` / `.defaultTools(...)`.
- **v2.0 change:** the old `SpringBeanToolCallbackResolver` (bare `Function` beans resolved by
  name via `toolNames(...)`) is **removed** — tools must be explicit `ToolCallback` beans.
- Per-call `.tools(...)` **appends** to `.defaultTools(...)` (union). `ToolCallingChatOptions`
  tools on the underlying `ChatModel` are **overridden** by the request-level list.
- **MCP:** `SyncMcpToolCallbackProvider`/`AsyncMcpToolCallbackProvider` are **NOT**
  auto-registered with `ChatClient` (eager listing = network round-trip). Inject + wire
  explicitly. Local+MCP tools share the `ToolCallback` interface; **name conflicts across
  local↔MCP are not resolved** by Spring AI (only within MCP via
  `DefaultMcpToolNamePrefixGenerator`) — `claudius` must handle its own "built-ins win on
  name clash" (§2.3 `getMergedTools`).
- **Tool call limits** (`DefaultToolCallingManager`): `maxCallsPerTool` (default 40),
  `maxTotalToolCalls` (default 150), `excludeToolFromLimit`, `unlimitedCallsPerTool()`,
  `onLimitExceeded(THROW|RETURN_ERROR_RESPONSE)`. Counts are per-turn only.

## `ToolCallingAdvisor` — the AUTO loop (default on; claudius disables it)

`ToolCallingAdvisor` (recursive `CallAdvisor` + `StreamAdvisor`, implements `ToolAdvisor`
marker) auto-registered by `DefaultChatClient`, order `HIGHEST_PRECEDENCE + 300`. It:

1. sends request + all tool defs to model,
2. on tool calls → `ToolCallingManager.executeToolCalls(prompt, chatResponse)` runs them,
   appends results, re-enters the downstream chain,
3. repeats until the response has **no** tool calls.

- Enforces **exactly one `ToolAdvisor`** in the chain (registering a 2nd fails fast).
- Builder: `ToolCallingAdvisor.builder().toolCallingManager(mgr)
  .toolExecutionEligibilityChecker(...) .advisorOrder(n) .conversationHistoryEnabled(false)
  .disableInternalConversationHistory() .build()`.
- `ToolExecutionEligibilityChecker` (default `chatResponse.hasToolCalls()`) decides loop
  continuation — the one seam for provider stop-reason logic.
- Hooks: `doInitializeLoop`, `doBeforeCall`, `doAfterCall`, `doFinalizeLoop` (+ `*Stream`
  variants) + `doGetNextInstructionsForToolCall`. Override via a subclass; plug in with a
  `ToolCallingAdvisor.Builder<?>` bean (`@AutoConfiguration(beforeName=...ChatClientAutoConfiguration)`).
- **Memory vs loop:** `MessageChatMemoryAdvisor` default order `+200` = **outside** the loop
  (sees only first user + final assistant). Put it **inside** (`order +400`) to feed tool
  transcript; auto-registered `ToolCallingAdvisor` then disables its own internal history.
- **Custom `ToolAdvisor` for approval/observability:** the docs recommend an *observing*
  advisor **inside** the loop over full opt-out for streaming-to-UI. But that gives no place
  to run a Kotlin-side permission gate + compaction between iterations (see mapping).

## Disabling the auto loop → user-controlled mode

Per call: `.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))`.
Global: `spring.ai.chat.client.tool-calling.enabled=false`.
Then you detect tool calls in `ChatResponse` and run them yourself:

```kotlin
val mgr = ToolCallingManager.builder().build()
val options = ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(WeatherTools())).build()

var resp = client.prompt().user(q).options(options)
    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)).call().chatClientResponse()
while (resp.chatResponse() != null && resp.chatResponse().hasToolCalls()) {
    val result = mgr.executeToolCalls(prompt, resp.chatResponse())   // → ToolExecutionResult
    prompt = Prompt(result.conversationHistory(), options)
    resp = client.prompt().messages(result.conversationHistory()).options(options)
        .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)).call().chatClientResponse()
}
```

- **User-controlled streaming:** aggregate each iteration's chunk `Flux` with
  `ChatClientMessageAggregator.aggregateChatClientResponse(flux, ref::set).blockLast()` while
  forwarding raw chunks to the UI via `.doOnNext(chunk -> forwardToSse(chunk))` on
  `.stream().chatClientResponse()`. Verbose but it's the exact shape `claudius` needs
  (live cards + manual loop).
- Driving the loop yourself bypasses the framework's advisor composition, observability, and
  the single-`ToolAdvisor` invariant.

## Exception handling

- Tool throws → `ToolExecutionException` → `ToolExecutionExceptionProcessor` (feeds an error
  message back to the model, or re-throws). Default: RuntimeException message → model;
  checked + `Error` re-thrown. `spring.ai.tools.throw-exception-on-error` toggles it.
- If claudius owns the loop, a **denied/aborted** tool call must still produce a result so the
  pair isn't left dangling — mirrors Architecture §6.1 (no dangling `tool_use`).

## claudius mapping

- **Bypass `ToolCallingAdvisor`, drive tools manually.** Architecture §2.1/§2.8 want
  `claudius`'s own `Flux<Message> loop()` with a per-call **permission gate** (§2.4
  `CompletableFuture<Decision>` over WS) and a **compaction cascade** at the top of each
  iteration (§2.6). The framework's recursive loop gives no Kotlin-side hook between
  iterations, so use `AdvisorParams.toolCallingAdvisorAutoRegister(false)` (+ optionally
  global `spring.ai.chat.client.tool-calling.enabled=false`) and call
  `ToolCallingManager.executeToolCalls` yourself. A custom *observing* advisor inside the loop
  is enough for streaming-to-UI alone, but **not** for a permission gate + compaction — that
  needs the manual loop. (Flag: Architecture.md does not currently mention `ToolCallingAdvisor`
  or this opt-out; worth a note in §2.3/§2.8.)
- **Registry + fail-closed.** Auto-discovered `ToolCallback` beans give the ordered registry
  (§2.3). Keep **prompt-cache-sensitive ordering** (§2.3/§6) — Spring AI sends defs per
  request; preserve order in the registry bean. **Layer fail-closed capability flags**
  (`isReadOnly`/`isConcurrencySafe`/`isDestructive`, §2.3, §6.3) on claudius's own `Tool`
  interface — Spring AI's `ToolCallback`/`ToolMetadata` has **no** such flags (only
  `returnDirect`). Default any unknown tool to mutating + non-concurrency-safe.
- **Per-call permission gate around each tool.** In the manual loop, before
  `executeToolCalls`, resolve each outstanding `tool_use` through
  `PermissionService.canUseTool(...)` → `CompletableFuture<Decision>` (WS `ToolUseConfirm`,
  §2.4). Reject/deny → synthesize a `tool_result` (fail-closed, §6.1) instead of executing.
  Bounded-concurrency runner: read-only + concurrency-safe in parallel, destructive serialized
  (§2.1, §2.3).
- **Concurrency/limits.** Use `ToolCallingManager` limits as a runaway guard, but the
  real per-turn budget is claudius's own (loop + `UsageService`).
- **MCP + name clash.** When MCP lands (§2.3 `getMergedTools`), wire the
  `*McpToolCallbackProvider` explicitly and implement "built-ins win on name clash"
  yourself — Spring AI does not resolve local↔MCP conflicts.
