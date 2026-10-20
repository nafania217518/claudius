package org.github.nafania217518.claudius.provider

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.openai.OpenAiChatModel

class ChatModelFactoryTest {
    private fun p(id: String, type: ProviderType, key: String = "", model: String = "m") =
        Provider(id, id, type, type.defaultBaseUrl, key, model, 0)

    @Test
    fun `the factory builds an Ollama, OpenAI or Anthropic chat model for the provider type`() {
        val f = ChatModelFactory()
        f.model(p("o", ProviderType.OLLAMA, model = "qwen")).also {
            it.shouldBeInstanceOf<OllamaChatModel>()
            it.defaultOptions.model shouldBe "qwen"
        }
        f.model(p("a", ProviderType.OPENAI, "sk-1", "gpt")).also {
            it.shouldBeInstanceOf<OpenAiChatModel>()
            it.defaultOptions.model shouldBe "gpt"
        }
        // A provider without a key still builds (a keyless OpenAI-compatible server).
        f.model(p("n", ProviderType.OPENAI)).shouldBeInstanceOf<OpenAiChatModel>()
        f.model(p("c", ProviderType.ANTHROPIC, "ak-1", "claude")).also {
            it.shouldBeInstanceOf<AnthropicChatModel>()
            it.defaultOptions.model shouldBe "claude"
        }
    }

    @Test
    fun `an updated provider gets a fresh chat model`() {
        val f = ChatModelFactory()
        val first = f.model(p("o", ProviderType.OLLAMA))
        f.model(p("o", ProviderType.OLLAMA)) shouldBeSameInstanceAs first
        f.evict("o")
        f.model(p("o", ProviderType.OLLAMA, model = "new")).also {
            it shouldNotBeSameInstanceAs first
            it.defaultOptions.model shouldBe "new"
        }
    }
}
