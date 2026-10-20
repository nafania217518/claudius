package org.github.nafania217518.claudius.chat

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// The chunk is mostly constructed with a payload; this pins the default-payload path
// (a chunk carrying no text) so the firehose's shape stays honest.
class StreamChunkTest {

    @Test
    fun `a chunk with no payload defaults text to empty`() {
        val c = StreamChunk(StreamChunk.Kind.TOKEN)
        c.kind shouldBe StreamChunk.Kind.TOKEN
        c.text shouldBe ""
    }
}
