# Spring AI Embeddings — reference notes (v2.0.1)

Terse API notes for `claudius` backend. Source: `embeddings.html` (Spring AI **2.0.1**).
Reference, not a copy of the docs. Backs Architecture §2.7 (Memory recall).

## `EmbeddingModel` interface

`public interface EmbeddingModel extends Model<EmbeddingRequest, EmbeddingResponse>`
— text/images/video → `float[]` vectors. Goals: portability + simplicity.

```java
EmbeddingResponse call(EmbeddingRequest request);       // primary; everything wraps this
float[] embed(Document document);                        // embed a Document
float[] embed(String text);                              // single string
List<float[]> embed(List<String> texts);                 // batch
EmbeddingResponse embedForResponse(List<String> texts);  // batch + metadata
String getEmbeddingContent(Document document);           // default: document.getText()
int dimensions();                                        // vector length, model-specific
```

- `getEmbeddingContent` default returns `Document.getText()`; providers supporting
  `MetadataMode` (OpenAI, Mistral) override it to embed `getFormattedContent(MetadataMode)`.
  This is the path `VectorStore` batch embedding uses.
- `embed(String)` → `embed(List.of(text)).iterator().next()`. `dimensions()` =
  `embed("Test String").size()`.

## Request / response

```java
EmbeddingRequest    // ModelRequest<List<String>>: inputs + EmbeddingOptions
EmbeddingResponse   // List<Embedding> + EmbeddingResponseMetadata
Embedding           // ModelResult<float[]>: float[] embedding + Integer index + EmbeddingResultMetadata
```

## Provider beans (starter auto-configures a bean; add one starter)

| Provider | Class | Starter module (approx) |
| --- | --- | --- |
| OpenAI | `OpenAiEmbeddingModel` (`text-embedding-ada-002`, or v3 w/ custom dims) | `spring-ai-starter-model-openai` |
| Ollama | `OllamaEmbeddingModel` | `...-ollama` |
| Bedrock | Titan / Cohere embedders | `...-bedrock` |
| Vertex AI | `VertexAiTextEmbeddingModel` | `...-vertex-ai-embedding` |
| Mistral / Transformers(ONNX) / PostgresML | same-named `*EmbeddingModel` | respective starters |

- `dimensions()` is model-specific: e.g. `text-embedding-ada-002` = 1536; OpenAI v3 lets you
  request a custom dimension; Vertex `auto_truncate` changes behavior. Fix the vector-store
  dimension to your chosen model's `dimensions()`.

## Store + query

Store: `vectorStore.add(List<Document>)` computes embeddings and persists. Query:
`vectorStore.similaritySearch(...)` (see `vector-databases.md`). Store does **not** generate
embeddings itself — it holds an `EmbeddingModel`. Batching of large loads is via
`BatchingStrategy` / `TokenCountBatchingStrategy` (default OpenAI 8191 tokens, 10% reserve).

## claudius mapping (§2.7 recall)

- **Recall = embed the memory-file *headers*, store them in a `VectorStore`, query with a
  topK cap.** §2.7's "≤5 cap" → `SearchRequest.builder().topK(5)`. Its "selectivity bias"
  (find only the most certain memories) → `similarityThreshold(t)`; a threshold rejects
  weak matches so you get fewer, higher-certainty hits.
- To "search over the headers" specifically, make the header the `Document` *content* (or
  store header as content + body in metadata) — `similaritySearch` returns whole
  `Document`s, not just a field. The index file (MEMORY.md-style) maps to the
  `EmbeddingModel`-free path: store only the index lines, not bodies.
- **Keyword-first, not embeddings-first.** Spring AI has no first-class hybrid search in
  this page; keyword = a metadata/filter or full-text query, embeddings =
  `similaritySearch`. For a small header index, a plain full-text/keyword match over the
  index lines is the laziest correct first step (no embedding model, no vector store, no
  dimension matching). **Adopt keyword search for §2.7 recall; add a `VectorStore` +
  `EmbeddingModel` only when keyword precision proves insufficient.** Defer team-sync /
  autoDream per §5.

### Flags for §2.7
- `topK` **defaults to 4** (`SearchRequest.DEFAULT_TOP_K`), **not 5** — set `topK(5)`
  explicitly for the ≤5 cap.
- No built-in "min-certainty"/"selectivity bias" gate — `similarityThreshold` is the only
  primitive; document it as that.
- **Do not build `MemoryService` on `ChatMemory`/`ChatMemoryRepository`** — those are for
  *conversation* memory, not the project MEMORY.md index (see `chat-memory.md`). §2.7's
  store is a `VectorStore` (or keyword index), not chat memory.
- 0o600 perms + secret-scan-before-sync are **claudius** concerns, not Spring AI's — don't
  delegate them to the store.
