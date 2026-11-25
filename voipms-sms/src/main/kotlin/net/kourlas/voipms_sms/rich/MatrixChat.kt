package net.kourlas.voipms_sms.rich

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import uniffi.matrix_sdk_base.EncryptionState

internal object MatrixChat {
    fun account(c: Context) = MatrixSessions.account(c)
    fun identity(c: Context) = MatrixSessions.identity(c)
    suspend fun login(c: Context, homeserver: String, user: String, password: String) =
        withContext(Dispatchers.IO) { MatrixSessions.login(c, homeserver, user, password); MatrixSyncWorker.schedule(c) }
    suspend fun logout(c: Context) = withContext(Dispatchers.IO) { MatrixSessions.logout(c); MatrixSyncWorker.stop(c) }
    suspend fun recover(c: Context, key: String) = withContext(Dispatchers.IO) {
        require(key.isNotBlank())
        MatrixSessions.open(c).client.encryption().use { it.recover(key.trim()) }
    }

    private suspend fun protected(room: Room): Boolean = when (room.encryptionState()) {
        EncryptionState.ENCRYPTED -> true
        EncryptionState.NOT_ENCRYPTED -> false
        EncryptionState.UNKNOWN -> room.latestEncryptionState() == EncryptionState.ENCRYPTED
    }

    private suspend fun peer(room: Room): String? {
        if (room.membership() != Membership.JOINED || !room.isDirect() || room.activeMembersCount() != 2uL) return null
        // Include invited members and service users so a room with an additional recipient is never bound as 1:1.
        val own = room.ownUserId()
        val members = room.activeHumanMemberIds().distinct()
        return if (members.size == 2 && own in members) members.single { it != own } else null
    }

    private suspend fun assertRoom(session: MatrixSession, room: Room, expectedPeer: String?) {
        check(MatrixSessions.active(session) && room.membership() == Membership.JOINED && protected(room)) {
            "Only joined, encrypted Matrix rooms can be opened."
        }
        if (expectedPeer != null) check(peer(room) == expectedPeer) { "The linked chat participants have changed." }
    }

    private suspend fun <T> inRoom(c: Context, id: String, expectedPeer: String?, block: suspend (MatrixSession, Room) -> T): T =
        withContext(Dispatchers.IO) {
            val session = MatrixSessions.open(c)
            requireNotNull(session.client.getRoom(id)) { "Matrix room unavailable." }.use { room ->
                assertRoom(session, room, expectedPeer)
                block(session, room)
            }
        }

    suspend fun rooms(c: Context): List<RichRoom> = withContext(Dispatchers.IO) {
        val s = MatrixSessions.open(c)
        if (s.updates.value == 0L) withTimeoutOrNull(3500) { s.updates.first { it != 0L } }
        s.client.rooms().mapNotNull { room -> room.use {
            val membership = it.membership()
            val invited = membership == Membership.INVITED
            if (it.isSpace() || (!invited && membership != Membership.JOINED)) return@use null
            if (!invited && !protected(it)) return@use null
            val peer = if (invited) null else try { peer(it) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            RichRoom(it.id(), it.displayName() ?: it.id(), invited, peer)
        } }.sortedWith(compareByDescending<RichRoom> { it.invited }.thenBy { it.title.lowercase() })
    }

    private fun validateUser(id: String) {
        require(id.startsWith('@') && id.indexOf(':') in 2 until id.lastIndex && id.length <= 255 &&
            id.none { it.isWhitespace() || it.isISOControl() }) { "Enter a full Matrix user ID." }
    }

    suspend fun create(c: Context, title: String, members: List<String>): String = withContext(Dispatchers.IO) {
        val s = MatrixSessions.open(c)
        val invites = members.map { it.trim() }.distinct().filter { it != s.client.userId() }
        require(invites.isNotEmpty()); invites.forEach(::validateUser)
        s.client.createRoom(CreateRoomParameters(name = title.trim().takeIf { it.isNotEmpty() }, isEncrypted = true,
            isDirect = invites.size == 1, visibility = RoomVisibility.Private, preset = RoomPreset.PRIVATE_CHAT,
            invite = invites)).also { s.client.awaitRoomRemoteEcho(it).close() }
    }

    suspend fun join(c: Context, roomId: String) = withContext(Dispatchers.IO) {
        val session = MatrixSessions.open(c)
        requireNotNull(session.client.getRoom(roomId)) { "No invitation found." }.use {
            check(it.membership() == Membership.INVITED)
            // An invite can lack stripped encryption state. Recheck full state after join, before exposing or sending content.
            check(it.encryptionState() != EncryptionState.NOT_ENCRYPTED)
            it.join()
        }
        val joined = withTimeoutOrNull(15000) {
            session.updates.first { session.client.getRoom(roomId)?.use { it.membership() == Membership.JOINED } == true }
        }
        check(joined != null) { "Room is still syncing." }
        requireNotNull(session.client.getRoom(roomId)).use {
            if (!protected(it)) { it.leave(); error("This invitation is for an unencrypted room.") }
        }
    }

    suspend fun invite(c: Context, roomId: String, member: String) = inRoom(c, roomId, null) { _, room ->
        validateUser(member.trim()); room.inviteUserById(member.trim())
    }

    fun observe(c: Context, roomId: String, expectedPeer: String? = null): Flow<RichTimeline> = flow {
        val s = MatrixSessions.open(c)
        requireNotNull(s.client.getRoom(roomId)).use { room ->
            assertRoom(s, room, expectedPeer)
            room.timeline().use { timeline ->
                emitAll(roomUpdates(room, timeline).combine(s.updates) { snapshot, _ ->
                    assertRoom(s, room, expectedPeer)
                    snapshot
                })
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun send(c: Context, roomId: String, text: String, expectedPeer: String? = null) =
        inRoom(c, roomId, expectedPeer) { _, room ->
            require(text.isNotBlank())
            room.timeline().use { tl -> messageEventContentFromMarkdown(text).use { tl.send(it).close() } }
            MatrixSyncWorker.wake(c)
        }

    suspend fun attach(c: Context, roomId: String, uri: Uri, expectedPeer: String? = null) =
        inRoom(c, roomId, expectedPeer) { session, room ->
            val attachment = readMatrixUpload(c, uri)
            MatrixSyncWorker.wake(c)
            assertRoom(session, room, expectedPeer)
            room.timeline().use { tl -> tl.sendAttachment(attachment.first, attachment.second).use { it.join() } }
        }

    suspend fun media(c: Context, roomId: String, eventId: String, expectedPeer: String? = null): Uri =
        inRoom(c, roomId, expectedPeer) { session, room ->
            openMatrixMedia(c, session, room, eventId) { assertRoom(session, room, expectedPeer) }
        }

    suspend fun typing(c: Context, roomId: String, active: Boolean, expectedPeer: String? = null) =
        inRoom(c, roomId, expectedPeer) { _, room -> room.typingNotice(active) }
    suspend fun read(c: Context, roomId: String, expectedPeer: String? = null) =
        inRoom(c, roomId, expectedPeer) { _, room -> room.markAsRead(ReceiptType.READ) }
}
