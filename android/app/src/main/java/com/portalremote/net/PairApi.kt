package com.portalremote.net

import com.portalremote.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The PC said no, or answered with something unusable. */
class PairRejected(message: String) : Exception(message)

private val pairClient = OkHttpClient.Builder()
    // Short connect timeout so a mistyped address fails while the user still
    // remembers what they typed; long read timeout because the server holds the
    // response open until someone answers the Allow dialog on the PC.
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.MINUTES)
    .build()

/**
 * Asks a PC for its pairing token. The PC puts an "allow this phone?" dialog on
 * screen and this call blocks until someone answers it — which is what lets a
 * phone pair with a discovered address without anyone typing a 32-character token.
 *
 * @param deviceName shown in that dialog, so the user can tell which phone is asking.
 */
suspend fun requestPairing(host: String, port: Int, deviceName: String): SavedHost =
    withContext(Dispatchers.IO) {
        val body = JSONObject().put("device", deviceName).toString()
            .toRequestBody("application/json".toMediaType())
        val call = pairClient.newCall(
            Request.Builder().url("http://$host:$port/pair/request").post(body).build()
        )
        // execute() blocks a thread that coroutine cancellation can't interrupt on
        // its own — and this one can be parked for minutes. Cancelling the call
        // breaks it out. Harmless no-op when the request finished normally.
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw PairRejected("Could not reach $host — is it on the same Wi-Fi?")
        }

        response.use {
            if (it.code == 403) throw PairRejected("The PC turned the request down")
            if (!it.isSuccessful) throw PairRejected("The PC answered HTTP ${it.code}")

            val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                ?: throw PairRejected("The PC sent a reply this app could not read")
            val token = json.optString("token").ifBlank { throw PairRejected("No pairing token in the reply") }

            SavedHost(
                host = host,
                port = json.optInt("port").takeIf { p -> p in 1..65535 } ?: port,
                token = token,
                name = json.optString("name").ifBlank { null },
            )
        }
    }
