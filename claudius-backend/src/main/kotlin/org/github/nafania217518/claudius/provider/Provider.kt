package org.github.nafania217518.claudius.provider

// M10: one configured model endpoint. apiKey is "" when the provider needs none.
data class Provider(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val createdAt: Long,
)

// One model of a provider and its limits, as the provider's API reports them (null = unknown).
// maxOutputTokens caps a chat reply; contextWindow is stored for M11 compaction.
data class ModelLimits(val id: String, val contextWindow: Int?, val maxOutputTokens: Int?)

// defaultBaseUrl: used when a provider is saved with a blank baseUrl. The OpenAI one
// includes /v1 (the openai-java client expects it).
enum class ProviderType(val defaultBaseUrl: String) {
    OLLAMA("http://localhost:11434"),
    OPENAI("https://api.openai.com/v1"),
    ANTHROPIC("https://api.anthropic.com"),
}

// The wire shape. apiKey is MASKED (see mask): the raw key never leaves the backend.
data class ProviderDto(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val models: List<ModelLimits>,
    val createdAt: Long,
)

// POST/PUT body. apiKey null = keep the stored key (PUT); "" = clear it.
// models null = keep the stored models; a list replaces them all.
data class ProviderRequest(
    val name: String,
    val type: ProviderType,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val defaultModel: String? = null,
    val models: List<ModelLimits>? = null,
)

// POST /api/providers/models: list a provider's models before it is saved. apiKey null with a
// providerId = that provider's stored key, so an edit never needs the raw key in the browser.
data class ModelFetchRequest(
    val type: ProviderType,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val providerId: String? = null,
)

// "" when no key; "…" + last 4 chars; keys shorter than 8 show "…" only.
fun mask(key: String): String =
    when {
        key.isEmpty() -> ""
        key.length < 8 -> "…"
        else -> "…" + key.takeLast(4)
    }

fun Provider.toDto(models: List<ModelLimits>) = ProviderDto(id, name, type, baseUrl, mask(apiKey), defaultModel, models, createdAt)
