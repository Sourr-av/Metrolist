/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import com.metrolist.shared.utils.getCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

sealed interface CircuitCallResult<out T> {
    data class Success<T>(
        val value: T,
    ) : CircuitCallResult<T>

    data object CircuitOpen : CircuitCallResult<Nothing>

    data object TimedOut : CircuitCallResult<Nothing>

    data object Failed : CircuitCallResult<Nothing>
}

/**
 * Consecutive-failure circuit breaker for external L5 calls.
 * Opens after [failureThreshold] consecutive failures or timeouts and stays open for [openDurationMs].
 */
class CircuitBreaker(
    private val failureThreshold: Int = SpaceRecConfig.Default.circuitBreakerFailureThreshold,
    private val openDurationMs: Long = SpaceRecConfig.Default.circuitBreakerOpenDurationMs,
    private val clock: () -> Long = ::getCurrentTimeMillis,
) {
    private val mutex = Mutex()
    private var consecutiveFailures: Int = 0
    private var openedAtMs: Long? = null

    @Volatile
    private var openSnapshot: Boolean = false

    val isOpenNow: Boolean
        get() {
            val openedAt = openedAtMs ?: return false
            val stillOpen = (clock() - openedAt) < openDurationMs
            if (!stillOpen) openSnapshot = false
            return stillOpen
        }

    suspend fun isOpen(): Boolean =
        mutex.withLock {
            val openedAt = openedAtMs ?: return@withLock false
            if (clock() - openedAt < openDurationMs) {
                openSnapshot = true
                true
            } else {
                openedAtMs = null
                consecutiveFailures = 0
                openSnapshot = false
                false
            }
        }

    suspend fun recordSuccess() {
        mutex.withLock {
            consecutiveFailures = 0
            openedAtMs = null
            openSnapshot = false
        }
    }

    suspend fun recordFailure() {
        mutex.withLock {
            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold) {
                openedAtMs = clock()
                openSnapshot = true
            }
        }
    }

    suspend fun <T> execute(
        timeoutMs: Long,
        block: suspend () -> T?,
    ): CircuitCallResult<T> {
        if (isOpen()) return CircuitCallResult.CircuitOpen
        return try {
            val result = withTimeout(timeoutMs) { block() }
            if (result == null) {
                recordFailure()
                CircuitCallResult.Failed
            } else {
                recordSuccess()
                CircuitCallResult.Success(result)
            }
        } catch (_: TimeoutCancellationException) {
            recordFailure()
            CircuitCallResult.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            recordFailure()
            CircuitCallResult.Failed
        }
    }
}
