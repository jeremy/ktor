/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.utils.io.core.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.winhttp.*
import kotlin.coroutines.*

@OptIn(ExperimentalForeignApi::class)
internal suspend inline fun <T> Closeable.closeableCoroutine(
    state: WinHttpConnect,
    errorMessage: String,
    crossinline block: (CancellableContinuation<T>) -> Unit
): T = suspendCancellableCoroutine { continuation ->
    if (!continuation.isActive) {
        close()
        return@suspendCancellableCoroutine
    }

    continuation.invokeOnCancellation {
        close()
    }

    state.handlers[WinHttpCallbackStatus.RequestError.value] = { statusInfo, _ ->
        if (continuation.isActive) {
            val result = statusInfo!!.reinterpret<WINHTTP_ASYNC_RESULT>().pointed
            continuation.resumeOnceWithException(getWinHttpException(errorMessage, result.dwError))
        } else {
            close()
        }
    }

    state.handlers[WinHttpCallbackStatus.SecureFailure.value] = { statusInfo, _ ->
        if (continuation.isActive) {
            val securityCode = statusInfo!!.reinterpret<UIntVar>().pointed.value
            continuation.resumeOnceWithException(getWinHttpException(errorMessage, securityCode))
        } else {
            close()
        }
    }

    try {
        block(continuation)
    } catch (cause: Throwable) {
        if (continuation.isActive) {
            continuation.resumeOnceWithException(cause)
        } else {
            close()
        }
    }
}

/**
 * Resumes the continuation unless it was already resumed or cancelled.
 *
 * All operations of a connection share one handler per status, so [WinHttpCallbackStatus.RequestError]
 * may resume an operation before its own completion status arrives, or the other way around.
 * A second `resume` would throw inside the WinHTTP callback, where nothing catches it, and abort the process.
 */
@OptIn(InternalCoroutinesApi::class)
internal fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    tryResume(value)?.let { completeResume(it) }
}

/**
 * Resumes the continuation with [cause] unless it was already resumed or cancelled. See [resumeOnce].
 */
@OptIn(InternalCoroutinesApi::class)
internal fun CancellableContinuation<*>.resumeOnceWithException(cause: Throwable) {
    tryResumeWithException(cause)?.let { completeResume(it) }
}
