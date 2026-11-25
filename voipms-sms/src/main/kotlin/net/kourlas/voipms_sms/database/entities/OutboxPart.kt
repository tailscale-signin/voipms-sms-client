package net.kourlas.voipms_sms.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Saved together with the SMS. Never retransmit an attempted part without a user decision. */
@Entity(
    tableName = "OutboxPart",
    foreignKeys = [ForeignKey(entity = Sms::class, parentColumns = ["DatabaseId"],
        childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["batch"])]
)
data class OutboxPart(
    @PrimaryKey val messageId: Long,
    val batch: String,
    val generation: Long,
    val dueAt: Long,
    val inlineReply: Boolean,
    val state: String = QUEUED,
    val attemptAt: Long? = null,
    val reason: String? = null,
    val isMms: Boolean = false
) {
    companion object {
        const val QUEUED = "queued"
        const val ATTEMPTING = "attempting"
        const val UNKNOWN = "unknown"
        const val REJECTED = "rejected"
        const val ACCEPTED = "accepted"
    }
}
