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
import com.squareup.okhttp.Request
import com.squareup.okhttp.mockwebserver.Dispatcher
import com.squareup.okhttp.mockwebserver.MockResponse
import com.squareup.okhttp.mockwebserver.MockWebServer
import com.squareup.okhttp.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions
import org.junit.Test
import java.util.*

class OkBackOffTest {
    @get:org.junit.Rule var server = MockWebServer()

    val client = OkHttpClient()
    val callback = RecordingCallback()

    @Test fun helloWorld() {
        server.enqueue(MockResponse().setResponseCode(200)
                .setBody("hello world!"))

        var response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        Assertions.assertThat(response.body().string()).isEqualTo("hello world!")
    }

    @Test fun retries() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("success!"))

        client.interceptors().add(OkBackOff(
                OkBackOff.DefaultExponentialBackOffPolicy(maxAttempts = 1)))
        var response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        Assertions.assertThat(response.code()).isEqualTo(200)
    }

    @Test fun noRetries() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("success!"))

        client.interceptors().add(OkBackOff(
                OkBackOff.DefaultExponentialBackOffPolicy(maxAttempts = 0)))
        var response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        Assertions.assertThat(response.code()).isEqualTo(500)
    }

    @Test fun backOffIntervalStartTimePreservedByCopy() {
        var interval = OkBackOff.BackOffInterval()
        val start = interval.startTime
        interval = interval.copy()
        val copy = interval.startTime
        Assertions.assertThat(start).isEqualTo(copy)
    }

    /**
     * request#     retry_interval     randomized_interval
     * 1             0.5                [0.25,   0.75]
     * 2             0.75               [0.375,  1.125]
     * 3             1.125              [0.562,  1.687]
     * 4             1.687              [0.8435, 2.53]
     * 5             2.53               [1.265,  3.795]
     * 6             3.795              [1.897,  5.692]
     * 7             5.692              [2.846,  8.538]
     * 8             8.538              [4.269, 12.807]
     * 9            12.807              [6.403, 19.210]
     * 10           19.210              [BackOff.STOP]
     */
    @Test fun expectedDefaultExponentialBackOffIntervals() {
        for (i in 0..9) {
            server.enqueue(MockResponse().setResponseCode(500))
        }

        val lower = arrayOf(    0.25, 0.375, 0.562, 0.8435, 1.265, 1.897, 2.846, 4.269,  6.403)
        val expected = arrayOf( 0.5,  0.75,  1.125, 1.687,  2.53,  3.795, 5.692, 8.538,  12.807)
        val upper = arrayOf(    0.75, 1.125, 1.687, 2.53,   3.795, 5.692, 8.538, 12.807, 19.210)

        val policy = OkBackOff.DefaultExponentialBackOffPolicy()

        // Fuzz the bounds.
        for (j in 1..100000) {
            var interval = OkBackOff.BackOffInterval(currentIntervalMidpointMillis = 500)
            val rand = Random()

            for ((i, e) in expected.withIndex()) {
                interval = policy.nextBackOffInterval(interval, rand).copy(attempts = interval.attempts + 1)
                Assertions.assertThat(interval.currentIntervalMidpointMillis).isEqualTo((e * 1000).toLong()) // Seconds to milliseconds
                Assertions.assertThat(interval.backOffMillis).isGreaterThanOrEqualTo((lower[i] * 1000).toLong())
                Assertions.assertThat(interval.backOffMillis).isLessThanOrEqualTo((upper[i] * 1000).toLong())
            }
        }
    }

    // TODO unit tests w/ header parsing and overrides

    // Example with a bunch of async calls at once with one of them failing.
    @Test fun manyAsyncBackOffsExample() {
        server.setDispatcher(object : Dispatcher() {
            var aCount: Int = 0;
            var bCount: Int = 0;
            var cCount: Int = 0;

            override fun dispatch(request: RecordedRequest): MockResponse {
                fun code(count: Int, max: Int, body: String): Pair<MockResponse, Int> {
                    return if (count < max) {
                        Pair(MockResponse().setResponseCode(500), count + 1)
                    } else {
                        Pair(MockResponse().setResponseCode(200).setBody(body), count);
                    }
                }

                when (request.path) {
                    "/a" -> {
                        val (response, count) = code(aCount, 3, "a")
                        aCount = count
                        return response
                    }
                    "/b" -> {
                        val (response, count) = code(bCount, 5, "b")
                        bCount = count
                        return response
                    }
                    "/c" -> {
                        val (response, count) = code(cCount, 7, "c")
                        cCount = count
                        return response
                    }
                    else -> throw UnsupportedOperationException()
                }
            }
        })

        client.interceptors().add(OkBackOff())

        val callbackA = RecordingCallback()
        val callbackB = RecordingCallback()
        val callbackC = RecordingCallback()

        val requestA = Request.Builder().url(server.url("/a")).build()
        val requestB = Request.Builder().url(server.url("/b")).build()
        val requestC = Request.Builder().url(server.url("/c")).build()
        client.newCall(requestA).enqueue(callbackA)
        client.newCall(requestB).enqueue(callbackB)
        client.newCall(requestC).enqueue(callbackC)
        callbackA.await(requestA.httpUrl()).assertSuccessful()
        callbackB.await(requestB.httpUrl()).assertSuccessful()
        callbackC.await(requestC.httpUrl()).assertNotSuccessful()
    }
}