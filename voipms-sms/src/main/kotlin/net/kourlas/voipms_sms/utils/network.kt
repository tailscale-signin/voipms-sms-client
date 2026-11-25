/*
 * VoIP.ms SMS
 * Copyright (C) 2015-2021 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.utils

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import net.kourlas.voipms_sms.preferences.getConnectTimeout
import net.kourlas.voipms_sms.preferences.getReadTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode") {
    val retryable: Boolean get() = statusCode == 408 || statusCode == 429 || statusCode in 500..599
}

/**
 * Sends a POST request with a multipart/form-data encoded request body to the
 * specified URL, and retrieves a JSON response body.
 */
@Suppress("SameParameterValue")
suspend inline fun <reified T> httpPostWithMultipartFormData(
    context: Context, url: String,
    formData: Map<String, String> = emptyMap(),
    allowReplay: Boolean = true
): T? {
    val requestBodyBuilder = MultipartBody.Builder()
    requestBodyBuilder.setType(MultipartBody.FORM)
    for ((key, value) in formData) {
        requestBodyBuilder.addFormDataPart(key, value)
    }
    val form = requestBodyBuilder.build()
    // A sendSMS request can already have reached VoIP.ms when its response is lost.
    val requestBody = if (allowReplay) form else object : RequestBody() {
        override fun contentType() = form.contentType()
        override fun contentLength() = form.contentLength()
        override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
        override fun isOneShot() = true
    }

    val request = Request.Builder()
        .url(url)
        .post(requestBody)
        .build()

    val requestClient = HttpClientManager.getInstance().client.newBuilder()
        .retryOnConnectionFailure(allowReplay)
        .followRedirects(allowReplay)
        .readTimeout(getReadTimeout(context) * 1000L, TimeUnit.MILLISECONDS)
        .connectTimeout(
            getConnectTimeout(context) * 1000L,
            TimeUnit.MILLISECONDS
        )
        .build()

    val adapter = JsonParserManager.getInstance().parser.adapter(T::class.java)
    return requestClient.newCall(request).awaitDecoded { response ->
        if (!response.isSuccessful) throw HttpStatusException(response.code)
        adapter.fromJson(response.body.source())
    }
}

/** Cancelling a sync or listening session also interrupts a pending socket read. */
@PublishedApi
internal suspend fun <T> Call.awaitDecoded(decode: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    response.use(decode)
                } catch (e: Exception) {
                    continuation.resumeWith(Result.failure(e))
                    return
                }
                continuation.resumeWith(Result.success(value))
            }
        })
    }
