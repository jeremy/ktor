/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.test.base.*
import io.ktor.http.websocket.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * When the server drops the connection while an operation is in flight, WinHTTP may report both an error
 * and the completion of that operation. Before the fix, the second status resumed the operation's continuation
 * again inside the WinHTTP callback and the process aborted with an `IllegalStateException`.
 */
class WinHttpConnectionLossTest : ClientEngineTest<WinHttpClientEngineConfig>(WinHttp) {

    @Test
    fun `request fails when the server closes the connection in the middle of the body`() = testClient {
        test { client ->
            withServer(
                respond = { _, _, output ->
                    output.writeStringUtf8("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n")
                    output.writeFully(ByteArray(1_000))
                }
            ) { port ->
                repeat(ATTEMPTS) {
                    assertFails { client.get("http://$HOST:$port/").bodyAsText() }
                }
            }
        }
    }

    @Test
    fun `websocket session ends when the server closes the connection while frames are sent`() = testClient {
        config {
            install(WebSockets)
        }

        test { client ->
            withServer(
                respond = { request, input, output ->
                    val key = request.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                        .substringAfter(':')
                        .trim()
                    output.writeStringUtf8(
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: ${websocketServerAccept(key)}\r\n\r\n"
                    )
                    output.flush()
                    // Drop the connection only once the client has started sending frames.
                    input.awaitContent()
                }
            ) { port ->
                var sessions = 0
                repeat(ATTEMPTS) {
                    try {
                        client.webSocket("ws://$HOST:$port/") {
                            sessions++
                            repeat(FRAMES) { send(Frame.Text("hello")) }
                            // Stay in the session until it hears that the server is gone; the server sends nothing.
                            assertTrue(incoming.receiveCatching().isClosed)
                        }
                    } catch (_: Exception) {
                        // The server is gone, which the client may notice at any point of the session.
                    }
                    currentCoroutineContext().ensureActive()
                }
                assertTrue(sessions > 0, "No WebSocket session was opened")
            }
        }
    }

    /**
     * Runs a server on a random port that reads each request's head, writes what [respond] writes,
     * and closes the connection as soon as [respond] returns, leaving whatever the client sent since unread.
     */
    private suspend fun withServer(
        respond: suspend (request: List<String>, input: ByteReadChannel, output: ByteWriteChannel) -> Unit,
        block: suspend (port: Int) -> Unit,
    ) = coroutineScope {
        SelectorManager(Dispatchers.IO).use { selector ->
            aSocket(selector).tcp().bind(HOST, 0).use { server ->
                val serving = launch {
                    while (isActive) {
                        val socket = server.accept()
                        launch {
                            try {
                                socket.use {
                                    val input = it.openReadChannel()
                                    val request = buildList {
                                        while (true) {
                                            val line = input.readLine()
                                            if (line.isNullOrEmpty()) break
                                            add(line)
                                        }
                                    }
                                    val output = it.openWriteChannel()
                                    respond(request, input, output)
                                    output.flushAndClose()
                                }
                            } catch (cause: Exception) {
                                if (cause is CancellationException) throw cause
                                // The client may drop the connection first, which is no failure of the server.
                            }
                        }
                    }
                }
                try {
                    block((server.localAddress as InetSocketAddress).port)
                } finally {
                    serving.cancel()
                }
            }
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val ATTEMPTS = 20
        const val FRAMES = 100
    }
}
