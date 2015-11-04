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

import com.squareup.okhttp.Request
import com.squareup.okhttp.Response
import com.squareup.okhttp.ws.WebSocket
import java.io.IOException
import java.net.URL
import java.util.*
import kotlin.test.*

/**
 * A received response or failure recorded by the response recorder.
 */
class RecordedResponse(val request: Request?,
                       val response: Response?,
                       val webSocket: WebSocket?,
                       val body: String?,
                       val failure: IOException?) {

    fun assertRequestUrl(url: URL): RecordedResponse {
        assertEquals(url, request!!.url())
        return this
    }

    fun assertRequestMethod(method: String): RecordedResponse {
        assertEquals(method, request!!.method())
        return this
    }

    fun assertRequestHeader(name: String, vararg values: String): RecordedResponse {
        assertEquals(Arrays.asList(*values), request!!.headers(name))
        return this
    }

    fun assertCode(expectedCode: Int): RecordedResponse {
        assertEquals(expectedCode.toLong(), response!!.code().toLong())
        return this
    }

    fun assertSuccessful(): RecordedResponse {
        assertTrue(response!!.isSuccessful)
        return this
    }

    fun assertNotSuccessful(): RecordedResponse {
        assertFalse(response!!.isSuccessful)
        return this
    }

    fun assertHeader(name: String, vararg values: String): RecordedResponse {
        assertEquals(Arrays.asList(*values), response!!.headers(name))
        return this
    }

    fun assertBody(expectedBody: String): RecordedResponse {
        assertEquals(expectedBody, body)
        return this
    }

    fun assertHandshake(): RecordedResponse {
        val handshake = response!!.handshake()
        assertNotNull(handshake.cipherSuite())
        assertNotNull(handshake.peerPrincipal())
        assertEquals(1, handshake.peerCertificates().size.toLong())
        assertNull(handshake.localPrincipal())
        assertEquals(0, handshake.localCertificates().size.toLong())
        return this
    }

    /**
     * Asserts that the current response was redirected and returns the prior
     * response.
     */
    fun priorResponse(): RecordedResponse {
        val priorResponse = response!!.priorResponse()
        assertNotNull(priorResponse)
        assertNull(priorResponse.body())
        return RecordedResponse(priorResponse.request(), priorResponse, null, null, null)
    }

    /**
     * Asserts that the current response used the network and returns the network
     * response.
     */
    fun networkResponse(): RecordedResponse {
        val networkResponse = response!!.networkResponse()
        assertNotNull(networkResponse)
        assertNull(networkResponse.body())
        return RecordedResponse(networkResponse.request(), networkResponse, null, null, null)
    }

    /** Asserts that the current response didn't use the network.  */
    fun assertNoNetworkResponse(): RecordedResponse {
        assertNull(response!!.networkResponse())
        return this
    }

    /** Asserts that the current response didn't use the cache.  */
    fun assertNoCacheResponse(): RecordedResponse {
        assertNull(response!!.cacheResponse())
        return this
    }

    /**
     * Asserts that the current response used the cache and returns the cache
     * response.
     */
    fun cacheResponse(): RecordedResponse {
        val cacheResponse = response!!.cacheResponse()
        assertNotNull(cacheResponse)
        assertNull(cacheResponse.body())
        return RecordedResponse(cacheResponse.request(), cacheResponse, null, null, null)
    }

    fun assertFailure(vararg messages: String) {
        assertNotNull(failure)
        assertTrue(failure!!.getMessage(), { Arrays.asList(*messages).contains(failure.getMessage()) })
    }
}
