package org.github.nafania217518.claudius

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient

// Single container: the image copies the frontend build into classpath:/static, and the
// backend serves it next to /api. The test classpath carries a stand-in index.html.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class FrontendServingTest {
    @Autowired
    lateinit var client: WebTestClient

    @Test
    fun `the backend serves the frontend index page at the root`() {
        client.get().uri("/").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .consumeWith { it.responseBody!! shouldContain "claudius-test-index" }
    }
}
