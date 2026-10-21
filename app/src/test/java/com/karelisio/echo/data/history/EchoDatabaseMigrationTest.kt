package com.karelisio.echo.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Une base créée par la version 1.0.0 (schéma exporté 1.json) reste lisible après la migration automatique. */
@RunWith(AndroidJUnit4::class)
class EchoDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migreLaVersion1SansPerdreLHistorique() {
        createVersion1()
        val database = Room.databaseBuilder(context, EchoDatabase::class.java, DB_NAME).build()
        try {
            runBlocking {
                val dao = database.echoDao()
                val old = dao.lastReceived()
                assertEquals("💜", old?.emoji)
                assertEquals("heartbeat", old?.pattern)
                assertNull(old?.message)

                dao.insert(EchoEvent(direction = EchoDirection.RECEIVED, emoji = "🫶", pattern = "purr", timestamp = 2_000, message = "Coucou"))
                assertEquals("Coucou", dao.lastReceived()?.message)
            }
        } finally {
            database.close()
        }
    }

    private fun createVersion1() {
        val schema = Json.parseToJsonElement(File(SCHEMA_V1).readText()).jsonObject["database"]!!.jsonObject
        val entity = schema["entities"]!!.jsonArray.single().jsonObject
        val statements = buildList {
            add(entity.sql("createSql"))
            entity["indices"]?.jsonArray?.forEach { add(it.jsonObject.sql("createSql")) }
            schema["setupQueries"]!!.jsonArray.forEach { add(it.jsonPrimitive.content) }
            add("INSERT INTO echoes (direction, emoji, pattern, timestamp) VALUES ('RECEIVED', '💜', 'heartbeat', 1000)")
        }
        context.deleteDatabase(DB_NAME)
        context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null).use { db ->
            statements.forEach(db::execSQL)
            db.version = 1
        }
    }

    private fun JsonObject.sql(key: String) = this[key]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "echoes")

    private companion object {
        const val DB_NAME = "migration-test.db"

        // Les tests unitaires Gradle s'exécutent depuis le dossier du module app.
        const val SCHEMA_V1 = "schemas/com.karelisio.echo.data.history.EchoDatabase/1.json"
    }
}
