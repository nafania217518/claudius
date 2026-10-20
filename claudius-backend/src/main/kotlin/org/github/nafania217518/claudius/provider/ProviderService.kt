package org.github.nafania217518.claudius.provider

import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

// → 502. The message never carries the API key.
class ModelListException(message: String) : RuntimeException(message)

// M10: provider CRUD + validation + model listing. Every change evicts the cached chat model.
@Service
class ProviderService(private val registry: ProviderRegistry, private val models: ChatModelFactory) {
    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val mapper = ObjectMapper()

    fun list(): List<Provider> = registry.list()

    fun find(id: String): Provider? = registry.find(id)

    fun create(req: ProviderRequest): Provider =
        save(null, req, "", System.currentTimeMillis()).also {
            registry.insert(it)
            req.models?.let { m -> registry.setModels(it.id, m) }
        }

    // apiKey null = keep the stored key; models null = keep the stored models.
    fun update(id: String, req: ProviderRequest): Provider {
        val old = get(id)
        return save(id, req, old.apiKey, old.createdAt).also {
            registry.update(it)
            req.models?.let { m -> registry.setModels(id, m) }
            models.evict(id)
        }
    }

    fun delete(id: String) {
        get(id)
        registry.delete(id)
        models.evict(id)
    }

    // The stored models of a provider, ordered by id.
    fun limits(id: String): List<ModelLimits> = registry.models(id)

    // The live model ids of a saved provider.
    fun models(id: String): List<String> = get(id).let { p -> fetch(p.type, p.baseUrl, p.apiKey).map { it.id } }

    // Pre-save fetch. A blank base url = the type default; a null key with a providerId = its stored key.
    fun fetch(req: ModelFetchRequest): List<ModelLimits> {
        val key = req.apiKey ?: req.providerId?.let { get(it).apiKey }.orEmpty()
        return fetch(req.type, req.baseUrl?.trim().orEmpty().ifEmpty { req.type.defaultBaseUrl }, key)
    }

    // A response of the wrong shape (missing field, non-array) is a ModelListException too.
    private fun fetch(type: ProviderType, baseUrl: String, key: String): List<ModelLimits> =
        try {
            list(type, baseUrl, key)
        } catch (e: ModelListException) {
            throw e
        } catch (e: Exception) {
            throw ModelListException("the provider returned an unexpected model list")
        }

    // Sorted by id, with the limits the API reports. Any HTTP/parse failure → ModelListException.
    // ponytail: the response body is never echoed — a server may reflect the key back.
    private fun list(type: ProviderType, baseUrl: String, key: String): List<ModelLimits> {
        val base = baseUrl.trimEnd('/')
        val auth: (HttpRequest.Builder) -> HttpRequest.Builder = { b ->
            when (type) {
                ProviderType.OLLAMA, ProviderType.OPENAI -> if (key.isNotEmpty()) b.header("Authorization", "Bearer $key") else b
                ProviderType.ANTHROPIC -> b.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            }
        }
        fun get(url: String) = auth(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET()).build()
        val list = when (type) {
            ProviderType.OPENAI ->
                json(get("$base/models")).get("data").values().map {
                    ModelLimits(it.get("id").asString(), it.get("context_length").int(), it.get("top_provider")?.get("max_completion_tokens").int())
                }
            ProviderType.ANTHROPIC -> {
                val all = mutableListOf<ModelLimits>()
                var after: String? = null
                do {
                    val page = json(get("$base/v1/models?limit=1000" + after?.let { "&after_id=$it" }.orEmpty()))
                    page.get("data").values().mapTo(all) {
                        ModelLimits(it.get("id").asString(), it.get("max_input_tokens").int(), it.get("max_tokens").int())
                    }
                    after = page.get("last_id")?.asString()
                } while (page.get("has_more")?.asBoolean() == true)
                all
            }
            ProviderType.OLLAMA -> {
                val names = json(get("$base/api/tags")).get("models").values().map { it.get("name").asString() }
                // ponytail: one /api/show call per model (all in parallel); fine for a local
                // server's handful of models, batch or cache if a catalogue gets large.
                names.map { name ->
                    val body = mapper.writeValueAsString(mapOf("model" to name))
                    val req = auth(HttpRequest.newBuilder(URI.create("$base/api/show")).timeout(TIMEOUT))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
                    name to http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                }.map { (name, future) ->
                    val info = parse(guard { future.join() })?.get("model_info")
                    val ctx = info?.properties()?.firstOrNull { it.key.endsWith(".context_length") }?.value
                    ModelLimits(name, ctx.int(), null)
                }
            }
        }
        return list.sortedBy { it.id }
    }

    private fun json(req: HttpRequest): JsonNode = parse(guard { http.send(req, HttpResponse.BodyHandlers.ofString()) })

    private fun guard(call: () -> HttpResponse<String>): HttpResponse<String> =
        try {
            call()
        } catch (e: Exception) {
            throw ModelListException("cannot reach the provider: ${e.javaClass.simpleName}")
        }

    private fun parse(res: HttpResponse<String>): JsonNode {
        if (res.statusCode() != 200) throw ModelListException("the provider returned HTTP ${res.statusCode()}")
        return try {
            mapper.readTree(res.body())
        } catch (e: Exception) {
            throw ModelListException("the provider returned an unexpected model list")
        }
    }

    private fun JsonNode?.int(): Int? = this?.takeIf { it.isNumber }?.asInt()

    private fun get(id: String) = registry.find(id) ?: throw NoSuchElementException("unknown provider: $id")

    private fun save(id: String?, req: ProviderRequest, oldKey: String, createdAt: Long): Provider {
        req.models?.let { m ->
            require(m.all { it.id.isNotBlank() }) { "model ids must not be blank" }
            require(m.map { it.id }.toSet().size == m.size) { "model ids must be unique" }
            require(m.all { (it.contextWindow ?: 1) > 0 && (it.maxOutputTokens ?: 1) > 0 }) { "model limits must be positive" }
        }
        require(req.name.isNotBlank()) { "provider name must not be blank" }
        require(registry.list().none { it.name == req.name && it.id != id }) { "a provider named '${req.name}' already exists" }
        val url = req.baseUrl?.trim().orEmpty().ifEmpty { req.type.defaultBaseUrl }
        require(url.startsWith("http://") || url.startsWith("https://")) { "base url must start with http:// or https://" }
        return Provider(
            id ?: UUID.randomUUID().toString(), req.name, req.type, url, req.apiKey ?: oldKey,
            req.defaultModel.orEmpty(), createdAt,
        )
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
