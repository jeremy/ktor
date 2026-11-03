/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import platform.windows.*
import platform.windows.HINTERNET
import platform.winhttp.*

@OptIn(ExperimentalForeignApi::class)
internal enum class WinHttpCallbackStatus(val value: UInt) {
    SecureFailure(WINHTTP_CALLBACK_STATUS_SECURE_FAILURE.convert<UInt>()),
    HeadersAvailable(WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE.convert<UInt>()),
    DataAvailable(WINHTTP_CALLBACK_STATUS_DATA_AVAILABLE.convert<UInt>()),
    ReadComplete(WINHTTP_CALLBACK_STATUS_READ_COMPLETE.convert<UInt>()),
    WriteComplete(WINHTTP_CALLBACK_STATUS_WRITE_COMPLETE.convert<UInt>()),
    RequestError(WINHTTP_CALLBACK_STATUS_REQUEST_ERROR.convert<UInt>()),
    SendRequestComplete(WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE.convert<UInt>()),
    CloseComplete(WINHTTP_CALLBACK_STATUS_CLOSE_COMPLETE.convert<UInt>()),
    HandleClosing(WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING.convert<UInt>())
}

@OptIn(ExperimentalForeignApi::class)
internal fun winHttpCallback(
    @Suppress("UNUSED_PARAMETER")
    hInternet: HINTERNET?,
    dwContext: DWORD_PTR,
    dwStatus: DWORD,
    statusInfo: LPVOID?,
    statusInfoLength: DWORD
) {
    val contextPtr = dwContext.toLong().toCPointer<COpaque>() ?: return

    val context = contextPtr.asStableRef<WinHttpHandleContext>().get()
    if (dwStatus == WinHttpCallbackStatus.HandleClosing.value) {
        context.dispose()
        return
    }

    val connect = context.connect
    if (context.isClosed || connect.isClosed) {
        return
    }

    connect.handlers[dwStatus]?.invoke(statusInfo, statusInfoLength)
}

/**
 * The context of a single WinHTTP handle, passed back to [winHttpCallback] with every status of that handle.
 *
 * WinHTTP keeps calling back after `WinHttpCloseHandle` returns, for example to complete
 * a WebSocket receive cancelled by the close, so the context must outlive the close.
 * It is disposed on [WinHttpCallbackStatus.HandleClosing], the last status WinHTTP sends for a handle.
 */
@OptIn(ExperimentalForeignApi::class)
internal class WinHttpHandleContext(val connect: WinHttpConnect) {
    private val reference = StableRef.create(this)
    private val closed = atomic(false)

    val pointer: Long
        get() = reference.asCPointer().rawValue.toLong()

    /**
     * Whether the handle was closed, after which its statuses are ignored.
     */
    val isClosed: Boolean
        get() = closed.value

    fun close() {
        closed.value = true
    }

    fun dispose() {
        reference.dispose()
    }
}
