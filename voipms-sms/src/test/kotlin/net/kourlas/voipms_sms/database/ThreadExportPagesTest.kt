package net.kourlas.voipms_sms.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.kourlas.voipms_sms.database.entities.Sms
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ThreadExportPagesTest {
    @Test fun chronologyAndThreadIsolationHoldAcrossSameTimestampPageBoundaries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AbstractDatabase::class.java).build()
        try {
            val dao = db.smsDao()
            val expected = (1..302).map { i ->
                val row = Sms(did = "line", contact = "peer", date = if (i % 7 == 0) 1 else 10,
                    text = "row $i", deliveryInProgress = if (i % 9 == 0) 1 else 0)
                row.copy(databaseId = dao.insert(row))
            }.sortedWith(compareBy<Sms> { it.date }.thenBy { it.databaseId })
            dao.insert(Sms(did = "other", contact = "peer", date = 5))
            dao.insert(Sms(did = "line", contact = "other", date = 5))
            val first = dao.threadExportPage("line", "peer", Long.MIN_VALUE, Long.MIN_VALUE, true, 128)
            val second = dao.threadExportPage("line", "peer", first.last().date, first.last().databaseId, false, 128)
            val third = dao.threadExportPage("line", "peer", second.last().date, second.last().databaseId, false, 128)
            assertEquals(listOf(128, 128, 46), listOf(first.size, second.size, third.size))
            assertEquals(expected, first + second + third)
            assertEquals(emptyList<Sms>(), dao.threadExportPage("line", "peer", third.last().date, third.last().databaseId, false, 128))
        } finally { db.close() }
    }
}
