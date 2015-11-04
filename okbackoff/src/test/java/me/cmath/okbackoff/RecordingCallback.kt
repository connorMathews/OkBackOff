/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.cmath.okbackoff

import com.squareup.okhttp.Callback
import com.squareup.okhttp.HttpUrl
import com.squareup.okhttp.Request
import com.squareup.okhttp.Response
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Records received HTTP responses so they can be later retrieved by tests.
 */
class RecordingCallback : Callback {
    private val responses = ArrayList<RecordedResponse>()

    @Synchronized override fun onFailure(request: Request, e: IOException) {
        responses.add(RecordedResponse(request, null, null, null, e))
        (this as Object).notifyAll()
    }

    @Synchronized @Throws(IOException::class)
    override fun onResponse(response: Response) {
        val body = response.body().string()
        responses.add(RecordedResponse(response.request(), response, null, body, null))
        (this as Object).notifyAll()
    }

    /**
     * Returns the recorded response triggered by `request`. Throws if the
     * response isn't enqueued before the timeout.
     */
    @Synchronized @Throws(Exception::class)
    fun await(url: HttpUrl): RecordedResponse {
        val timeoutMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + TIMEOUT_MILLIS
        while (true) {
            val i = responses.iterator()
            while (i.hasNext()) {
                val recordedResponse = i.next()
                if (recordedResponse.request!!.httpUrl().equals(url)) {
                    i.remove()
                    return recordedResponse
                }
            }

            val nowMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
            if (nowMillis >= timeoutMillis) break
            (this as Object).wait(timeoutMillis - nowMillis)
        }

        throw AssertionError("Timed out waiting for response to " + url)
    }

    @Synchronized @Throws(Exception::class)
    fun assertNoResponse(url: HttpUrl) {
        for (recordedResponse in responses) {
            if (recordedResponse.request!!.httpUrl().equals(url)) {
                throw AssertionError("Expected no response for " + url)
            }
        }
    }

    companion object {
        val TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20)
    }
}
