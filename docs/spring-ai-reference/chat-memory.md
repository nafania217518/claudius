# Spring AI ChatMemory — reference notes (v2.0.1)

Terse API notes for `claudius` backend. Source: `docs.spring.io/spring-ai/reference/api/chat-memory.html`.
Spring AI **2.0.1**. This is a reference, not a copy of the docs.

> Key distinction the docs draw: **Chat Memory** = what the LLM is shown for context;
> **Chat History** = full transcript. `ChatMemory` is *not* a transcript store — for full
> history the docs say "rely on Spring Data". Every `ChatMemory` op is keyed by a
> conversation ID (`ChatMemory.CONVERSATION_ID` advisor param, required; no default).

## `ChatMemory` interface

Storage is a separate `ChatMemoryRepository` (store/retrieve only); the `ChatMemory`
implementation decides what to keep. Docs show two operations:

```kotlin
chatMemory.add(conversationId, message)      // append
val msgs: List<Message> = chatMemory.get(conversationId)   // oldest-to-newest
// plus delete/conversation-id listing per repository
```

## Built-in implementations

- **`MessageWindowChatMemory`** (the only built-in `ChatMemory`, also the autoconfigured
  default). Sliding window, `maxMessages` default **20**.
  - Eviction is at **turn boundaries**: a turn = `UserMessage` + all following assistant
    replies / tool calls / tool responses up to the next `UserMessage`; the cut snaps
    forward to the next `UserMessage`. So `maxMessages` is an *upper bound*.
  - `SystemMessage` instances are always preserved.
  - If `maxMessages` < one full tool-calling turn, all non-system messages may be evicted
    until a new `UserMessage` arrives — size the window generously.
  - Builder: `MessageWindowChatMemory.builder().maxMessages(n).chatMemoryRepository(repo).build()`
- **`VectorStoreChatMemoryAdvisor`** — not a `ChatMemory` impl; retrieves from a
  `VectorStore` and appends history to the system message as *plain text* (custom
  `PromptTemplate` with `instructions` + `long_term_memory` placeholders).

## Repositories (`ChatMemoryRepository`)

- **`InMemoryChatMemoryRepository`** — `ConcurrentHashMap`, autoconfigured default.
- **`JdbcChatMemoryRepository`** — table `SPRING_AI_CHAT_MEMORY`, `sequence_id` (since 2.0.0)
  for ordering + `timestamp` (exposed per-message via
  `JdbcChatMemoryRepository.CONVERSATION_TS` metadata, `java.time.Instant`); dialects:
  Postgres/MySQL/MariaDB/SQL Server/HSQLDB/Oracle; `JdbcChatMemoryRepositoryDialect.from(DataSource)`.
- **`CassandraChatMemoryRepository`** — time-series schema (all past windows, TTL-recommended);
  **`Neo4jChatMemoryRepository`** — nodes/rels, per-message labels incl. `ToolCall`/`ToolResponse`.
- **`CosmosDBChatMemoryRepository`** — external Azure module.
- **`MongoChatMemoryRepository`**, **`RedisChatMemoryRepository`** (Redis Stack 7+,
  `AdvancedRedisChatMemoryRepository` search: `findByType`/`findByContent`/`findByTimeRange`/
  `findByMetadata`/`executeQuery`; TTL support).
- **⚠ `Jdbc`, `Cassandra`, and `Mongo` repositories silently drop tool-call messages**
  (`AssistantMessage` with tool calls + `ToolResponseMessage` filtered on save). Only
  Neo4j (and the external Spring AI *Session* project, e.g. its JDBC session store) persist
  all message types.
- `ChatClient` advisor path (`MessageChatMemoryAdvisor`): "intermediate messages exchanged
  with an LLM when performing tool calls are **not stored** in the memory" — a known
  limitation of the autoconfigured path.

## claudius mapping

- **§2.2 session history → `MessageWindowChatMemory` is NOT the orchestrator.** It's a
  keyed, stateless store (`conversationId → messages`), not the per-session stateful object
  §2.2 wants (mutable history + abort + denial log + total usage + `FileStateCache` in one
  `QueryEngine` instance). claude-code's `QueryEngine` holds the history itself; keep that.
  Spring memory is at most the *storage backend* (e.g. transcript persistence for the
  "review full history" story), and then:
  - JDBC/Cassandra/Mongo repos **break invariant 4** (`tool_use`/`tool_result` pairs must
    survive) by dropping tool messages — Neo4j or a custom repo only, or just store the
    transcript via Spring Data as the docs suggest, separate from `ChatMemory` entirely.
- **§2.6 compaction cascade interoperates by ownership, not by configuration.**
  `MessageWindowChatMemory`'s windowing is last-N trimming; it cannot express the cascade
  (snip → microcompact → context-collapse → autocompact, circuit breaker, LLM-free fast
  path). claude-code **owns the cascade**; Spring memory is best used as the repository the
  cascade writes the *reduced* history into. Two concrete conflicts to avoid:
  1. Windowing evicts by message count, not tokens — §2.6's threshold is
     context-window − 13,000 tokens. Don't let Spring's `maxMessages` trim at all: size it
     above any turn (or above the window) and let `CompactStrategy` be the only trims.
  2. The LLM-free fast path (prune session-memory notes, no LLM call) = mutate the list,
     then `clear(conversationId)` + re-`add` the pruned list, or skip Spring memory
     mid-session and sync at boundaries.
- **§2.7 project-memory store is NOT `ChatMemory`.** Docs' own framing agrees: `ChatMemory`
  is per-conversation context, not durable project knowledge. `MemoryService` (§2.7: table
  or config dir keyed by project, `MEMORY.md`-style index + topic files, `findRelevantMemories`
  recall) is a separate component — a `VectorStore` + metadata index is the natural Spring
  home for the recall side, but it is not `ChatMemory` and not a repository.

**Architecture.md flag:** none needed — §2.2/§2.7 make no claim about Spring's `ChatMemory`
and the docs back "roll the orchestrator, store the transcript separately". One caveat for
when §2.2 lands: `InMemoryChatMemoryRepository` is per-process (lost on restart); a
session registry keyed in memory has the same property.
