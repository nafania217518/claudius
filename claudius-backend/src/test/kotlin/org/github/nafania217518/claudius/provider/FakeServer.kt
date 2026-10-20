package org.github.nafania217518.claudius.provider

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections

// A local HTTP endpoint on port 0. route answers each request with (status, body); every
// request is kept in `requests` for assertions.
class FakeServer(private val route: (Req) -> Pair<Int, String>) : AutoCloseable {
    data class Req(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String)

    // The same answer for every request.
    constructor(status: Int, body: String) : this({ status to body })

    val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
    val path get() = requests.last().path
    val headers get() = requests.last().headers

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val req = Req(
                ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery.orEmpty(),
                ex.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() },
                ex.requestBody.readAllBytes().decodeToString(),
            )
            requests.add(req)
            val (status, body) = route(req)
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
