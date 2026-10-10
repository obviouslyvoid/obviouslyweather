// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Reuse connections while bounding each complete request and supporting cancellation. */
internal class OpenWeatherTransport(
    private val client: OkHttpClient = sharedClient,
    private val origin: HttpUrl = "https://api.openweathermap.org/".toHttpUrl(),
    private val dailyTimeoutMillis: Long = 30_000,
    private val otherTimeoutMillis: Long = 8_000
) {
    suspend fun request(path: String, parameters: Map<String, String>, key: String): String =
        suspendCancellableCoroutine { continuation ->
            val daily = DailyForecastProvider.isDailyPath(path)
            val url = origin.newBuilder().encodedPath(path).apply {
                parameters.forEach { (name, value) -> addQueryParameter(name, value) }
                addQueryParameter("appid", key)
            }.build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "obviouslyweather/${BuildConfig.VERSION_NAME} Android")
                .header("Accept", "application/json").build()
            // Share the connection pool and dispatcher, but allow the daily
            // server enough read time; the shorter core limit stays unchanged.
            val requestClient = if (daily) dailyClient else client
            val call = requestClient.newCall(request)
            // This includes connection establishment and the entire body, rather
            // than restarting a read timeout each time a few bytes arrive.
            call.timeout().timeout(if (daily) dailyTimeoutMillis else otherTimeoutMillis, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val message = if (e is InterruptedIOException) "OpenWeather took too long to respond. Please try again."
                        else "Could not reach OpenWeather. Check your connection and try again."
                    // Raw exceptions and request URLs can contain the supplied key.
                    continuation.resumeWith(Result.failure(WeatherFailure(message)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            if (!it.isSuccessful) Result.failure(WeatherFailure(serviceError(it.code, daily || path.startsWith("/data/4.0/"))))
                            else Result.success(it.body?.string() ?: throw IOException("Missing response"))
                        } catch (error: IOException) {
                            val message = if (error is InterruptedIOException) "OpenWeather took too long to respond. Please try again."
                                else "Could not reach OpenWeather. Check your connection and try again."
                            Result.failure(WeatherFailure(message))
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    private val dailyClient = client.newBuilder()
        .readTimeout(dailyTimeoutMillis, TimeUnit.MILLISECONDS).build()

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // Keep the bound on paid forecast requests explicit, even after a
            // broken connection; the next user/scheduled refresh can retry.
            .retryOnConnectionFailure(false)
            .build()
    }
}
