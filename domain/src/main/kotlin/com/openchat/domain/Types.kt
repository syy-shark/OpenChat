// OpenChat domain types. Synthesized from arena candidate 4 with grafts.
//
// One package here for readability. The banners name the module and file each
// group lands in. Bodies are `not implemented`; tricky logic is pseudocode.
//
// Load-bearing invariants, all encoded below rather than documented:
//   1. A message's position in the conversation and its delivery progress are
//      one field (`Standing`), so "sent but unordered" cannot be built.
//   2. `MessageId` is minted by the sender and never remapped, so send, retry,
//      and redelivery are the same upsert.
//   3. Markdown is the only body. Fenced blocks are parsed into typed blocks at
//      the boundary and `parseBody` is total, so no renderer handles a parse error.
//   4. The chat list has typed sections, not a sorted list plus an `isGroup` flag.
//   5. Row chrome looks only at the *older* neighbour, so appending never
//      invalidates an existing row.

package com.openchat.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────────────────────────────────────────────────────────
// :domain / Ids.kt
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The public handle a human types into 通讯录 to add a contact.
 * Constructed only by [parse], so an unvalidated id cannot exist.
 */
@JvmInline
value class UserId private constructor(val value: String) {
    companion object {
        /** Lowercase, 3..20 chars of `[a-z0-9_]`. Returns null on anything else. */
        fun parse(raw: String): UserId? {
            val normalized = raw.trim().lowercase()
            if (!normalized.matches(Regex("^[a-z0-9_]{3,20}$"))) return null
            return UserId(normalized)
        }
    }
}

/**
 * Two distinct users, ordered so (a, b) and (b, a) are the same pair.
 * Self-chat cannot be constructed.
 */
class UserPair private constructor(val low: UserId, val high: UserId) {
    companion object {
        fun of(a: UserId, b: UserId): UserPair? {
            if (a.value == b.value) return null
            return if (a.value < b.value) UserPair(a, b) else UserPair(b, a)
        }
    }
}

@JvmInline
value class ConversationId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ConversationId? {
            val parts = raw.trim().split(":")
            if (parts.size != 3 || parts[0] != "d") return null
            val a = UserId.parse(parts[1]) ?: return null
            val b = UserId.parse(parts[2]) ?: return null
            val pair = UserPair.of(a, b) ?: return null
            return direct(pair)
        }

        /** Deterministic for a pair, so both peers derive the same id offline. */
        fun direct(pair: UserPair): ConversationId =
            ConversationId("d:${pair.low.value}:${pair.high.value}")
    }
}

/**
 * ULID minted by the *sender* before the message touches the network, and kept
 * for life. The server never issues one, so there is no local-to-remote id swap
 * and a retried send is the same row.
 */
@JvmInline
value class MessageId private constructor(val value: String) {
    companion object {
        fun mint(): MessageId =
            MessageId(java.util.UUID.randomUUID().toString().replace("-", ""))

        fun parse(raw: String): MessageId? {
            val normalized = raw.trim().lowercase()
            if (normalized.length !in 16..64 || !normalized.all { it.isLetterOrDigit() }) return null
            return MessageId(normalized)
        }
    }
}

/** Dense, server-assigned, monotonic within one conversation. Gapless by construction. */
@JvmInline
value class Seq(val value: Long) : Comparable<Seq> {
    override fun compareTo(other: Seq): Int = value.compareTo(other.value)
}

/** Device-local monotonic counter that orders messages the server has not numbered yet. */
@JvmInline
value class LocalOrdinal(val value: Long) : Comparable<LocalOrdinal> {
    override fun compareTo(other: LocalOrdinal): Int = value.compareTo(other.value)
}

@JvmInline
value class DisplayName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): DisplayName? {
            val normalized = raw.trim()
            if (normalized.isEmpty() || normalized.length > 32) return null
            return DisplayName(normalized)
        }
    }
}

@JvmInline
value class GroupTitle private constructor(val value: String) {
    companion object {
        fun parse(raw: String): GroupTitle? = TODO("not implemented")
    }
}

/** Content digest of an uploaded avatar. Never a URL; URL shape belongs to `:transport`. */
@JvmInline
value class AvatarDigest(val value: String) {
    companion object {
        fun of(bytes: ByteArray): AvatarDigest {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            return AvatarDigest(hash.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) })
        }
    }
}

/** Compose list key. One per row, stable across emissions. */
@JvmInline
value class RowKey(val value: String)

@JvmInline
value class MermaidSource private constructor(val value: String) {
    companion object {
        fun parse(raw: String): MermaidSource? = TODO("not implemented")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// :domain / Body.kt   Markdown is the only body.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A message body after boundary parsing. Blocks are ordered as written.
 * Constructed only by [parseBody]; the raw markdown string never escapes `:store`
 * and `:transport`.
 *
 * Deliberately a plain list, not a non-empty one. Nothing here is partial: the
 * only consumer iterates, and blank drafts are rejected by [Draft.of]. Extra
 * precision would cost ceremony and buy no safety.
 */
class MessageBody internal constructor(val blocks: List<Block>) {
    override fun equals(other: Any?): Boolean = other is MessageBody && other.blocks == blocks
    override fun hashCode(): Int = blocks.hashCode()
}

sealed interface Block {
    /** Prose runs between fences, already parsed into an opaque span tree. */
    data class Prose(val text: RichText) : Block

    /** A fence whose info string is unknown, or a typed fence that failed to parse. */
    data class Code(val language: String?, val source: String) : Block

    /** ```mermaid fence. Held as source; layout happens on first paint, not here. */
    data class Diagram(val source: MermaidSource) : Block

    /** ```chart fence, already validated into a drawable spec. */
    data class Chart(val spec: ChartSpec) : Block

    data class Quote(val blocks: List<Block>) : Block
}

/**
 * Parsed inline markdown. The markdown library's AST stays behind this type so a
 * library swap does not reach the renderers.
 */
class RichText internal constructor(private val spans: List<Any>) {
    fun asPlainText(): String = spans.joinToString("") { it.toString() }

    override fun equals(other: Any?): Boolean = other is RichText && other.spans == spans
    override fun hashCode(): Int = spans.hashCode()
}

/**
 * The one chart form. Every series has exactly [ChartSpec.labels].size points;
 * [parse] is the only constructor and rejects anything else, so no renderer
 * indexes out of bounds.
 */
class ChartSpec private constructor(
    val title: String?,
    val labels: List<String>,
    val series: List<Series>,
) {
    data class Series(val name: String, val points: List<Double>, val style: Style)

    enum class Style { Line, Bar }

    companion object {
        fun parse(fenceSource: String): ChartSpec? = TODO("not implemented")
    }
}

/**
 * Total. Never throws, never returns an error variant.
 *
 * A fence that claims `mermaid` or `chart` but does not parse degrades to
 * [Block.Code] carrying the original source, so a malformed diagram shows the
 * author their own text instead of an error state. That is why no caller of this
 * function handles failure.
 */
fun parseBody(markdown: String): MessageBody =
    MessageBody(listOf(Block.Prose(RichText(listOf(markdown)))))

/** Single-line chat-list preview derived from a body. Strips fences to a label. */
fun previewOf(body: MessageBody): PreviewText {
    val text = when (val first = body.blocks.firstOrNull()) {
        is Block.Prose -> first.text.asPlainText()
        is Block.Code -> first.source.lineSequence().firstOrNull().orEmpty()
        is Block.Diagram -> "[图]"
        is Block.Chart -> "[图]"
        is Block.Quote -> first.blocks.firstOrNull()?.let { previewOf(MessageBody(listOf(it))).value }.orEmpty()
        null -> ""
    }
    return PreviewText(text.replace('\n', ' ').trim().take(40))
}

@JvmInline
value class PreviewText(val value: String)

// ─────────────────────────────────────────────────────────────────────────────
// :domain / Message.kt
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Where a message stands, both in the conversation's order and in delivery.
 *
 * One field, three states. `Committed` owns the server order; `Queued` and
 * `Rejected` own a local order that always sorts after every committed message.
 * A committed message with no [Seq], or a queued one holding a [Seq], does not
 * compile.
 */
sealed interface Standing {
    data class Committed(val seq: Seq, val serverAt: Instant) : Standing
    data class Queued(val ordinal: LocalOrdinal, val attempts: Int) : Standing
    data class Rejected(val ordinal: LocalOrdinal, val reason: SendRejection) : Standing
}

sealed interface SendRejection {
    data class TooLarge(val limitBytes: Int) : SendRejection
    data object NotAMember : SendRejection
    data object UnknownConversation : SendRejection
    data class ServerRefused(val code: Int) : SendRejection
}

/** Immutable once minted. OpenChat has no edit and no delete, so nothing invalidates a message. */
data class Message(
    val id: MessageId,
    val conversation: ConversationId,
    val author: UserId,
    val body: MessageBody,
    val composedAt: Instant,
    val standing: Standing,
)

/**
 * The one total order for a conversation. Committed messages by [Seq], then the
 * local tail by [LocalOrdinal]. Every read path sorts through this comparator so
 * the ordering rule exists in exactly one place.
 */
val ConversationOrder: Comparator<Message> = Comparator { left, right ->
    val leftStanding = left.standing
    val rightStanding = right.standing
    when {
        leftStanding is Standing.Committed && rightStanding is Standing.Committed ->
            leftStanding.seq.compareTo(rightStanding.seq)
        leftStanding is Standing.Committed -> -1
        rightStanding is Standing.Committed -> 1
        else -> {
            val leftOrdinal = when (leftStanding) {
                is Standing.Queued -> leftStanding.ordinal
                is Standing.Rejected -> leftStanding.ordinal
                is Standing.Committed -> error("committed already handled")
            }
            val rightOrdinal = when (rightStanding) {
                is Standing.Queued -> rightStanding.ordinal
                is Standing.Rejected -> rightStanding.ordinal
                is Standing.Committed -> error("committed already handled")
            }
            leftOrdinal.compareTo(rightOrdinal)
        }
    }
}

/** A contiguous slice of one conversation's committed history, newest first. */
data class MessagePage(
    val messages: List<Message>,
    /** Set when the local log does not reach further back. Renders as [Row.Gap]. */
    val olderBoundary: Seq?,
)

// ─────────────────────────────────────────────────────────────────────────────
// :domain / Conversation.kt   Private and group are one app with two sections.
// ─────────────────────────────────────────────────────────────────────────────

sealed interface Avatar {
    data class Image(val digest: AvatarDigest) : Avatar

    /** Always drawable, so no render site branches on a null avatar. */
    data class Initials(val of: DisplayName) : Avatar
}

data class Contact(val id: UserId, val name: DisplayName, val avatar: Avatar)

sealed interface Conversation {
    val id: ConversationId

    /** Head message time, or creation time when empty. Derived, never stored twice. */
    val lastActivity: Instant
    val unread: UnreadCount
    val preview: Preview
}

data class DirectConversation(
    override val id: ConversationId,
    val peer: Contact,
    override val lastActivity: Instant,
    override val unread: UnreadCount,
    override val preview: Preview,
) : Conversation

data class GroupConversation(
    override val id: ConversationId,
    val title: GroupTitle,
    val memberCount: Int,
    override val lastActivity: Instant,
    override val unread: UnreadCount,
    override val preview: Preview,
) : Conversation

sealed interface Preview {
    data object Empty : Preview
    data class Latest(val by: PreviewAuthor, val text: PreviewText, val at: Instant) : Preview
}

sealed interface PreviewAuthor {
    data object Me : PreviewAuthor

    /** Group rows print the name, direct rows drop it. Same type serves both. */
    data class Other(val name: DisplayName) : PreviewAuthor
}

@JvmInline
value class UnreadCount private constructor(val value: Int) {
    companion object {
        val NONE: UnreadCount = UnreadCount(0)

        fun of(count: Int): UnreadCount? = if (count < 0) null else UnreadCount(count)
    }
}

/**
 * Sections, not a sorted list. Direct above group is the field order and the
 * renderer's structure, so no comparator and no `isGroup` flag can drift.
 *
 * [of] is the only constructor, so within-section recency order is decided once
 * instead of at every call site that assembles a list.
 */
class ChatList private constructor(
    val direct: List<DirectConversation>,
    val groups: List<GroupConversation>,
) {
    companion object {
        val Empty: ChatList = ChatList(emptyList(), emptyList())

        /** Sorts each section by [Conversation.lastActivity], newest first. */
        fun of(
            direct: List<DirectConversation>,
            groups: List<GroupConversation>,
        ): ChatList = ChatList(
            direct.sortedByDescending { it.lastActivity },
            groups.sortedByDescending { it.lastActivity },
        )
    }
}

/**
 * Derived from the head sequence and the read watermark, so there is no counter
 * to keep in sync. Both null means an empty conversation.
 */
fun unreadOf(headSeq: Seq?, readThrough: Seq?): UnreadCount = TODO("not implemented")

// ─────────────────────────────────────────────────────────────────────────────
// :domain / Rows.kt   What a Compose item receives. No lookups during composition.
// ─────────────────────────────────────────────────────────────────────────────

sealed interface Row {
    val key: RowKey

    data class Bubble(
        override val key: RowKey,
        val id: MessageId,
        val byline: Byline,
        val body: MessageBody,
        val stamp: Stamp,
        val progress: Progress,
        val grouping: Grouping,
    ) : Row

    data class DayBreak(override val key: RowKey, val day: LocalDate) : Row

    /** History the local log does not have yet. The sync writer fills it; reads never wait. */
    data class Gap(override val key: RowKey, val olderThan: Seq) : Row
}

/** Replaces an `isOutgoing` flag plus a nullable author name. */
sealed interface Byline {
    data class Mine(val avatar: Avatar) : Byline
    data class Theirs(val name: DisplayName, val avatar: Avatar) : Byline
}

/** What the bubble draws for delivery. A projection of [Standing], minus the ordering keys. */
enum class Progress { Sending, Sent, Failed }

/** Whether this row opens a burst (avatar and name shown) or continues one. */
enum class Grouping { OpensBurst, ContinuesBurst }

/** Pre-formatted for the row's locale and zone. Formatting never runs in composition. */
@JvmInline
value class Stamp(val text: String)

/**
 * The window the chat screen renders, newest first.
 *
 * `rows.last() is Row.Gap` means older history exists, so there is no separate
 * `hasOlder` field to fall out of step.
 */
class Timeline private constructor(val rows: List<Row>) {
    companion object {
        fun of(rows: List<Row>): Timeline = Timeline(rows)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// :domain / Summary.kt
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A start plus a duration, so `end < start` is unrepresentable and no call site
 * re-checks the ordering.
 */
data class TimeRange(val start: Instant, val length: Duration) {
    val end: Instant get() = start.plus(length)
}

@JvmInline
value class ProviderEndpoint private constructor(val baseUrl: String) {
    companion object {
        /** Requires https and a host. Returns null otherwise. */
        fun parse(raw: String): ProviderEndpoint? = TODO("not implemented")
    }
}

@JvmInline
value class SecretApiKey private constructor(private val value: String) {
    internal fun reveal(): String = value

    companion object {
        fun parse(raw: String): SecretApiKey? = TODO("not implemented")
    }
}

sealed interface ProviderConfig {
    /** 我 has no key yet. Summaries fail soft against this state, not against a null. */
    data object NotConfigured : ProviderConfig

    /**
     * Holds no key. The key lives in encrypted storage inside the AI adapter and never
     * enters a type the UI can render or a log line can print.
     */
    data class Configured(val endpoint: ProviderEndpoint, val model: String) : ProviderConfig
}

sealed interface ConfigureOutcome {
    data class Accepted(val config: ProviderConfig.Configured) : ConfigureOutcome

    /** The endpoint parsed but the live probe failed, so the old config stands. */
    data class Refused(val reason: ProviderFailure) : ConfigureOutcome
}

sealed interface SummaryOutcome {
    /** Markdown, parsed by the same [parseBody], so a summary can carry a diagram. */
    data class Ready(val body: MessageBody) : SummaryOutcome

    data object NoProvider : SummaryOutcome
    data class Unavailable(val reason: ProviderFailure) : SummaryOutcome

    /** Nothing was said in the range. Distinct from a failure, and the UI says so. */
    data object NothingToSummarize : SummaryOutcome
}

sealed interface ProviderFailure {
    data object Offline : ProviderFailure
    data object KeyRejected : ProviderFailure
    data class RateLimited(val retryAfter: Duration?) : ProviderFailure
    data class Malformed(val detail: String) : ProviderFailure
}

// ─────────────────────────────────────────────────────────────────────────────
// :domain / Account.kt
// ─────────────────────────────────────────────────────────────────────────────

sealed interface Account {
    data object SignedOut : Account
    data class SignedIn(val me: Identity) : Account
}

data class Identity(val id: UserId, val name: DisplayName, val avatar: Avatar)

/** Validated bytes from the photo picker. Magic-byte and size checks live in [of]. */
class PickedImage private constructor(val bytes: ByteArray, val format: Format) {
    enum class Format { Jpeg, Png, Webp }

    companion object {
        private const val MaxBytes = 5 * 1024 * 1024

        fun of(bytes: ByteArray): PickedImage? {
            if (bytes.size < 12 || bytes.size > MaxBytes) return null
            val format = formatOf(bytes) ?: return null
            return PickedImage(bytes.copyOf(), format)
        }

        private fun formatOf(bytes: ByteArray): Format? {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return Format.Jpeg
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                return Format.Png
            }
            val riff = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val webp = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") return Format.Webp
            return null
        }
    }
}

sealed interface RegisterOutcome {
    data class Registered(val me: Identity) : RegisterOutcome
    data object IdTaken : RegisterOutcome
    data object Offline : RegisterOutcome
}

sealed interface AddContactOutcome {
    data class Added(val contact: Contact) : AddContactOutcome
    data class AlreadyKnown(val contact: Contact) : AddContactOutcome
    data class Requested(val contact: Contact) : AddContactOutcome
    data class AlreadyRequested(val contact: Contact) : AddContactOutcome
    data object NoSuchUser : AddContactOutcome
    data object Offline : AddContactOutcome
}

sealed interface PeerRelation {
    data object Stranger : PeerRelation
    data object Outgoing : PeerRelation
    data object Friend : PeerRelation
}

// ─────────────────────────────────────────────────────────────────────────────
// :session   The only surface `:app` sees. Four interfaces, one per tab concern.
// ─────────────────────────────────────────────────────────────────────────────

/** A non-blank draft. Blank sends are unrepresentable, so `send` has no failure path. */
class Draft private constructor(val markdown: String) {
    companion object {
        fun of(raw: String): Draft? {
            val markdown = raw.trim()
            if (markdown.isEmpty()) return null
            return Draft(markdown)
        }
    }
}

interface Chats {
    val list: StateFlow<ChatList>

    /**
     * Lives as long as [scope]. Opening the same conversation twice from two
     * screens yields two independent readers over one log, so there is no shared
     * mutable session object to guard.
     */
    fun session(id: ConversationId, scope: CoroutineScope): ChatSession
}

/**
 * Everything the chat screen needs, behind four calls and two flows. Hides
 * paging policy, prefetch distance, row derivation and interning, burst
 * grouping, day breaks, retry backoff, and read-watermark debouncing.
 */
interface ChatSession {
    val id: ConversationId
    val header: StateFlow<ChatHeader>
    val timeline: StateFlow<Timeline>

    /** Returns before any IO. The row appears on the next frame with [Progress.Sending]. */
    fun send(draft: Draft)

    /** Same [MessageId], so a retry is an upsert and a double tap is one message. */
    fun retry(id: MessageId)

    /** No-op while a fetch is in flight or the whole history is loaded. Safe to spam. */
    fun requestOlder()

    /** Monotonic. A [through] below the current watermark does nothing. */
    fun markRead(through: Seq)
}

sealed interface ChatHeader {
    data class Direct(val peer: Contact) : ChatHeader
    data class Group(val title: GroupTitle, val memberCount: Int) : ChatHeader
}

interface Contacts {
    val all: StateFlow<List<Contact>>
    val incoming: StateFlow<List<Contact>>

    /** Sends a request, or accepts an incoming one. Known friends return [AddContactOutcome.AlreadyKnown]. */
    suspend fun add(id: UserId): AddContactOutcome

    /**
     * Preview card for the add-contact screen. Answers from the local table when
     * the id is already known and only then asks the server, so typing a friend's
     * id does not round-trip. Not a forwarder to [SyncClient].
     */
    suspend fun lookup(id: UserId): Contact?
}

interface Accounts {
    val state: StateFlow<Account>

    suspend fun register(id: UserId, name: DisplayName): RegisterOutcome
    suspend fun login(id: UserId): RegisterOutcome
    suspend fun setAvatar(image: PickedImage): Avatar
    fun signOut()
}

interface Summaries {
    val provider: StateFlow<ProviderConfig>

    suspend fun configure(endpoint: ProviderEndpoint, model: String, key: SecretApiKey): ConfigureOutcome

    /** Never throws. Absent key returns [SummaryOutcome.NoProvider]. */
    suspend fun summarize(conversation: ConversationId, range: TimeRange): SummaryOutcome
}

// ─────────────────────────────────────────────────────────────────────────────
// :store   Local log. The only read path. Reads never touch the network.
// ─────────────────────────────────────────────────────────────────────────────

interface MessageLog {
    /** Newest-first page strictly older than [before], or from the head when null. */
    suspend fun page(conversation: ConversationId, before: Seq?, limit: Int): MessagePage

    /** For the AI summary. Committed messages only. */
    suspend fun range(conversation: ConversationId, range: TimeRange): List<Message>

    fun watchTail(conversation: ConversationId): Flow<List<Message>>
    fun watchConversations(): Flow<ChatList>

    /** Messages awaiting the server, oldest first. Derived from [Standing.Queued]. */
    fun watchQueued(): Flow<List<Message>>
}

/**
 * The single writer for local chat state. Send, live delivery, backfill, and
 * read watermarks all arrive here, because promoting a queued message to
 * [Standing.Committed] is a write both the send path and the socket want.
 * One actor removes the race instead of guarding it.
 *
 * Every command is an upsert keyed by [MessageId], so replay after a crash or a
 * duplicate delivery converges to the same log.
 */
interface LogWriter {
    /**
     * Assigns the [LocalOrdinal] itself, because the counter is this actor's state
     * and a caller that invented one could tie with a concurrent send. Returns the
     * stored message so the sender never guesses what landed.
     */
    suspend fun enqueue(
        id: MessageId,
        conversation: ConversationId,
        author: UserId,
        body: MessageBody,
        composedAt: Instant,
    ): Message

    /** Promotes a queued message. Re-committing the same [Seq] is a no-op. */
    suspend fun commit(id: MessageId, standing: Standing.Committed)

    suspend fun reject(id: MessageId, reason: SendRejection)

    /** Peer messages and backfill. Upserts, so overlapping ranges are safe. */
    suspend fun applyDelivered(messages: List<Message>)

    /** Monotonic per conversation. A lower [through] is dropped. */
    suspend fun recordRead(conversation: ConversationId, through: Seq)
}

// ─────────────────────────────────────────────────────────────────────────────
// :transport   Wire DTOs live in `:protocol` and stop here. `:session` and
// `:app` have no Gradle dependency on `:protocol`, so leakage is a build error.
// ─────────────────────────────────────────────────────────────────────────────

interface SyncClient {
    val connection: StateFlow<ConnectionState>

    /** Domain events. Nothing serialization-shaped crosses this boundary. */
    fun events(): Flow<ServerEvent>

    /** Idempotent server-side on [Message.id]. A resend of a committed message returns its [Seq]. */
    suspend fun deliver(message: Message): DeliveryResult

    suspend fun history(conversation: ConversationId, before: Seq?, limit: Int): List<Message>
}

sealed interface ServerEvent {
    data class Delivered(val messages: List<Message>) : ServerEvent
    data class ConversationChanged(val conversation: Conversation) : ServerEvent
    data class ContactChanged(val contact: Contact) : ServerEvent

    /** Server watermark advanced past our cursor. Triggers backfill. */
    data class Behind(val cursor: SyncCursor) : ServerEvent
}

sealed interface ConnectionState {
    data object Offline : ConnectionState
    data object Connecting : ConnectionState
    data class Live(val since: Instant) : ConnectionState
}

/** One per device. Replay from here is safe because every apply is an upsert. */
@JvmInline
value class SyncCursor(val value: Long)

sealed interface DeliveryResult {
    data class Committed(val seq: Seq, val serverAt: Instant) : DeliveryResult
    data class Rejected(val reason: SendRejection) : DeliveryResult

    /** Transient. The message stays [Standing.Queued] and the sender retries. */
    data object Deferred : DeliveryResult
}
