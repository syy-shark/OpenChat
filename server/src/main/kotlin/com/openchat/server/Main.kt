package com.openchat.server

import com.openchat.domain.Contact
import com.openchat.domain.ConversationId
import com.openchat.domain.DisplayName
import com.openchat.domain.Identity
import com.openchat.domain.MessageId
import com.openchat.domain.UserId
import com.openchat.domain.UserPair
import com.openchat.server.protocol.AddContactRequest
import com.openchat.server.protocol.AuthResponse
import com.openchat.server.protocol.ContactResponse
import com.openchat.server.protocol.ContactsResponse
import com.openchat.server.protocol.FriendRequestsResponse
import com.openchat.server.protocol.ChatResponse
import com.openchat.server.protocol.ChatsResponse
import com.openchat.server.protocol.ErrorResponse
import com.openchat.server.protocol.IdentityResponse
import com.openchat.server.protocol.LoginRequest
import com.openchat.server.protocol.MessageResponse
import com.openchat.server.protocol.MessagesResponse
import com.openchat.server.protocol.RegisterRequest
import com.openchat.server.protocol.SendMessageRequest
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.nio.file.Path

fun main() {
    val port = System.getenv("OPENCHAT_PORT")?.toIntOrNull() ?: 8080
    val databasePath = System.getenv("OPENCHAT_DB") ?: "openchat.db"
    Database(Path.of(databasePath)).use { database ->
        embeddedServer(CIO, host = "0.0.0.0", port = port) {
            contactsModule(database, updateCatalog())
        }.start(wait = true)
    }
}

internal fun Application.contactsModule(database: Database, updates: UpdateCatalog = updateCatalog()) {
    install(ContentNegotiation) {
        json()
    }
    routing {
        post("/v1/register") {
            val request = call.receiveOrNull<RegisterRequest>()
            val id = request?.id?.let(UserId::parse)
            val name = request?.name?.let(DisplayName::parse)
            if (id == null || name == null) {
                call.error(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            val registered = database.register(id, name)
            if (registered == null) {
                call.error(HttpStatusCode.Conflict, "id_taken")
                return@post
            }
            call.respond(registered.toResponse())
        }

        post("/v1/login") {
            val request = call.receiveOrNull<LoginRequest>()
            val id = request?.id?.let(UserId::parse)
            if (id == null) {
                call.error(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            val login = database.login(id)
            if (login == null) {
                call.error(HttpStatusCode.NotFound, "not_found")
                return@post
            }
            call.respond(login.toResponse())
        }

        get("/v1/me") {
            val me = call.authenticated(database) ?: return@get
            call.respond(me.toResponse())
        }

        get("/v1/users/{id}") {
            if (call.authenticated(database) == null) return@get
            val id = call.parameters["id"]?.let(UserId::parse)
            if (id == null) {
                call.error(HttpStatusCode.NotFound, "not_found")
                return@get
            }
            val user = database.user(id)
            if (user == null) {
                call.error(HttpStatusCode.NotFound, "not_found")
                return@get
            }
            call.respond(user.toResponse())
        }

        get("/v1/contacts") {
            val me = call.authenticated(database) ?: return@get
            call.respond(ContactsResponse(database.contacts(me.id).map(Contact::toResponse)))
        }

        post("/v1/contacts") {
            val me = call.authenticated(database) ?: return@post
            val request = call.receiveOrNull<AddContactRequest>()
            val peer = request?.id?.let(UserId::parse)
            if (peer == null) {
                call.error(HttpStatusCode.NotFound, "not_found")
                return@post
            }
            if (UserPair.of(me.id, peer) == null) {
                call.error(HttpStatusCode.BadRequest, "self")
                return@post
            }
            val added = database.addContact(me.id, peer)
            if (added == null) {
                call.error(HttpStatusCode.NotFound, "not_found")
                return@post
            }
            call.respond(added.contact.toResponse(added.already, added.state))
        }

        get("/v1/friend-requests") {
            val me = call.authenticated(database) ?: return@get
            call.respond(FriendRequestsResponse(database.incomingRequests(me.id).map(Contact::toResponse)))
        }

        get("/v1/chats") {
            val me = call.authenticated(database) ?: return@get
            val chats = database.chats(me.id).map { chat ->
                ChatResponse(
                    peerId = chat.peer.id.value,
                    peerName = chat.peer.name.value,
                    preview = chat.latest?.body,
                    atMillis = chat.latest?.serverAtMillis ?: 0,
                    unread = 0,
                )
            }
            call.respond(ChatsResponse(chats))
        }

        get("/v1/conversations/{id}/messages") {
            val me = call.authenticated(database) ?: return@get
            val conversation = call.parameters["id"]?.let(ConversationId::parse)
            val peer = conversation?.peerFor(me.id)
            if (conversation == null || peer == null) {
                call.error(HttpStatusCode.Forbidden, "not_a_member")
                return@get
            }
            if (database.user(peer) == null) {
                call.error(HttpStatusCode.NotFound, "not_found")
                return@get
            }
            if (!database.canAccessConversation(me.id, peer, conversation)) {
                call.error(HttpStatusCode.Forbidden, "not_a_member")
                return@get
            }
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            if (after < 0) {
                call.error(HttpStatusCode.BadRequest, "invalid_after")
                return@get
            }
            call.respond(MessagesResponse(database.messages(conversation, after).map { it.toResponse() }))
        }

        post("/v1/conversations/{id}/messages") {
            val me = call.authenticated(database) ?: return@post
            val conversation = call.parameters["id"]?.let(ConversationId::parse)
            val peer = conversation?.peerFor(me.id)
            if (conversation == null || peer == null) {
                call.error(HttpStatusCode.Forbidden, "not_a_member")
                return@post
            }
            if (database.user(peer) == null) {
                call.error(HttpStatusCode.NotFound, "not_found")
                return@post
            }
            if (!database.canAccessConversation(me.id, peer, conversation)) {
                call.error(HttpStatusCode.Forbidden, "not_a_member")
                return@post
            }
            val request = call.receiveOrNull<SendMessageRequest>()
            val messageId = request?.id?.let(MessageId::parse)
            val body = request?.body?.trim()?.takeIf(String::isNotEmpty)
            if (messageId == null || body == null) {
                call.error(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            val stored = database.putMessage(messageId, conversation, me.id, body)
            if (stored == null) {
                call.error(HttpStatusCode.Conflict, "message_id_conflict")
                return@post
            }
            call.respond(stored.toResponse())
        }

        get("/v1/update") {
            val release = updates.manifest()
            if (release == null) {
                call.error(HttpStatusCode.NotFound, "no_update")
                return@get
            }
            call.respond(release)
        }

        get("/v1/update/apk") {
            val apk = updates.apk()
            if (apk == null) {
                call.error(HttpStatusCode.NotFound, "no_update")
                return@get
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "openchat.apk").toString(),
            )
            call.respondFile(apk)
        }
    }
}

private fun updateCatalog(): UpdateCatalog {
    val raw = System.getenv("OPENCHAT_UPDATE_DIR") ?: "update"
    return UpdateCatalog(File(raw))
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? =
    runCatching { receive<T>() }.getOrNull()

private suspend fun ApplicationCall.authenticated(database: Database): Identity? {
    val authorization = request.headers[HttpHeaders.Authorization]
    val token = authorization
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?.takeIf(String::isNotBlank)
    val identity = token?.let(database::authenticate)
    if (identity == null) error(HttpStatusCode.Unauthorized, "unauthorized")
    return identity
}

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) {
    respond(status, ErrorResponse(code))
}

private fun AuthenticatedIdentity.toResponse(): AuthResponse =
    AuthResponse(identity.id.value, identity.name.value, token)

private fun Identity.toResponse(): IdentityResponse =
    IdentityResponse(id.value, name.value)

private fun Contact.toResponse(already: Boolean? = null, state: String? = null): ContactResponse =
    ContactResponse(id.value, name.value, already, state)

private fun ConversationId.peerFor(me: UserId): UserId? {
    val parts = value.split(":")
    if (parts.size != 3) return null
    val first = UserId.parse(parts[1]) ?: return null
    val second = UserId.parse(parts[2]) ?: return null
    return when (me) {
        first -> second
        second -> first
        else -> null
    }
}

private fun StoredMessage.toResponse(): MessageResponse =
    MessageResponse(id.value, author.value, body, seq, serverAtMillis)
