package com.karelisio.echo.data.history

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class EchoDirection { SENT, RECEIVED }

@Entity(tableName = "echoes", indices = [Index("timestamp")])
data class EchoEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: EchoDirection,
    val emoji: String,
    /** Identifiant du motif de vibration (voir VibrationPattern). */
    val pattern: String,
    val timestamp: Long,
    /** Phrase accompagnant l'écho (base v2) ; `null` = phrase par défaut. */
    val message: String? = null,
)

@Dao
interface EchoDao {
    @Insert
    suspend fun insert(event: EchoEvent): Long

    @Query("SELECT * FROM echoes ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EchoEvent>>

    @Query("SELECT * FROM echoes WHERE direction = 'RECEIVED' ORDER BY timestamp DESC LIMIT 1")
    fun observeLastReceived(): Flow<EchoEvent?>

    @Query("SELECT * FROM echoes WHERE direction = 'RECEIVED' ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastReceived(): EchoEvent?

    @Query("SELECT * FROM echoes WHERE direction = 'SENT' ORDER BY timestamp DESC LIMIT 1")
    fun observeLastSent(): Flow<EchoEvent?>

    @Query("DELETE FROM echoes")
    suspend fun clear()

    @Query("DELETE FROM echoes WHERE id NOT IN (SELECT id FROM echoes ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

@Database(entities = [EchoEvent::class], version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class EchoDatabase : RoomDatabase() {
    abstract fun echoDao(): EchoDao

    companion object {
        fun build(context: Context): EchoDatabase =
            Room.databaseBuilder(context, EchoDatabase::class.java, "echo.db").build()
    }
}
