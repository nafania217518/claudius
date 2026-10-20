# Spring AI Vector Databases — reference notes (v2.0.1)

Terse API notes for `claudius` backend. Source: `vectordbs.html` (Spring AI **2.0.1**).
Reference, not a copy of the docs. Backs Architecture §2.7 (Memory store + recall).

## `VectorStore` interface

`public interface VectorStore extends DocumentWriter, VectorStoreRetriever`

```java
void add(List<Document> documents);
void delete(List<String> idList);
void delete(Filter.Expression filterExpression);
void delete(String filterExpression);            // convenience, parsed internally
String getName();
<T> Optional<T> getNativeClient();               // escape hatch; default empty
```

`VectorStoreRetriever` (read-only, `@FunctionalInterface`):

```java
List<Document> similaritySearch(SearchRequest request);
List<Document> similaritySearch(String query);    // default → topK 4, threshold 0.0
```

Use `VectorStoreRetriever` in read-only components (principle of least privilege).

## `SearchRequest` (builder)

```java
SearchRequest.builder()
    .query("...")
    .topK(5)                 // max results; DEFAULT_TOP_K = 4
    .similarityThreshold(0.7)// [0,1]; 0.0 = SIMILARITY_THRESHOLD_ACCEPT_ALL
    .filterExpression("project == 'foo'")   // or Filter.Expression
    .build();
SearchRequest.builder().from(existing)       // clone+mutate
```

- `similarityThreshold`: closer to 1 = stricter. `similarityThresholdAll()` = 0.0.
- Result = `List<Document>`. Documents carry `id`, `content`/`getText()`, and
  `metadata` (key→value Map).

## Metadata filters (project-keying + grouping)

Two forms:
- **String DSL** (SQL-like): `"country == 'BG'"`, `"genre == 'drama' && year >= 2020"`,
  `"genre in ['comedy','documentary']"`.
- **`FilterExpressionBuilder`** fluent: `b.eq("k","v").gte("y",2020).in("g",...).not(b.lt("y",2020))`;
  operators `== != > >= < <= in nin not is/null`.

**Partitioning a shared index** = the §2.7 project-keying pattern:
```java
Document d = new Document(header, Map.of("project", projectId, "type", "user"));
vectorStore.add(List.of(d));
// search/delete scoped to the project:
vectorStore.similaritySearch(SearchRequest.builder()
    .query(q).topK(5).filterExpression("project == '" + projectId + "'").build());
vectorStore.delete(new FilterExpressionBuilder().eq("project", projectId).build());
```
- `IS NULL` / `IS NOT NULL` not implemented in *all* stores — avoid for the initial design.
- Schema init is opt-in (constructor bool or `*.initialize-schema=true`); check per store.

## Lightweight stores (choose ONE; ~20 providers exist, skip the rest)

| Store | Friction | topK | metadata filter | Notes |
| --- | --- | --- | --- | --- |
| `SimpleVectorStore` | none (in-process) | yes | yes | **test/demo only, NOT production** — no persistence |
| **Redis** | one service | yes | yes (`@meta` filters, full-text) | lightest real store; Redis Stack 7+ (RQE+RedisJSON) |
| **PgVector** | Postgres | yes | yes (SQL `WHERE`) | pick if Postgres already in stack; `PgVectorStore.builder(jdbcTemplate, embeddingModel)` |
| Chroma | dedicated service | yes | yes | more ops; standalone vector DB |
| Qdrant | dedicated service | yes | yes | more ops; standalone vector DB |
| S3 | AWS only | yes | limited | AWS S3 Vectors; cloud-locked |

Also exist: Azure, Cassandra, Elasticsearch, Milvus, MongoDB, Neo4j, OpenSearch, Oracle,
Weaviate, Typesense, GemFire — higher-ops; skip for a small project.

## claudius mapping (§2.7 store)

- **Recommended first step: do NOT stand up a vector store yet.** §2.7 recall = "keyword /
  embedding over the headers, ≤5 cap, selectivity bias." For a small header index a
  **keyword/full-text search over the MEMORY.md index lines is the least-ops option** —
  no embedding model, no vector DB, no dimension matching. Add `topK(5)` + a threshold-like
  relevance cutoff to enforce the cap + selectivity.
- **When embeddings are needed, pick the least-ops store that supports topK + metadata
  filter** (for `project`-keying + ≤5 cap):
  - **Redis `RedisVectorStore`** — single lightweight service, metadata filter, topK.
  - **PgVector `PgVectorStore`** — *only if Postgres is already in the stack* (no new
    service); also gives SQL full-text as the keyword path.
  - Avoid Chroma/Qdrant (dedicated services) and `SimpleVectorStore` (not persistent).
- Project-keying = a `project` metadata key on every `Document`, filtered on every
  search/delete (the "partitioning a shared index" pattern above).
- `topK(5)` explicit — default is **4**, not 5.
- Defer (per §5): team sync, autoDream, and swapping the store for a richer one.
- 0o600 file perms + secret scan before sync are **claudius** concerns, not the store's.

### Flags for §2.7
- §2.7 implies a persistent store, but the *laziest correct* first store is keyword
  search over the index (no vector infra). State this explicitly so we don't add a
  `VectorStore` + `EmbeddingModel` before keyword precision is shown insufficient.
- `SearchRequest.DEFAULT_TOP_K` is 4 → the "≤5 cap" must be set as `topK(5)`, not assumed.
- No built-in "selectivity bias" — map it to `similarityThreshold`, or to a keyword
  relevance cutoff.

## (skim) `chat-memory.html` — keep minimal, own file later

`ChatMemory` / `ChatMemoryRepository` = **conversation** memory, NOT the §2.7 project
index. Default: `MessageWindowChatMemory` (sliding window, default 20 msgs, turn-boundary
eviction, preserves `SystemMessage`) + `InMemoryChatMemoryRepository` (not persistent).
Keyed by `ChatMemory.CONVERSATION_ID` (required; no default). Repositories: JDBC
(`JdbcChatMemoryRepository`, `SPRING_AI_CHAT_MEMORY` table, **drops tool-call messages**),
Cassandra/Neo4j/Mongo/Redis (tool-call msgs filtered except Neo4j). Advisories:
`MessageChatMemoryAdvisor`, `VectorStoreChatMemoryAdvisor`. → **not** the §2.7 store.
