package com.openchat.server

import com.openchat.domain.Avatar
import com.openchat.domain.Contact
import com.openchat.domain.DisplayName
import com.openchat.domain.Identity
import com.openchat.domain.ConversationId
import com.openchat.domain.MessageId
import com.openchat.domain.UserId
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

internal class Database(path: Path) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS users(
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    token TEXT NOT NULL UNIQUE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS contacts(
                    owner TEXT NOT NULL REFERENCES users(id),
                    peer TEXT NOT NULL REFERENCES users(id),
                    PRIMARY KEY(owner, peer)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS messages(
                    id TEXT PRIMARY KEY,
                    conversation TEXT NOT NULL,
                    author TEXT NOT NULL,
                    body TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    server_at INTEGER NOT NULL,
                    UNIQUE(conversation, seq)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS friend_requests(
                    from_id TEXT NOT NULL REFERENCES users(id),
                    to_id TEXT NOT NULL REFERENCES users(id),
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(from_id, to_id)
                )
                """.trimIndent(),
            )
        }
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO friend_requests(from_id, to_id, created_at)
            SELECT owner, peer, ?
            FROM contacts
            WHERE NOT EXISTS (
                SELECT 1 FROM contacts AS reverse
                WHERE reverse.owner = contacts.peer AND reverse.peer = contacts.owner
            )
            """.trimIndent(),
        ).use { backfill ->
            backfill.setLong(1, Instant.now().toEpochMilli())
            backfill.executeUpdate()
        }
    }

    @Synchronized
    fun register(id: UserId, name: DisplayName): AuthenticatedIdentity? {
        val token = mintToken()
        connection.prepareStatement("INSERT OR IGNORE INTO users(id, name, token) VALUES (?, ?, ?)").use { statement ->
            statement.setString(1, id.value)
            statement.setString(2, name.value)
            statement.setString(3, token)
            if (statement.executeUpdate() == 0) return null
        }
        return AuthenticatedIdentity(identity(id, name), token)
    }

    @Synchronized
    fun login(id: UserId): AuthenticatedIdentity? {
        val name = connection.prepareStatement("SELECT name FROM users WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                DisplayName.parse(rows.getString("name")) ?: error("Invalid display name in users table")
            }
        }
        val token = mintToken()
        connection.prepareStatement("UPDATE users SET token = ? WHERE id = ?").use { statement ->
            statement.setString(1, token)
            statement.setString(2, id.value)
            check(statement.executeUpdate() == 1)
        }
        return AuthenticatedIdentity(identity(id, name), token)
    }

    @Synchronized
    fun authenticate(token: String): Identity? =
        connection.prepareStatement("SELECT id, name FROM users WHERE token = ?").use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                identity(rows.getString("id"), rows.getString("name"))
            }
        }

    @Synchronized
    fun user(id: UserId): Contact? =
        connection.prepareStatement("SELECT id, name FROM users WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                contact(rows.getString("id"), rows.getString("name"))
            }
        }

    @Synchronized
    fun contacts(owner: UserId): List<Contact> =
        connection.prepareStatement(
            """
            SELECT users.id, users.name
            FROM contacts
            JOIN users ON users.id = contacts.peer
            WHERE contacts.owner = ?
            ORDER BY users.name COLLATE NOCASE, users.id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, owner.value)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(contact(rows.getString("id"), rows.getString("name")))
                    }
                }
            }
        }

    @Synchronized
    fun incomingRequests(owner: UserId): List<Contact> =
        connection.prepareStatement(
            """
            SELECT users.id, users.name
            FROM friend_requests
            JOIN users ON users.id = friend_requests.from_id
            WHERE friend_requests.to_id = ?
            ORDER BY friend_requests.created_at, users.name COLLATE NOCASE, users.id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, owner.value)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(contact(rows.getString("id"), rows.getString("name")))
                    }
                }
            }
        }

    @Synchronized
    fun addContact(owner: UserId, peer: UserId): AddedContact? {
        val contact = user(peer) ?: return null
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            val outcome = when {
                hasContact(owner, peer) ->
                    AddedContact(contact, already = true, state = "already")
                hasRequest(from = peer, to = owner) -> {
                    insertContact(owner, peer)
                    insertContact(peer, owner)
                    deleteRequestsBetween(owner, peer)
                    AddedContact(contact, already = false, state = "added")
                }
                hasRequest(from = owner, to = peer) ->
                    AddedContact(contact, already = false, state = "pending")
                else -> {
                    insertRequest(from = owner, to = peer)
                    AddedContact(contact, already = false, state = "requested")
                }
            }
            connection.commit()
            outcome
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    @Synchronized
    fun chats(owner: UserId): List<ChatRecord> =
        contacts(owner).map { peer ->
            val conversation = directConversation(owner, peer.id)
            ChatRecord(peer, latestMessage(conversation))
        }

    @Synchronized
    fun canAccessConversation(owner: UserId, peer: UserId, conversation: ConversationId): Boolean {
        val contactExists = connection.prepareStatement(
            """
            SELECT 1
            FROM contacts
            WHERE (owner = ? AND peer = ?) OR (owner = ? AND peer = ?)
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, owner.value)
            statement.setString(2, peer.value)
            statement.setString(3, peer.value)
            statement.setString(4, owner.value)
            statement.executeQuery().use { it.next() }
        }
        if (contactExists) return true
        return connection.prepareStatement(
            "SELECT 1 FROM messages WHERE conversation = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, conversation.value)
            statement.executeQuery().use { it.next() }
        }
    }

    @Synchronized
    fun messages(conversation: ConversationId, after: Long): List<StoredMessage> =
        connection.prepareStatement(
            """
            SELECT id, conversation, author, body, seq, server_at
            FROM messages
            WHERE conversation = ? AND seq > ?
            ORDER BY seq
            LIMIT 100
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversation.value)
            statement.setLong(2, after)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toStoredMessage())
                }
            }
        }

    @Synchronized
    fun putMessage(
        id: MessageId,
        conversation: ConversationId,
        author: UserId,
        body: String,
    ): StoredMessage? {
        storedMessage(id)?.let { stored ->
            return stored.takeIf {
                it.conversation == conversation &&
                    it.author == author &&
                    it.body == body
            }
        }

        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            val nextSeq = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE conversation = ?",
            ).use { statement ->
                statement.setString(1, conversation.value)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
            val serverAt = Instant.now().toEpochMilli()
            connection.prepareStatement(
                """
                INSERT INTO messages(id, conversation, author, body, seq, server_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id.value)
                statement.setString(2, conversation.value)
                statement.setString(3, author.value)
                statement.setString(4, body)
                statement.setLong(5, nextSeq)
                statement.setLong(6, serverAt)
                check(statement.executeUpdate() == 1)
            }
            connection.commit()
            StoredMessage(id, conversation, author, body, nextSeq, serverAt)
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun close() {
        connection.close()
    }

    private fun identity(id: UserId, name: DisplayName): Identity =
        Identity(id, name, Avatar.Initials(name))

    private fun identity(rawId: String, rawName: String): Identity {
        val id = UserId.parse(rawId) ?: error("Invalid id in users table")
        val name = DisplayName.parse(rawName) ?: error("Invalid display name in users table")
        return identity(id, name)
    }

    private fun contact(rawId: String, rawName: String): Contact {
        val identity = identity(rawId, rawName)
        return Contact(identity.id, identity.name, identity.avatar)
    }

    private fun latestMessage(conversation: ConversationId): StoredMessage? =
        connection.prepareStatement(
            """
            SELECT id, conversation, author, body, seq, server_at
            FROM messages
            WHERE conversation = ?
            ORDER BY seq DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, conversation.value)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toStoredMessage() else null
            }
        }

    private fun storedMessage(id: MessageId): StoredMessage? =
        connection.prepareStatement(
            "SELECT id, conversation, author, body, seq, server_at FROM messages WHERE id = ?",
        ).use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toStoredMessage() else null
            }
        }

    private fun java.sql.ResultSet.toStoredMessage(): StoredMessage =
        StoredMessage(
            id = MessageId.parse(getString("id")) ?: error("Invalid message id in messages table"),
            conversation = ConversationId.parse(getString("conversation"))
                ?: error("Invalid conversation id in messages table"),
            author = UserId.parse(getString("author")) ?: error("Invalid author in messages table"),
            body = getString("body"),
            seq = getLong("seq"),
            serverAtMillis = getLong("server_at"),
        )

    private fun directConversation(first: UserId, second: UserId): ConversationId {
        val pair = com.openchat.domain.UserPair.of(first, second)
            ?: error("A contact cannot reference its owner")
        return ConversationId.direct(pair)
    }

    private fun hasContact(owner: UserId, peer: UserId): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM contacts WHERE owner = ? AND peer = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, owner.value)
            statement.setString(2, peer.value)
            statement.executeQuery().use { it.next() }
        }

    private fun hasRequest(from: UserId, to: UserId): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, from.value)
            statement.setString(2, to.value)
            statement.executeQuery().use { it.next() }
        }

    private fun insertContact(owner: UserId, peer: UserId) {
        connection.prepareStatement("INSERT OR IGNORE INTO contacts(owner, peer) VALUES (?, ?)").use { statement ->
            statement.setString(1, owner.value)
            statement.setString(2, peer.value)
            statement.executeUpdate()
        }
    }

    private fun insertRequest(from: UserId, to: UserId) {
        connection.prepareStatement(
            "INSERT INTO friend_requests(from_id, to_id, created_at) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, from.value)
            statement.setString(2, to.value)
            statement.setLong(3, Instant.now().toEpochMilli())
            check(statement.executeUpdate() == 1)
        }
    }

    private fun deleteRequestsBetween(left: UserId, right: UserId) {
        connection.prepareStatement(
            """
            DELETE FROM friend_requests
            WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, left.value)
            statement.setString(2, right.value)
            statement.setString(3, right.value)
            statement.setString(4, left.value)
            statement.executeUpdate()
        }
    }

    private fun mintToken(): String = UUID.randomUUID().toString()
}

internal data class AuthenticatedIdentity(val identity: Identity, val token: String)

internal data class AddedContact(val contact: Contact, val already: Boolean, val state: String)

internal data class ChatRecord(val peer: Contact, val latest: StoredMessage?)

internal data class StoredMessage(
    val id: MessageId,
    val conversation: ConversationId,
    val author: UserId,
    val body: String,
    val seq: Long,
    val serverAtMillis: Long,
)
