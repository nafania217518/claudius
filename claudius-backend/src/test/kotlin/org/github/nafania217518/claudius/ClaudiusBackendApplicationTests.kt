package org.github.nafania217518.claudius

import io.kotest.matchers.shouldBe
import org.github.nafania217518.claudius.provider.ProviderService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path

// M10: no model starter. Chat models are built per provider at request time, so the
// context needs no API key (and no reachable model server) to start.
@SpringBootTest
class ClaudiusBackendApplicationTests {
    @Autowired
    lateinit var providers: ProviderService

    @Test
    fun `the context loads without any provider api key`() {
        providers.list().map { it.name } shouldBe listOf("Ollama")
    }

    companion object {
        @TempDir
        @JvmStatic
        lateinit var dir: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(r: DynamicPropertyRegistry) {
            r.add("claudius.workspaces-dir") { dir.toString() }
        }
    }
}
