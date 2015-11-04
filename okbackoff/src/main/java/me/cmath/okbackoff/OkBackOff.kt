/*
 * Copyright (c) 2015 Connor Mathews
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.cmath.okbackoff

import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Interceptor
import com.squareup.okhttp.Interceptor.Chain
import com.squareup.okhttp.Request
import com.squareup.okhttp.Response
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * An [OkHttpClient] [Interceptor] that will retry calls with a back off as
 * specified by a [Policy].
 *
 * [DefaultExponentialBackOffPolicy] is forked from the Google Java Http Client.
 * https://github.com/google/google-http-java-client
 *
 * This interceptor must be used as with [OkHttpClient.interceptors] because it
 * might call [Chain.proceed] multiple times per intercept.
 *
 * Retry and back off can be customized by inheriting [Policy] and overriding
 * [Policy.retryRequired], [Policy.backOffRequired] and
 * [Policy.nextBackOffInterval].
 *
 * This implementation attempts to be thread-safe.
 */
class OkBackOff(val defaultPolicy: OkBackOff.Policy =
                OkBackOff.DefaultExponentialBackOffPolicy()) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response? {
        val (policy, request) =
                defaultPolicy.parseHeadersForOverride(chain.request());
        var response: Response = chain.proceed(request)

        // Spin up a new Random per thread rather than using the synchronized
        // Math.random().
        val rand = Random()

        var interval = BackOffInterval()

        while (policy.retryRequired(response, interval)) {
            if (policy.backOffRequired(response, interval)) {
                interval = policy.nextBackOffInterval(interval, rand)
                Thread.sleep(interval.backOffMillis)
            }
            response = chain.proceed(request)
        }

        return response;
    }

    /** Immutable value type for a back off interval. */
    data class BackOffInterval(
            /**
             * Number of attempted calls. Client [Policy]'s are responsible for
             * incrementing (to allow for unlimited retries.
             */
            val attempts: Int = 0,
            /** Current retry interval midpoint in milliseconds. */
            val currentIntervalMidpointMillis: Long = 0,
            /** Back off time in milliseconds. */
            val backOffMillis: Long = 0,
            /** Start time invariant. Public for testing. */
            val startTime: Long = System.nanoTime()) {
        /** Function literal for elapsed time. */
        val elapsedTimeMillis: () -> Long = {
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
        }
    }

    abstract class Policy(
            /**
             * The maximum number of retry attempts allowed. Retries will cease
             * when either [.maxAttempts] or [.maxElapsedTimeMillis] are
             * exceeded.
             */
            val maxAttempts: Int,
            /**
             * Maximum value of elapsed time since the first response was
             * received. Retries will cease when either [.maxAttempts] or
             * [.maxElapsedTimeMillis] are exceeded.
             */
            val maxElapsedTimeMillis: Long) {
        /**
         * Maintains invariants and delegates to overridden
         * [retryRequiredImpl].
         */
        fun retryRequired(response: Response,
                          interval: BackOffInterval): Boolean {
            return interval.attempts < maxAttempts
                    && interval.elapsedTimeMillis() < maxElapsedTimeMillis
                    && retryRequiredImpl(response, interval)
        }

        /** Override this to set retry policy. */
        abstract fun retryRequiredImpl(response: Response,
                                       interval: BackOffInterval): Boolean

        /** Override this to set back off policy. */
        abstract fun backOffRequired(response: Response,
                                     interval: BackOffInterval): Boolean

        /** Override this to change the back off algorithm. */
        abstract fun nextBackOffInterval(lastInterval: BackOffInterval,
                                         rand: Random): BackOffInterval

        /**
         * Override this to allow for call specific [Policy] overrides via
         * [Request] headers. You should copy the [Request] and remove internal
         * headers when they have been consumed.
         */
        open fun parseHeadersForOverride(request: Request):
                Pair<Policy, Request> {
            return Pair(this, request);
        }
    }

    /**
     * A default exponential back off implementation forked from
     * Google Java Http Client.
     */
    class DefaultExponentialBackOffPolicy(
            /** 5 retries. Will fall before max elapsed time. */
            maxAttempts: Int = 5,
            /** 15 minutes. */
            maxElapsedTimeMillis: Long = 900000,
            /** Intervals increase by 50%. */
            val multiplier: Double = 1.5,
            /** The initial retry interval in milliseconds. Half a second. */
            val initialIntervalMidpointMillis: Long = 500,
            /**
             * Used for creating a range around the retry interval. Distribution
             * that ranges form 50% below to 50% above.
             */
            val randomizationFactor: Double = 0.5,
            /**
             * Maximum value of the back off period in milliseconds. Stops
             * increasing after this is reached. 1 minute.
             */
            val maxIntervalMidpointMillis: Long = 60000) :
    // Delegate.
            Policy(maxAttempts = maxAttempts,
                    maxElapsedTimeMillis = maxElapsedTimeMillis) {

        override fun retryRequiredImpl(response: Response,
                                       interval: BackOffInterval): Boolean {
            return !(response.isSuccessful || response.isRedirect)
        }

        override fun backOffRequired(response: Response,
                                     interval: BackOffInterval): Boolean {
            return true
        }

        // TODO example header parsing and override
        override fun parseHeadersForOverride(request: Request):
                Pair<Policy, Request> {
            return super.parseHeadersForOverride(request)
        }

        override fun nextBackOffInterval(lastInterval: BackOffInterval,
                                         rand: Random):
                BackOffInterval {
            // Increment current interval.
            val newIntervalMillis: Long =
                    if (lastInterval.attempts.equals(0)) {
                        // First retry attempt is special cased.
                        initialIntervalMidpointMillis
                    } else if (lastInterval.currentIntervalMidpointMillis >=
                            maxIntervalMidpointMillis / multiplier) {
                        maxIntervalMidpointMillis
                    } else {
                        (lastInterval.currentIntervalMidpointMillis * multiplier)
                                .toLong()
                    }

            // Random value from interval.
            val delta = randomizationFactor * newIntervalMillis
            val minIntervalBound = newIntervalMillis - delta
            val maxIntervalBound = newIntervalMillis + delta
            val newBackOffMillis =
                    (rand.nextDouble() * (maxIntervalBound - minIntervalBound) +
                            minIntervalBound).toLong()

            return lastInterval.copy(
                    // Increment attempts.
                    attempts = lastInterval.attempts + 1,
                    currentIntervalMidpointMillis = newIntervalMillis,
                    backOffMillis = newBackOffMillis
            )
        }
    }
}
