package com.openchat.session

import android.content.Context
import com.openchat.domain.Account
import com.openchat.domain.Accounts
import com.openchat.domain.AddContactOutcome
import com.openchat.domain.Avatar
import com.openchat.domain.AvatarDigest
import com.openchat.domain.Block
import com.openchat.domain.Byline
import com.openchat.domain.ChatList
import com.openchat.domain.ChatHeader
import com.openchat.domain.ChatSession
import com.openchat.domain.Chats
import com.openchat.domain.Contact
import com.openchat.domain.Contacts
import com.openchat.domain.ConversationOrder
import com.openchat.domain.ConversationId
import com.openchat.domain.DirectConversation
import com.openchat.domain.DisplayName
import com.openchat.domain.Draft
import com.openchat.domain.Grouping
import com.openchat.domain.Identity
import com.openchat.domain.LocalOrdinal
import com.openchat.domain.Message
import com.openchat.domain.MessageBody
import com.openchat.domain.MessageId
import com.openchat.domain.PickedImage
import com.openchat.domain.Preview
import com.openchat.domain.PreviewAuthor
import com.openchat.domain.Progress
import com.openchat.domain.RegisterOutcome
import com.openchat.domain.Row
import com.openchat.domain.RowKey
import com.openchat.domain.SendRejection
import com.openchat.domain.Seq
import com.openchat.domain.Standing
import com.openchat.domain.Stamp
import com.openchat.domain.Timeline
import com.openchat.domain.UnreadCount
import com.openchat.domain.UserId
import com.openchat.domain.UserPair
import com.openchat.domain.parseBody
import com.openchat.domain.previewOf
import com.openchat.transport.AuthResponse
import com.openchat.transport.ChatResponse
import com.openchat.transport.ContactResponse
import com.openchat.transport.ContactsApi
import com.openchat.transport.HttpFailure
import com.openchat.transport.IdentityResponse
import com.openchat.transport.MessageResponse
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

class OpenChatSession(context: Context) {
    private val filesDir = context.filesDir
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private var token: String? = preferences.getString(TokenKey, null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = ContactsApi({ DefaultServerUrl }, ::token)
    private val accountStore = RemoteAccounts()
    private val contactStore = RemoteContacts()
    private val chatStore = RemoteChats()

    val accounts: Accounts = accountStore
    val chats: Chats = chatStore
    val contacts: Contacts = contactStore

    init {
        if (accountStore.state.value is Account.SignedIn) {
            scope.launch {
                contactStore.refresh()
                chatStore.refresh()
            }
        }
    }

    fun bytesFor(digest: AvatarDigest): ByteArray? = accountStore.bytesFor(digest)

    suspend fun newerRelease(installedCode: Int): AppRelease? =
        try {
            val remote = api.latestRelease()
            if (remote.versionCode > installedCode) {
                AppRelease(remote.versionCode, remote.versionName)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    suspend fun downloadUpdate(into: File) {
        api.downloadApk(into)
    }

    private fun saveAuthentication(response: AuthResponse): Identity {
        val identity = response.toIdentity()
        token = response.token
        preferences.edit()
            .putString(TokenKey, response.token)
            .putString(UserIdKey, identity.id.value)
            .putString(DisplayNameKey, identity.name.value)
            .apply()
        accountStore.mutableState.value = Account.SignedIn(identity)
        return identity
    }

    private fun signOut() {
        token = null
        preferences.edit()
            .remove(TokenKey)
            .remove(UserIdKey)
            .remove(DisplayNameKey)
            .remove(AvatarDigestKey)
            .apply()
        File(filesDir, AvatarFileName).delete()
        accountStore.mutableState.value = Account.SignedOut
        contactStore.mutableAll.value = emptyList()
        contactStore.mutableIncoming.value = emptyList()
        chatStore.mutableList.value = ChatList.Empty
    }

    suspend fun refreshInbox() {
        contactStore.refresh()
        chatStore.refresh()
    }

    private inner class RemoteAccounts : Accounts {
        private val avatarBytes = mutableMapOf<AvatarDigest, ByteArray>()
        val mutableState = MutableStateFlow(restoredAccount())
        override val state: StateFlow<Account> = mutableState.asStateFlow()

        override suspend fun register(id: UserId, name: DisplayName): RegisterOutcome =
            try {
                val identity = saveAuthentication(api.register(id.value, name.value))
                contactStore.refresh()
                chatStore.refresh()
                RegisterOutcome.Registered(identity)
            } catch (failure: HttpFailure) {
                if (failure.status == 409) RegisterOutcome.IdTaken else RegisterOutcome.Offline
            } catch (_: IOException) {
                RegisterOutcome.Offline
            }

        override suspend fun login(id: UserId): RegisterOutcome =
            try {
                val identity = saveAuthentication(api.login(id.value))
                contactStore.refresh()
                chatStore.refresh()
                RegisterOutcome.Registered(identity)
            } catch (_: HttpFailure) {
                RegisterOutcome.Offline
            } catch (_: IOException) {
                RegisterOutcome.Offline
            }

        override suspend fun setAvatar(image: PickedImage): Avatar {
            val signedIn = mutableState.value as? Account.SignedIn
                ?: error("An account must be registered before setting an avatar")
            val digest = AvatarDigest.of(image.bytes)
            val avatar = Avatar.Image(digest)
            val bytes = image.bytes.copyOf()
            avatarBytes[digest] = bytes
            File(filesDir, AvatarFileName).writeBytes(bytes)
            preferences.edit().putString(AvatarDigestKey, digest.value).apply()
            mutableState.value = Account.SignedIn(signedIn.me.copy(avatar = avatar))
            return avatar
        }

        override fun signOut() {
            this@OpenChatSession.signOut()
        }

        fun bytesFor(digest: AvatarDigest): ByteArray? = avatarBytes[digest]

        private fun restoredAccount(): Account {
            val id = preferences.getString(UserIdKey, null)?.let(UserId::parse)
            val name = preferences.getString(DisplayNameKey, null)?.let(DisplayName::parse)
            if (token == null || id == null || name == null) return Account.SignedOut
            val storedDigest = preferences.getString(AvatarDigestKey, null)
            val file = File(filesDir, AvatarFileName)
            val avatar = if (storedDigest != null && file.isFile) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                val digest = bytes?.let(AvatarDigest::of)
                if (digest != null && digest.value == storedDigest) {
                    avatarBytes[digest] = bytes
                    Avatar.Image(digest)
                } else {
                    Avatar.Initials(name)
                }
            } else {
                Avatar.Initials(name)
            }
            return Account.SignedIn(Identity(id, name, avatar))
        }
    }

    private inner class RemoteContacts : Contacts {
        val mutableAll = MutableStateFlow<List<Contact>>(emptyList())
        val mutableIncoming = MutableStateFlow<List<Contact>>(emptyList())
        override val all: StateFlow<List<Contact>> = mutableAll.asStateFlow()
        override val incoming: StateFlow<List<Contact>> = mutableIncoming.asStateFlow()

        override suspend fun add(id: UserId): AddContactOutcome {
            val me = (accountStore.state.value as? Account.SignedIn)?.me ?: return AddContactOutcome.Offline
            checkNotNull(UserPair.of(me.id, id)) { "Self contacts must be rejected by the UI" }
            return try {
                val response = api.addContact(id.value)
                val contact = response.toContact() ?: return AddContactOutcome.Offline
                val outcome = response.toAddOutcome(contact)
                refresh()
                when (outcome) {
                    is AddContactOutcome.Added, is AddContactOutcome.AlreadyKnown -> chatStore.refresh()
                    is AddContactOutcome.Requested, is AddContactOutcome.AlreadyRequested,
                    AddContactOutcome.NoSuchUser, AddContactOutcome.Offline -> Unit
                }
                outcome
            } catch (failure: HttpFailure) {
                when (failure.status) {
                    404 -> AddContactOutcome.NoSuchUser
                    401 -> {
                        signOut()
                        AddContactOutcome.Offline
                    }
                    else -> AddContactOutcome.Offline
                }
            } catch (_: Exception) {
                AddContactOutcome.Offline
            }
        }

        override suspend fun lookup(id: UserId): Contact? {
            mutableAll.value.firstOrNull { it.id == id }?.let { return it }
            mutableIncoming.value.firstOrNull { it.id == id }?.let { return it }
            try {
                return api.user(id.value).toContact()
            } catch (failure: HttpFailure) {
                if (failure.status == 404) return null
                if (failure.status == 401) signOut()
                return null
            } catch (_: Exception) {
                return null
            }
        }

        suspend fun refresh() {
            try {
                mutableAll.value = api.contacts().contacts.mapNotNull { it.toContact() }
                mutableIncoming.value = api.friendRequests().requests.mapNotNull { it.toContact() }
            } catch (failure: HttpFailure) {
                if (failure.status == 401) signOut()
            } catch (_: Exception) {
                Unit
            }
        }
    }

    private inner class RemoteChats : Chats {
        val mutableList = MutableStateFlow(ChatList.Empty)
        override val list: StateFlow<ChatList> = mutableList.asStateFlow()

        suspend fun refresh() {
            val me = (accountStore.state.value as? Account.SignedIn)?.me ?: return
            val remote = try {
                api.chats().chats.associateBy { it.peerId }
            } catch (failure: HttpFailure) {
                if (failure.status == 401) signOut()
                emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            val contacts = (contactStore.mutableAll.value + remote.values.mapNotNull { it.toContact() })
                .distinctBy { it.id }
            mutableList.value = ChatList.of(
                direct = contacts.mapNotNull { peer ->
                    val pair = UserPair.of(me.id, peer.id) ?: return@mapNotNull null
                    val chat = remote[peer.id.value]
                    val at = Instant.ofEpochMilli(chat?.atMillis ?: 0)
                    DirectConversation(
                        id = ConversationId.direct(pair),
                        peer = peer,
                        lastActivity = at,
                        unread = UnreadCount.of(chat?.unread ?: 0) ?: UnreadCount.NONE,
                        preview = chat?.preview?.let { text ->
                            Preview.Latest(PreviewAuthor.Other(peer.name), previewOf(parseBody(text)), at)
                        } ?: Preview.Empty,
                    )
                },
                groups = emptyList(),
            )
        }

        override fun session(id: ConversationId, scope: CoroutineScope): ChatSession {
            val me = (accountStore.state.value as? Account.SignedIn)?.me
                ?: error("A chat session requires a signed-in account")
            val peerId = id.peerFor(me.id) ?: error("Conversation does not include the signed-in user")
            val peer = mutableList.value.direct.firstOrNull { it.id == id }?.peer
                ?: contactStore.mutableAll.value.firstOrNull { it.id == peerId }
                ?: Contact(
                    peerId,
                    checkNotNull(DisplayName.parse(peerId.value)),
                    Avatar.Initials(checkNotNull(DisplayName.parse(peerId.value))),
                )
            return LiveChatSession(id, me, peer, scope)
        }
    }

    private inner class LiveChatSession(
        override val id: ConversationId,
        private val me: Identity,
        private val peer: Contact,
        private val sessionScope: CoroutineScope,
    ) : ChatSession {
        private val messages = MutableStateFlow<List<Message>>(emptyList())
        private val ordinal = AtomicLong()
        override val header: StateFlow<ChatHeader> = MutableStateFlow(ChatHeader.Direct(peer))
        private val mutableTimeline = MutableStateFlow(Timeline.of(emptyList()))
        override val timeline: StateFlow<Timeline> = mutableTimeline.asStateFlow()

        init {
            sessionScope.launch {
                while (currentCoroutineContext().isActive) {
                    fetchNewer()
                    delay(1_000)
                }
            }
        }

        override fun send(draft: Draft) {
            val message = Message(
                id = MessageId.mint(),
                conversation = id,
                author = me.id,
                body = parseBody(draft.markdown),
                composedAt = Instant.now(),
                standing = Standing.Queued(LocalOrdinal(ordinal.incrementAndGet()), attempts = 1),
            )
            upsert(message)
            sessionScope.launch { deliver(message) }
        }

        override fun retry(id: MessageId) {
            val message = messages.value.firstOrNull { it.id == id } ?: return
            val standing = message.standing as? Standing.Rejected ?: return
            val queued = message.copy(
                standing = Standing.Queued(standing.ordinal, attempts = 2),
            )
            upsert(queued)
            sessionScope.launch { deliver(queued) }
        }

        override fun requestOlder() = Unit

        override fun markRead(through: Seq) = Unit

        private suspend fun fetchNewer() {
            val after = messages.value.mapNotNull {
                (it.standing as? Standing.Committed)?.seq?.value
            }.maxOrNull() ?: 0
            try {
                api.messages(id.value, after).messages.forEach { upsert(it.toMessage(id)) }
            } catch (failure: HttpFailure) {
                if (failure.status == 401) signOut()
            } catch (_: IOException) {
                Unit
            }
        }

        private suspend fun deliver(message: Message) {
            try {
                val response = api.sendMessage(id.value, message.id.value, message.body.asWireText())
                upsert(response.toMessage(id))
                chatStore.refresh()
            } catch (failure: HttpFailure) {
                val reason = when (failure.status) {
                    403 -> SendRejection.NotAMember
                    404 -> SendRejection.UnknownConversation
                    else -> SendRejection.ServerRefused(failure.status)
                }
                reject(message, reason)
            } catch (_: IOException) {
                reject(message, SendRejection.ServerRefused(0))
            }
        }

        private fun reject(message: Message, reason: SendRejection) {
            val queued = message.standing as? Standing.Queued ?: return
            upsert(message.copy(standing = Standing.Rejected(queued.ordinal, reason)))
        }

        private fun upsert(message: Message) {
            messages.value = (messages.value.filterNot { it.id == message.id } + message)
                .sortedWith(ConversationOrder)
            mutableTimeline.value = Timeline.of(messages.value.asReversed().map { it.toRow() })
        }

        private fun Message.toRow(): Row.Bubble {
            val byline = if (author == me.id) {
                Byline.Mine(me.avatar)
            } else {
                Byline.Theirs(peer.name, peer.avatar)
            }
            val progress = when (standing) {
                is Standing.Committed -> Progress.Sent
                is Standing.Queued -> Progress.Sending
                is Standing.Rejected -> Progress.Failed
            }
            val at = when (val current = standing) {
                is Standing.Committed -> current.serverAt
                is Standing.Queued -> composedAt
                is Standing.Rejected -> composedAt
            }
            return Row.Bubble(
                key = RowKey(id.value),
                id = id,
                byline = byline,
                body = body,
                stamp = Stamp(StampClock.format(at)),
                progress = progress,
                grouping = Grouping.OpensBurst,
            )
        }
    }

    private fun AuthResponse.toIdentity(): Identity {
        val id = UserId.parse(id) ?: error("Server returned an invalid user id")
        val name = DisplayName.parse(name) ?: error("Server returned an invalid display name")
        return Identity(id, name, Avatar.Initials(name))
    }

    private fun IdentityResponse.toContact(): Contact? {
        val id = UserId.parse(id) ?: return null
        val name = DisplayName.parse(name) ?: return null
        return Contact(id, name, Avatar.Initials(name))
    }

    private fun ContactResponse.toContact(): Contact? {
        val id = UserId.parse(id) ?: return null
        val name = DisplayName.parse(name) ?: return null
        return Contact(id, name, Avatar.Initials(name))
    }

    private fun ContactResponse.toAddOutcome(contact: Contact): AddContactOutcome =
        when (state) {
            "already" -> AddContactOutcome.AlreadyKnown(contact)
            "added" -> AddContactOutcome.Added(contact)
            "requested" -> AddContactOutcome.Requested(contact)
            "pending" -> AddContactOutcome.AlreadyRequested(contact)
            null -> if (already == true) {
                AddContactOutcome.AlreadyKnown(contact)
            } else {
                AddContactOutcome.Added(contact)
            }
            else -> if (already == true) {
                AddContactOutcome.AlreadyKnown(contact)
            } else {
                AddContactOutcome.Added(contact)
            }
        }

    private fun ChatResponse.toContact(): Contact? {
        val id = UserId.parse(peerId) ?: return null
        val name = DisplayName.parse(peerName) ?: return null
        return Contact(id, name, Avatar.Initials(name))
    }

    private fun MessageResponse.toMessage(conversation: ConversationId): Message {
        val messageId = MessageId.parse(id) ?: error("Server returned an invalid message id")
        val authorId = UserId.parse(author) ?: error("Server returned an invalid message author")
        val serverAt = Instant.ofEpochMilli(serverAtMillis)
        return Message(
            id = messageId,
            conversation = conversation,
            author = authorId,
            body = parseBody(body),
            composedAt = serverAt,
            standing = Standing.Committed(Seq(seq), serverAt),
        )
    }

    private fun MessageBody.asWireText(): String =
        blocks.joinToString("\n") { it.asWireText() }

    private fun Block.asWireText(): String =
        when (this) {
            is Block.Prose -> text.asPlainText()
            is Block.Code -> source
            is Block.Diagram -> source.value
            is Block.Chart -> spec.title.orEmpty()
            is Block.Quote -> blocks.joinToString("\n") { it.asWireText() }
        }

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

    private companion object {
        const val PreferencesName = "openchat_session"
        const val TokenKey = "token"
        const val UserIdKey = "user_id"
        const val DisplayNameKey = "display_name"
        const val AvatarDigestKey = "avatar_digest"
        const val AvatarFileName = "avatar.bin"
        const val DefaultServerUrl = "http://43.167.169.51:8080"
        val StampClock: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }
}

data class AppRelease(val versionCode: Int, val versionName: String)
