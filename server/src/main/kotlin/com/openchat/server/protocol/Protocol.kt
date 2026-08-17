package com.openchat.server.protocol

import kotlinx.serialization.Serializable

@Serializable
internal data class RegisterRequest(val id: String, val name: String)

@Serializable
internal data class LoginRequest(val id: String)

@Serializable
internal data class AddContactRequest(val id: String)

@Serializable
internal data class IdentityResponse(
    val id: String,
    val name: String,
)

@Serializable
internal data class AuthResponse(
    val id: String,
    val name: String,
    val token: String,
)

@Serializable
internal data class ContactResponse(
    val id: String,
    val name: String,
    val already: Boolean? = null,
    val state: String? = null,
)

@Serializable
internal data class ContactsResponse(val contacts: List<ContactResponse>)

@Serializable
internal data class FriendRequestsResponse(val requests: List<ContactResponse>)

@Serializable
internal data class ChatResponse(
    val peerId: String,
    val peerName: String,
    val preview: String?,
    val atMillis: Long,
    val unread: Int,
)

@Serializable
internal data class ChatsResponse(val chats: List<ChatResponse>)

@Serializable
internal data class SendMessageRequest(val id: String, val body: String)

@Serializable
internal data class MessageResponse(
    val id: String,
    val author: String,
    val body: String,
    val seq: Long,
    val serverAtMillis: Long,
)

@Serializable
internal data class MessagesResponse(val messages: List<MessageResponse>)

@Serializable
internal data class ErrorResponse(val error: String)

@Serializable
internal data class UpdateResponse(
    val versionCode: Int,
    val versionName: String,
)
