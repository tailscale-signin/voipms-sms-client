package net.kourlas.voipms_sms.tools

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.kourlas.voipms_sms.database.AbstractDatabase
import net.kourlas.voipms_sms.database.entities.Sms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MessageSearchTest {
    @Test fun migrationIndexesExistingTextAndKeepsDerivedTextInSync() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AbstractDatabase::class.java).build()
        try {
            val line = "4165550100"
            val first = db.smsDao().insert(Sms(did = line, contact = "4165550199", text = "Réunion demain"))
            val connection = db.openHelper.writableDatabase
            SearchIndex.install(connection)
            fun filters(body: String, media: Boolean = false) = SearchFilters(body, "", listOf(line), null, null, false, media, 0).query(0)
            assertEquals(listOf(first), db.messageSearchDao().search(filters("reu")).map { it.databaseId })
            val other = db.smsDao().insert(Sms(did = line, contact = "4165550199", text = "Picture", media1 = "image"))
            connection.execSQL("UPDATE message_search SET ocr='tracking reference' WHERE rowid=?", arrayOf(other))
            assertEquals(listOf(other), db.messageSearchDao().search(filters("track", true)).map { it.databaseId })
            connection.execSQL("UPDATE sms SET Media1='replacement' WHERE DatabaseId=?", arrayOf(other))
            assertEquals(0, db.messageSearchDao().search(filters("tracking")).size)
            connection.execSQL("DELETE FROM sms WHERE DatabaseId=?", arrayOf(first))
            assertEquals(0, db.messageSearchDao().search(filters("reu")).size)
        } finally { db.close() }
    }
    @Test fun opensCarrierOwnedTrackingOnlyForRecognizableIdentifiers() {
        assertEquals("https://www.ups.com/track?tracknum=1Z1234567890123456",
            MessageActions.parcelUrl("Delivery 1Z1234567890123456 https://example.com"))
        assertEquals("https://www.canadapost-postescanada.ca/track-reperage/en#/details/1234567890123456",
            MessageActions.parcelUrl("Postes Canada 1234567890123456"))
        assertNull(MessageActions.parcelUrl("Card 1234567890123456. Visit example.com"))
    }
}
