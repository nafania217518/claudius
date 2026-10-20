# CLAUDE.md

## Rules

1. Write replies and documentation "80% of the way to ASD-STE100" (Simplified Technical English): short sentences (about 20 words max), one idea per sentence, active voice, present tense, one approved meaning per word, no idioms, no filler. Do not go full STE: keep natural phrasing and domain terms where strict STE would read awkwardly.
2. KISS: keep it simple.
3. TDD approach for coding: Requirement -> Test -> Code. The requirement is the test name. New requirement = new test. The task is done when all requirements have tests and all tests pass.
4. Use low-effort sub-agents to write code. Instruct them to follow the TDD approach and give them the necessary set of requirements and instructions.
5. Tests must pass. Coverage must be no less than 90% per core functionality module.
6. Tests for every feature, no exceptions. Every feature ships with its test(s) unless the user explicitly states otherwise for that feature.
7. TDD on bug reports. When the user reports a bug, first add test(s). Only after those tests reproduce the bug, fix it.
8. TDD: tests are the only proof code works. All code must have test coverage via unit tests and/or functional, integration, browser, or end-to-end tests. Frontend: unit and/or browser tests. Backend: unit and/or integration tests. Both are acceptable per surface.
9. NEVER use `nohup` or `command &` to start a background task. Such tasks cannot be tracked and cause unexpected errors. ALWAYS start background tasks through the tools (`run_in_background=true`) so the task stays tracked and can be stopped with the corresponding stop tools.
10. Do not use the built-in web search. It is broken. Use the `mcp-browser` MCP tools to search the web and read sites.
11. You may have outdated information. Do research with `mcp-browser` and check at least 4 sources for each claim you make. Put anything worth noticing under the Caveats section below in the format `SHORT_DESCRIPTION : file_reference.md`.

## Caveats

- Spring AI `DefaultToolCallingManager` resolves tool callbacks from the prompt options first; the `ToolCallbackResolver` is an off-by-default fallback, so a resolver-only filter gates nothing : docs/review-2026-10-09.md
- Spring AI 2.0 chat models (incl. `OllamaChatModel`) do not run an internal tool loop; `ToolCallingAdvisor` does, so `chatModel.stream(Prompt)` returns raw tool calls : docs/review-2026-10-09.md
- Reactor: a `Flux.create` producer must stop on `FluxSink.onCancel`; a `blockLast()` inside it does not observe downstream cancel : docs/review-2026-10-09.md
- Reactor 3.8: `subscribeOn(scheduler)` defaults `requestOnSeparateThread=true`; a blocking `Flux.create` then starves Netty's one-at-a-time demand. Use `subscribeOn(scheduler, false)`; test with bounded demand, not StepVerifier's default : docs/roadmap.md
- Spring AI 2.0.1 is the current GA line (2.0.0 GA 2026-06-12) : docs/review-2026-10-09.md
- Vitest 5.0.3 is current (5.0 on 2026-09-03); the frontend is on 3.2.7, upgrade optional : docs/review-2026-10-09.md
- Docker's default seccomp blocks `unshare(CLONE_NEWUSER)`, so bubblewrap fails in a default container; Landlock (via `landrun`) works there unprivileged : docs/roadmap.md
- `landrun --best-effort` silently runs UNSANDBOXED on a kernel without Landlock; always probe that a denied path stays denied : docs/roadmap.md
- Landlock is allow-list only: it cannot deny a subdir inside an allowed dir, and it does not cover UDP (DNS works inside the sandbox) : docs/roadmap.md
- Java `PathMatcher` glob `**/*.kt` needs a `/`, so it misses root files; models expect it to match them : docs/roadmap.md
- Spring AI 2.0.1: `ToolCallingChatOptions.mutate()` gives a provider-neutral builder (`model`, `maxTokens`, `toolCallbacks`); the OpenAI/Anthropic SDK clients reject a blank API key, so a keyless local server needs a placeholder : docs/roadmap.md
- Ollama 0.34 also serves OpenAI-compatible (`/v1`) and Anthropic-compatible (`/v1/messages`) APIs, so all three adapters can be tested against one local model : docs/roadmap.md
- Model limits from list APIs: Anthropic `/v1/models` has `max_input_tokens`/`max_tokens` (20 per page by default); OpenAI `/v1/models` has none; Ollama needs `/api/show` per model (`model_info.*.context_length`); ollama.com needs `Authorization: Bearer` : docs/roadmap.md
- Spring AI 2.0.1 `MessageAggregator.aggregate(flux, cb)` passes the raw chunks through; only `cb` gets the merged response (all tool calls, usage). `blockLast()` returns the last raw chunk : docs/roadmap.md
- Spring AI Anthropic adapter: non-streaming `call()` fails ("`signature` is not set") on thinking blocks without a signature (Ollama's Anthropic-compatible API); streaming works : docs/roadmap.md
- A summarizer prompt whose input ends in the assistant's voice gets continued, not summarized (tool calls, invented values); quote it as a `<transcript>` with the instruction after it. Spring AI's `OllamaChatModel` throws an NPE when Ollama's tool parser fails and sends a chunk without `message` : docs/roadmap.md
- Spring AI 2.0.1: cancelling the stream Flux (`takeUntilOther`) closes the provider HTTP connection for the Ollama, OpenAI and Anthropic adapters (tested against a slow fake server) : docs/roadmap.md
- Spring AI 2.0.1 `OllamaChatModel` throws an NPE on Ollama's stream `{"error":...}` chunk (no `message`, e.g. a failed qwen tool-call parse); 2.0.2 null-checks it (PR #7066), which turns the error into a silent empty chunk : docs/roadmap.md
- Gradle 9 removed the JaCoCo `classExcludes` API; scope coverage rules by package instead : docs/roadmap.md
- react-markdown 10 treats a single newline as a space (CommonMark), so model replies like `1\n2\n3` collapse; `remark-breaks` restores chat-style line breaks. Raw HTML is skipped by default : docs/roadmap.md
- A WebSocket follower that gets only `event` frames for a run it never saw drops them; a new run must be announced (the `started` frame) before its events : docs/roadmap.md
- `@cloudflare/computer` 0.5.0 (2026-10-09) is a preview-only virtual filesystem for Cloudflare Durable Objects with Workers/container execution; its README marks it not for production and the project takes no outside PRs. claudius stays self-hosted (decision in M13) : docs/roadmap.md
