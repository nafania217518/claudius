package org.github.nafania217518.claudius.provider

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.ConcurrentHashMap

// M10: one ChatModel per provider, cached by id. ProviderService evicts on update/delete.
// None of these models runs tools itself (Spring AI 2.0), so AgentLoop keeps the tool loop.
@Component
class ChatModelFactory {
    private val cache = ConcurrentHashMap<String, ChatModel>()

    fun model(p: Provider): ChatModel = cache.computeIfAbsent(p.id) { build(p) }

    fun evict(id: String) {
        cache.remove(id)
    }

    // ollama.com (cloud) wants a bearer key on every call; a local server needs none. call()
    // goes through the RestClient, stream() through the WebClient, so both carry it.
    private fun ollamaApi(p: Provider): OllamaApi {
        val api = OllamaApi.builder().baseUrl(p.baseUrl)
        if (p.apiKey.isNotEmpty()) {
            val auth = "Bearer ${p.apiKey}"
            api.restClientBuilder(RestClient.builder().defaultHeader("Authorization", auth))
            api.webClientBuilder(WebClient.builder().defaultHeader("Authorization", auth))
        }
        return api.build()
    }

    // ponytail: a keyless OpenAI/Anthropic provider gets a placeholder key, because the SDK
    // clients refuse a blank one; a keyless local server ignores it.
    private fun build(p: Provider): ChatModel {
        val key = p.apiKey.ifEmpty { "none" }
        return when (p.type) {
            ProviderType.OLLAMA ->
                OllamaChatModel.builder()
                    .ollamaApi(ollamaApi(p))
                    .options(OllamaChatOptions.builder().model(p.defaultModel).build())
                    .build()
            ProviderType.OPENAI ->
                OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder().also { it.baseUrl(p.baseUrl); it.apiKey(key); it.model(p.defaultModel) }.build())
                    .build()
            ProviderType.ANTHROPIC ->
                AnthropicChatModel.builder()
                    .options(AnthropicChatOptions.builder().also { it.baseUrl(p.baseUrl); it.apiKey(key); it.model(p.defaultModel) }.build())
                    .build()
        }
    }
}
