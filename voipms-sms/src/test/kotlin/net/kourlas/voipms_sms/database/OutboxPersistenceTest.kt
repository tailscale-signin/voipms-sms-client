package net.kourlas.voipms_sms.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.kourlas.voipms_sms.database.entities.OutboxPart
import net.kourlas.voipms_sms.database.entities.Sms
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxPersistenceTest {
    @Test fun offlineQueueAndAttemptSurviveRestartWithoutASecondClaim() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "outbox-restart-test"
        fun open() = Room.databaseBuilder(context, AbstractDatabase::class.java, name).build()
        context.deleteDatabase(name)
        val first = open()
        val part = try {
            first.withTransaction {
                val id = first.smsDao().insert(Sms(did = "4165550100", contact = "6475550101",
                    text = "Scheduled", deliveryInProgress = 1))
                OutboxPart(id, "a", 2, 3000, false).also { first.outboxDao().add(it) }
            }
        } finally { first.close() }
        val restarted = open()
        try {
            restarted.smsDao().resetUntrackedSends()
            assertEquals(1L, restarted.smsDao().getById(part.messageId)?.deliveryInProgress)
            assertEquals(listOf(part), restarted.outboxDao().active())
            assertEquals(0, restarted.outboxDao().claim("a", part.messageId, 2999))
            assertEquals(1, restarted.outboxDao().claim("a", part.messageId, 3000))
        } finally { restarted.close() }
        val interrupted = open()
        try {
            assertEquals(OutboxPart.ATTEMPTING, interrupted.outboxDao().byId(part.messageId)?.state)
            assertEquals(0, interrupted.outboxDao().claim("a", part.messageId, 4000))
            interrupted.outboxDao().update("a", part.messageId, OutboxPart.UNKNOWN, "Unconfirmed")
            assertTrue(interrupted.outboxDao().active().isEmpty())
            interrupted.smsDao().deleteById(part.messageId)
            assertNull(interrupted.outboxDao().byId(part.messageId))
        } finally { interrupted.close(); context.deleteDatabase(name) }
    }
}
