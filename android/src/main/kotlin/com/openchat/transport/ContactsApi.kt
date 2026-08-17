package com.openchat.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

internal class ContactsApi(
    private val baseUrl: () -> String,
    private val token: () -> String?,
) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun register(id: String, name: String): AuthResponse =
        post("/v1/register", json.encodeToString(RegisterRequest(id, name)), authenticated = false)

    suspend fun login(id: String): AuthResponse =
        post("/v1/login", json.encodeToString(LoginRequest(id)), authenticated = false)

    suspend fun me(): IdentityResponse = get("/v1/me")

    suspend fun user(id: String): IdentityResponse = get("/v1/users/$id")

    suspend fun contacts(): ContactsResponse = get("/v1/contacts")

    suspend fun addContact(id: String): ContactResponse =
        post("/v1/contacts", json.encodeToString(AddContactRequest(id)), authenticated = true)

    suspend fun friendRequests(): FriendRequestsResponse = get("/v1/friend-requests")

    suspend fun chats(): ChatsResponse = get("/v1/chats")

    suspend fun messages(conversation: String, after: Long): MessagesResponse =
        get("/v1/conversations/$conversation/messages?after=$after")

    suspend fun sendMessage(conversation: String, id: String, body: String): MessageResponse =
        post(
            "/v1/conversations/$conversation/messages",
            json.encodeToString(SendMessageRequest(id, body)),
            authenticated = true,
        )

    suspend fun latestRelease(): UpdateResponse =
        execute(Request.Builder().url(url("/v1/update")).get().build())

    suspend fun downloadApk(into: File) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url("/v1/update/apk")).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            val body = response.body ?: throw HttpFailure(response.code)
            into.parentFile?.mkdirs()
            into.outputStream().use { output -> body.byteStream().copyTo(output) }
        }
    }

    private suspend inline fun <reified T> get(path: String): T =
        execute(Request.Builder().url(url(path)).authenticated().get().build())

    private suspend inline fun <reified T> post(path: String, body: String, authenticated: Boolean): T {
        val builder = Request.Builder()
            .url(url(path))
            .post(body.toRequestBody(JsonMediaType))
        if (authenticated) builder.authenticated()
        return execute(builder.build())
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            json.decodeFromString<T>(response.requiredBody())
        }
    }

    private fun Request.Builder.authenticated(): Request.Builder {
        val currentToken = token() ?: throw HttpFailure(401)
        return header("Authorization", "Bearer $currentToken")
    }

    private fun url(path: String): String = "${baseUrl().trimEnd('/')}$path"

    private fun Response.requiredBody(): String =
        body?.string() ?: throw IllegalStateException("Server returned an empty response")

    private companion object {
        val JsonMediaType = "application/json".toMediaType()
    }
}

internal class HttpFailure(val status: Int) : Exception()

internal fun normalizeBaseUrl(raw: String): String? {
    val normalized = raw.trim().trimEnd('/')
    val url = normalized.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    return normalized
}
